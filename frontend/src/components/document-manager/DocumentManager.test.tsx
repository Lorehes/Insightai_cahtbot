import { act } from "react";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DocumentManager } from "./DocumentManager";
import { ApiError } from "@/lib/api/client";
import { deleteDocument, listDocuments, uploadDocument, type DocumentListItem } from "@/lib/api/documents";

/**
 * Only the network boundary (`lib/api/documents`) is mocked, mirroring
 * `SessionStatus.test.tsx`/`ConversationSidebar`'s own tests. The prototype's own
 * scenario-preview chips are demo tooling this card deliberately does not port (this
 * component's own [Rationale]) -- every state below is instead reached the ordinary way, by
 * controlling what the mocked `listDocuments`/`uploadDocument`/`deleteDocument` resolve or
 * reject with.
 */
jest.mock("@/lib/api/documents");

const mockedListDocuments = listDocuments as jest.MockedFunction<typeof listDocuments>;
const mockedUploadDocument = uploadDocument as jest.MockedFunction<typeof uploadDocument>;
const mockedDeleteDocument = deleteDocument as jest.MockedFunction<typeof deleteDocument>;

function doc(overrides: Partial<DocumentListItem> = {}): DocumentListItem {
  return {
    id: "doc-1",
    filename: "report.pdf",
    mimeType: "application/pdf",
    sizeBytes: 1_887_437, // rounds to "1.8 MB", the prototype's own example
    status: "INDEXED",
    chunkCount: 12,
    errorMessage: null,
    createdAt: "2026-08-25T05:02:00Z",
    indexedAt: "2026-08-25T05:03:00Z",
    ...overrides,
  };
}

/** Mirrors `DocumentManager.tsx`'s own `formatDateTime` (local-timezone rendering) so this
 *  file's assertions do not assume the test runner's timezone is KST. */
