package art.insightai.chatbot.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import art.insightai.chatbot.rag.ingest.ChunkRepository;
import art.insightai.chatbot.support.ChunkSeeder;
import art.insightai.chatbot.support.IntegrationTestBase;

/**
 * [Purpose] End-to-end coverage of {@code POST /api/admin/documents/orphan-chunks/cleanup}
 *   (document-ingestion Spec §4 AC-18, Tech Review T10 High-1, user-approved fix 2) through the
 *   real servlet filter chain against a real database: seeds orphaned chunks (a {@code
 *   document_id} with no matching {@code documents} row) alongside a normal, fully-populated
 *   document's chunk, calls the endpoint once, and checks both halves of AC-18's sentence --
 *   the orphans are gone and the response's {@code deletedCount} is exactly the number removed.
 * [Rationale] {@code ChunkRepository#deleteOrphaned}'s own Javadoc already documents the {@code
 *   NOT EXISTS} predicate; this class does not re-derive it, it exercises the endpoint built on
 *   top of it. The expected {@code deletedCount} is computed as a live baseline query against the
 *   same predicate immediately before the call, not a hardcoded literal -- this project's tests
 *   share one live {@code chatbot-db} container across concurrently active sessions (`_protocol.md`
 *   §4), so a hardcoded "3" would be wrong (and, worse, silently pass or fail depending on
 *   unrelated leftover data) if another session's leaked test row happened to already be orphaned
 *   at the moment this test runs. Comparing against a freshly measured baseline keeps the assertion
 *   exact regardless of that shared state, while the post-call re-query (asserting the global
 *   orphan count is exactly zero) still proves the endpoint's own completeness, not merely "at
 *   least our 3 rows".
 *   <p>
 *   The normal document's chunk is the discriminating case: a predicate bug that deleted every row
 *   in {@code vector_store_bgem3} (dropped {@code WHERE}) or one that matched on the wrong column
 *   would also pass a lone-orphan assertion; only a chunk that is deliberately NOT orphaned and is
 *   re-checked present afterward can catch that (mirrors {@code
 *   DocumentDeleteControllerIntegrationTest}'s own sibling-document discriminating pattern for
 *   T10's delete endpoint).
 * [Caution] This endpoint has no scoping parameter -- it sweeps the entire table
 *   ({@code ChunkRepository#deleteOrphaned}'s own [Rationale]) -- so this test's assertions are
 *   deliberately phrased against a measured baseline rather than an assumed-empty table, per
 *   [Rationale] above. Unauthenticated, same as every other {@code /api/admin/documents} endpoint
 *   in this Spec (§7 OQ-7); no auth setup is exercised here.
 *   <p>
 *   <b>Requires the multi-session exclusive DB window (review 2026-08-30 T10 round 2 correctness
 *   Low-3).</b> The class does not merely read the shared {@code chatbot-db} container -- {@link
 *   #deletesOnlyOrphanedChunksAndReportsExactCount} calls the real endpoint, which permanently
 *   deletes every row in {@code vector_store_bgem3} matching the orphan predicate, including rows
 *   another concurrently running session's own test happens to have left orphaned at that exact
 *   moment (`_protocol.md` §4's shared-container premise). Running this test outside the exclusive
 *   window (`_protocol.md` R4-9) is not merely a flakiness risk -- it can destroy another session's
 *   fixture data. Run only inside a claimed exclusive window, same as the concurrency scenarios in
 *   {@code DocumentIngestConcurrencyIntegrationTest}.
 */
