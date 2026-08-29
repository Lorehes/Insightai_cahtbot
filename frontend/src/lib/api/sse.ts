/**
 * [Purpose] Low-level parsing for the Server-Sent Events (SSE) wire format used by
 *   `POST /api/conversations/{id}/messages` (`text/event-stream`). Kept separate from
 *   `messages.ts` so the two gotchas card F2's brief calls out -- event-boundary
 *   buffering and the single leading space after `data:` -- are isolated, pure, and
 *   directly unit-testable without mocking `fetch`/`ReadableStream` at all.
 * [Rationale] `EventSource` cannot be used here (it has no POST support), so this project
 *   parses the byte stream by hand via `fetch` + `response.body.getReader()` (chat Spec
 *   Tech Review, card F2 title: "fetch SSE 파싱"). This file only implements the generic
 *   SSE framing algorithm (WHATWG HTML "Server-sent events" spec, event-stream parsing) --
 *   it knows nothing about `ChatChunk` or JSON; `messages.ts` layers that on top.
 */

/**
 * [Purpose] Buffers decoded SSE text across multiple `reader.read()` calls and hands back
 *   only complete event blocks (the text between two blank-line terminators).
 * [Caution] A single `read()` has no relationship to event boundaries -- it may deliver
 *   half an event, or several events concatenated (card F2 brief, "함정 1"). Every decoded
 *   text chunk must go through {@link push}; never parse a raw chunk directly.
 */
export class SseEventBuffer {
  private buffer = "";

  /**
   * [Purpose] Feeds newly decoded text in and returns zero or more complete event blocks
   *   (their trailing blank-line terminator already stripped).
   * [Rationale] Line endings are normalized to `\n` first (CRLF and lone CR both count as
   *   a line break per the SSE spec) so the rest of this class only ever has to look for a
   *   single "\n\n" separator, regardless of which line ending the server used.
   */
  push(text: string): string[] {
    this.buffer += text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

    const blocks: string[] = [];
    let separatorIndex = this.buffer.indexOf("\n\n");
    while (separatorIndex !== -1) {
      blocks.push(this.buffer.slice(0, separatorIndex));
      this.buffer = this.buffer.slice(separatorIndex + 2);
      separatorIndex = this.buffer.indexOf("\n\n");
    }
    return blocks;
  }

  /**
   * [Purpose] Returns whatever text is left in the buffer once the stream has closed (no
   *   more bytes are coming), treating it as one final, un-terminated event. Returns
   *   `null` when nothing but whitespace is left.
   * [Rationale] The backend is expected to always end its last event with a blank line
   *   (standard Spring SSE emitter behavior), so in practice this should return `null`. It
   *   exists as a safety net: without it, a stream that happened to close one byte early
   *   would silently drop its last chunk (often the one carrying `finishReason`) instead
   *   of surfacing anything at all.
   */
  flushRemainder(): string | null {
    const rest = this.buffer;
    this.buffer = "";
    return rest.trim().length > 0 ? rest : null;
  }
}

/** [Purpose] Result of parsing one complete SSE event block into its `data:` payload. */
export interface SseEvent {
  /** Concatenation of every `data:` line in the block, joined with "\n" per the SSE spec. */
  data: string;
}

/**
 * [Purpose] Parses one event block (as produced by {@link SseEventBuffer}) into an
 *   {@link SseEvent}, or `null` when the block carries no `data:` line at all (e.g. a
 *   blank keep-alive block, or a block containing only a comment/`event:`/`id:` line).
 * [Rationale] `:`-prefixed comment lines and non-`data` fields (`event:`, `id:`, `retry:`)
 *   are valid per the SSE spec and must not break parsing, even though this backend never
 *   sends them today (chat Spec Tech Review A-6/A-11) -- they are recognized only well
 *   enough to be ignored, not surfaced, since nothing in this project consumes them yet.
 * [Caution] Strips **at most one** leading space after `data:`, per the SSE field-parsing
 *   algorithm -- `data:foo` and `data: foo` both yield `"foo"`, but `data:  foo` (two
 *   spaces) correctly keeps its second space (project CLAUDE.md fact #6; card F2 brief
 *   "함정 2"). Do not change this to `trimStart()`/`trim()`, which would also eat
 *   meaningful leading whitespace that happens to sit inside the JSON payload.
 */
export function parseSseEvent(block: string): SseEvent | null {
  const dataLines: string[] = [];

  for (const line of block.split("\n")) {
    if (line === "" || line.startsWith(":")) {
      continue; // blank line inside a block (shouldn't happen) / comment line -- ignore
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).replace(/^ /, ""));
    }
    // Any other field (`event:`, `id:`, `retry:`, or an unknown name) is valid SSE but
    // unused by this project today -- falling through here is the same as ignoring it.
  }

  return dataLines.length > 0 ? { data: dataLines.join("\n") } : null;
}
