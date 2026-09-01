package art.insightai.chatbot.rag.domain;

/**
 * [Purpose] Signals that an uploaded PDF opens (empty user password) but carries an
 *   owner-password permission set that forbids text extraction -- the upload is rejected before
 *   any {@code documents} row or on-disk file is created (batch review 2026-09-01 security Low-1,
 *   user decision 2026-09-02: "추출 금지 표시는 만든 사람의 의사" -- such a document must not be
 *   silently indexed into the RAG corpus).
 * [Rationale] Thrown from {@code DocumentUploadService#upload}'s guard chain, same flat pattern
 *   as {@link UnsupportedDocumentTypeException}/{@link DocumentTooLargeException} (no new
 *   hierarchy). Deliberately NOT thrown for a PDF that cannot be opened at all (a real user
 *   password, or corrupt bytes) -- those fall through the guard and fail later in the pipeline's
 *   existing PARSE_FAILURE path, unchanged: this exception's one meaning is "openable, but
 *   extraction is forbidden", so the admin sees a distinct, actionable reason instead of
 *   mistaking the file for broken.
 * [Caution] Fixed message, no caller-supplied content (INC-007 shape) --
 *   {@code GlobalExceptionHandler} maps it to 400 with its own fixed client string.
 */
public class DocumentExtractionForbiddenException extends RuntimeException {

    public DocumentExtractionForbiddenException() {
        super("document forbids text extraction (owner-password permission)");
    }
}
