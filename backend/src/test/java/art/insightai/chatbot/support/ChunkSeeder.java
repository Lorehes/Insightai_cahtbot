package art.insightai.chatbot.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Shared helper for tests that need to insert rows directly into
 * {@code vector_store_bgem3} (bypassing {@code VectorStore.add()}/the real embedding path) and
 * clean them up afterward -- the pattern both {@code ChunkRepositoryIntegrationTest} and
 * {@code ParadeDbBm25SearchAdapterIntegrationTest} (rag/ingest, rag/retrieval) had each written
 * their own copy of ({@code insertChunk}/{@code buildDummyEmbeddingLiteral}/per-{@code @AfterEach}
 * delete-by-{@code document_id}).
 * [Rationale] review 2026-08-26_document-ingestion-T4.md Medium item 1: those two files are
 * owned by other cards (T6/hybrid-rag T4) and are intentionally NOT modified by this rework --
 * this class only centralizes the pattern for future callers that don't yet have their own copy.
 * {@link #insertChunk(UUID, String)}/{@link #insertChunk(UUID, String, String)} insert a dummy
 * all-zero 1024-dimension embedding literal (matches both existing files' approach) since
 * content/metadata predicates, not vector similarity, are what those suites test; a caller that
 * specifically needs a discriminating vector for a similarity/ranking assertion must use
 * {@link #insertChunk(UUID, String, float[])} with a vector from
 * {@link StubEmbeddingModel#embed(String)} instead -- see {@link #dummyEmbeddingLiteral()}'s
 * {@code [Caution]} for why the all-zero literal cannot be reused there (review
 * 2026-08-27_document-ingestion-T4-round2.md Medium-C). Metadata is written via a fixed
 * {@code json_build_object(...)::json} SQL template with each value bound as its own JDBC
 * parameter -- not Java-side string concatenation -- so Postgres itself escapes
 * commas/quotes/braces in a value like {@code filename} (mirrors
 * {@code ParadeDbBm25SearchAdapterIntegrationTest}'s fix for review 2026-08-26_hybrid-rag-T4.md
 * correctness Medium-4 / security Low-3).
 * [Caution] {@code vector_store_bgem3.metadata} is a plain {@code json} column, not {@code jsonb}
 * (per {@code V1__init.sql} -- {@code PgVectorStore}'s own default schema; see
 * {@code ChunkRepository}'s Javadoc for the same fact). This class is not thread-safe for
 * concurrent use from multiple test threads against the same instance -- each test should
 * construct its own {@code ChunkSeeder} in {@code @BeforeEach} (mirrors how both existing files
 * scope their tracked-id list to one test instance).
 */
public final class ChunkSeeder {

    private final JdbcTemplate jdbcTemplate;
    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    public ChunkSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * [Purpose] Inserts one chunk row under a freshly generated {@code document_id} and returns
     * that id, so a caller that only needs "some document's chunk" doesn't have to generate a
     * {@link UUID} itself first.
     */
    public UUID insertChunk(String content) {
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, content);
        return documentId;
    }

    /**
     * [Purpose] Inserts one chunk row with {@code content} under the given {@code documentId},
     * with no other metadata keys.
     */
    public void insertChunk(UUID documentId, String content) {
        insertedDocumentIds.add(documentId);
        jdbcTemplate.update(
                "INSERT INTO vector_store_bgem3 (content, metadata, embedding) "
                        + "VALUES (?, json_build_object('document_id', ?::text)::json, ?::vector)",
                content, documentId.toString(), dummyEmbeddingLiteral());
    }

    /**
     * [Purpose] Same as {@link #insertChunk(UUID, String)} but also sets a {@code filename}
     * metadata key -- for tests that assert on filename round-tripping (e.g. one containing
     * commas/quotes/braces).
     */
    public void insertChunk(UUID documentId, String content, String filename) {
        insertedDocumentIds.add(documentId);
        jdbcTemplate.update(
                "INSERT INTO vector_store_bgem3 (content, metadata, embedding) "
                        + "VALUES (?, json_build_object("
                        + "'document_id', ?::text, 'filename', ?::text)::json, ?::vector)",
                content, documentId.toString(), filename, dummyEmbeddingLiteral());
    }

    /**
     * [Purpose] Deletes every row this instance has inserted (by {@code document_id}) and clears
     * the tracked list -- call this from {@code @AfterEach} so one test's rows never leak into
     * another test/another card's session sharing the same live {@code infra} container.
     * [Side effects] Permanently removes rows from {@code vector_store_bgem3}. Uses a direct
     * {@link JdbcTemplate} delete rather than any production repository, so a bug in the class
     * under test cannot also break cleanup (mirrors both existing files' rationale).
     */
    public void cleanUp() {
        for (UUID documentId : insertedDocumentIds) {
            jdbcTemplate.update(
                    "DELETE FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                    documentId.toString());
        }
        insertedDocumentIds.clear();
    }

    /**
     * [Purpose] A syntactically valid {@code vector(1024)} literal (all-zero components) for
     * callers that need to insert a row where the embedding's actual values don't matter to the
     * test (content/metadata predicates only, not vector similarity).
     * [Caution] Do NOT use this for any test that asserts on vector-similarity search results or
     * ranking (review 2026-08-27_document-ingestion-T4-round2.md Medium-C). pgvector's
     * {@code <=>} (cosine distance) operator normalizes each operand internally, which divides by
     * the vector's magnitude -- for an all-zero vector that magnitude is 0, so the division
     * produces {@code NaN}, not a large-but-finite distance. A {@code document-ingestion:AC-9}
     * style test ("상위 미포함") that seeds a "distractor" chunk with this literal and then
     * queries by {@code embedding <=> :query_vector ASC LIMIT n} would see that row sort as
     * {@code NaN} -- PostgreSQL's ordering places {@code NaN} at the end for ascending order, so
     * the row would happen to rank last regardless of what the code under test actually does,
     * making the assertion pass unconditionally instead of testing anything. Use
     * {@link #insertChunk(UUID, String, float[])} with a vector from
     * {@link StubEmbeddingModel#embed(String)} instead whenever a test's assertions touch vector
     * similarity or ranking; keep this method only for content/metadata-predicate tests (BM25,
     * filename round-tripping, deletion, etc.) where the embedding column is a required-NOT-NULL
     * placeholder and nothing else.
     */
    public static String dummyEmbeddingLiteral() {
        return vectorLiteral(new float[1024]);
    }

    /**
     * [Purpose] A {@code vector(1024)} literal built from an actual embedding, for callers whose
     * assertions DO depend on vector similarity/ranking -- e.g. a {@code document-ingestion:AC-9}
     * style test that needs a "distractor" chunk to genuinely score lower than the source chunk
     * against a query, not merely be present in the table (review
     * 2026-08-27_document-ingestion-T4-round2.md Medium-C).
     * [Rationale] Pass {@code StubEmbeddingModel#embed(someDistractorText)}'s result here instead
     * of reaching for {@link #dummyEmbeddingLiteral()} -- see that method's {@code [Caution]} for
     * why an all-zero vector cannot be used in a similarity-ranking assertion at all.
     */
    public void insertChunk(UUID documentId, String content, float[] embedding) {
        insertedDocumentIds.add(documentId);
        jdbcTemplate.update(
                "INSERT INTO vector_store_bgem3 (content, metadata, embedding) "
                        + "VALUES (?, json_build_object('document_id', ?::text)::json, ?::vector)",
                content, documentId.toString(), vectorLiteral(embedding));
    }

    /**
     * [Purpose] Renders a {@code float[]} as a {@code vector(n)} SQL literal (e.g.
     * {@code "[0.0,1.0,...]"}). Shared by {@link #dummyEmbeddingLiteral()} (all-zero input) and
     * {@link #insertChunk(UUID, String, float[])} (a real embedding), so both stay byte-for-byte
     * consistent in how they format each component.
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
