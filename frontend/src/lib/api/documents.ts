import { apiFetch, apiFetchVoid } from "./client";
import type { DocumentStatus } from "@/components/status-badge/StatusBadge";

/**
 * [Purpose] Response body for `POST /api/admin/documents` (document-ingestion Spec §5, `T5`,
 *   confirmed against `DocumentUploadController.DocumentUploadResponse`).
 * [Caution] `status` reuses {@link DocumentStatus} from `components/status-badge/StatusBadge`
 *   rather than redeclaring the same 5-value union here -- that file's own [Rationale] states
 *   this card is expected to import it, not define a second copy that could drift.
 * [AC] document-ingestion:AC-14
 */
export interface DocumentUploadResponse {
  id: string;
  filename: string;
  status: DocumentStatus;
}

/**
 * [Purpose] One entry of `GET /api/admin/documents` (document-ingestion Spec §5, `T9`,
 *   confirmed against `DocumentListController.DocumentListItemResponse`) -- the exact field
 *   set and order the backend record declares.
 * [Caution] `chunkCount`/`errorMessage`/`indexedAt` are `null` for any document that has not
 *   reached a terminal state yet -- mirrors `DocumentListItemResponse`'s own [Caution]. Do not
 *   default these to `0`/`""` client-side; a real `chunkCount: 0` (e.g. a parsed-but-empty
 *   file) must stay distinguishable from "not yet known".
 * [AC] document-ingestion:AC-14
 */
export interface DocumentListItem {
  id: string;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  status: DocumentStatus;
  chunkCount: number | null;
  errorMessage: string | null;
  createdAt: string;
  indexedAt: string | null;
}

/**
 * [Purpose] Calls `POST /api/admin/documents` with `file` as a `multipart/form-data` part, for
 *   the admin document manager's upload form (document-ingestion:AC-14).
 * [Rationale] Does not set a `Content-Type` header -- `FormData` as a `fetch` body makes the
 *   browser generate the `multipart/form-data; boundary=...` header itself with the correct
 *   boundary string; setting it manually here would omit that boundary and break parsing on
 *   the backend (`DocumentUploadController#upload`'s `@RequestParam("file") MultipartFile`).
 *   The field name `"file"` matches that controller's own [Rationale] (no prior convention to
 *   follow, chosen as the plainest available name) exactly -- this is the one place on the FE
 *   that must stay byte-for-byte in sync with it.
 * [AC] document-ingestion:AC-14
 */
export function uploadDocument(file: File): Promise<DocumentUploadResponse> {
  const formData = new FormData();
  formData.append("file", file);
  return apiFetch<DocumentUploadResponse>("/api/admin/documents", {
    method: "POST",
    body: formData,
  });
}

/**
 * [Purpose] Calls `GET /api/admin/documents` to list every uploaded document, for the admin
 *   document manager's table and its 3-second status poll (document-ingestion:AC-14, AD-12).
 * [Rationale] The backend already orders the result `created_at` descending
 *   (`DocumentListController`'s own [Purpose]) -- this function does not re-sort, for the same
 *   "trust the backend's own order" reason `lib/api/conversations.ts`'s `listConversations`
 *   documents.
 * [AC] document-ingestion:AC-14
 */
export function listDocuments(): Promise<DocumentListItem[]> {
  return apiFetch<DocumentListItem[]>("/api/admin/documents");
}

/**
 * [Purpose] Calls `DELETE /api/admin/documents/{id}` to remove a document and its chunks, for
 *   the admin document manager's delete-confirm dialog (document-ingestion:AC-14).
 * [Rationale] Routed through {@link apiFetchVoid}, not {@link apiFetch} -- this endpoint
 *   returns `204 No Content` (`DocumentDeleteController#delete` [Rationale]), same reason
 *   `lib/api/conversations.ts`'s `deleteConversation` does the same. `id` is URL-encoded for
 *   the same reason that function documents.
 * [Caution] A 503 here (surfaced as {@link ApiError} with `status === 503`) means the
 *   per-document delete lock could not be acquired within the backend's timeout
 *   (`DocumentDeleteLockTimeoutException` [Rationale]) -- it is transient, not "the document
 *   cannot be deleted"; a caller should suggest retrying, not treat it like a 404.
 * [AC] document-ingestion:AC-14
 */
export function deleteDocument(id: string): Promise<void> {
  return apiFetchVoid(`/api/admin/documents/${encodeURIComponent(id)}`, { method: "DELETE" });
}
