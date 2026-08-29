import { SseEventBuffer, parseSseEvent } from "./sse";

/**
 * Pure-function tests: `SseEventBuffer`/`parseSseEvent` touch no `fetch`/`ReadableStream`,
 * so nothing here is mocked. Coverage focuses on the two failure modes card F2's brief
 * flags for hand-rolled SSE framing -- a block split across separate `push()` calls
 * (including down to one character at a time) and the "strip exactly one leading space
 * after `data:`" rule (project CLAUDE.md fact #6) -- plus CRLF/Korean edge cases.
 */
describe("SseEventBuffer", () => {
  it("returns nothing until a full \\n\\n-terminated block has arrived", () => {
    const buffer = new SseEventBuffer();

    expect(buffer.push('data: {"a":1}')).toEqual([]);
  });

  it("[chat:AC-36] completes a block once the terminator arrives in a later push (event split across reads)", () => {
    const buffer = new SseEventBuffer();

    expect(buffer.push('data: {"a":1}')).toEqual([]);
    expect(buffer.push("\n\n")).toEqual(['data: {"a":1}']);
  });

  it("[chat:AC-36] splits an event down to a single character at a time and still reassembles it", () => {
    const buffer = new SseEventBuffer();
    const source = 'data: {"delta":"hi"}\n\n';
    const collected: string[] = [];

    for (const char of source) {
      collected.push(...buffer.push(char));
    }

    expect(collected).toEqual(['data: {"delta":"hi"}']);
  });

  it("returns multiple blocks when one push contains several complete events", () => {
    const buffer = new SseEventBuffer();

    const blocks = buffer.push('data: {"a":1}\n\ndata: {"a":2}\n\ndata: {"a":3}\n\n');

    expect(blocks).toEqual(['data: {"a":1}', 'data: {"a":2}', 'data: {"a":3}']);
  });

  it("keeps a trailing partial event buffered across a push that ends mid-event", () => {
    const buffer = new SseEventBuffer();

    const first = buffer.push('data: {"a":1}\n\ndata: {"a":2');
    expect(first).toEqual(['data: {"a":1}']);

    const second = buffer.push('}\n\n');
    expect(second).toEqual(['data: {"a":2}']);
  });

  it("normalizes CRLF and lone CR line endings to LF before looking for the separator", () => {
    const buffer = new SseEventBuffer();

    const blocks = buffer.push('data: {"a":1}\r\n\r\n');

    expect(blocks).toEqual(['data: {"a":1}']);
  });

  describe("flushRemainder", () => {
    it("returns null when nothing (or only whitespace) is buffered", () => {
      const buffer = new SseEventBuffer();
      expect(buffer.flushRemainder()).toBeNull();

      buffer.push("\n\n"); // fully consumed, empty block already returned via push()
      expect(buffer.flushRemainder()).toBeNull();
    });

    it("returns a leftover un-terminated event once the stream has closed", () => {
      const buffer = new SseEventBuffer();
      buffer.push('data: {"a":1}\n\ndata: {"finishReason":"STOP"}');

      expect(buffer.flushRemainder()).toBe('data: {"finishReason":"STOP"}');
    });

    it("clears the buffer so a second call returns null", () => {
      const buffer = new SseEventBuffer();
      buffer.push('data: {"a":1}');

      expect(buffer.flushRemainder()).toBe('data: {"a":1}');
      expect(buffer.flushRemainder()).toBeNull();
    });
  });
});

describe("parseSseEvent", () => {
  it("[chat:AC-36] extracts the data payload of a simple event", () => {
    expect(parseSseEvent('data: {"delta":"hi"}')).toEqual({ data: '{"delta":"hi"}' });
  });

  it("returns null for a block with no data: line (blank keep-alive block)", () => {
    expect(parseSseEvent("")).toBeNull();
  });

  it("ignores a `:`-prefixed comment line", () => {
    expect(parseSseEvent(': keep-alive\ndata: {"a":1}')).toEqual({ data: '{"a":1}' });
  });

  it("returns null for a block containing only a comment", () => {
    expect(parseSseEvent(": keep-alive")).toBeNull();
  });

  it("ignores event:/id:/retry: fields alongside a data: line", () => {
    const block = ["event: message", "id: 42", "retry: 3000", 'data: {"a":1}'].join("\n");

    expect(parseSseEvent(block)).toEqual({ data: '{"a":1}' });
  });

  it("joins multiple data: lines in one block with \\n, per the SSE spec", () => {
    const block = ["data: line one", "data: line two"].join("\n");

    expect(parseSseEvent(block)).toEqual({ data: "line one\nline two" });
  });

  it("[chat:AC-36] strips exactly one leading space after `data:`, not more (project CLAUDE.md fact #6)", () => {
    expect(parseSseEvent("data: single")).toEqual({ data: "single" });
    expect(parseSseEvent("data:no-space")).toEqual({ data: "no-space" });
    // Two leading spaces: only the first is SSE syntax: the second is part of the value.
    expect(parseSseEvent("data:  two-spaces")).toEqual({ data: " two-spaces" });
  });

  it("preserves a Korean payload untouched (project CLAUDE.md fact #6)", () => {
    expect(parseSseEvent('data: {"delta":"안녕하세요"}')).toEqual({ data: '{"delta":"안녕하세요"}' });
  });
});
