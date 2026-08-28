package art.insightai.chatbot.rag.domain;

/**
 * [Purpose] Signals that a caller-supplied upload's {@code Content-Type} is not one of {@code
 *   app.ingest.allowed-mime-types}' whitelist entries (document-ingestion Spec Tech Review AD-5,
 *   T5).
 * [Rationale] Thrown from {@code DocumentUploadService#upload} before any {@code documents} row
 *   is created, so a rejected upload never leaves a row behind (document-ingestion:AC-2). Per
 *   AD-5, the check is the {@code Content-Type} header alone -- no extension or magic-byte
 *   sniffing -- so a caller can spoof this and still 400 only to have Tika later fail parsing and
 *   the pipeline resolve to FAILED (Tech Review NR-5, accepted risk).
 * [Caution] The message embeds the caller-supplied {@code mimeType} verbatim -- safe for a log
 *   line since it only echoes the caller's own request header, not server-side configuration (same
 *   reasoning as {@code chat.domain.InvalidModelException}'s [Caution]). Do not add the full
 *   {@code app.ingest.allowed-mime-types} list to this message. {@code GlobalExceptionHandler} no
 *   longer echoes this message verbatim as the client-facing {@code detail} either (review
 *   2026-08-28 security Low-1): an unbounded caller-supplied {@code Content-Type} header reflected
 *   into a response a D3 admin screen renders is best kept short and separate, so the handler uses
 *   a fixed detail string and surfaces {@link #mimeType()} through a dedicated, length-capped
 *   {@code ProblemDetail} property instead.
 * [AC] document-ingestion:AC-2
 */
public class UnsupportedDocumentTypeException extends RuntimeException {

    private final String mimeType;

    public UnsupportedDocumentTypeException(String mimeType) {
        super("unsupported document MIME type: " + mimeType);
        this.mimeType = mimeType;
    }

    /**
     * [Purpose] The caller-supplied {@code Content-Type} that failed the whitelist check, for
     *   {@code GlobalExceptionHandler} to surface as a capped {@code ProblemDetail} property
     *   instead of re-parsing it out of {@link #getMessage()}.
     */
    public String mimeType() {
        return mimeType;
    }
}
