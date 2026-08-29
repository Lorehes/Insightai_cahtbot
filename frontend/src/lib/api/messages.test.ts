import { ApiError } from "./client";
import { streamAssistantReply, isErrorFinish, getMessages, type ChatChunk, type MessageResponse } from "./messages";

/**
 * These tests mock `fetch` with a duck-typed `Response`-like object whose `body` exposes
 * only the `getReader()` surface `streamAssistantReply` actually calls (`.read()` /
 * `.releaseLock()`), for the same portability reason `client.test.ts` mocks `Response`
 * itself (jest-environment-jsdom does not implement `ReadableStream`, confirmed by direct
 * probe -- see this card's dev-log). Byte chunks are built with the real `TextEncoder`
 * (polyfilled for this test environment in jest.setup.ts) so UTF-8 boundary-splitting
 * behavior is exercised faithfully, not simulated.
 */

function encode(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

function makeReader(chunks: Uint8Array[]) {
  let index = 0;
  return {
    read: jest.fn(async () => {
      if (index < chunks.length) {
        const value = chunks[index];
        index += 1;
        return { done: false, value };
      }
      return { done: true, value: undefined };
    }),
    releaseLock: jest.fn(),
  };
}

function mockStreamResponse(
  chunks: Uint8Array[],
  overrides: Partial<{ ok: boolean; status: number; statusText: string; json: () => Promise<unknown> }> = {},
) {
  const reader = makeReader(chunks);
  const response = {
    ok: true,
    status: 200,
    statusText: "OK",
    body: { getReader: () => reader },
    json: async () => ({}),
    ...overrides,
  };
  const fetchMock = jest.fn().mockResolvedValue(response);
  global.fetch = fetchMock as unknown as typeof fetch;
  return { fetchMock, reader };
}

async function collect<T>(gen: AsyncGenerator<T, void, void>): Promise<T[]> {
  const out: T[] = [];
  for await (const item of gen) out.push(item);
  return out;
}

describe("streamAssistantReply", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("POSTs to the conversation's messages endpoint with same-origin credentials and a JSON body", async () => {
    const { fetchMock } = mockStreamResponse([]);

    await collect(streamAssistantReply("conv-1", "hello"));

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/conversations/conv-1/messages",
      expect.objectContaining({
        method: "POST",
        credentials: "same-origin",
        headers: expect.objectContaining({ "Content-Type": "application/json", Accept: "text/event-stream" }),
        body: JSON.stringify({ message: "hello", model: undefined }),
      }),
    );
  });

  it("URL-encodes the conversation id", async () => {
    const { fetchMock } = mockStreamResponse([]);

    await collect(streamAssistantReply("conv/needs encoding", "hi"));

    expect(fetchMock).toHaveBeenCalledWith("/api/conversations/conv%2Fneeds%20encoding/messages", expect.anything());
  });

  it("forwards an optional model and an AbortSignal through to fetch", async () => {
    const { fetchMock } = mockStreamResponse([]);
    const controller = new AbortController();

    await collect(streamAssistantReply("conv-1", "hi", { model: "some-model", signal: controller.signal }));

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/conversations/conv-1/messages",
      expect.objectContaining({
        body: JSON.stringify({ message: "hi", model: "some-model" }),
        signal: controller.signal,
      }),
    );
  });

  it("[chat:AC-36] yields a chunk as soon as its event is complete, without waiting for the stream to close", async () => {
    const { reader } = mockStreamResponse([encode('data: {"conversationId":"c1","delta":"a","finishReason":null,"usage":null}\n\n')]);

    const generator = streamAssistantReply("c1", "hi");
    const result = await generator.next();

    expect(result.done).toBe(false);
    expect(result.value).toEqual({ conversationId: "c1", delta: "a", finishReason: null, usage: null });
    // Only one read() was needed to produce this chunk -- the generator did not drain the
    // whole stream (which isn't even closed yet) before yielding it.
    expect(reader.read).toHaveBeenCalledTimes(1);
  });

  it("[chat:AC-36] reassembles one SSE event whose bytes are split across two separate reads", async () => {
    const full = 'data: {"conversationId":"c1","delta":"hi","finishReason":null,"usage":null}\n\n';
    const fullBytes = encode(full);
    const splitPoint = 10; // arbitrary point strictly inside the event, well before its "\n\n"
    mockStreamResponse([fullBytes.slice(0, splitPoint), fullBytes.slice(splitPoint)]);

    const chunks = await collect(streamAssistantReply("c1", "hi"));

    expect(chunks).toEqual([{ conversationId: "c1", delta: "hi", finishReason: null, usage: null }]);
  });

  it("[chat:AC-36] reassembles a Korean delta whose multi-byte UTF-8 encoding is split mid-character across reads", async () => {
    const prefixBeforeDelta = 'data: {"conversationId":"c1","delta":"';
    const full = `${prefixBeforeDelta}안녕","finishReason":null,"usage":null}\n\n`;
    const fullBytes = encode(full);
    // One byte into "안"'s 3-byte UTF-8 encoding -- guaranteed mid-character, computed from
    // the real encoded prefix length rather than a hardcoded magic offset.
    const splitPoint = encode(prefixBeforeDelta).length + 1;
    mockStreamResponse([fullBytes.slice(0, splitPoint), fullBytes.slice(splitPoint)]);

    const chunks = await collect(streamAssistantReply("c1", "hi"));

    expect(chunks).toEqual([{ conversationId: "c1", delta: "안녕", finishReason: null, usage: null }]);
  });

  it("[chat:AC-36] yields several events delivered in a single read, in order", async () => {
    const twoEvents =
      'data: {"conversationId":"c1","delta":"a","finishReason":null,"usage":null}\n\n' +
      'data: {"conversationId":"c1","delta":"b","finishReason":null,"usage":null}\n\n';
    mockStreamResponse([encode(twoEvents)]);

    const chunks = await collect(streamAssistantReply("c1", "hi"));

    expect(chunks.map((c) => c.delta)).toEqual(["a", "b"]);
  });

  it("does not stop early just because a non-final chunk happens to carry a non-null finishReason", async () => {
    const events =
      'data: {"conversationId":"c1","delta":"a","finishReason":"STOP","usage":null}\n\n' +
      'data: {"conversationId":"c1","delta":"b","finishReason":null,"usage":null}\n\n';
    const { reader } = mockStreamResponse([encode(events)]);

    const chunks = await collect(streamAssistantReply("c1", "hi"));

    // Completion is driven by reader.read()'s `done`, not by finishReason -- both chunks
    // are yielded, and the stream only ends once `done: true` is actually observed.
    expect(chunks).toHaveLength(2);
    expect(reader.read).toHaveBeenCalledTimes(2); // one more read() to observe `done: true`
  });

  it("flushes a final un-terminated event once the stream closes without a trailing blank line", async () => {
    // No trailing "\n\n" -- exercises SseEventBuffer.flushRemainder via streamAssistantReply.
    mockStreamResponse([encode('data: {"conversationId":"c1","delta":null,"finishReason":"STOP","usage":null}')]);

    const chunks = await collect(streamAssistantReply("c1", "hi"));

    expect(chunks).toEqual([{ conversationId: "c1", delta: null, finishReason: "STOP", usage: null }]);
  });

  it("releases the reader lock once the stream ends", async () => {
    const { reader } = mockStreamResponse([encode('data: {"conversationId":"c1","delta":"a","finishReason":"STOP","usage":null}\n\n')]);

    await collect(streamAssistantReply("c1", "hi"));

    expect(reader.releaseLock).toHaveBeenCalledTimes(1);
  });

  it("throws ApiError carrying the response status when the request is rejected before streaming (e.g. 404/400/quota)", async () => {
    mockStreamResponse([], { ok: false, status: 404, statusText: "Not Found", json: async () => ({ title: "Not Found", detail: "no such conversation" }) });

    await expect(collect(streamAssistantReply("missing", "hi"))).rejects.toMatchObject({
      status: 404,
      message: "no such conversation",
    });
    await expect(collect(streamAssistantReply("missing", "hi"))).rejects.toBeInstanceOf(ApiError);
  });

  it("falls back to the status line when a non-2xx response body is not JSON", async () => {
    mockStreamResponse([], {
      ok: false,
      status: 500,
      statusText: "Internal Server Error",
      json: async () => {
        throw new SyntaxError("not json");
      },
    });

    await expect(collect(streamAssistantReply("c1", "hi"))).rejects.toMatchObject({
      status: 500,
      message: "500 Internal Server Error",
    });
  });
});

