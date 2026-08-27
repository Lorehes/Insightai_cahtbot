package art.insightai.chatbot.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Self-verification for {@link ChunkSeeder} against the live {@code chatbot} ParadeDB
 * instance -- review 2026-08-27_document-ingestion-T4-round2.md Low item: this helper had zero
 * call sites and zero tests of its own when it was introduced, so its documented behavior
 * (insert/cleanup round-trip, filename metadata round-tripping through the JSON-parameter-bound
 * insert, and the all-zero-vs-real-embedding cosine-distance {@code NaN} claim in
 * {@link ChunkSeeder#dummyEmbeddingLiteral()}'s {@code [Caution]}) was, until now, only asserted
 * in prose.
 * [Rationale] The last test in particular is not a style nitpick -- it is the empirical proof
 * behind {@link ChunkSeeder#dummyEmbeddingLiteral()}'s warning not to use it in a
 * vector-similarity assertion (project rule P3: verify against the real instance, don't guess
 * from pgvector documentation). If pgvector's actual behavior ever changes (a version bump, a
 * different distance operator), this test -- not just the Javadoc -- would catch the drift.
 */
class ChunkSeederIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChunkSeeder chunkSeeder;

    @BeforeEach
    void setUp() {
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        chunkSeeder.cleanUp();
    }

    @Test
    @DisplayName("insertChunk(UUID, String) 로 심은 행이 실제로 존재하고 cleanUp 후 사라진다")
    void insertChunkInsertsAndCleanUpRemoves() {
        // given
        UUID documentId = UUID.randomUUID();

        // when
        chunkSeeder.insertChunk(documentId, "청크 본문");

        // then
        assertThat(countRowsFor(documentId)).isEqualTo(1);

        // when
        chunkSeeder.cleanUp();

        // then
        assertThat(countRowsFor(documentId)).isZero();
    }

    @Test
    @DisplayName("insertChunk(String) 오버로드는 새 document_id를 생성해 반환한다")
    void insertChunkWithoutExplicitIdGeneratesAndReturnsOne() {
        // when
        UUID generatedId = chunkSeeder.insertChunk("자동 생성 id 청크");

        // then
        assertThat(generatedId).isNotNull();
        assertThat(countRowsFor(generatedId)).isEqualTo(1);
    }

    @Test
    @DisplayName("insertChunk(UUID, String, String filename) 로 콤마·따옴표가 섞인 filename도 그대로 저장된다")
    void insertChunkWithFilenameStoresMetadataVerbatim() {
        // given: a filename containing comma/quote/brace characters -- the exact hazard the
        // json_build_object(...)::json + bound-parameter template (class Javadoc) exists to
        // survive without hand-escaping.
        UUID documentId = UUID.randomUUID();
        String filename = "보고서, \"최종\"{v2}.txt";

        // when
        chunkSeeder.insertChunk(documentId, "청크 본문", filename);

        // then
        String storedFilename = jdbcTemplate.queryForObject(
                "SELECT metadata->>'filename' FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                String.class, documentId.toString());
        assertThat(storedFilename).isEqualTo(filename);
    }

    @Test
    @DisplayName("[Medium-C 실증] 전영 임베딩 행은 코사인 거리가 NaN이고, 실제 임베딩 행은 NaN이 아니다")
    void allZeroEmbeddingProducesNaNCosineDistanceButRealEmbeddingDoesNot() {
        // given: one row seeded via the all-zero convenience literal, one via a real
        // (StubEmbeddingModel-derived) vector -- both against the same query vector
        StubEmbeddingModel model = new StubEmbeddingModel();
        float[] queryVector = model.embed("코사인 거리 검증용 질의 문장");

        UUID zeroEmbeddingDocumentId = UUID.randomUUID();
        chunkSeeder.insertChunk(zeroEmbeddingDocumentId, "전영 임베딩으로 심어진 청크");

        UUID realEmbeddingDocumentId = UUID.randomUUID();
        String realEmbeddingText = "실제 임베딩으로 심어진 청크";
        chunkSeeder.insertChunk(realEmbeddingDocumentId, realEmbeddingText, model.embed(realEmbeddingText));

        // when
        Double zeroDistance = cosineDistanceTo(queryVector, zeroEmbeddingDocumentId);
        Double realDistance = cosineDistanceTo(queryVector, realEmbeddingDocumentId);

        // then: this is the live-database confirmation behind
        // ChunkSeeder#dummyEmbeddingLiteral()'s [Caution] -- an all-zero vector's cosine
        // distance is NaN (division by a zero-magnitude norm inside pgvector's <=> operator),
        // which is why a distractor chunk seeded that way cannot be used in a
        // vector-similarity-ranking assertion (it would sort as NaN, not as "far").
        assertThat(zeroDistance).isNaN();
        assertThat(realDistance).isNotNaN();
    }

    private int countRowsFor(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                Integer.class, documentId.toString());
        return count == null ? 0 : count;
    }

    private Double cosineDistanceTo(float[] queryVector, UUID documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT (embedding <=> ?::vector) FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                Double.class, vectorLiteral(queryVector), documentId.toString());
    }

    /**
     * [Purpose] Renders a {@code float[]} as a {@code vector(n)} SQL literal for this test's own
     * query-side parameter -- deliberately not reusing {@link ChunkSeeder}'s private
     * {@code vectorLiteral}, since this class exercises {@link ChunkSeeder} as a black box (its
     * own inserted rows), not its internals; this is the query vector, not an inserted row.
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
