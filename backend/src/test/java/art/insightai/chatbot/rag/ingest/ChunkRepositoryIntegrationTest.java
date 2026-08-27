package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Verifies {@link ChunkRepository}'s count/delete SQL against the live ParadeDB
 *   container -- {@code vector_store_bgem3.embedding} is a pgvector {@code vector(1024)} column
 *   that H2 cannot host, so this is Integration-only, mirroring how
 *   {@code V1InitSchemaIntegrationTest} handles the same constraint for the table's DDL.
 * [Rationale] Every test uses its own randomly generated {@code document_id} and cleans up its
 *   own rows in {@link #cleanUpInsertedRows()} (via a direct {@link JdbcTemplate} delete, not
 *   {@link ChunkRepository#deleteByDocumentId}, so a bug in that method under test cannot also
 *   break cleanup and leak rows into other tests/other cards' sessions running against the same
 *   shared {@code infra} container). Rows are inserted with a dummy all-zero 1024-dimension
 *   embedding literal -- this suite tests the {@code metadata->>'document_id'} predicate, not
 *   vector similarity, so the embedding's actual values are irrelevant.
 */
class ChunkRepositoryIntegrationTest extends IntegrationTestBase {

    private static final String DUMMY_EMBEDDING_LITERAL = buildDummyEmbeddingLiteral();

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedRows() {
        for (UUID documentId : insertedDocumentIds) {
            jdbcTemplate.update(
                    "DELETE FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                    documentId.toString());
        }
        insertedDocumentIds.clear();
    }

    @Test
    @DisplayName("청크가 없는 document_id 는 countByDocumentId 가 0을 반환한다")
    void countByDocumentIdReturnsZeroWhenNoChunksExist() {
        // given: a document id that has never had a chunk inserted for it
        UUID documentId = UUID.randomUUID();

        // when
        int count = chunkRepository.countByDocumentId(documentId);

        // then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("청크가 없는 document_id 는 deleteByDocumentId 가 0건을 삭제하고 0을 반환한다")
    void deleteByDocumentIdReturnsZeroWhenNoChunksExist() {
        // given
        UUID documentId = UUID.randomUUID();

        // when
        int deleted = chunkRepository.deleteByDocumentId(documentId);

        // then
        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("document_id 로 청크 3건을 insert 하면 countByDocumentId 가 3을 반환한다")
    void countByDocumentIdReflectsInsertedChunks() {
        // given: 3 chunk rows inserted for the same document
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "chunk 1");
        insertChunk(documentId, "chunk 2");
        insertChunk(documentId, "chunk 3");

        // when
        int count = chunkRepository.countByDocumentId(documentId);

        // then
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("deleteByDocumentId 는 해당 document_id 의 청크만 삭제하고 삭제 건수를 반환한다")
    void deleteByDocumentIdRemovesOnlyMatchingChunksAndReturnsCount() {
        // given: 2 chunks for the target document and 1 chunk for a different document
        UUID targetDocumentId = UUID.randomUUID();
        UUID otherDocumentId = UUID.randomUUID();
        insertChunk(targetDocumentId, "target chunk 1");
        insertChunk(targetDocumentId, "target chunk 2");
        insertChunk(otherDocumentId, "other chunk");

        // when
        int deleted = chunkRepository.deleteByDocumentId(targetDocumentId);

        // then: exactly the target document's 2 rows were deleted
        assertThat(deleted).isEqualTo(2);
        assertThat(chunkRepository.countByDocumentId(targetDocumentId)).isZero();

        // then: the other document's chunk is untouched
        assertThat(chunkRepository.countByDocumentId(otherDocumentId)).isEqualTo(1);
    }

    private void insertChunk(UUID documentId, String content) {
        insertedDocumentIds.add(documentId);
        String metadataJson = "{\"document_id\": \"" + documentId + "\"}";
        jdbcTemplate.update(
                "INSERT INTO vector_store_bgem3 (content, metadata, embedding) VALUES (?, ?::json, ?::vector)",
                content, metadataJson, DUMMY_EMBEDDING_LITERAL);
    }

    private static String buildDummyEmbeddingLiteral() {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append('0');
        }
        return literal.append(']').toString();
    }
}