describe("isErrorFinish", () => {
  it("is true only for a case-insensitive match against \"error\"", () => {
    expect(isErrorFinish("error")).toBe(true);
    expect(isErrorFinish("ERROR")).toBe(true);
    expect(isErrorFinish("Error")).toBe(true);
  });

  it("is false for null, and for other known finishReason values regardless of casing", () => {
    expect(isErrorFinish(null)).toBe(false);
    expect(isErrorFinish("STOP")).toBe(false);
    expect(isErrorFinish("stop")).toBe(false);
    expect(isErrorFinish("cancelled")).toBe(false);
  });
});

describe("getMessages", () => {
  /** Same duck-typed Response stand-in `client.test.ts` uses, for the same portability
   *  reason -- `getMessages` is routed through `apiFetch`, an ordinary bounded JSON call,
   *  unlike `streamAssistantReply` above. */
  function mockFetchOnce(overrides: {
    ok?: boolean;
    status?: number;
    statusText?: string;
    json?: () => Promise<unknown>;
    text?: () => Promise<string>;
  }) {
    const response = {
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ([]),
      text: async () => "",
      ...overrides,
    };
    const fetchMock = jest.fn().mockResolvedValue(response);
    global.fetch = fetchMock as unknown as typeof fetch;
    return fetchMock;
  }

  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[chat:AC-38] GETs the conversation's messages path with same-origin credentials", async () => {
    const fetchMock = mockFetchOnce({ json: async () => [] });

    await getMessages("conv-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/conversations/conv-1/messages",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("URL-encodes the conversation id", async () => {
    const fetchMock = mockFetchOnce({ json: async () => [] });

    await getMessages("conv/needs encoding");

    expect(fetchMock).toHaveBeenCalledWith("/api/conversations/conv%2Fneeds%20encoding/messages", expect.anything());
  });

  it("[chat:AC-38] returns prior messages unmodified, in the order the backend sent them", async () => {
    const history: MessageResponse[] = [
      { role: "user", content: "hi", createdAt: "2026-08-28T00:00:00Z" },
      { role: "assistant", content: "hello back", createdAt: "2026-08-28T00:00:01Z" },
    ];
    mockFetchOnce({ json: async () => history });

    await expect(getMessages("conv-1")).resolves.toEqual(history);
  });

  it("throws ApiError carrying 404 for an unknown/foreign/already-deleted conversation", async () => {
    mockFetchOnce({ ok: false, status: 404, statusText: "Not Found", text: async () => "not found" });

    await expect(getMessages("missing")).rejects.toBeInstanceOf(ApiError);
    await expect(getMessages("missing")).rejects.toMatchObject({ status: 404 });
  });
});

// Exercises the exported type shape at compile time -- if the wire contract in
// messages.ts ever drifts from this literal, `tsc --noEmit` fails, not just this test.
const _typeCheck: ChatChunk = { conversationId: "c1", delta: null, finishReason: null, usage: null };
void _typeCheck;
const _messageTypeCheck: MessageResponse = { role: "user", content: "hi", createdAt: "2026-08-28T00:00:00Z" };
void _messageTypeCheck;
