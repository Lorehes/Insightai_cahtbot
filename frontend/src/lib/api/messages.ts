import { ApiError, apiFetch } from "./client";
import { SseEventBuffer, parseSseEvent } from "./sse";

/**
 * [Purpose] Token/cost summary carried on `ChatChunk.usage` (chat Spec Tech Review A-6).
 * [Caution] Stays `null` on every chunk until card T11 ships (backend `ChatChunk`
 *   [Rationale]) -- nothing in this card reads this type. Field names are `snake_case`
 *   because the backend fixes them with `@JsonProperty` (AC-32/33), not because of any
 *   frontend convention. `cost` is a JSON number (Jackson's default `BigDecimal`
 *   serialization), not a string -- do not do currency arithmetic on it in JS if a future
 *   card ever reads it; format it for display only.
 */
export interface ChatUsage {
  prompt_tokens: number | null;
  completion_tokens: number | null;
  cost: number | null;
}

/**
 * [Purpose] One citation entry carried on `ChatChunk.sources` (hybrid-rag Spec AC-11,
 *   confirmed against `chat/domain/ChatChunk.java`'s nested `Source` record for this card).
 * [Caution] `score` and `snippet` are part of the wire shape but this card does not render
 *   them (hybrid-rag Spec §7 OQ-3, decision D-A, 2026-08-30) -- do not add UI for them without
 *   a new AC. `filename`/`snippet` reach the wire unescaped (hybrid-rag Spec T15 security
 *   Low-1) -- render them only as plain text nodes (JSX interpolation), never via
 *   `dangerouslySetInnerHTML`.
 */
export interface ChatSource {
  documentId: string;
  filename: string;
  score: number;
  snippet: string;
  page: number | null;
}

/**
 * [Purpose] Wire shape for one SSE event of `POST /api/conversations/{id}/messages` --
 *   mirrors the backend `ChatChunk` record verbatim (chat Spec Tech Review A-6, confirmed
 *   against `chat/domain/ChatChunk.java` and `chat/api/ConversationController.java` for
 *   this card).
 * [Caution] `finishReason` is normalized to lowercase by the backend as of `BL-003`
 *   (2026-08-30) -- `ChatChunk.normalizeFinishReason` folds the SDK's uppercase `enum.name()`
 *   (`"STOP"` -> `"stop"`) before it reaches the wire, and a value the SDK can't map plus an
 *   absent value both become the literal `"unknown"` at the storage axis. `"error"`/
 *   `"cancelled"` were already lowercase. **Unresolved**: on the path where the SDK never
 *   supplies a finish reason at all, the final chunk can still arrive with `finishReason:
 *   null` -- tracked as `BL-009` in `{DOC_ROOT}/docs/harness/backlog.jsonl`, not yet closed.
 *   Do not assume a non-null value is now guaranteed. Never branch on a specific literal
 *   value beyond {@link isErrorFinish}'s check. Only two uses of this field are safe: (a)
 *   whether it is non-null, as a *candidate* signal that a chunk might be the last one, and
 *   (b) {@link isErrorFinish}'s case-insensitive compare against `"error"`, to show a
 *   failure. Stream *completion* must be read from the reader closing (`done`), never from
 *   this field -- see {@link streamAssistantReply}.
 * [Caution] `sources` follows the same "present only on the terminal chunk" pattern as
 *   `usage`: every mid-stream chunk carries `sources: null`. Unlike `usage`, the terminal
 *   chunk is **not guaranteed** to carry a non-null value -- `BL-019` (2026-08-31, accepted as
 *   a known defect, not fixed): on the same SDK path that leaves `finishReason: null`
 *   (`BL-009`), `sources` leaks `null` on the terminal chunk too. A consumer must therefore
 *   read stream completion from the reader closing (`done`, see {@link streamAssistantReply}),
 *   not from `sources` becoming non-null, and must treat "stream ended, `sources` still null"
 *   as its own distinct state -- never assume that state means "no citations" (hybrid-rag
 *   Spec AC-21, clause ③).
 * [AC] chat:AC-36, hybrid-rag:AC-21
 */
