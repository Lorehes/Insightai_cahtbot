package art.insightai.chatbot.rag.retrieval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Adapts T7's {@link HybridDocumentRetriever} ({@code String} query -&gt;
 *   {@code List<RetrievedChunk>}) to Spring AI's {@link DocumentRetriever} contract
 *   ({@link Query} -&gt; {@code List<Document>}) that {@code RetrievalAugmentationAdvisor}
 *   requires as its single mandatory collaborator.
 * [Rationale] {@link HybridDocumentRetriever}'s own Javadoc explicitly defers this binding to
 *   T9 (Tech Review "T9 소관") because its internal pipeline speaks {@link RetrievedChunk}
 *   throughout, not the Spring AI {@link Document} type. {@link RetrievedChunk#score()} on every
 *   element {@link HybridDocumentRetriever#retrieve} returns is already the final RRF fusion
 *   score (reranking, if applied, does not rewrite it -- Tech Review D-T6), so this adapter
 *   copies it onto {@link Document#getScore()} verbatim, keeping {@code sources.score}
 *   (downstream, T8's {@link SourceRefMapper}) the RRF score end to end once T11 maps the
 *   {@code Document} list back out of the advisor response metadata. {@link #toDocument} copies
 *   only a whitelisted subset of {@code chunk.metadata()} -- {@code document_id}, {@code
 *   filename}, {@code page} -- rather than the full map ({@code
 *   art.insightai.chatbot.rag.ingest.ChunkMetadataBuilder} stamps six keys: those three plus
 *   {@code chunk_index}, {@code dept}, {@code acl}). Verified by reading {@link SourceRefMapper}
 *   directly (2026-08-28 review Low-2): it is the only consumer of this metadata shape and reads
 *   exactly {@code document_id}/{@code filename}/{@code page}; {@code chunk_index} is
 *   ingest-only, and {@code dept}/{@code acl} are Phase-3 ACL-filtering placeholders with no
 *   reader anywhere yet. No production {@code documentFormatter}/{@code MetadataMode} in this
 *   codebase renders {@link Document} metadata into the prompt today ({@code
 *   RagAdvisorConfig}'s {@code ContextualQueryAugmenter} uses the library default formatter,
 *   which only reads {@code Document::getText}), so this whitelist has no observable effect yet
 *   -- it exists to keep {@code dept}/{@code acl} from becoming reachable by a one-line formatter
 *   change (2026-08-28 review Low-2) before Phase 3 actually needs them on this path. When Phase
 *   3 adds ACL filtering, this is the update point: either widen this whitelist to include {@code
 *   dept}/{@code acl} once a consumer reads them, or (preferably) push the ACL check into {@link
 *   HybridDocumentRetriever}'s pipeline instead, so unauthorized chunks never reach this adapter
 *   at all.
 * [Caution] {@link Document}'s constructor/builder throws {@link IllegalArgumentException} on
 *   any null metadata *value* ({@code Assert.noNullElements(metadata.values(), ...)}, confirmed
 *   by reading {@code spring-ai-commons:2.0.1}'s {@code Document.java} directly -- the same
 *   landmine {@link art.insightai.chatbot.rag.ingest.ChunkMetadataBuilder}'s Javadoc documents on
 *   the ingest side). {@code page} is {@code null} on effectively every chunk in this project
 *   today (Tika never supplies page boundaries, document-ingestion Spec OQ-5), so copying it
 *   unconditionally into the whitelisted map would throw on the common case, not an edge case.
 *   {@link #toDocument} therefore drops the {@code page} entry when its value is null before
 *   building. This is safe for {@code SourceRefMapper#readPage}, the only downstream reader of
 *   the {@code page} key: {@code Map.get("page")} returns {@code null} identically whether the
 *   key is absent or present-with-a-null-value, so dropping the key changes nothing observable
 *   for that mapper. Adding a new key to {@link #METADATA_WHITELIST} that can legitimately be
 *   null must go through the same drop-if-null treatment, not a direct {@code Map.put}.
 */
@Component
public class SpringAiDocumentRetrieverAdapter implements DocumentRetriever {

    /**
     * [Rationale] See class [Rationale] for why this is a whitelist rather than a straight copy
     *   of {@code chunk.metadata()}, and for the Phase-3 update point when {@code dept}/{@code
     *   acl} gain a reader.
     */
    private static final Set<String> METADATA_WHITELIST = Set.of("document_id", "filename", "page");

    private final HybridDocumentRetriever hybridDocumentRetriever;

    public SpringAiDocumentRetrieverAdapter(HybridDocumentRetriever hybridDocumentRetriever) {
        this.hybridDocumentRetriever = hybridDocumentRetriever;
    }

    /**
     * [Purpose] Runs T7's full retrieval pipeline for {@code query.text()} and maps every
     *   returned {@link RetrievedChunk} to a Spring AI {@link Document}, preserving list order
     *   and the RRF fusion score.
     * [Caution] {@code query.history()}/{@code query.context()} are intentionally unused here --
     *   {@link HybridDocumentRetriever} has no history-aware behavior of its own. History-driven
     *   query rewriting is T12's {@code CompressionQueryTransformer}, which (per the Spec
     *   "어드바이저 등록 순서 계약") runs earlier in {@code RetrievalAugmentationAdvisor.before()},
     *   before this method is ever invoked -- by the time it runs, any compression is already
     *   baked into {@code query.text()}.
     */
    @Override
    public List<Document> retrieve(Query query) {
        List<RetrievedChunk> chunks = hybridDocumentRetriever.retrieve(query.text());
        return chunks.stream().map(SpringAiDocumentRetrieverAdapter::toDocument).toList();
    }

    /**
     * [Purpose] Copies only {@link #METADATA_WHITELIST} keys from {@code chunk.metadata()} onto
     *   the built {@link Document}, dropping any whitelisted entry whose value is null (see class
     *   [Caution]).
     */
    private static Document toDocument(RetrievedChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        for (String key : METADATA_WHITELIST) {
            Object value = chunk.metadata().get(key);
            if (value != null) {
                metadata.put(key, value);
            }
        }
        return Document.builder()
                .id(chunk.id())
                .text(chunk.content())
                .metadata(metadata)
                .score(chunk.score())
                .build();
    }
}
