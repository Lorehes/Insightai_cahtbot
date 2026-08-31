"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api/client";
import {
  deleteDocument,
  listDocuments,
  uploadDocument,
  type DocumentListItem,
} from "@/lib/api/documents";
import { StatusBadge, type DocumentStatus } from "@/components/status-badge/StatusBadge";
import { ErrorMessageCell, TruncatedCell } from "@/components/truncated-cell/TruncatedCell";
import styles from "./DocumentManager.module.css";

/** Spec Tech Review AD-12: admin status refresh is a 3s poll, not SSE (SSE is chat Spec's
 *  own concern) -- stopped once nothing in the list is still mid-pipeline. */
const POLL_INTERVAL_MS = 3000;

/** The 3 states `DocumentStatus` covers before a document reaches INDEXED or FAILED --
 *  presence of any of these in the list is what keeps {@link POLL_INTERVAL_MS} polling alive
 *  and shows the polling-indicator banner. */
const NON_TERMINAL_STATUSES: ReadonlySet<DocumentStatus> = new Set(["PENDING", "PARSING", "EMBEDDING"]);

/** Matches the upload form's own visible hint text below -- kept as one list so the `accept`
 *  attribute and the hint text can never silently drift apart from each other. All 6 extensions
 *  the backend actually accepts (`application.yaml`'s `app.ingest.allowed-mime-types`,
 *  document-ingestion Spec §7 OQ-2/AD-5 confirmed 6-type set) -- review 2026-08-30 T11 1R
 *  Medium-1 (correctness): this list used to stop at 4 (missing `.pptx`/`.docx`'s sibling
 *  `.xlsx`), even though {@link MIME_TO_FORMAT_LABEL} below already listed all 6, so a user
 *  could not even pick a pptx/xlsx file from the OS file dialog for a format the backend was
 *  always willing to accept. */
const ACCEPTED_FILE_EXTENSIONS = [".pdf", ".docx", ".pptx", ".xlsx", ".txt", ".md"];

type ListState =
  | { status: "loading" }
  | { status: "ready"; documents: DocumentListItem[] }
  | { status: "error"; detail: string };

/**
 * [Purpose] Card T11's admin document management screen -- upload form, status table with a
 *   3-second poll while anything is mid-pipeline, and a delete-confirm dialog (Spec §5
 *   Affected Surfaces, prototype blob `8761c6b8-...`). The single composition root/state owner
 *   for `app/admin/documents/page.tsx`, mirroring how `ChatWorkspace` is the thin `page.tsx`'s
 *   real logic owner one Spec over.
 * [Rationale] The prototype's scenario-preview chips (목록/빈 상태/로딩/에러 전환) are its own
 *   demo tooling, not part of the shipped screen -- per this card's brief, every one of those
 *   states is instead reached the ordinary way, branching on this component's own fetch/poll
 *   state (`ListState` above) rather than a manually-selected preview mode. `StatusBadge` and
 *   `TruncatedCell`/`ErrorMessageCell` (design-system D5/D6) are reused as-is, not
 *   reimplemented -- both were purpose-built for this exact screen (see their own [Purpose]).
 * [Caution] Upload does not simulate server-side progress the way the prototype's demo script
 *   did -- a real backend actually runs the PENDING->...->INDEXED/FAILED pipeline
 *   asynchronously, so this component only ever refetches the list (on upload success, and on
 *   every poll tick) and lets the real state drive what renders; it never fabricates an
 *   intermediate status client-side.
 * [AC] document-ingestion:AC-14
 */
