package art.insightai.chatbot.rag.domain;

import java.util.UUID;

/**
 * [Purpose] Signals that {@code DELETE /api/admin/documents/{id}} could not acquire {@code
 *   DocumentIngestLockRegistry}'s per-document stripe within {@code
 *   AppProperties.Ingest#deleteLockTimeout} (document-ingestion Spec §4 AC-17, Tech Review T10,
 *   review 2026-08-30 round 3 Medium-1).
 * [Rationale] Distinguishes "this document is busy right now, retry shortly" from two other
 *   states this same endpoint can report: {@link DocumentNotFoundException} ("no such document",
 *   404) and, one endpoint over, {@code OrphanChunkCleanupService}'s AC-18 response ("there was
 *   nothing to clean up" -- a completed-and-empty result, not a failure). Collapsing this case
 *   into either of those would mislead a caller into treating a transient contention window as
 *   either a permanent 404 or a no-op success. The id embedded in the message is safe to echo
 *   verbatim, same reasoning as {@link DocumentNotFoundException}: it is the id the caller
 *   already supplied in the request path, not server-internal detail.
 * [AC] document-ingestion:AC-17
 */
public class DocumentDeleteLockTimeoutException extends RuntimeException {

    public DocumentDeleteLockTimeoutException(UUID documentId) {
        super("document delete is temporarily busy (a concurrent ingestion or delete may be in "
                + "progress) -- retry shortly: " + documentId);
    }
}
