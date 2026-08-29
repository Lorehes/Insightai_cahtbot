import { ApiError } from "./client";
import { deleteConversation, listConversations } from "./conversations";

/** Same duck-typed Response stand-in `client.test.ts` uses, for the same portability reason
 *  (jest-environment-jsdom does not reliably expose a global `Response` constructor). */
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

describe("listConversations", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[chat:AC-38] GETs /api/conversations and returns the parsed array unmodified", async () => {
    const summaries = [
      { id: "c1", title: "first", model: "m1", ragEnabled: false, updatedAt: "2026-08-28T00:00:00Z" },
      { id: "c2", title: null, model: "m1", ragEnabled: true, updatedAt: "2026-08-27T00:00:00Z" },
    ];
    const fetchMock = mockFetchOnce({ json: async () => summaries });

    await expect(listConversations()).resolves.toEqual(summaries);

    expect(fetchMock).toHaveBeenCalledWith("/api/conversations", expect.objectContaining({ credentials: "same-origin" }));
  });

  it("does not re-sort or re-filter the response -- trusts the backend's own order as-is", async () => {
    // Deliberately out of updatedAt-DESC order -- proves this function passes the array
    // through unchanged rather than re-sorting it client-side (would duplicate
    // ConversationRepository's own ordering, per this function's [Rationale]).
    const summaries = [
      { id: "older", title: null, model: "m1", ragEnabled: false, updatedAt: "2026-08-01T00:00:00Z" },
      { id: "newer", title: null, model: "m1", ragEnabled: false, updatedAt: "2026-08-27T00:00:00Z" },
    ];
    mockFetchOnce({ json: async () => summaries });

    const result = await listConversations();

    expect(result.map((c) => c.id)).toEqual(["older", "newer"]);
  });

  it("throws ApiError on a non-2xx response", async () => {
    mockFetchOnce({ ok: false, status: 500, statusText: "Internal Server Error", text: async () => "boom" });

    await expect(listConversations()).rejects.toBeInstanceOf(ApiError);
  });
});

describe("deleteConversation", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[chat:AC-38] DELETEs the encoded conversation path and resolves without a body", async () => {
    // A real 204's .json() throws on the empty body -- this stand-in makes that failure loud
    // if deleteConversation ever regresses to routing through apiFetch instead of
    // apiFetchVoid.
    const json = jest.fn(async () => {
      throw new SyntaxError("Unexpected end of JSON input");
    });
    const fetchMock = mockFetchOnce({ status: 204, json });

    await expect(deleteConversation("conv-1")).resolves.toBeUndefined();

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/conversations/conv-1",
      expect.objectContaining({ method: "DELETE", credentials: "same-origin" }),
    );
    expect(json).not.toHaveBeenCalled();
  });

  it("URL-encodes the conversation id", async () => {
    const fetchMock = mockFetchOnce({ status: 204 });

    await deleteConversation("conv/needs encoding");

    expect(fetchMock).toHaveBeenCalledWith("/api/conversations/conv%2Fneeds%20encoding", expect.anything());
  });

  it("throws ApiError carrying 404 for an unknown/foreign/already-deleted conversation", async () => {
    mockFetchOnce({ ok: false, status: 404, statusText: "Not Found", text: async () => "not found" });

    await expect(deleteConversation("missing")).rejects.toMatchObject({ status: 404 });
  });
});
