package art.insightai.chatbot.rag.domain;

/**
 * [Purpose] Signals that a caller-supplied upload's size exceeds {@code
 *   app.ingest.max-file-size} -- the application-level half of the Spec's 2-tier size guard
 *   (document-ingestion Spec Tech Review AD-6, T5).
 * [Rationale] Thrown from {@code DocumentUploadService#upload} before any {@code documents} row
 *   is created and before any bytes are written to {@code app.ingest.upload-dir}, so a rejected
 *   upload leaves neither a row nor a file behind (document-ingestion:AC-3). The container-level
 *   half of the same guard ({@code spring.servlet.multipart.max-file-size}, 25MB) never reaches
 *   this class -- it surfaces as {@code MaxUploadSizeExceededException}, which {@code
 *   GlobalExceptionHandler} maps to the same 413 through a separate handler.
 * [Caution] The message embeds the caller's own uploaded {@code sizeBytes}, not the configured
 *   threshold -- avoid adding {@code app.ingest.max-file-size}'s numeric value or property key
 *   here (INC-007: a configured ceiling leaking into a client-visible exception message has
 *   already recurred twice in this project).
 * [AC] document-ingestion:AC-3
 */
public class DocumentTooLargeException extends RuntimeException {

    public DocumentTooLargeException(long sizeBytes) {
        super("uploaded file exceeds the allowed size: " + sizeBytes + " bytes");
    }
}