function expectedDateTime(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

describe("DocumentManager", () => {
  beforeEach(() => {
    mockedListDocuments.mockReset();
    mockedUploadDocument.mockReset();
    mockedDeleteDocument.mockReset();
  });

  describe("로딩 상태", () => {
    it("shows the skeleton table with an accessible status text before the first fetch resolves", async () => {
      let resolveList: (docs: DocumentListItem[]) => void = () => {};
      mockedListDocuments.mockReturnValue(
        new Promise((resolve) => {
          resolveList = resolve;
        }),
      );

      render(<DocumentManager />);

      // `role="status"` computes its accessible name from `aria-label`/`aria-labelledby`
      // only, never from content (ARIA "name from content" is not allowed for this role) --
      // so this asserts on the `sr-only` text node directly, the same way
      // `SessionStatus.test.tsx` asserts its own `role="status"` loading text via
      // `findByText`, not `findByRole(..., { name })`.
      const status = screen.getByRole("status");
      expect(within(status).getByText("문서 목록을 불러오는 중입니다")).toBeInTheDocument();

      await act(async () => {
        resolveList([]);
      });

      await waitFor(() => expect(screen.queryByText("문서 목록을 불러오는 중입니다")).not.toBeInTheDocument());
    });
  });

  describe("에러 상태", () => {
    it("[document-ingestion:AC-14] shows the retry-capable error banner reflecting the real status code, and retries on click", async () => {
      mockedListDocuments.mockRejectedValueOnce(new ApiError(500, "500 Internal Server Error: boom"));
      mockedListDocuments.mockResolvedValueOnce([]);

      render(<DocumentManager />);

      expect(await screen.findByRole("alert")).toHaveTextContent("문서 목록을 불러오지 못했습니다");
      expect(
        screen.getByText("GET /api/admin/documents 요청이 실패했습니다 (500 Internal Server Error)."),
      ).toBeInTheDocument();

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText("업로드된 문서가 없습니다")).toBeInTheDocument();
      expect(mockedListDocuments).toHaveBeenCalledTimes(2);
    });
  });

  describe("빈 상태", () => {
    it("shows the empty-list message when the fetch succeeds with zero documents", async () => {
      mockedListDocuments.mockResolvedValueOnce([]);

      render(<DocumentManager />);

      expect(await screen.findByText("업로드된 문서가 없습니다")).toBeInTheDocument();
      expect(screen.getByText(/PDF, DOCX, PPTX, XLSX, TXT, MD 를 지원합니다/)).toBeInTheDocument();
    });
  });

  describe("정상 상태 (표)", () => {
    it("[document-ingestion:AC-14] renders one row per document with the documented columns", async () => {
      mockedListDocuments.mockResolvedValueOnce([
        doc(),
        doc({
          id: "doc-2",
          filename: "notes", // no extension -- exercises the mimeType fallback
          mimeType: "text/markdown",
          sizeBytes: 831_488, // rounds to "812 KB" -- deliberately distinct from row 1's size
          status: "FAILED",
          chunkCount: null,
          indexedAt: null,
          errorMessage: "parse failed: unexpected EOF",
        }),
      ]);

      render(<DocumentManager />);

      const table = await screen.findByRole("table");
      const rows = within(table).getAllByRole("row");
      expect(rows).toHaveLength(3); // header + 2 documents
      // Row-scoped (not table-scoped) queries below -- "색인 완료" is both a column header
      // (rows[0]) and the INDEXED status label, so a table-wide getByText would collide.
      const [, row1, row2] = rows;

      // Row 1 -- INDEXED, extension-derived format, chunk count, both dates.
      expect(within(row1).getByText("report.pdf")).toBeInTheDocument();
      expect(within(row1).getByText("pdf")).toBeInTheDocument();
      expect(within(row1).getByText("1.8 MB")).toBeInTheDocument();
      expect(within(row1).getByText("색인 완료")).toBeInTheDocument();
      // `StatusBadge`'s own [Caution]: `data-status` is the wording-independent anchor --
      // asserted alongside (not instead of) the Korean label above, so a future label wording
      // change there is not misattributed as this card's own regression.
      expect(within(row1).getByText("색인 완료")).toHaveAttribute("data-status", "INDEXED");
      expect(within(row1).getByText("12")).toBeInTheDocument();
      expect(within(row1).getByText(expectedDateTime(doc().createdAt))).toBeInTheDocument();
      expect(within(row1).getByText(expectedDateTime(doc().indexedAt as string))).toBeInTheDocument();

      // Row 2 -- FAILED, mimeType-derived format (no extension), null chunk/indexedAt as "—".
      expect(within(row2).getByText("notes")).toBeInTheDocument();
      expect(within(row2).getByText("md")).toBeInTheDocument();
      expect(within(row2).getByText("812 KB")).toBeInTheDocument();
      expect(within(row2).getByText("실패")).toBeInTheDocument();
      expect(within(row2).getByText("실패")).toHaveAttribute("data-status", "FAILED");
      expect(within(row2).getByText("parse failed: unexpected EOF")).toBeInTheDocument();
      const dashes = within(row2).getAllByText("—");
      expect(dashes.length).toBe(2); // chunk count + indexed-at, both null

      expect(screen.getByText("총 2개 문서 · 최신 업로드 순")).toBeInTheDocument();
    });
  });

  describe("폴링 안내", () => {
    it("[document-ingestion:AC-14] polls every 3s while a document is non-terminal, and stops once it reaches a terminal status", async () => {
      jest.useFakeTimers();
      try {
        mockedListDocuments
          .mockResolvedValueOnce([doc({ status: "EMBEDDING", chunkCount: null, indexedAt: null })])
          .mockResolvedValueOnce([doc({ status: "INDEXED" })]);

        render(<DocumentManager />);

        // Flushes the mount effect's initial listDocuments() call. Real fetch/timer fake
        // timers don't gate microtasks, but `jest.advanceTimersByTimeAsync` (Jest 30) also
        // drains any promise settled during the advance -- `waitFor`/`findBy*`'s own retry
        // loop schedules through the (now fake) `setTimeout` too, so it would otherwise never
        // fire on its own the way it does under real timers.
        await act(async () => {
          await jest.advanceTimersByTimeAsync(0);
        });

        // `role="status"` computes its accessible name from `aria-label`/`aria-labelledby`
        // only, not content -- asserted on the visible text directly (same reasoning as the
        // loading-state test above).
        expect(screen.getByText(/처리 중인 문서가 있어/)).toBeInTheDocument();
        expect(mockedListDocuments).toHaveBeenCalledTimes(1);

        await act(async () => {
          await jest.advanceTimersByTimeAsync(3000);
        });

        expect(mockedListDocuments).toHaveBeenCalledTimes(2);
        expect(screen.queryByText(/처리 중인 문서가 있어/)).not.toBeInTheDocument();

        // Terminal state reached -- a further tick must not poll again.
        await act(async () => {
          await jest.advanceTimersByTimeAsync(3000);
        });
        expect(mockedListDocuments).toHaveBeenCalledTimes(2);
      } finally {
        jest.useRealTimers();
      }
    });

    it("does not show the polling banner when every document is already terminal", async () => {
      mockedListDocuments.mockResolvedValueOnce([doc({ status: "INDEXED" }), doc({ id: "doc-2", status: "FAILED" })]);

      render(<DocumentManager />);

      await screen.findByRole("table");
      expect(screen.queryByText(/처리 중인 문서가 있어/)).not.toBeInTheDocument();
    });

    it("does not poll a moment before 3s elapses, but does the instant it does (lower bound)", async () => {
      jest.useFakeTimers();
      try {
        mockedListDocuments
          .mockResolvedValueOnce([doc({ status: "EMBEDDING", chunkCount: null, indexedAt: null })])
          .mockResolvedValueOnce([doc({ status: "EMBEDDING", chunkCount: null, indexedAt: null })]);

        render(<DocumentManager />);

        await act(async () => {
          await jest.advanceTimersByTimeAsync(0);
        });
        expect(mockedListDocuments).toHaveBeenCalledTimes(1);

        // 1ms short of the 3s interval -- must not have polled yet (review correctness Low-1:
        // only the upper bound was previously fixed).
        await act(async () => {
          await jest.advanceTimersByTimeAsync(2999);
        });
        expect(mockedListDocuments).toHaveBeenCalledTimes(1);

        // The remaining 1ms crosses the 3000ms mark.
        await act(async () => {
          await jest.advanceTimersByTimeAsync(1);
        });
        expect(mockedListDocuments).toHaveBeenCalledTimes(2);
      } finally {
        jest.useRealTimers();
      }
    });

    it("does not resume polling after unmount", async () => {
      jest.useFakeTimers();
      try {
        mockedListDocuments.mockResolvedValueOnce([doc({ status: "EMBEDDING", chunkCount: null, indexedAt: null })]);

        const { unmount } = render(<DocumentManager />);
        await act(async () => {
          await jest.advanceTimersByTimeAsync(0);
        });
        expect(mockedListDocuments).toHaveBeenCalledTimes(1);

        unmount();

        // review correctness Low-5: the mount effect's cleanup must clear the interval, so a
        // full interval elapsing post-unmount must not trigger a further fetch.
        await act(async () => {
          await jest.advanceTimersByTimeAsync(3000);
        });
        expect(mockedListDocuments).toHaveBeenCalledTimes(1);
      } finally {
        jest.useRealTimers();
      }
    });
  });

  describe("업로드", () => {
    it("[document-ingestion:AC-14] enables Upload only once a file is chosen, then uploads and shows the refreshed PENDING status", async () => {
      // review correctness Medium-2: the post-upload refetch fixture used to resolve straight
      // to INDEXED, so AC-14's first clause ("진행 상태 PENDING 표시") was never actually
      // observed by any tagged test -- it only holds now because this fixture mirrors what a
      // real backend returns immediately after upload (Spec §2 pipeline starts at PENDING).
      mockedListDocuments
        .mockResolvedValueOnce([])
        .mockResolvedValueOnce([doc({ status: "PENDING", chunkCount: null, indexedAt: null })]);
      mockedUploadDocument.mockResolvedValueOnce({ id: "doc-1", filename: "report.pdf", status: "PENDING" });

      const user = userEvent.setup();
      render(<DocumentManager />);
      await screen.findByText("업로드된 문서가 없습니다");

      const uploadButton = screen.getByRole("button", { name: "업로드" });
      expect(uploadButton).toBeDisabled();
      expect(screen.getByText("PDF, DOCX, PPTX, XLSX, TXT, MD · 파일당 최대 20 MB")).toBeInTheDocument();

      const file = new File(["%PDF-1.4"], "report.pdf", { type: "application/pdf" });
      await user.upload(screen.getByLabelText("파일 선택"), file);

      expect(screen.getByText(/^선택됨 · report\.pdf/)).toBeInTheDocument();
      expect(uploadButton).toBeEnabled();

      await user.click(uploadButton);

      await waitFor(() => expect(mockedUploadDocument).toHaveBeenCalledWith(file));
      await screen.findByText("report.pdf"); // list refreshed -- table now shows the row
      expect(mockedListDocuments).toHaveBeenCalledTimes(2);
      // AC-14 first clause, actually observed: the freshly-uploaded row's progress status
      // (PENDING/"대기 중") renders, and the polling banner appears because the list now
      // contains a non-terminal document.
      expect(screen.getByText("대기 중")).toBeInTheDocument();
      expect(screen.getByText("대기 중")).toHaveAttribute("data-status", "PENDING");
      expect(screen.getByText(/처리 중인 문서가 있어/)).toBeInTheDocument();
      // Selection cleared back to the default hint after a successful upload.
      expect(screen.getByText("PDF, DOCX, PPTX, XLSX, TXT, MD · 파일당 최대 20 MB")).toBeInTheDocument();
    });

    it("shows an inline error when the upload call fails", async () => {
      mockedListDocuments.mockResolvedValue([]);
      mockedUploadDocument.mockRejectedValueOnce(new ApiError(413, "413 Payload Too Large: too big"));

      const user = userEvent.setup();
      render(<DocumentManager />);
      await screen.findByText("업로드된 문서가 없습니다");

      const file = new File(["x"], "huge.pdf", { type: "application/pdf" });
      await user.upload(screen.getByLabelText("파일 선택"), file);
      await user.click(screen.getByRole("button", { name: "업로드" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("업로드하지 못했습니다");
    });
  });

  describe("삭제 확인 다이얼로그", () => {
    it("[document-ingestion:AC-14] opens on the row's delete button, cancels without calling DELETE, then confirms and removes the row", async () => {
      mockedListDocuments.mockResolvedValueOnce([doc()]).mockResolvedValueOnce([]);
      mockedDeleteDocument.mockResolvedValueOnce(undefined);

      const user = userEvent.setup();
      render(<DocumentManager />);
      await screen.findByText("report.pdf");

      await user.click(screen.getByRole("button", { name: "report.pdf 삭제" }));

      const dialog = await screen.findByRole("dialog", { name: "문서를 삭제할까요?" });
      expect(within(dialog).getByText(/“report\.pdf” 과 이 문서에서 생성된/)).toBeInTheDocument();

      await user.click(within(dialog).getByRole("button", { name: "취소" }));
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(mockedDeleteDocument).not.toHaveBeenCalled();

      await user.click(screen.getByRole("button", { name: "report.pdf 삭제" }));
      const reopened = await screen.findByRole("dialog");
      await user.click(within(reopened).getByRole("button", { name: "삭제" }));

      await waitFor(() => expect(mockedDeleteDocument).toHaveBeenCalledWith("doc-1"));
      expect(await screen.findByText("업로드된 문서가 없습니다")).toBeInTheDocument();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("closes on Escape without calling DELETE", async () => {
      mockedListDocuments.mockResolvedValue([doc()]);
      const user = userEvent.setup();
      render(<DocumentManager />);
      await screen.findByText("report.pdf");

      await user.click(screen.getByRole("button", { name: "report.pdf 삭제" }));
      await screen.findByRole("dialog");

      await user.keyboard("{Escape}");

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(mockedDeleteDocument).not.toHaveBeenCalled();
    });

    it("shows an inline error and keeps the list unchanged when DELETE fails", async () => {
      mockedListDocuments.mockResolvedValue([doc()]);
      mockedDeleteDocument.mockRejectedValueOnce(new ApiError(503, "503 Service Unavailable: lock timeout"));

      const user = userEvent.setup();
      render(<DocumentManager />);
      await screen.findByText("report.pdf");

      await user.click(screen.getByRole("button", { name: "report.pdf 삭제" }));
      const dialog = await screen.findByRole("dialog");
      await user.click(within(dialog).getByRole("button", { name: "삭제" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("삭제하지 못했습니다");
      expect(screen.getByText("report.pdf")).toBeInTheDocument(); // row still present
    });
  });
});
