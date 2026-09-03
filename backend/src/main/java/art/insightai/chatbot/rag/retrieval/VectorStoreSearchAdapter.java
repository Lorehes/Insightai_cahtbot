package art.insightai.chatbot.rag.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] {@link VectorSearchPort} implementation that runs a pgvector cosine-similarity
 *   query directly against {@code vector_store_bgem3} via {@link JdbcTemplate} (Tech Review
 *   D-T11), mirroring {@link ParadeDbBm25SearchAdapter}'s raw-SQL shape for the BM25 axis.
 * [Rationale] T13/D-T10 requires this axis to symmetrically exclude non-{@code INDEXED}
 *   documents' chunks. The first implementation (T13 first pass) handed Spring AI's {@code
 *   VectorStore#similaritySearch(SearchRequest)} a {@code document_id NIN (...)} {@link
 *   org.springframework.ai.vectorstore.filter.Filter.Expression}. T13 review performance High-1
 *   measured that path against the live {@code chatbot-db} container ({@code EXPLAIN (ANALYZE,
 *   BUFFERS)}, coordinator's original report: 3,000/30,000-row corpora, 39x/160x slower than
 *   unfiltered): Spring AI's {@code PgVectorFilterExpressionConverter} (source-verified against
 *   {@code spring-ai-pgvector-store-2.0.1-sources.jar}) renders {@code nin(...)} as {@code
 *   metadata::jsonb @@ '<jsonpath>'::jsonpath}, which the query planner cannot combine with the
 *   {@code idx_vector_store_bgem3_embedding} HNSW index. This rework re-measured on a fresh
 *   60,000-row synthetic corpus (10 excluded ids, same query/corpus/exclusion set fed to both
 *   queries, 2026-08-28): the old {@code jsonpath} predicate still forces {@code Seq Scan}
 *   (493.4ms, ~391,000 buffer touches, disk-spilling sort), while this class's raw {@code
 *   metadata->>'document_id' NOT IN (?, ...)} on the identical corpus/query/excluded-id set
 *   resolves to {@code Index Scan using idx_vector_store_bgem3_embedding} (0.78ms, 458 buffer
 *   touches -- dev-log carries the full {@code EXPLAIN} output for both runs). Below a
 *   corpus-size crossover (this container: ~30,000-60,000 rows, driven by the planner's cost
 *   comparison between a cheap sequential heap scan and the HNSW path's near-flat cost, not by
 *   which filter syntax is used) the planner prefers {@code Seq Scan} regardless of filter
 *   syntax, so the fix's benefit is invisible on a small corpus and grows exactly where High-1
 *   said it would: as the corpus grows. This class therefore no longer depends on {@link
 *   org.springframework.ai.vectorstore.VectorStore} for reads at all -- {@link
 *   art.insightai.chatbot.rag.ingest.ChunkIndexer} (the write path) is unaffected and keeps
 *   using {@code VectorStore#add(List)} (D-T11 scopes this change to reads only).
 * [Caution] The query embedding is produced by calling {@link EmbeddingModel#embed(String)}
 *   directly on the injected bean (the real {@code OpenAiEmbeddingModel} in production, {@code
 *   StubEmbeddingModel} in integration tests) -- {@code javap}-verified against {@code
 *   spring-ai-model-2.0.1.jar} as a default method backed by {@code
 *   EmbeddingModel#call(EmbeddingRequest)}, the exact same call {@code PgVectorStore} itself
 *   makes internally ({@code PgVectorStore#getQueryEmbedding(String)}, sources jar) -- so this
 *   class produces the same query vector Spring AI's own path would have. {@link
 *   #MAX_COSINE_DISTANCE} (1.0) mirrors {@code PgVectorStore#doSimilaritySearch}'s {@code
 *   distance = 1 - request.getSimilarityThreshold()} with Tech Review D-T3's {@code
 *   similarityThresholdAll()} ({@link SearchRequest#SIMILARITY_THRESHOLD_ACCEPT_ALL} = 0.0) --
 *   this is not a new threshold introduced by this rework, it reproduces the {@code WHERE}
 *   clause every query already carried before D-T11 (D-T3 still forbids adding any additional
 *   threshold here; AC-4's score-threshold filter still runs exactly once, in the orchestrator,
 *   after RRF fusion). Every excluded document id is always a bound JDBC parameter, never
 *   string-concatenated -- mirrors {@link ParadeDbBm25SearchAdapter}'s injection guard, and
 *   additionally closes T13 review security L-1/L-5 (the old {@code filterExpression} path
 *   inlined excluded ids as escaped SQL text, not bind parameters, per {@code
 *   PgVectorFilterExpressionConverter}). {@code metadata->>'document_id'} returns SQL {@code
 *   NULL} for a row whose {@code metadata} has no {@code document_id} key; {@code NULL NOT IN
 *   (...)} evaluates to {@code NULL} (not {@code true}), so such a row is silently excluded from
 *   every result whenever the exclusion list is non-empty -- this is an existing sharp edge of
 *   the {@code NOT IN} predicate itself (T13 review Low L-1), not introduced by this class; no
 *   {@code vector_store_bgem3} row written by this project's own ingestion path
 *   (document-ingestion T6/T7) is ever missing that key, but a row written by anything else
 *   would be silently unsearchable while the exclusion list is non-empty.
 */
@Component
public class VectorStoreSearchAdapter implements VectorSearchPort {

    /**
     * [Purpose] Cosine-distance cutoff mirroring Tech Review D-T3's "no threshold on this axis"
     *   requirement -- see class [Caution] for the {@code PgVectorStore} formula this reproduces.
     */
    private static final double MAX_COSINE_DISTANCE =
            1.0 - SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;

    private static final String VECTOR_SEARCH_SQL =
            "SELECT id, content, metadata, embedding <=> ?::vector AS distance "
                    + "FROM vector_store_bgem3 "
                    + "WHERE embedding <=> ?::vector < ? "
                    + "ORDER BY distance "
                    + "LIMIT ?";

    /**
     * [Purpose] Same query as {@link #VECTOR_SEARCH_SQL} plus an exclusion predicate for
     *   orphaned chunks (T13/D-T10/D-T11, hybrid-rag:AC-16) -- used only when {@link #search}
     *   finds a non-empty exclusion population.
     * [Caution] {@code %s} is substituted with a placeholder list ({@code "?, ?, ..."}) sized to
     *   that population -- never with an id value itself -- before this string reaches {@link
     *   JdbcTemplate}; every actual excluded id still arrives as its own bound parameter. Mirrors
     *   {@link ParadeDbBm25SearchAdapter#EXCLUSION_PREDICATE_TEMPLATE}'s same pattern.
     */
    private static final String VECTOR_SEARCH_SQL_WITH_EXCLUSION_TEMPLATE =
            "SELECT id, content, metadata, embedding <=> ?::vector AS distance "
                    + "FROM vector_store_bgem3 "
                    + "WHERE embedding <=> ?::vector < ? "
                    + "AND metadata->>'document_id' NOT IN (%s) "
                    + "ORDER BY distance "
                    + "LIMIT ?";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final JsonParser jsonParser;
    private final NonIndexedDocumentLookup nonIndexedDocumentLookup;

    public VectorStoreSearchAdapter(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            NonIndexedDocumentLookup nonIndexedDocumentLookup) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.jsonParser = JsonParserFactory.getJsonParser();
        this.nonIndexedDocumentLookup = nonIndexedDocumentLookup;
    }

    /**
     * [Purpose] Runs the vector axis query (raw SQL, D-T11) and maps each row straight to a
     *   {@link RetrievedChunk}, excluding chunks that belong to a non-{@code INDEXED} document
     *   (T13/D-T10).
     * [Caution] The query embedding and every excluded document id are passed as JDBC bind
     *   parameters, never concatenated into the SQL text -- see this class's [Caution] for why
     *   that replaces the old {@code filterExpression} path.
     * [AC] hybrid-rag:AC-16
     */
    @Override
    public List<RetrievedChunk> search(String query, int topK) {
        String queryVector = vectorLiteral(embeddingModel.embed(query));
        List<String> excludedDocumentIds = nonIndexedDocumentLookup.findAll().stream()
                .map(NonIndexedDocument::id)
                .toList();
        if (excludedDocumentIds.isEmpty()) {
            return jdbcTemplate.query(
                    VECTOR_SEARCH_SQL,
                    this::mapRow,
                    queryVector, queryVector, MAX_COSINE_DISTANCE, topK);
        }
        String placeholders =
                excludedDocumentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = String.format(VECTOR_SEARCH_SQL_WITH_EXCLUSION_TEMPLATE, placeholders);
        List<Object> params = new ArrayList<>(excludedDocumentIds.size() + 4);
        params.add(queryVector);
        params.add(queryVector);
        params.add(MAX_COSINE_DISTANCE);
        params.addAll(excludedDocumentIds);
        params.add(topK);
        return jdbcTemplate.query(sql, this::mapRow, params.toArray());
    }

    /**
     * [Purpose] Maps one result row to this project's axis-agnostic {@link RetrievedChunk}.
     * [Caution] {@code score} is {@code 1 - distance} (cosine similarity), matching {@code
     *   PgVectorStore.DocumentRowMapper}'s own {@code .score(1.0 - distance)} (sources jar) so
     *   downstream consumers (RRF fusion, T7) see identical score semantics to before this class
     *   stopped depending on {@code PgVectorStore}. {@code metadata} arrives as a JSON text
     *   string (the column type is {@code json}, not {@code jsonb}); a null column value maps to
     *   an empty map, mirroring {@link ParadeDbBm25SearchAdapter#mapRow}.
     */
    private RetrievedChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        double distance = rs.getDouble("distance");
        return new RetrievedChunk(
                rs.getString("id"),
                rs.getString("content"),
                parseMetadata(rs.getString("metadata")),
                1.0 - distance);
    }

    /**
     * [Purpose] Parses the {@code metadata} column's raw JSON text into a {@code Map}.
     * [Caution] Mirrors {@link ParadeDbBm25SearchAdapter#parseMetadata} exactly -- a malformed
     *   value means the row was written by something other than this project's own ingestion
     *   path.
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null) {
            return Map.of();
        }
        try {
            return jsonParser.parseMap(metadataJson);
        } catch (JsonParseException e) {
            throw new IllegalStateException(
                    "Malformed metadata JSON in vector_store_bgem3 row", e);
        }
    }

    /**
     * [Purpose] Renders a {@code float[]} embedding as a {@code vector(n)} SQL literal (e.g.
     *   {@code "[0.0,1.0,...]"}) for the {@code ?::vector} bind parameters above -- the same
     *   textual format {@code support.ChunkSeeder#insertChunk(UUID, String, float[])} already
     *   uses for {@code INSERT}, reused here for {@code SELECT}.
     */
    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
