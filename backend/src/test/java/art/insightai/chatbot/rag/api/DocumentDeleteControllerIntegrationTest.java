package art.insightai.chatbot.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.rag.ingest.ChunkRepository;
import art.insightai.chatbot.rag.ingest.DocumentIngestLockRegistry;
import art.insightai.chatbot.support.ChunkSeeder;
import art.insightai.chatbot.support.DocumentRowCounter;
import art.insightai.chatbot.support.IntegrationTestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * [Purpose] End-to-end coverage of {@code DELETE /api/admin/documents/{id}} (document-ingestion
 *   Spec Tech Review T10, document-ingestion:AC-12/AC-13) through the real servlet filter chain
 *   against a real database and a real filesystem write: the 204 success path leaving all three
 *   traces (row, {@code vector_store_bgem3} chunks, upload-dir file) individually gone
 *   (document-ingestion:AC-12), and the 404-plus-preservation combination the AC-13 sentence
 *   actually specifies -- an unknown id returns 404 <em>and</em> a pre-existing other document's
 *   row, chunk and file are all still present afterward, checked in one test rather than the 404
 *   and the preservation being asserted in two unrelated tests. A third, separate test drives the
 *   <em>success</em> path against two documents to catch an over-broad delete (dropped {@code
 *   WHERE} clause or a coarser key) that a lone-document assertion cannot distinguish from
 *   "deleted everything".
 * [Rationale] {@code DocumentDeleteServiceTest} (backend-engineer, unit level, mocked repositories)
 *   already proves the three-collaborator call sequence and the exception-first-no-side-effect
 *   shape. This class adds what only a real {@code @SpringBootTest} can: the actual HTTP status
 *   code through {@code GlobalExceptionHandler}, the row/chunk/file individually confirmed absent
 *   against the real {@code documents}/{@code vector_store_bgem3} tables and a real upload
 *   directory, and -- the part a mocked {@link ChunkRepository} cannot show at all -- that the
 *   {@code metadata->>'document_id'} predicate in the real delete SQL does not spill onto a
 *   sibling document's rows (project convention 통합 테스트는 실제 인스턴스, 모킹 금지). Mirrors {@code
 *   DocumentListControllerIntegrationTest}'s direct-{@link JdbcTemplate}-insert style for
 *   {@code documents} rows (the entity does not expose a way to construct an already-INDEXED row
 *   with a caller-chosen id) and {@link ChunkSeeder} (T4 {@code support/} convention) for
 *   {@code vector_store_bgem3} rows, rather than driving the real upload/ingestion pipeline --
 *   this class's own contract under test is the delete path, not ingestion.
 * [Caution] The "second document untouched" case ({@link
 *   #deletingOneDocumentLeavesAnotherDocumentsChunksIntactAndDoesNotDeleteItsRow()}) exists
 *   specifically for discriminating power against an over-broad delete on the <em>success</em>
 *   path (e.g. a predicate that drops the {@code WHERE} clause, or one keyed by something
 *   coarser than {@code document_id}) -- asserting only on the target document's own absence, as
 *   a lone-document test would, cannot distinguish "deleted exactly this document" from "deleted
 *   everything"; the sibling document's documents row is also asserted to still exist for the
 *   same reason on the {@code documents} side (this card is the first in document-ingestion to
 *   introduce this kind of discriminating coverage for delete assertions -- no prior review
 *   round flagged it as missing; T9's review rounds are about list ordering discriminating
 *   power, not deletion). {@link #returns404ForUnknownIdAndLeavesAnExistingDocumentIntact()}
 *   applies the same discriminating idea to the 404 path: it seeds a real document (row + chunk
 *   + file) before the unknown-id DELETE and re-reads all three afterward, so the test actually
 *   exercises the combined 404-and-preservation path the AC-13 sentence specifies, rather than
 *   the 404 status alone.
 *   {@code app.ingest.upload-dir} is
 *   overridden to a JUnit-managed {@link #uploadDir} via {@link DynamicPropertySource} (mirrors
 *   {@code DocumentUploadControllerIntegrationTest}'s own [Caution] on why the real {@code
 *   infra/.env UPLOAD_DIR} must never be read/written directly by a test, INC-010).
 */
@AutoConfigureMockMvc
class DocumentDeleteControllerIntegrationTest extends IntegrationTestBase {

    private static final byte[] ORIGINAL_FILE_CONTENT =
            "sample content for document-ingestion T10 delete integration coverage"
                    .getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void overrideUploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.ingest.upload-dir", () -> uploadDir.toString());
    }

    /**
     * [Purpose] Shortens {@code app.ingest.delete-lock-timeout} from the 30s production default to
     *   200ms for this whole test class, so {@link
     *   #returnsServiceUnavailableWhenLockCannotBeAcquiredWithinTimeout} does not have to block a
     *   real request thread for 30 real seconds to observe the 503 path (review 2026-08-30 T10
     *   round 3, Medium-1). Safe for this class's other tests: none of them contends for a lock a
     *   second thread already holds, so a shorter timeout never changes their outcome, only how
     *   long a genuine contention would take to surface.
     */
    @DynamicPropertySource
    static void overrideDeleteLockTimeout(DynamicPropertyRegistry registry) {
        registry.add("app.ingest.delete-lock-timeout", () -> "200ms");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private DocumentIngestLockRegistry ingestLockRegistry;

    private ChunkSeeder chunkSeeder;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    @BeforeEach
    void createChunkSeeder() {
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
    }

    @AfterEach
    void cleanUpInsertedRowsChunksAndFiles() {
        chunkSeeder.cleanUp();
        for (UUID documentId : insertedDocumentIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            deleteQuietly(uploadedFilePath(documentId));
        }
        insertedDocumentIds.clear();
    }

    @Test
    @DisplayName("[document-ingestion:AC-12] 존재하는 문서를 삭제하면 204를 반환하고 documents 행·"
            + "vector_store_bgem3 청크·upload-dir 원본 파일이 모두 개별적으로 사라진다")
    void deletesRowChunksAndFileAndReturns204ForAnExistingDocument() throws Exception {
        // given: a fully-populated document with a real row, two real chunks and a real file on
        // disk -- all three traces AC-12 requires gone are seeded independently so each can be
        // checked on its own, not inferred from the others
        UUID documentId = UUID.randomUUID();
        insertDocument(documentId, "delete-target.txt", "text/plain", 42L, "INDEXED", 2);
        chunkSeeder.insertChunk(documentId, "chunk 1 of delete-target.txt");
        chunkSeeder.insertChunk(documentId, "chunk 2 of delete-target.txt");
        Path originalFile = uploadedFilePath(documentId);
        Files.write(originalFile, ORIGINAL_FILE_CONTENT);
        assertThat(DocumentRowCounter.countDocuments(jdbcTemplate)).isGreaterThan(0);
        assertThat(chunkRepository.countByDocumentId(documentId)).isEqualTo(2);
        assertThat(originalFile).exists();

        // when
        mockMvc.perform(delete("/api/admin/documents/{id}", documentId))
                .andExpect(status().isNoContent());

        // then: three individually-checked absences -- the row...
        List<Integer> remainingRows = jdbcTemplate.queryForList(
                "SELECT 1 FROM documents WHERE id = ?", Integer.class, documentId);
        assertThat(remainingRows).isEmpty();

        // then: ...the chunks...
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();

        // then: ...and the original file
        assertThat(originalFile).doesNotExist();
    }

    @Test
    @DisplayName("[document-ingestion:AC-13] 존재하지 않는 id로 DELETE 하면 404를 반환하고, 존재하는 "
            + "다른 문서의 행·청크·파일은 그대로 보존된다")
    void returns404ForUnknownIdAndLeavesAnExistingDocumentIntact() throws Exception {
        // given: an unrelated, fully-populated document (row + chunk + file) is seeded so its
        // preservation can be checked after the 404 -- this is the combined 404-plus-preservation
        // path AC-13's sentence actually specifies, not two independently-true facts
        UUID unknownId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        insertDocument(otherId, "unaffected.txt", "text/plain", 15L, "INDEXED", 1);
        chunkSeeder.insertChunk(otherId, "unaffected chunk");
        Path otherFile = uploadedFilePath(otherId);
        Files.write(otherFile, ORIGINAL_FILE_CONTENT);

        // when
        mockMvc.perform(delete("/api/admin/documents/{id}", unknownId))
                .andExpect(status().isNotFound());

        // then: the unrelated document's row, chunk and file are all still present
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM documents WHERE id = ?", Integer.class, otherId))
                .hasSize(1);
        assertThat(chunkRepository.countByDocumentId(otherId)).isEqualTo(1);
        assertThat(otherFile).exists().hasBinaryContent(ORIGINAL_FILE_CONTENT);
    }

    @Test
    @DisplayName("[document-ingestion:AC-13] 문서 2건 중 1건만 삭제하면 삭제하지 않은 문서의 행과 청크는 "
            + "그대로 남는다 -- 전체 삭제 같은 과잉 삭제를 판별한다")
    void deletingOneDocumentLeavesAnotherDocumentsChunksIntactAndDoesNotDeleteItsRow() throws Exception {
        // given: two independent documents, each with its own row and chunks -- only one is
        // targeted for deletion. A predicate bug that drops the document_id filter (or scopes to
        // the wrong column) would delete both; only checking the deleted document's own absence,
        // as the AC-12 case above does, cannot catch that -- the untouched document's own state
        // must be read back directly.
        UUID targetId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        insertDocument(targetId, "target.txt", "text/plain", 10L, "INDEXED", 1);
        insertDocument(otherId, "other.txt", "text/plain", 20L, "INDEXED", 2);
        chunkSeeder.insertChunk(targetId, "target chunk");
        chunkSeeder.insertChunk(otherId, "other chunk 1");
        chunkSeeder.insertChunk(otherId, "other chunk 2");
        Path targetFile = uploadedFilePath(targetId);
        Path otherFile = uploadedFilePath(otherId);
        Files.write(targetFile, ORIGINAL_FILE_CONTENT);
        Files.write(otherFile, ORIGINAL_FILE_CONTENT);

        // when
        mockMvc.perform(delete("/api/admin/documents/{id}", targetId))
                .andExpect(status().isNoContent());

        // then: the target document is gone (row, chunks, file)
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM documents WHERE id = ?", Integer.class, targetId))
                .isEmpty();
        assertThat(chunkRepository.countByDocumentId(targetId)).isZero();
        assertThat(targetFile).doesNotExist();

        // then: the other document's row, chunks and file are all still present -- this is the
        // discriminating assertion this test exists for
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM documents WHERE id = ?", Integer.class, otherId))
                .hasSize(1);
        assertThat(chunkRepository.countByDocumentId(otherId)).isEqualTo(2);
        assertThat(otherFile).exists().hasBinaryContent(ORIGINAL_FILE_CONTENT);
    }

    @Test
    @DisplayName("[document-ingestion:AC-17] 존재하지 않는 임의의 UUID 200개로 반복 DELETE 를 시도해도 "
            + "락 레지스트리의 스트라이프 배열 크기는 고정 64를 그대로 유지한다 (review round 3 High-1)")
    void lockRegistryStripeArrayStaysFixedSizeAcross200UnknownDeleteAttempts() throws Exception {
        // given: this is the exact unauthenticated-attacker shape review round 3 High-1 found
        // dangerous under the round-1/round-2 never-evicted-per-id-map design -- a repeated DELETE
        // against distinct, arbitrary UUIDs that do not exist. The array is read through the real
        // Spring-managed singleton bean (not a fresh instance this test constructs itself), so this
        // exercises the exact object the controller's request thread also uses.
        int before = stripeArrayLength();
        assertThat(before).as("stripe array length before any request").isEqualTo(64);

        // when
        for (int i = 0; i < 200; i++) {
            mockMvc.perform(delete("/api/admin/documents/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }

        // then: same length as before -- allocation happened exactly once, at bean construction,
        // independent of how many distinct ids were ever looked up (valid or not)
        assertThat(stripeArrayLength())
                .as("stripe array length after 200 distinct unknown ids")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("[document-ingestion:AC-17] 같은 문서 id 의 스트라이프를 다른 스레드가 쥐고 있으면 HTTP 요청은 "
            + "설정된 타임아웃 후 503을 반환하고, 트랜잭션은 실행되지 않아 행이 그대로 남는다 "
            + "(review round 3 Medium-1)")
    void returnsServiceUnavailableWhenLockCannotBeAcquiredWithinTimeout() throws Exception {
        // given: a real, existing document, whose lock this test acquires directly through the
        // autowired DocumentIngestLockRegistry bean -- the same bean instance the controller's
        // request thread will contend on for the same id
        UUID documentId = UUID.randomUUID();
        insertDocument(documentId, "locked-during-delete.txt", "text/plain", 5L, "INDEXED", 0);
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            ingestLockRegistry.lock(documentId);
            holderAcquired.countDown();
            try {
                releaseHolder.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ingestLockRegistry.unlock(documentId);
            }
        }, "delete-lock-timeout-holder");
        holder.start();
        boolean acquired = holderAcquired.await(10, TimeUnit.SECONDS);
        assertThat(acquired).as("background thread acquired the lock before the request was sent").isTrue();

        try {
            // when / then: the request thread's own tryLock (bounded to 200ms for this class, see
            // overrideDeleteLockTimeout) cannot acquire the same id's stripe while the background
            // thread still holds it, so GlobalExceptionHandler maps the resulting
            // DocumentDeleteLockTimeoutException to 503
            mockMvc.perform(delete("/api/admin/documents/{id}", documentId))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            releaseHolder.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
        }

        // then: the row is untouched -- the transaction body never ran because the lock was never
        // acquired by the request thread
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM documents WHERE id = ?", Integer.class, documentId))
                .hasSize(1);
    }

    /**
     * [Purpose] Reads {@link DocumentIngestLockRegistry}'s private {@code stripes} array length via
     *   reflection -- the class deliberately exposes no production getter for this (it is an
     *   internal sizing detail, not part of its public contract), so a test-only reflective read is
     *   the least invasive way to observe the fixed-size claim review round 3 High-1 makes.
     */
    private int stripeArrayLength() throws ReflectiveOperationException {
        Field stripesField = DocumentIngestLockRegistry.class.getDeclaredField("stripes");
        stripesField.setAccessible(true);
        Object[] stripes = (Object[]) stripesField.get(ingestLockRegistry);
        return stripes.length;
    }

    /**
     * [Purpose] Inserts one {@code documents} row directly via {@link JdbcTemplate}, tracking the
     *   id for {@link #cleanUpInsertedRowsChunksAndFiles}.
     * [Rationale] Mirrors {@code DocumentListControllerIntegrationTest#insertDocument} -- {@link
     *   art.insightai.chatbot.rag.domain.Document}'s constructor does not accept a caller-chosen id
     *   or an already-INDEXED status, both of which this class's fixtures need.
     */
    private void insertDocument(UUID id, String filename, String mimeType, long sizeBytes,
            String status, int chunkCount) {
        insertedDocumentIds.add(id);
        jdbcTemplate.update(
                "INSERT INTO documents (id, filename, mime_type, size_bytes, status, chunk_count, "
                        + "created_at, indexed_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, filename, mimeType, sizeBytes, status, chunkCount);
    }

    private Path uploadedFilePath(UUID documentId) {
        return uploadDir.resolve(documentId.toString());
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean up test file " + path, e);
        }
    }
}
