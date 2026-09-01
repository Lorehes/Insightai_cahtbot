package art.insightai.chatbot.rag.ingest;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
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
 *     <li>BL-023 (2026-09-01): item 1's null {@code page} value also crashes the real (non-stub)
 *     embedding path with a {@code NullPointerException} -- every integration test ran against
 *     {@code StubEmbeddingModel} and never exercised it, so this went undetected until a real
 *     document was embedded for the first time. {@link #excludeNullablePageFromFormatting}
 *     resolves it; see that method's own Javadoc for the mechanism.</li>
 *   </ol>
 * [Caution] {@link DocumentParser}'s Javadoc documents that {@code TikaDocumentReader} never
 *   exposes any page-boundary metadata at all -- not even a document-level page count -- for any
 *   format it handles. Since 2026-09-01 (user decision "A + C") PDFs bypass Tika and are read page
 *   by page with {@code PagePdfDocumentReader}, so {@link #resolvePage} returns a real 1-based page
 *   number for PDF chunks and {@code null} for every other format -- the latter is still a fully
 *   valid outcome under AC-8's literal text ("Tika가 페이지 경계를 제공하지 않는 문서 형식이면 값은
 *   null이다") and Spec §7 OQ-5's "best-effort" framing. The null-page defenses below (items 1 and
 *   3 above) therefore remain load-bearing for DOCX/PPTX/XLSX/TXT/MD.
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
     * [Purpose] Shared across every chunk of every document (performance review, 2026-09-01) --
     *   {@code DefaultContentFormatter} is immutable/thread-safe (all fields {@code private final},
     *   no setters) and this instance is identical for every chunk, so building it once here avoids
     *   a small per-chunk allocation in {@link #excludeNullablePageFromFormatting}.
     */
    private static final DefaultContentFormatter PAGE_EXCLUDED_FORMATTER = DefaultContentFormatter.builder()
            .withExcludedEmbedMetadataKeys(KEY_PAGE)
            .withExcludedInferenceMetadataKeys(KEY_PAGE)
            .build();

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
            // Batch review 2026-09-01 Medium-2: once the page number is promoted to AC-8's own
            // `page` key, the PDF reader's raw keys are removed. Left in place they reach two
            // sinks they don't belong in: the EMBED-mode formatted content (BL-023 proved
            // metadata is prepended to the embedding input, so PDF chunks would embed
            // "page_number: N" / "file_name: <upload UUID>" noise non-PDF chunks don't have) and
            // the vector_store_bgem3.metadata column (an internal storage identifier persisted
            // for no reader). Non-PDF chunks never have these keys -- remove() is a no-op there.
            chunk.getMetadata().remove(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
            chunk.getMetadata().remove(PagePdfDocumentReader.METADATA_END_PAGE_NUMBER);
            chunk.getMetadata().remove(PagePdfDocumentReader.METADATA_FILE_NAME);
            excludeNullablePageFromFormatting(chunk);
        }
        return chunks;
    }

    /**
     * [Purpose] Keeps the {@code page} metadata key out of the text handed to the embedding
     *   model, while leaving it in {@code chunk.getMetadata()} untouched.
     * [Rationale] {@code page} is null on every non-PDF chunk (PDF chunks carry a real number
     *   since 2026-09-01 -- see class Javadoc -- and excluding a non-null value here is harmless,
     *   so this exclusion stays correct and load-bearing for both cases), and the real
     *   (non-stub) embedding path -- {@code OpenAiEmbeddingModel.getEmbeddingContent} ->
     *   {@code Document.getFormattedContent} -> {@code DefaultContentFormatter.metadataFilter}
     *   (this chunk type's {@code MetadataMode} is {@code EMBED}, confirmed by this fix's own
     *   before/after experiment: reverting it reproduces the crash, so the mode cannot be
     *   {@code ALL} or {@code NONE}) -- rebuilds the metadata map with {@code Collectors.toMap},
     *   which throws {@code NullPointerException} on any null value. No test caught this before
     *   BL-023 because every integration test runs against {@code StubEmbeddingModel}, which
     *   never calls {@code getFormattedContent}. Excluding {@code page} from {@code EMBED} mode
     *   removes the null entry before that collector ever sees it, while satisfying AC-8's
     *   requirement that {@code page} still be null rather than absent or a sentinel.
     *   {@code withExcludedInferenceMetadataKeys(KEY_PAGE)} is also set, but is inert rather than
     *   protective (correctness review, 2026-09-01): the chunk instance this method mutates never
     *   reaches chat inference at all -- {@code SpringAiDocumentRetrieverAdapter.toDocument}
     *   builds a brand-new {@code Document} from the search result row via a metadata whitelist
     *   that already drops null values (that class's own Javadoc), so no formatter this class sets
     *   is ever consulted on the retrieval/chat-prompt side. It is kept anyway as cheap defensive
     *   redundancy against a future change that reuses this chunk instance downstream, not because
     *   it currently guards anything.
     * [Caution] {@code PgVectorStore.toJson} serializes {@code chunk.getMetadata()} directly with
     *   a plain {@code JsonMapper} and never calls this content formatter, so the stored
     *   {@code vector_store_bgem3.metadata} column still gets a literal {@code "page": null}
     *   (confirmed in the class Javadoc's item 2). Do not remove the {@code EMBED} exclusion
     *   assuming {@code page} is harmless -- doing so reintroduces the NPE the moment a document
     *   is embedded for real instead of through the stub. This defense is scoped to {@code EMBED}/
     *   {@code INFERENCE} only: a caller that invokes {@code chunk.getFormattedContent()}
     *   (no-arg, {@code MetadataMode.ALL}) or a future {@code spring.ai.openai.embedding.metadata-
     *   mode: ALL} setting bypasses both exclusions -- whether that reproduces this exact {@code
     *   NullPointerException} or merely renders the literal text {@code "null"} depends on which
     *   code path in {@code DefaultContentFormatter} handles {@code MetadataMode.ALL} (unverified
     *   in this fix, correctness review 2026-09-01); treat it as unsafe either way.
     * [AC] document-ingestion:AC-8
     */
    private void excludeNullablePageFromFormatting(org.springframework.ai.document.Document chunk) {
        chunk.setContentFormatter(PAGE_EXCLUDED_FORMATTER);
    }

    /**
     * [Purpose] Resolves the page number for a single chunk, best-effort.
     * [Rationale] {@link DocumentParser} reads PDFs page by page with {@code PagePdfDocumentReader}
     *   (2026-09-01, user decision "A + C"), which stamps {@code page_number} ({@link
     *   PagePdfDocumentReader#METADATA_START_PAGE_NUMBER}, 1-based) on each page's source document;
     *   {@code TokenTextSplitter} copies that metadata onto every chunk it cuts from that page, so
     *   it is read back here and promoted to AC-8's {@code page} key. Every other format still
     *   arrives through Tika with no such key and resolves to {@code null} -- AC-8's "Tika가 페이지
     *   경계를 제공하지 않는 문서 형식이면 값은 null이다" branch, unchanged. {@link #build} removes
     *   the reader's raw keys right after this promotion (batch review 2026-09-01 Medium-2), so
     *   this method is the only consumer they ever have; a non-numeric value (never produced by
     *   the reader, but the metadata map is untyped) is treated as absent rather than failing the
     *   whole document.
     */
    private Integer resolvePage(org.springframework.ai.document.Document chunk) {
        Object raw = chunk.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
