package art.insightai.chatbot.rag.domain;

/**
 * [Purpose] Signals that a caller-supplied upload's original filename is missing, blank, longer
 *   than {@code documents.filename}'s column width, or contains an ISO control character
 *   (document-ingestion Spec Tech Review T5, review 2026-08-28 High-1, security Low-4). The
 *   control-character case exists because PostgreSQL's {@code text}/{@code varchar} columns
 *   refuse U+0000 outright ({@code ERROR: null character not permitted}) -- without this guard
 *   such a filename would surface as a 500 from the database instead of this exception's 400.
 * [Rationale] Thrown from {@code DocumentUploadService#upload} before any {@code documents} row
 *   is inserted and before any byte is written to {@code app.ingest.upload-dir} -- same placement
 *   as {@link UnsupportedDocumentTypeException}/{@link DocumentTooLargeException} ahead of the
 *   row insert. The 255 threshold mirrors {@code Document.filename}'s {@code varchar(255)} column
 *   (see {@code V1__init.sql}), the same "reject at the boundary, mirror the column width"
 *   pattern {@code chat.api.ConversationController.CreateConversationRequest.model}'s
 *   {@code @Size(max = 64)} established (chat T6 review 2026-08-27, Medium-1). Unlike that field,
 *   this check lives in the service rather than a Bean Validation annotation, because {@code
 *   MultipartFile.getOriginalFilename()} is not a {@code @Valid}-checked request-body field --
 *   there is no DTO for {@code @Size} to attach to.
 * [Caution] The message carries neither the caller-supplied filename nor the 255 column-width
 *   number (INC-007, review 2026-08-28 High-1) -- same "fixed string, no argument" shape {@link
 *   DocumentQuotaExceededException} already uses. The actual filename (or its absence) belongs in
 *   a WARN log at the throw site, not in this exception's message.
 * [AC] document-ingestion §5 (originalFilename validation, T5 scope, no dedicated AC -- same
 *   shape as {@link DocumentQuotaExceededException}'s own [AC] tag)
 */
public class InvalidDocumentFilenameException extends RuntimeException {

    public InvalidDocumentFilenameException() {
        super("invalid document filename");
    }
}
