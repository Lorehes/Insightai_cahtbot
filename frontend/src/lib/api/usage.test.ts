import { ApiError } from "./client";
import { fetchUsage } from "./usage";

/** Same duck-typed Response stand-in `client.test.ts`/`documents.test.ts` use, for the same
 *  portability reason (jest-environment-jsdom does not reliably expose a global `Response`
 *  constructor). */
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

/**
 * [Purpose] Locks `fetchUsage`'s URL-building path (review 2026-08-30 Medium-2 — this was the
 *   one `lib/api/*` module with zero tests exercising `global.fetch` directly, unlike its 6
 *   sibling modules; `documents.test.ts`'s pattern is reused verbatim).
 * [Caution] None of the `it()`s below carry the `[eval-usage-dashboard:AC-18]` tag -- AC-18 is a
 *   screen-display requirement (`UsageDashboard` shows 4 fields), and these tests never render
 *   anything; they lock `fetchUsage`'s own request-building contract one layer below the screen.
 *   Tagging them anyway would repeat the over-scoping review 2026-08-30 Medium-3 found in
 *   `UsageDashboard.test.tsx` (that file's own [Caution] explains the same distinction).
 */
describe("fetchUsage", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("percent-encodes the offset-bearing 'from'/'to' query values (the '+' in '+09:00' becomes %2B)", async () => {
    const body = {
      totalRequests: 1,
      promptTokens: 100,
      completionTokens: 40,
      totalCostUsd: 0.0025,
      missingCostCount: 0,
      byModel: [],
    };
    const fetchMock = mockFetchOnce({ json: async () => body });

    await expect(
      fetchUsage("2026-08-01T00:00:00+09:00", "2026-08-02T00:00:00+09:00"),
    ).resolves.toEqual(body);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe(
      "/api/admin/usage?from=2026-08-01T00%3A00%3A00%2B09%3A00&to=2026-08-02T00%3A00%3A00%2B09%3A00",
    );
    expect(init).toEqual(expect.objectContaining({ credentials: "same-origin" }));
  });

  it("omits the query string entirely when both 'from' and 'to' are left out", async () => {
    const fetchMock = mockFetchOnce({
      json: async () => ({
        totalRequests: 0,
        promptTokens: 0,
        completionTokens: 0,
        totalCostUsd: 0,
        missingCostCount: 0,
        byModel: [],
      }),
    });

    await fetchUsage();

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/usage",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("throws ApiError on a non-2xx response", async () => {
    mockFetchOnce({ ok: false, status: 400, statusText: "Bad Request", text: async () => "from > to" });

    await expect(fetchUsage("2026-08-02", "2026-08-01")).rejects.toBeInstanceOf(ApiError);
  });
});
