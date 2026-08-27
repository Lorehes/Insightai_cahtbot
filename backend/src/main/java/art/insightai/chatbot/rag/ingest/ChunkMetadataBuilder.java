package art.insightai.chatbot.rag.ingest;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import art.insightai.chatbot.rag.domain.Document;

/**
 * [Purpose] Stamps AC-8's required metadata keys ({@code document_id}, {@code filename}, {@code
 *   chunk_index}, {@code dept}, {@code acl}, {@code page}) onto each already-split chunk (an
 *   {@code org.springframework.ai.document.Document} -- referenced by FQN throughout this class
 *   because its simple name collides with this project's own {@link Document} entity, which this
 *   class also needs for {@code document_id}/{@code filename}).
 * [Rationale] AC-8 requires {@code document_id}/{@code filename}/{@code chunk_index} to be
 *   present, {@code dept}/{@code acl} fixed to the literal string {@code "ALL"}, and a {@code
 *   page} key that is present but {@code null} whenever Tika can't supply page boundaries (Spec
 *   §7 OQ-5, best-effort). Tech Review AD-9 flagged two risks this class resolves by construction
 *   rather than by convention, both confirmed empirically in a disposable spike test
 *   (document-ingestion-T6 dev-log entry has the raw output):
 *   <ol>
 *     <li>{@code org.springframework.ai.document.Document}'s constructor and {@code
 *     Builder.build()} both call {@code Assert.noNullElements(metadata.values(), ...)} and
 *     *throw* {@code IllegalArgumentException} if any metadata value is null (read directly from
 *     {@code spring-ai-commons:2.0.1}'s {@code Document.java}). So {@code page: null} can never
 *     be produced via {@code Document.builder()...build()} or {@code Document#mutate()...build()}
 *     -- both re-run that assertion. This class instead mutates each already-built chunk's *live*
 *     metadata map directly via {@code chunk.getMetadata().put("page", null)}: {@code
 *     getMetadata()} returns the exact {@code HashMap} instance the constructor populated, and a
 *     plain {@code Map.put()} call performs no such validation. This is the only way to actually
 *     get a null {@code page} value into a {@code Document}.</li>
 *     <li>NR-2 asked whether a null {@code page} value then gets silently dropped when {@code
 *     PgVectorStore} serializes metadata into the {@code vector_store_bgem3.metadata} json
 *     column. Confirmed it is NOT dropped: {@code PgVectorStore.toJson()} uses a plain {@code
 *     tools.jackson.databind.json.JsonMapper} with no {@code NON_NULL} inclusion configured, so a
 *     map entry with a null value serializes as {@code "page": null} and round-trips through
 *     Postgres's {@code json} column verbatim (verified by inserting through the real {@code
 *     VectorStore} bean against the live ParadeDB container and reading the raw column back). No
 *     workaround was needed for this half of AD-9 -- only item 1 above required one.</li>
 *   </ol>
 * [Caution] {@link DocumentParser}'s Javadoc documents that {@code TikaDocumentReader} never
 *   exposes any page-boundary metadata at all -- not even a document-level page count -- for any
 *   format in the ingestion MIME whitelist. So with the current pipeline, {@link #resolvePage}
 *   always returns {@code null}. That is a fully valid outcome under AC-8's literal text ("Tika가
 *   페이지 경계를 제공하지 않는 문서 형식이면 값은 null이다") and Spec §7 OQ-5's "best-effort" framing --
 *   not a defect in this class. Producing a real page number would require a page-aware reader
 *   for at least the PDF branch (e.g. Spring AI's {@code PagePdfDocumentReader}, from a module
 *   this project does not currently depend on) and is out of this card's scope.
 */
@Component
public class ChunkMetadataBuilder {

    private static final String KEY_DOCUMENT_ID = "document_id";
    private static final String KEY_FILENAME = "filename";
    private static final String KEY_CHUNK_INDEX = "chunk_index";
    private static final String KEY_DEPT = "dept";
    private static final String KEY_ACL = "acl";
    private static final String KEY_PAGE = "page";

    private static final String DEPT_ALL = "ALL";
    private static final String ACL_ALL = "ALL";

    /**
     * [Purpose] Stamps the AC-8 metadata keys onto every chunk, in list order.
     * [Side effects] Mutates each element of {@code chunks} in place (see class Javadoc item 1)
     *   and returns the same list instance for convenient chaining.
     * [AC] document-ingestion:AC-8
     */
    public List<org.springframework.ai.document.Document> build(
            List<org.springframework.ai.document.Document> chunks, Document document) {
        UUID documentId = document.getId();
        String filename = document.getFilename();
        for (int i = 0; i < chunks.size(); i++) {
            org.springframework.ai.document.Document chunk = chunks.get(i);
            chunk.getMetadata().put(KEY_DOCUMENT_ID, documentId.toString());
            chunk.getMetadata().put(KEY_FILENAME, filename);
            chunk.getMetadata().put(KEY_CHUNK_INDEX, i);
            chunk.getMetadata().put(KEY_DEPT, DEPT_ALL);
            chunk.getMetadata().put(KEY_ACL, ACL_ALL);
            chunk.getMetadata().put(KEY_PAGE, resolvePage(chunk));
        }
        return chunks;
    }

    /**
     * [Purpose] Resolves the page number for a single chunk, best-effort.
     * [Rationale] Always {@code null} today -- see class [Caution]. Isolated into its own method
     *   (rather than inlining {@code null} in {@link #build}) so a future card that adds a
     *   page-aware reader for one format has a single seam to change.
     */
    private Integer resolvePage(org.springframework.ai.document.Document chunk) {
        return null;
    }
}