export interface ChatChunk {
  conversationId: string;
  delta: string | null;
  finishReason: string | null;
  usage: ChatUsage | null;
  sources: ChatSource[] | null;
}

/**
 * [Purpose] Case-insensitive check for whether a `ChatChunk.finishReason` marks a
 *   server-side failure (chat Spec Tech Review A-7, `ChatChunk.error`).
 * [Rationale] The backend's own error constant is the lowercase literal `"error"`, and as of
 *   `BL-003` (2026-08-30) the backend normalizes `finishReason` to lowercase before it ever
 *   reaches the wire (see {@link ChatChunk} [Caution]) -- so this comparison now matches the
 *   wire value exactly either way. The case-insensitive compare is kept anyway: it costs
 *   nothing and stays a defense against a backend regression that reintroduces mixed casing.
 *   The project's no-specific-literal-branching principle still applies everywhere else on
 *   this field; this function remains the one documented exception.
 */
export function isErrorFinish(finishReason: string | null): boolean {
  return finishReason !== null && finishReason.toLowerCase() === "error";
}

/** [Purpose] Optional per-call knobs for {@link streamAssistantReply}. */
export interface StreamAssistantReplyOptions {
  /** Forwarded as `SendMessageRequest.model` (chat Spec Tech Review A-11) when set; card
   *  F2 builds no model-picker UI (chat:AC-39 is card F3's), so nothing in this card
   *  passes this today -- it exists so a future caller does not have to bypass this
   *  function. */
  model?: string;
  /** Lets a caller cancel an in-flight stream (e.g. on component unmount). */
  signal?: AbortSignal;
}

/**
 * [Purpose] Opens the SSE stream for one turn of a conversation
 *   (`POST /api/conversations/{id}/messages`) and yields each {@link ChatChunk} as it
 *   arrives, so a UI can render the reply incrementally instead of waiting for the whole
 *   response.
 * [Rationale] Not routed through `apiFetch` (`client.ts`) -- `apiFetch` always awaits
 *   `response.json()` on the *entire* body before returning, which is incompatible with an
 *   unbounded stream (per this card's brief: "apiFetch가 SSE 스트리밍에 맞지 않으면 덮어쓰지
 *   말고 별도 함수를 추가"). This function still enforces the same
 *   `credentials: "same-origin"` guarantee `apiFetch` does, for the same reason (chat Spec
 *   AC-40, OQ-11) -- a session-scoped endpoint must never silently fall back to a
 *   same-origin default that a caller could override.
 * [Caution] Stream completion is read from `reader.read()`'s `done` flag, never from
 *   `finishReason` (see {@link ChatChunk} [Caution] -- that field's values are not
 *   normalized). `response.ok` is checked *before* any SSE parsing begins, because a
 *   rejected request (404 unknown/foreign/deleted conversation, 400 unknown model, 4xx
 *   over the per-conversation message quota) comes back as an ordinary
 *   `ProblemDetail` JSON body, not as an event stream (chat Spec Tech Review A-8, A-11) --
 *   attempting to SSE-parse that body would fail confusingly instead of surfacing the real
 *   status.
 * [AC] chat:AC-36
 */
