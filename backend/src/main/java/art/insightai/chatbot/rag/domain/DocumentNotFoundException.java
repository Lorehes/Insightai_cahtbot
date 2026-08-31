package art.insightai.chatbot.rag.domain;

import java.util.UUID;

/**
 * [Purpose] Signals that a document id passed to {@code DELETE /api/admin/documents/{id}}
 *   resolves to no row in {@code documents} (document-ingestion Spec §4 AC-13, Tech Review T10).
 * [Rationale] Mirrors {@code chat.domain.ConversationNotFoundException} one Spec over: a single
 *   exception type, mapped by {@code GlobalExceptionHandler} to 404, carrying only the id that was
 *   looked up. Unlike {@code ConversationNotFoundException} this endpoint has no ownership/session
 *   concept to fold in (§7 OQ-7 -- this endpoint is unauthenticated), so "not found" here means
 *   exactly one thing: no {@code documents} row with this id exists. Embedding the id in the
 *   message is safe to echo verbatim -- it is the same id the caller already supplied in the
 *   request path, not server-internal detail (unlike the quota exceptions' INC-007 concern).
 * [AC] document-ingestion:AC-13
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID documentId) {
        super("document not found: " + documentId);
    }
}
