import { ApiError } from "./client";
import { listModels } from "./models";

/** Same duck-typed Response stand-in `client.test.ts` uses, for the same portability reason. */
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

describe("listModels", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[chat:AC-39] GETs /api/models with same-origin credentials", async () => {
    const fetchMock = mockFetchOnce({ json: async () => [] });

    await listModels();

    expect(fetchMock).toHaveBeenCalledWith("/api/models", expect.objectContaining({ credentials: "same-origin" }));
  });

  it("[chat:AC-39] returns the parsed catalog unmodified, count and ids intact", async () => {
    const catalog = [
      { id: "upstage/solar-pro4", displayName: "Solar Pro 4", default: true },
      { id: "some/other-model", displayName: "Other", default: false },
    ];
    mockFetchOnce({ json: async () => catalog });

    await expect(listModels()).resolves.toEqual(catalog);
  });

  it("preserves the wire field under the key 'default', not 'isDefault' (JsonProperty pitfall)", async () => {
    // Guards the exact mistake this card's brief calls out: the backend serializes its
    // isDefault record component under @JsonProperty("default"). A regression that read/
    // remapped this to `isDefault` anywhere in this function would fail this assertion.
    mockFetchOnce({ json: async () => [{ id: "m1", displayName: "M1", default: true }] });

    const [model] = await listModels();

    expect(model.default).toBe(true);
    expect((model as unknown as Record<string, unknown>).isDefault).toBeUndefined();
  });

  it("throws ApiError on a non-2xx response", async () => {
    mockFetchOnce({ ok: false, status: 500, statusText: "Internal Server Error", text: async () => "boom" });

    await expect(listModels()).rejects.toBeInstanceOf(ApiError);
  });
});
