import { ApiError } from "./client";
import { deleteDocument, listDocuments, uploadDocument } from "./documents";

/** Same duck-typed Response stand-in `client.test.ts`/`conversations.test.ts` use, for the
 *  same portability reason (jest-environment-jsdom does not reliably expose a global
 *  `Response` constructor). */
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

describe("uploadDocument", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[document-ingestion:AC-14] POSTs a multipart body under the 'file' field and returns the parsed response", async () => {
    const created = { id: "doc-1", filename: "a.pdf", status: "PENDING" };
    const fetchMock = mockFetchOnce({ status: 202, json: async () => created });
    const file = new File(["hello"], "a.pdf", { type: "application/pdf" });

    await expect(uploadDocument(file)).resolves.toEqual(created);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/admin/documents");
    expect(init).toEqual(
      expect.objectContaining({ method: "POST", credentials: "same-origin" }),
    );
    const body = init.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect(body.get("file")).toBe(file);
  });

  it("throws ApiError on a non-2xx response", async () => {
    mockFetchOnce({ ok: false, status: 400, statusText: "Bad Request", text: async () => "boom" });
    const file = new File(["x"], "bad.exe");

    await expect(uploadDocument(file)).rejects.toBeInstanceOf(ApiError);
  });
});

describe("listDocuments", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[document-ingestion:AC-14] GETs /api/admin/documents and returns the parsed array unmodified", async () => {
    const documents = [
      {
        id: "doc-1",
        filename: "a.pdf",
        mimeType: "application/pdf",
        sizeBytes: 1024,
        status: "INDEXED",
        chunkCount: 3,
        errorMessage: null,
        createdAt: "2026-08-25T00:00:00Z",
        indexedAt: "2026-08-25T00:01:00Z",
      },
    ];
    const fetchMock = mockFetchOnce({ json: async () => documents });

    await expect(listDocuments()).resolves.toEqual(documents);

    expect(fetchMock).toHaveBeenCalledWith("/api/admin/documents", expect.objectContaining({ credentials: "same-origin" }));
  });

  it("throws ApiError on a non-2xx response", async () => {
    mockFetchOnce({ ok: false, status: 500, statusText: "Internal Server Error", text: async () => "boom" });

    await expect(listDocuments()).rejects.toBeInstanceOf(ApiError);
  });
});

describe("deleteDocument", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("[document-ingestion:AC-14] DELETEs the encoded document path and resolves without a body", async () => {
    // A real 204's .json() throws on the empty body -- this stand-in makes that failure loud
    // if deleteDocument ever regresses to routing through apiFetch instead of apiFetchVoid.
    const json = jest.fn(async () => {
      throw new SyntaxError("Unexpected end of JSON input");
    });
    const fetchMock = mockFetchOnce({ status: 204, json });

    await expect(deleteDocument("doc-1")).resolves.toBeUndefined();

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/documents/doc-1",
      expect.objectContaining({ method: "DELETE", credentials: "same-origin" }),
    );
    expect(json).not.toHaveBeenCalled();
  });

  it("URL-encodes the document id", async () => {
    const fetchMock = mockFetchOnce({ status: 204 });

    await deleteDocument("doc/needs encoding");

    expect(fetchMock).toHaveBeenCalledWith("/api/admin/documents/doc%2Fneeds%20encoding", expect.anything());
  });

  it("throws ApiError carrying 503 when the delete lock times out", async () => {
    mockFetchOnce({ ok: false, status: 503, statusText: "Service Unavailable", text: async () => "lock timeout" });

    await expect(deleteDocument("doc-1")).rejects.toMatchObject({ status: 503 });
  });

  it("throws ApiError carrying 404 for an unknown document", async () => {
    mockFetchOnce({ ok: false, status: 404, statusText: "Not Found", text: async () => "not found" });

    await expect(deleteDocument("missing")).rejects.toMatchObject({ status: 404 });
  });
});