@AutoConfigureMockMvc
class OrphanChunkCleanupControllerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkRepository chunkRepository;

    private ChunkSeeder chunkSeeder;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    @BeforeEach
    void createChunkSeeder() {
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
    }

    @AfterEach
    void cleanUpInsertedRowsAndChunks() {
        chunkSeeder.cleanUp();
        for (UUID documentId : insertedDocumentIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
        }
        insertedDocumentIds.clear();
    }

    @Test
    @DisplayName("[document-ingestion:AC-18] documents 행이 없는 고아 청크만 정확히 삭제되고 정상 문서의 청크는 "
            + "보존되며, 삭제 건수가 응답 deletedCount 에 정확히 담긴다")
    void deletesOnlyOrphanedChunksAndReportsExactCount() throws Exception {
        // given: two orphaned document ids -- no documents row for either -- carrying 3 chunk rows
        // total between them, plus a real, fully-populated document with its own chunk that must
        // survive the sweep
        UUID orphanId1 = UUID.randomUUID();
        UUID orphanId2 = UUID.randomUUID();
        chunkSeeder.insertChunk(orphanId1, "고아 청크 1");
        chunkSeeder.insertChunk(orphanId1, "고아 청크 2");
        chunkSeeder.insertChunk(orphanId2, "고아 청크 3");

        UUID normalId = UUID.randomUUID();
        insertDocument(normalId, "kept.txt", "text/plain", 10L, "INDEXED", 1);
        chunkSeeder.insertChunk(normalId, "정상 문서의 청크");

        assertThat(chunkRepository.countByDocumentId(orphanId1)).isEqualTo(2);
        assertThat(chunkRepository.countByDocumentId(orphanId2)).isEqualTo(1);
        assertThat(chunkRepository.countByDocumentId(normalId)).isEqualTo(1);

        // given: the exact number this call must report and remove, measured live right before the
        // call against the same predicate ChunkRepository#deleteOrphaned uses -- see class
        // [Rationale] for why a hardcoded literal would not be safe on this project's shared DB
        long expectedDeletedCount = countGlobalOrphans();
        assertThat(expectedDeletedCount)
                .as("sanity: our 3 seeded orphans are included in the measured baseline")
                .isGreaterThanOrEqualTo(3L);

        // when
        mockMvc.perform(post("/api/admin/documents/orphan-chunks/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value((int) expectedDeletedCount));

        // then: the endpoint's own predicate is now satisfied by nothing at all -- not merely "our
        // 3 rows are gone", which a partial sweep could also achieve
        assertThat(countGlobalOrphans()).as("no orphan of any kind survives the sweep").isZero();
        assertThat(chunkRepository.countByDocumentId(orphanId1)).isZero();
        assertThat(chunkRepository.countByDocumentId(orphanId2)).isZero();

        // then: the normal document's chunk and row are untouched -- the discriminating assertion
        // against an over-broad delete
        assertThat(chunkRepository.countByDocumentId(normalId))
                .as("a non-orphaned chunk must survive the sweep")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM documents WHERE id = ?", Integer.class, normalId))
                .hasSize(1);
    }

    /**
     * [Purpose] Live count of every {@code vector_store_bgem3} row matching {@code
     *   ChunkRepository#deleteOrphaned}'s own predicate, independent of the class under test --
     *   used as this test's measured baseline (class [Rationale]) rather than a hardcoded literal.
     */
    private long countGlobalOrphans() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store_bgem3 v "
                        + "WHERE v.metadata->>'document_id' IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id::text = v.metadata->>'document_id')",
                Long.class);
        return count != null ? count : 0L;
    }

    /**
     * [Purpose] Inserts one {@code documents} row directly via {@link JdbcTemplate}, tracking the
     *   id for {@link #cleanUpInsertedRowsAndChunks}. Mirrors {@code
     *   DocumentDeleteControllerIntegrationTest#insertDocument} -- {@link
     *   art.insightai.chatbot.rag.domain.Document}'s constructor does not accept a caller-chosen id
     *   or an already-INDEXED status, both of which this class's fixture needs.
     */
    private void insertDocument(UUID id, String filename, String mimeType, long sizeBytes,
            String status, int chunkCount) {
        insertedDocumentIds.add(id);
        jdbcTemplate.update(
                "INSERT INTO documents (id, filename, mime_type, size_bytes, status, chunk_count, "
                        + "created_at, indexed_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, filename, mimeType, sizeBytes, status, chunkCount);
    }
}