export async function* streamAssistantReply(
  conversationId: string,
  message: string,
  options: StreamAssistantReplyOptions = {},
): AsyncGenerator<ChatChunk, void, void> {
  const response = await fetch(`/api/conversations/${encodeURIComponent(conversationId)}/messages`, {
    method: "POST",
    // Same guarantee as apiFetch (client.ts) -- deliberately not reusing apiFetch itself,
    // see [Rationale] above.
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify({ message, model: options.model }),
    signal: options.signal,
  });

  if (!response.ok) {
    throw new ApiError(response.status, await describeStreamFailure(response));
  }

  if (!response.body) {
    // No environment this project targets should hit this (every supported browser's
    // fetch() exposes a readable body), but fail loudly rather than silently yielding
    // nothing if it ever does.
    throw new Error("streamAssistantReply: the response has no readable body");
  }

  const reader = response.body.getReader();
  // { stream: true } is required so a multi-byte UTF-8 character (e.g. Korean text) split
  // across two `read()` calls decodes correctly instead of corrupting at the boundary
  // (project CLAUDE.md fact #6).
  const decoder = new TextDecoder();
  const events = new SseEventBuffer();

  try {
    for (;;) {
      const { done, value } = await reader.read();

      if (value) {
        yield* drainEvents(events, decoder.decode(value, { stream: true }));
      }

      if (done) {
        yield* drainEvents(events, decoder.decode()); // flush any pending decoder state
        const remainder = events.flushRemainder();
        if (remainder !== null) {
          const chunk = toChatChunk(remainder);
          if (chunk !== null) yield chunk;
        }
        return;
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/** Pushes decoded text into `events` and yields a {@link ChatChunk} for each complete block. */
function* drainEvents(events: SseEventBuffer, text: string): Generator<ChatChunk, void, void> {
  for (const block of events.push(text)) {
    const chunk = toChatChunk(block);
    if (chunk !== null) yield chunk;
  }
}

function toChatChunk(block: string): ChatChunk | null {
  const event = parseSseEvent(block);
  if (event === null) return null;
  // Trusts the backend's wire contract (chat Spec Tech Review A-6) rather than
  // re-validating every field -- a shape mismatch here means the BE/FE contract itself
  // drifted, which should surface loudly (JSON.parse throwing on malformed JSON, or a
  // consumer's field access producing `undefined`) rather than being silently swallowed.
  return JSON.parse(event.data) as ChatChunk;
}

/** Reads a non-2xx response's `ProblemDetail` (RFC 9457) body for a human-readable message. */
async function describeStreamFailure(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { title?: string; detail?: string };
    return problem.detail ?? problem.title ?? `${response.status} ${response.statusText}`;
  } catch {
    return `${response.status} ${response.statusText}`;
  }
}

/**
 * [Purpose] One entry of `GET /api/conversations/{id}/messages` (chat Spec Tech Review A-11,
 *   confirmed against `ConversationController.MessageResponse`) -- what card F3 loads to show
 *   a selected conversation's prior turns (chat:AC-38).
 * [Caution] Distinct from {@link ChatChunk} (one SSE delta of an in-progress reply) and from
 *   `components/chat-panel/MessageList.tsx`'s `ChatMessage` (the UI-level transcript row this
 *   project renders) -- this is the wire shape of one already-completed, persisted turn. A
 *   caller must map this to a `ChatMessage` (adding a client-only `id`) before handing it to
 *   `MessageList`; see {@link ../../components/chat-workspace/ChatWorkspace}'s `toChatMessages`.
 * [AC] chat:AC-38
 */
export interface MessageResponse {
  role: "user" | "assistant";
  content: string;
  createdAt: string;
  /** The citations persisted with an assistant turn (`messages.sources`, hybrid-rag AC-12's
   *  read side) -- the same `ChatChunk.Source` shape the SSE terminal chunk carried when the
   *  turn was streamed (`ConversationController.MessageResponse`). `null` for a user row, and
   *  for an assistant row that never had citations recorded. Optional in the type only so older
   *  fixtures/callers that omit it stay valid; the backend always sends the key. */
  sources?: ChatSource[] | null;
}

/**
 * [Purpose] Calls `GET /api/conversations/{id}/messages` to load a conversation's prior
 *   messages, for card F3's "select a conversation from the sidebar" flow (chat:AC-38).
 * [Rationale] Routed through {@link apiFetch} (unlike {@link streamAssistantReply}) -- this is
 *   an ordinary bounded JSON response, not a stream, so {@link apiFetch}'s
 *   `response.json()`-then-return shape fits directly. The backend already returns messages in
 *   `created_at` ascending order (`ConversationController#messages` [Purpose]), so this
 *   function does not re-sort.
 * [Caution] A 404 here covers the same three indistinguishable causes `lib/api/conversations.ts`'s
 *   `deleteConversation` [Caution] documents (unknown id, another session's conversation, or an
 *   already-deleted one) -- do not guess which one occurred.
 * [AC] chat:AC-38
 */
export function getMessages(conversationId: string): Promise<MessageResponse[]> {
  return apiFetch<MessageResponse[]>(`/api/conversations/${encodeURIComponent(conversationId)}/messages`);
}
