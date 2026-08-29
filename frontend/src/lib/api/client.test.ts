import { apiFetch, apiFetchVoid, ApiError } from "./client";

/**
 * Mocks fetch() with a plain duck-typed response object rather than a real `Response`
 * instance -- jest-environment-jsdom does not reliably expose a global `Response`
 * constructor, and client.ts only ever touches `.ok`/`.status`/`.statusText`/`.json`/
 * `.text`, so a plain object is a faithful and portable stand-in.
 */
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
    json: async () => ({}),
    text: async () => "",
    ...overrides,
  };
  const fetchMock = jest.fn().mockResolvedValue(response);
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

describe("apiFetch", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[chat:AC-40] requests same-origin credentials so the session cookie rides along", async () => {
    const fetchMock = mockFetchOnce({ json: async () => ({ userId: "u1" }) });

    await apiFetch("/api/session");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/session",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("[chat:AC-40] a caller cannot override credentials away from same-origin", async () => {
    const fetchMock = mockFetchOnce({ json: async () => ({}) });

    await apiFetch("/api/session", { credentials: "omit" });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/session",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("resolves with the parsed JSON body on a 2xx response", async () => {
    mockFetchOnce({ json: async () => ({ userId: "u1" }) });

    await expect(apiFetch("/api/session")).resolves.toEqual({ userId: "u1" });
  });

  it("throws ApiError carrying the response status on a non-2xx response", async () => {
    mockFetchOnce({ ok: false, status: 503, statusText: "Service Unavailable", text: async () => "server busy" });

    await expect(apiFetch("/api/session")).rejects.toBeInstanceOf(ApiError);
    await expect(apiFetch("/api/session")).rejects.toMatchObject({ status: 503 });
  });
});

describe("apiFetchVoid", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("requests same-origin credentials, same as apiFetch", async () => {
    const fetchMock = mockFetchOnce({ status: 204 });

    await apiFetchVoid("/api/conversations/conv-1", { method: "DELETE" });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/conversations/conv-1",
      expect.objectContaining({ method: "DELETE", credentials: "same-origin" }),
    );
  });

  it("resolves with undefined on a 2xx response, never touching response.json()", async () => {
    // A real 204 Response's .json() would throw on the empty body -- this stand-in makes
    // that failure loud (a broken apiFetchVoid that still called .json() would reject here)
    // instead of silently passing because the mock happened to return something parseable.
    const json = jest.fn(async () => {
      throw new SyntaxError("Unexpected end of JSON input");
    });
    mockFetchOnce({ status: 204, json });

    await expect(apiFetchVoid("/api/conversations/conv-1", { method: "DELETE" })).resolves.toBeUndefined();
    expect(json).not.toHaveBeenCalled();
  });

  it("throws ApiError carrying the response status on a non-2xx response", async () => {
    mockFetchOnce({ ok: false, status: 404, statusText: "Not Found", text: async () => "no such conversation" });

    await expect(apiFetchVoid("/api/conversations/missing", { method: "DELETE" })).rejects.toBeInstanceOf(ApiError);
    await expect(apiFetchVoid("/api/conversations/missing", { method: "DELETE" })).rejects.toMatchObject({
      status: 404,
    });
  });
});
