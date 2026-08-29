package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.support.ChunkSeeder;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.support.StubEmbeddingModel;

/**
 * [Purpose] Verifies {@link VectorStoreSearchAdapter}'s {@code metadata->>'document_id' NOT IN
 *   (...)} exclusion (T13/D-T10/D-T11) against the live pgvector index -- {@link
 *   VectorStoreSearchAdapterTest} covers the same SQL/parameter construction with a mocked {@link
 *   org.springframework.jdbc.core.JdbcTemplate}, but only a real {@code documents}/{@code
 *   vector_store_bgem3} round trip can prove this class's raw SQL actually excludes the right
 *   rows against pgvector (hybrid-rag:AC-16).
 * [Rationale] Both seeded chunks use the exact same content/embedding (via {@link
 *   StubEmbeddingModel}, never the all-zero literal -- see {@link ChunkSeeder#dummyEmbeddingLiteral()}'s
 *   Javadoc for why an all-zero vector cannot be used with a {@code similarityThresholdAll()}-style
 *   distance cutoff: a zero-magnitude vector's cosine distance against any query is {@code NaN},
 *   and this class's own {@code WHERE embedding <=> ? < 1.0} clause evaluates {@code NaN < 1.0}
 *   as false, silently dropping the row from every result regardless of this class's own
 *   filtering logic). This test only needs presence/absence, not ranking, so identical
 *   content/vectors for both chunks keep the seed minimal.
 */
class VectorStoreSearchAdapterIntegrationTest extends IntegrationTestBase {

    @Autowired
    private VectorStoreSearchAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChunkSeeder chunkSeeder;
    private final StubEmbeddingModel embeddingModel = new StubEmbeddingModel();
    private final List<UUID> insertedDocumentTableIds = new ArrayList<>();

    @BeforeEach
    void setUpSeeder() {
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        chunkSeeder.cleanUp();
        for (UUID documentId : insertedDocumentTableIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
        }
        insertedDocumentTableIds.clear();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-16] documents.status 가 INDEXED 가 아닌 문서의 청크는 벡터 검색 결과에서 "
            + "제외되고, INDEXED 문서의 청크는 같은 쿼리로 반환된다 (양성 대조)")
    void excludesChunksOfNonIndexedDocumentsFromVectorSearchWithPositiveControl() {
        // given: one INDEXED document (positive control) and one FAILED document (orphaned chunk
        // per document-ingestion T8 review correctness Medium-2), each with a chunk carrying the
        // exact same content/embedding -- this test only needs presence/absence, not ranking
        UUID indexedDocumentId = UUID.randomUUID();
        UUID failedDocumentId = UUID.randomUUID();
        insertDocument(indexedDocumentId, "INDEXED");
        insertDocument(failedDocumentId, "FAILED");
        String sharedContent = "고아청크제외검증용_벡터검색_테스트문장";
        float[] sharedEmbedding = embeddingModel.embed(sharedContent);
        chunkSeeder.insertChunk(indexedDocumentId, sharedContent, sharedEmbedding);
        chunkSeeder.insertChunk(failedDocumentId, sharedContent, sharedEmbedding);

        // when
        List<RetrievedChunk> result = adapter.search(sharedContent, 20);

        // then: positive control first -- the INDEXED document's chunk IS returned, ruling out
        // "the query itself finds nothing" before the exclusion assertion below is trusted
        assertThat(result)
                .extracting(chunk -> chunk.metadata().get("document_id"))
                .contains(indexedDocumentId.toString());
        // then: the FAILED document's chunk is excluded despite carrying an identical vector
        assertThat(result)
                .extracting(chunk -> chunk.metadata().get("document_id"))
                .doesNotContain(failedDocumentId.toString());
    }

    /**
     * [Purpose] Inserts one {@code documents} row with the given {@code status} (T13/D-T10,
     *   hybrid-rag:AC-16) -- a minimal row satisfying the table's {@code NOT NULL} columns
     *   ({@code filename}, {@code status}), tracked for cleanup in {@link #cleanUp()}.
     */
    private void insertDocument(UUID id, String status) {
        insertedDocumentTableIds.add(id);
        jdbcTemplate.update(
                "INSERT INTO documents (id, filename, status) VALUES (?, ?, ?)",
                id, "ac16-test-" + id + ".txt", status);
    }
}