export function DocumentManager() {
  const [list, setList] = useState<ListState>({ status: "loading" });
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<{ id: string; filename: string } | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    try {
      const documents = await listDocuments();
      setList({ status: "ready", documents });
    } catch (err: unknown) {
      setList({ status: "error", detail: describeListFailureDetail(err) });
    }
  }, []);

  // Initial load -- an inline async IIFE, not `void refresh()`, for the same reason
  // `ChatWorkspace`'s own mount effect does this: `react-hooks/set-state-in-effect` flags a
  // direct call to a `useCallback` that (eventually, after an `await`) calls `setState` as if
  // it were synchronous, even though the actual update only happens post-`await`. `cancelled`
  // guards against setting state after unmount, mirroring `ModelSelector`'s own mount effect.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const documents = await listDocuments();
        if (!cancelled) setList({ status: "ready", documents });
      } catch (err: unknown) {
        if (!cancelled) setList({ status: "error", detail: describeListFailureDetail(err) });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Spec AD-12: poll every 3s while any row is still mid-pipeline; stop the instant none are.
  // Re-runs on every `list` change (including the poll's own refetch), which just re-arms the
  // same 3s cadence -- the cleanup below still guarantees exactly one live timer and fires on
  // unmount, same "clear on cleanup" shape every other effect in this codebase uses.
  useEffect(() => {
    if (list.status !== "ready") return;
    const hasNonTerminal = list.documents.some((doc) => NON_TERMINAL_STATUSES.has(doc.status));
    if (!hasNonTerminal) return;
    const timer = setInterval(() => {
      void refresh();
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [list, refresh]);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSelectedFile(e.target.files?.[0] ?? null);
    setUploadError(null);
  };

  const handleUpload = useCallback(async () => {
    if (!selectedFile) return;
    setIsUploading(true);
    setUploadError(null);
    try {
      await uploadDocument(selectedFile);
      setSelectedFile(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
      await refresh();
    } catch (err: unknown) {
      setUploadError(describeActionError(err, "업로드하지 못했습니다"));
    } finally {
      setIsUploading(false);
    }
  }, [selectedFile, refresh]);

  const handleDeleteClick = useCallback((id: string, filename: string) => {
    setDeleteError(null);
    setPendingDelete({ id, filename });
  }, []);

  const handleCancelDelete = useCallback(() => {
    setPendingDelete(null);
  }, []);

  const handleConfirmDelete = useCallback(async () => {
    if (!pendingDelete) return;
    setIsDeleting(true);
    try {
      await deleteDocument(pendingDelete.id);
      setPendingDelete(null);
      await refresh();
    } catch (err: unknown) {
      setDeleteError(describeActionError(err, "삭제하지 못했습니다"));
    } finally {
      setIsDeleting(false);
    }
  }, [pendingDelete, refresh]);

  const hasNonTerminal =
    list.status === "ready" && list.documents.some((doc) => NON_TERMINAL_STATUSES.has(doc.status));

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1>문서 관리</h1>
        <p className={styles.description}>
          업로드한 문서를 파싱·임베딩해 검색 색인에 반영합니다. 색인이 완료된 문서만 답변 근거로
          사용됩니다.
        </p>
      </header>

      <section className={styles.uploadForm} aria-label="문서 업로드">
        <div className={styles.uploadHeading}>
          <span aria-hidden="true">📄</span> 문서 업로드
        </div>
        <p className={styles.uploadHint}>
          {selectedFile
            ? `선택됨 · ${selectedFile.name} (${formatSize(selectedFile.size)})`
            : // 프로토타입 원문은 "최대 50 MB" -- 실제 백엔드 한도는 application.yaml의
              // app.ingest.max-file-size: 20MB (document-ingestion Spec §7 OQ-2 확정값)이라 그
              // 값으로 정정해 표시한다. 프로토타입 문구를 그대로 두면 20~50MB 파일이 이 화면의
              // 안내만 보고는 통과할 것으로 오인했다가 413으로 거부당한다.
              //
              // review 2026-08-30 T11 1R Medium-1(correctness): 프로토타입 원문("PDF, DOCX,
              // TXT, MD")도 4종만 나열해 실제 백엔드 허용 6종(PPTX·XLSX 누락) 중 2종을 빠뜨리고
              // 있었다 -- ACCEPTED_FILE_EXTENSIONS와 함께 6종 전부로 정정.
              "PDF, DOCX, PPTX, XLSX, TXT, MD · 파일당 최대 20 MB"}
        </p>
        <div className={styles.uploadActions}>
          <label className={styles.fileLabel}>
            파일 선택
            <input
              ref={fileInputRef}
              type="file"
              accept={ACCEPTED_FILE_EXTENSIONS.join(",")}
              className={styles.fileInput}
              onChange={handleFileChange}
              disabled={isUploading}
            />
          </label>
          <button
            type="button"
            className={styles.uploadButton}
            onClick={() => void handleUpload()}
            disabled={!selectedFile || isUploading}
          >
            업로드
          </button>
        </div>
        {uploadError !== null && (
          <p role="alert" className={styles.error}>
            {uploadError}
          </p>
        )}
      </section>

      {hasNonTerminal && (
        <p role="status" className={styles.pollingIndicator}>
          <span aria-hidden="true">⟳</span> 처리 중인 문서가 있어 3초마다 상태를 확인하고
          있습니다.
        </p>
      )}

      {list.status === "loading" && <LoadingState />}

      {list.status === "error" && <ErrorState detail={list.detail} onRetry={() => void refresh()} />}

      {list.status === "ready" && list.documents.length === 0 && <EmptyState />}

      {list.status === "ready" && list.documents.length > 0 && (
        <>
          {deleteError !== null && (
            <p role="alert" className={styles.error}>
              {deleteError}
            </p>
          )}
          <DocumentTable documents={list.documents} onDeleteClick={handleDeleteClick} />
          <p className={styles.footerCount}>총 {list.documents.length}개 문서 · 최신 업로드 순</p>
        </>
      )}

      {pendingDelete !== null && (
        <DeleteConfirmDialog
          filename={pendingDelete.filename}
          isDeleting={isDeleting}
          onCancel={handleCancelDelete}
          onConfirm={() => void handleConfirmDelete()}
        />
      )}
    </div>
  );
}

/** [Purpose] "최초 목록 로딩 중" state -- 5 shimmering skeleton rows, no visible text (prototype
 *  spec), with an `sr-only` status text so the wait is still announced non-visually. */
function LoadingState() {
  return (
    <div role="status" className={styles.loadingState}>
      <span className={styles.srOnly}>문서 목록을 불러오는 중입니다</span>
      <table className={styles.table} aria-hidden="true">
        <tbody>
          {Array.from({ length: 5 }, (_, rowIndex) => (
            <tr key={rowIndex}>
              {Array.from({ length: 8 }, (__, cellIndex) => (
                <td key={cellIndex}>
                  <div className={styles.skeletonBar} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** [Purpose] `GET /api/admin/documents` failure state -- prototype's fixed "(500 ...)" wording,
 *  with `detail` (this call's actual status) substituted in place of the prototype's hardcoded
 *  500 (this card's brief: "프로토타입은 500 고정, 실제로는 받은 상태코드 반영"). */
function ErrorState({ detail, onRetry }: { detail: string; onRetry: () => void }) {
  return (
    <div role="alert" className={styles.errorState}>
      <h2>문서 목록을 불러오지 못했습니다</h2>
      <p>GET /api/admin/documents 요청이 실패했습니다 ({detail}).</p>
      <button type="button" className={styles.retryButton} onClick={onRetry}>
        다시 시도
      </button>
    </div>
  );
}

/** [Purpose] Zero-document state -- distinct from the error state above; a successful fetch
 *  that simply returned an empty array. */
function EmptyState() {
  return (
    <div className={styles.emptyState}>
      <h2>업로드된 문서가 없습니다</h2>
      <p>
        문서를 업로드하면 자동으로 파싱·임베딩되어 채팅 답변의 근거로 사용됩니다. PDF, DOCX,
        PPTX, XLSX, TXT, MD 를 지원합니다.
      </p>
    </div>
  );
}

/**
 * [Purpose] The document list table -- one row per {@link DocumentListItem}, columns in the
 *   prototype's specified order (파일명/형식/크기/상태/청크/업로드/색인 완료/삭제).
 * [Rationale] `TruncatedCell`/`ErrorMessageCell`/`StatusBadge` (design-system D5/D6) are reused
 *   verbatim for the filename, FAILED error text, and status columns respectively -- this
 *   component only supplies the `<table>` structure, column-width CSS, and the remaining
 *   columns those primitives do not cover (their own [Caution]s explicitly leave column-width
 *   constraints and status-mapping colors to this card).
 * [AC] document-ingestion:AC-14
 */
function DocumentTable({
  documents,
  onDeleteClick,
}: {
  documents: DocumentListItem[];
  onDeleteClick: (id: string, filename: string) => void;
}) {
  return (
    <table className={styles.table}>
      <colgroup>
        <col className={styles.colFilename} />
        <col className={styles.colFormat} />
        <col className={styles.colSize} />
        <col className={styles.colStatus} />
        <col className={styles.colChunks} />
        <col className={styles.colUploaded} />
        <col className={styles.colIndexed} />
        <col className={styles.colActions} />
      </colgroup>
      <thead>
        <tr>
          <th scope="col">파일명</th>
          <th scope="col">형식</th>
          <th scope="col" className={styles.alignRight}>
            크기
          </th>
          <th scope="col">상태</th>
          <th scope="col" className={styles.alignRight}>
            청크
          </th>
          <th scope="col">업로드</th>
          <th scope="col">색인 완료</th>
          <th scope="col">
            <span className={styles.srOnly}>삭제</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {documents.map((doc) => (
          <tr key={doc.id}>
            <td>
              <TruncatedCell text={doc.filename} />
              {doc.status === "FAILED" && doc.errorMessage !== null && (
                <div className={styles.rowError}>
                  <span aria-hidden="true">⚠</span>
                  <ErrorMessageCell text={doc.errorMessage} />
                </div>
              )}
            </td>
            <td className={styles.formatCell}>{formatFileFormat(doc.filename, doc.mimeType)}</td>
            <td className={styles.alignRight}>{formatSize(doc.sizeBytes)}</td>
            <td>
              <StatusBadge status={doc.status} />
            </td>
            <td className={styles.alignRight}>{doc.chunkCount ?? "—"}</td>
            <td>{formatDateTime(doc.createdAt)}</td>
            <td>{doc.indexedAt !== null ? formatDateTime(doc.indexedAt) : "—"}</td>
            <td className={styles.alignRight}>
              <button
                type="button"
                className={styles.deleteButton}
                onClick={() => onDeleteClick(doc.id, doc.filename)}
                aria-label={`${doc.filename} 삭제`}
              >
                <span aria-hidden="true">🗑</span> 삭제
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * [Purpose] Delete-confirm modal (prototype `[DeleteConfirmDialog]`) -- the one irreversible
 *   action this screen exposes, guarded the way the prototype's own copy specifies rather than
 *   `ConversationSidebar`'s plain `window.confirm()` (that component's own [Rationale]
 *   explicitly chose `confirm()` only because its card left the dialog *undecided*; this
 *   card's brief gives the exact title/body/button text, so a real dialog is what was asked
 *   for, not a judgment call being made here).
 * [Caution] Not a full focus trap -- Escape closes and the "삭제" button receives initial
 *   focus (both required by this project's accessibility defaults), but Tab can still reach
 *   the table behind the overlay. Scoped this way deliberately (card brief: "과하게 설계하지
 *   마라"); revisit if a future card needs a stricter trap.
 * [AC] document-ingestion:AC-14
 */
function DeleteConfirmDialog({
  filename,
  isDeleting,
  onCancel,
  onConfirm,
}: {
  filename: string;
  isDeleting: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const confirmButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    confirmButtonRef.current?.focus();
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onCancel]);

  return (
    <div className={styles.dialogOverlay} onClick={onCancel}>
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-confirm-title"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="delete-confirm-title">문서를 삭제할까요?</h2>
        <p>
          “{filename}” 과 이 문서에서 생성된 청크·임베딩이 모두 삭제됩니다. 이후 답변에서 이
          문서는 근거로 사용되지 않습니다.
        </p>
        <div className={styles.dialogActions}>
          <button type="button" className={styles.cancelButton} onClick={onCancel} disabled={isDeleting}>
            취소
          </button>
          <button
            type="button"
            ref={confirmButtonRef}
            className={styles.confirmDeleteButton}
            onClick={onConfirm}
            disabled={isDeleting}
          >
            삭제
          </button>
        </div>
      </div>
    </div>
  );
}

/** Backend `allowed-mime-types` (application.yaml, document-ingestion Spec §7 OQ-2) -- used
 *  only as this table's fallback when a filename has no extension of its own. Kept in sync
 *  with that config list by hand; a mismatch only ever degrades the "형식" column's label,
 *  never upload validation (the backend enforces that independently). */
const MIME_TO_FORMAT_LABEL: Record<string, string> = {
  "application/pdf": "pdf",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation": "pptx",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx",
  "text/plain": "txt",
  "text/markdown": "md",
};

/** Prefers the filename's own extension (prototype: "파일명 확장자 또는 mimeType에서 유도한
 *  짧은 표기") -- falls back to {@link MIME_TO_FORMAT_LABEL} only when the filename has none. */
function formatFileFormat(filename: string, mimeType: string): string {
  const dotIndex = filename.lastIndexOf(".");
  if (dotIndex > 0 && dotIndex < filename.length - 1) {
    return filename.slice(dotIndex + 1).toLowerCase();
  }
  return MIME_TO_FORMAT_LABEL[mimeType] ?? mimeType;
}

/** Formats bytes as "120 B" / "812 KB" / "1.8 MB" (prototype's own examples) -- integer when
 *  the rounded value has no fractional part, one decimal place otherwise. */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units: [threshold: number, label: string][] = [
    [1024 * 1024, "MB"],
    [1024, "KB"],
  ];
  for (const [threshold, label] of units) {
    if (bytes >= threshold) {
      const rounded = Math.round((bytes / threshold) * 10) / 10;
      return `${Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1)} ${label}`;
    }
  }
  return `${bytes} B`;
}

/** Formats an ISO-8601 instant as "2026-08-25 14:02" (prototype's own example) in the
 *  browser's local timezone -- `createdAt`/`indexedAt` both arrive as an `OffsetDateTime`
 *  (backend CLAUDE.md fact #5), so `new Date(iso)` parses it unambiguously. */
function formatDateTime(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Extracts just the "{status} {statusText}" lead-in from an {@link ApiError}'s message
 *  (`client.ts`'s `describeFailure` always builds it as `"{status} {statusText}"`, optionally
 *  followed by `": {body}"`) so the error banner's parenthetical matches the prototype's fixed
 *  "(500 ...)" shape without also dumping a raw response body into it. */
function describeListFailureDetail(err: unknown): string {
  if (err instanceof ApiError) {
    return err.message.split(":")[0];
  }
  return describeError(err);
}

function describeActionError(err: unknown, action: string): string {
  if (err instanceof ApiError) return `${action}: ${err.message}`;
  return err instanceof Error ? `${action}: ${err.message}` : `${action}: ${String(err)}`;
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
