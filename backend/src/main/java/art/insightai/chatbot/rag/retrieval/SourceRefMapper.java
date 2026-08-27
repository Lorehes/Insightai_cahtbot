package art.insightai.chatbot.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.rag.domain.SourceRef;

/**
 * [Purpose] Maps the pipeline's final {@link RetrievedChunk} list into the {@link SourceRef}
 *   shape exposed to callers (hybrid-rag Spec AC-11).
 * [Rationale] Tech Review D-T7: {@code sources} is built from chunk {@code metadata} alone —
 *   there is no {@code documents} table lookup here. {@code filename}/{@code page} come from
 *   {@code metadata.filename}/{@code metadata.page} (document-ingestion Spec AC-8 guarantees both
 *   keys are present on every indexed chunk, {@code page} nullable). {@code score} is copied
 *   straight from {@link RetrievedChunk#score()} unchanged: by the time a chunk reaches this
 *   mapper it has passed through RRF fusion and — per {@link RerankPort}'s contract — optionally
 *   through reranking without that stage touching {@code score}, so this field is always the RRF
 *   fusion score (Tech Review D-T6), never a rerank score. {@code snippet} is the first 200
 *   characters of {@code content} (Tech Review D-T7, RK-T6 — an unvalidated placeholder pending
 *   D7 re-evaluation, not a considered UX decision; do not "improve" it without a Spec change).
 *   This class takes {@link RetrievedChunk} rather than a narrower RRF-specific type because no
 *   such type exists in this codebase (Tech Review D-T2 keeps every pipeline stage, including
 *   fusion, working on the same axis-agnostic record) and {@link RerankPort} confirms the type is
 *   unchanged all the way through reranking.
 * [Caution] {@code document_id} and {@code filename} are required per document-ingestion Spec
 *   AC-8; a chunk missing either indicates a data-integrity problem elsewhere in the pipeline
 *   (see {@link ParadeDbBm25SearchAdapter}'s parsing caution), so this class throws
 *   {@link IllegalStateException} rather than silently producing an incomplete {@link SourceRef}.
 *   {@code page} is the one field allowed to be absent/null (document-ingestion Spec OQ-5) and is
 *   mapped straight through as {@code null} in that case.
 * [AC] hybrid-rag:AC-11
 */
@Component
public class SourceRefMapper {

    private static final int SNIPPET_MAX_CHARS = 200;

    private static final String METADATA_KEY_DOCUMENT_ID = "document_id";
    private static final String METADATA_KEY_FILENAME = "filename";
    private static final String METADATA_KEY_PAGE = "page";

    /**
     * [Purpose] Maps a list of chunks to their {@link SourceRef} equivalents, preserving order.
     * [AC] hybrid-rag:AC-11
     */
    public List<SourceRef> toSourceRefs(List<RetrievedChunk> chunks) {
        return chunks.stream().map(this::toSourceRef).toList();
    }

    /**
     * [Purpose] Maps a single chunk to a {@link SourceRef}, reading {@code documentId}/
     *   {@code filename}/{@code page} out of {@code chunk.metadata()} (Tech Review D-T7).
     * [AC] hybrid-rag:AC-11
     */
    public SourceRef toSourceRef(RetrievedChunk chunk) {
        Map<String, Object> metadata = chunk.metadata();
        UUID documentId = readDocumentId(metadata);
        String filename = readFilename(metadata);
        Integer page = readPage(metadata);
        String snippet = buildSnippet(chunk.content());
        return new SourceRef(documentId, filename, chunk.score(), snippet, page);
    }

    /**
     * [Purpose] Reads the required {@code document_id} key and parses it as a {@link UUID}.
     * [Caution] The value is stored as the string form of a UUID (same convention as
     *   {@code metadata->>'document_id'} elsewhere in this package), never a native UUID object —
     *   both the BM25 and vector search axes produce metadata parsed from JSON text.
     */
    private UUID readDocumentId(Map<String, Object> metadata) {
        Object raw = metadata.get(METADATA_KEY_DOCUMENT_ID);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalStateException(
                    "Chunk metadata missing required '" + METADATA_KEY_DOCUMENT_ID + "'");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Chunk metadata '" + METADATA_KEY_DOCUMENT_ID + "' is not a valid UUID: " + value, e);
        }
    }

    /** [Purpose] Reads the required {@code filename} key. */
    private String readFilename(Map<String, Object> metadata) {
        Object raw = metadata.get(METADATA_KEY_FILENAME);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalStateException(
                    "Chunk metadata missing required '" + METADATA_KEY_FILENAME + "'");
        }
        return value;
    }

    /**
     * [Purpose] Reads the {@code page} key, which is nullable by design (document-ingestion Spec
     *   OQ-5) whenever Tika could not recover a page boundary for the source document.
     * [Caution] Accepts {@link Number} (the common case — JSON integers) and numeric
     *   {@link String} alike, since the two metadata sources in this package (JDBC JSON parsing
     *   in {@link ParadeDbBm25SearchAdapter}, Spring AI's {@code VectorStore} in
     *   {@link VectorStoreSearchAdapter}) are not guaranteed to box a JSON integer identically.
     */
    private Integer readPage(Map<String, Object> metadata) {
        Object raw = metadata.get(METADATA_KEY_PAGE);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "Chunk metadata '" + METADATA_KEY_PAGE + "' is not a valid integer: " + value, e);
            }
        }
        throw new IllegalStateException(
                "Chunk metadata '" + METADATA_KEY_PAGE + "' has unsupported type: " + raw.getClass());
    }

    /**
     * [Purpose] Builds the AC-11 {@code snippet} field: the first 200 characters of the chunk's
     *   content (Tech Review D-T7, provisional value — RK-T6).
     */
    private String buildSnippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_MAX_CHARS ? content : content.substring(0, SNIPPET_MAX_CHARS);
    }
}
