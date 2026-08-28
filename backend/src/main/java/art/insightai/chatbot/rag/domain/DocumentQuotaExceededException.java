package art.insightai.chatbot.rag.domain;

/**
 * [Purpose] Signals that the {@code documents} table already holds {@code
 *   app.ingest.max-document-count} rows and may not accept another upload (document-ingestion
 *   Spec §5 Affected Surfaces, review 2026-08-25 Medium-2; T5).
 * [Rationale] Thrown from {@code DocumentUploadService#upload} before any new row is inserted,
 *   mirroring {@code chat.domain.ConversationQuotaExceededException}'s "count, then reject before
 *   insert" placement and {@code GlobalExceptionHandler}'s same 403 mapping (not 429): the
 *   ceiling is not time-windowed, so retrying later fails again for the same reason, and the
 *   caller can only recover by deleting an existing document (T10) -- 403 ("understood the
 *   request, refusing to authorize it") fits; 429's "wait, then retry" would be misleading.
 * [Caution] Unlike {@code chat.domain.ConversationQuotaExceededException} (a chat-Spec sibling
 *   left as its own pre-existing legacy behavior), this message deliberately omits both {@code
 *   app.ingest.max-document-count}'s numeric value and its property key (INC-007, same pattern
 *   {@code chat.domain.MessageQuotaExceededException} already follows) -- carries no argument at
 *   all. The actual configured ceiling and current count belong in a WARN log at the throw site,
 *   not in this exception's message; {@code GlobalExceptionHandler} additionally never echoes
 *   even this generic message verbatim, returning a fixed client-facing string instead.
 * [AC] document-ingestion §5 (max-document-count enforcement, T5 scope)
 */
public class DocumentQuotaExceededException extends RuntimeException {

    public DocumentQuotaExceededException() {
        super("document quota exceeded");
    }
}
