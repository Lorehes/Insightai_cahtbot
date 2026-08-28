package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentRepository;
import art.insightai.chatbot.support.ChunkSeeder;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.support.SampleDocumentFixtures;

/**
 * [Purpose] Drives {@link DocumentIngestionPipeline#ingest(UUID)} against the live
 *   {@code chatbot-db} ParadeDB container and observes what it actually commits, closing the
 *   three ledger rows Spec §6's Test Cases table places under Integration:
 *   document-ingestion:AC-5 (PENDING -&gt; PARSING -&gt; EMBEDDING -&gt; INDEXED really recorded in
 *   that order, {@code indexed_at} non-null), document-ingestion:AC-6 (parse/index failure =&gt;
 *   FAILED + non-blank {@code error_message} + zero chunks, cleanup ordered before the FAILED
 *   record) and document-ingestion:AC-7 ({@code chunk_count} equals the row count under
 *   {@code metadata->>'document_id'} and is at least 1).
 * [Rationale] The Spec's AC-5 cell explicitly forbids closing these rows on the strength of T8's
 *   mock-based unit suite ({@link DocumentIngestionPipelineStateMachineTest}): a mocked
 *   {@code DocumentStatusTransitioner} can prove call order but not that anything was
 *   <i>committed</i>, and AD-3's whole reason for {@code REQUIRES_NEW} is external observability.
 *   Every state assertion here is therefore read back through {@link #onSeparateConnection} -- a
 *   raw {@link DataSource} connection borrowed on a different thread, outside any Spring-managed
 *   transaction -- so a future change that wrapped the pipeline in one enclosing transaction
 *   would make the intermediate reads return PENDING and fail this file, which is exactly the
 *   regression AD-3 exists to prevent. The pipeline is invoked <b>synchronously</b> (its own
 *   Javadoc [Caution] documents this as the intended test entry point), so no executor race and
 *   no polling is involved in the transition/failure-message assertions.
 *   <p>
 *   AC-7's case deliberately arranges for {@code chunks.size()} and
 *   {@link ChunkRepository#countByDocumentId} to disagree (one pre-existing row is seeded under
 *   the same {@code document_id} before the run) and asserts the recorded value equals the count
 *   and differs from the list size -- without that divergence the assertion would pass for a
 *   {@code chunks.size()} implementation too and would carry no discriminating power. AC-7's own
 *   wording defines the expected value as the row count under that predicate, so the count is the
 *   correct answer by definition whenever the two differ.
 * [Caution] <b>Connection budget.</b> The test JDBC pool is pinned to
 *   {@code maximum-pool-size: 3} / {@code connection-timeout: 5000} and
 *   {@link IngestAsyncConfig}'s [Caution] shows the arithmetic that leaves zero headroom. No test
 *   method here is {@code @Transactional} (so the test thread never pins a connection across an
 *   ingest step) and at most one helper thread is alive at a time: the observation points used by
 *   {@link #recordsCommittedStatusTransitionsInOrder()} run while the pipeline holds no
 *   connection at all, and {@link #deletesPartialChunksBeforeRecordingFailed()}'s watcher adds
 *   exactly one concurrent borrower to the pipeline's one. Do not add a second concurrent
 *   observer, and do not annotate anything here {@code @Transactional}.
 *   <p>
 *   {@code app.ingest.upload-dir} is redirected to a JUnit {@code @TempDir}, following
 *   {@code DocumentUploadControllerIntegrationTest}'s [Caution] (review 2026-08-28 High-2): this
 *   file writes and deletes files under that directory and must never touch a developer's real
 *   {@code infra/.env} {@code UPLOAD_DIR}.
 *   <p>
 *   <b>One case here is a counterexample, not evidence.</b>
 *   {@link #leavesChunksBehindWhenTheCompensatingDeleteItselfFails()} shows the one branch on which
 *   AC-6's third element ("청크 0건") does not hold -- the compensating delete failing on its own --
 *   and is therefore tagged {@code [document-ingestion §5]} so it can never be counted as AC-6
 *   evidence (review 2026-08-28 T8 Medium-4). The implementation choice it pins is the accepted one
 *   (the alternative, leaving the document non-terminal, is permanent under Spec §7 OQ-8); what the
 *   test adds is that the exception stays observable and cannot be quietly changed. The Spec §4
 *   AC-6 cell carries the matching note.
 */
class DocumentIngestionPipelineDbIntegrationTest extends IntegrationTestBase {

    /**
     * How long the simulated partial index keeps its rows visible before failing, so the watcher
     * in {@link #deletesPartialChunksBeforeRecordingFailed()} is guaranteed several samples while
     * orphaned chunks exist. Not a synchronization device -- the assertion that depends on it
     * ("at least one sample saw chunks &gt; 0") is checked explicitly rather than assumed.
     */
    private static final long PARTIAL_INDEX_VISIBLE_MILLIS = 300L;

    private static final long WATCHER_POLL_INTERVAL_MILLIS = 10L;

    private static final long WATCHER_TERMINAL_WAIT_MILLIS = 10_000L;

    private static final long OBSERVER_JOIN_TIMEOUT_MILLIS = 10_000L;

    /** Text whose every character is whitespace -- see {@link #failsWithEmptyContentMessage()}. */
    private static final byte[] WHITESPACE_ONLY_CONTENT = "   \n\t  \n   ".getBytes(StandardCharsets.UTF_8);

    /**
     * The message of the simulated indexing exception. It embeds a Windows-style absolute path and
     * a driver class name on purpose: {@link #deletesPartialChunksBeforeRecordingFailed()} asserts
     * none of it reaches {@code documents.error_message}, which
     * {@code GET /api/admin/documents} (AC-11) exposes on an unauthenticated endpoint (INC-007).
     */
    private static final String SIMULATED_INDEX_FAILURE_DETAIL =
            "org.postgresql.util.PSQLException: relation failure at C:\\secret\\uploads\\internal";

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void overrideUploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.ingest.upload-dir", () -> uploadDir.toString());
    }

    @Autowired
    private DocumentIngestionPipeline pipeline;

    @Autowired
    private DocumentRepository documentRepository;

    /**
     * Spied rather than plain {@code @Autowired} so
     * {@link #leavesChunksBehindWhenTheCompensatingDeleteItselfFails()} can make the compensating
     * delete throw. Every other case in this class gets the real behaviour: a Mockito spy delegates
     * to the real bean unless a specific invocation is stubbed, and the one stub that exists is
     * scoped to a single {@code documentId} and disarms itself after the first call so
     * {@link #cleanUpDocumentsAndChunks()} can still delete (spy stubbings are reset after
     * {@code @AfterEach}, not before it).
     */
    @MockitoSpyBean
    private ChunkRepository chunkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private DocumentParser documentParser;

    @MockitoSpyBean
    private ChunkIndexer chunkIndexer;

    private final List<UUID> trackedDocumentIds = new ArrayList<>();

    private ChunkSeeder chunkSeeder;

    @AfterEach
    void cleanUpDocumentsAndChunks() {
        if (chunkSeeder != null) {
            chunkSeeder.cleanUp();
            chunkSeeder = null;
        }
        for (UUID documentId : trackedDocumentIds) {
            chunkRepository.deleteByDocumentId(documentId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            try {
                Files.deleteIfExists(uploadDir.resolve(documentId.toString()));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete test upload " + documentId, e);
            }
        }
        trackedDocumentIds.clear();
    }

    @Test
    @DisplayName("[document-ingestion:AC-5][document-ingestion:AC-7] 색인 성공 시 상태가 PENDING→PARSING→EMBEDDING→INDEXED "
            + "순서로 실제 커밋되고 indexed_at 과 chunk_count 가 함께 기록된다")
    void recordsCommittedStatusTransitionsInOrder() {
        // given: a real PENDING row and a real fixture file at upload-dir/{id}
        UUID documentId = createPendingDocumentWithFile(
                SampleDocumentFixtures.TEXT_FILENAME,
                SampleDocumentFixtures.TEXT_MIME_TYPE,
                SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME));
        List<String> observedStatuses = Collections.synchronizedList(new ArrayList<>());
        observedStatuses.add(statusOf(documentId));

        // given: two probes that read documents.status from a separate connection at the exact
        // moments the pipeline is between steps. If the pipeline ever stopped committing each
        // transition on its own (AD-3), these reads would both still see PENDING.
        doAnswer(invocation -> {
            observedStatuses.add(statusOf(documentId));
            return invocation.callRealMethod();
        }).when(documentParser).parse(any());
        doAnswer(invocation -> {
            observedStatuses.add(statusOf(documentId));
            return invocation.callRealMethod();
        }).when(chunkIndexer).index(anyList());

        // when
        pipeline.ingest(documentId);
        observedStatuses.add(statusOf(documentId));

        // then: the four states were observed from outside, in the order AC-5 prescribes
        assertThat(observedStatuses)
                .as("status values committed and visible to an outside reader, in observation order")
                .containsExactly("PENDING", "PARSING", "EMBEDDING", "INDEXED");

        // then: the terminal row carries indexed_at and a chunk_count (AC-5 / AC-7)
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, indexed_at, chunk_count, error_message FROM documents WHERE id = ?",
                documentId);
        assertThat(row.get("status")).isEqualTo("INDEXED");
        assertThat(row.get("indexed_at")).isNotNull();
        assertThat(row.get("error_message")).isNull();
        assertThat((Integer) row.get("chunk_count")).isGreaterThanOrEqualTo(1);
        assertThat(row.get("chunk_count")).isEqualTo(chunkRepository.countByDocumentId(documentId));
    }

    @Test
    @DisplayName("[document-ingestion:AC-7] chunk_count 는 chunks.size() 가 아니라 실제 색인된 행 수와 일치하고 1 이상이다")
    void recordsChunkCountFromRowCountNotFromChunkListSize() {
        // given: one row already carries this document_id before the run, so the number of chunks
        // handed to the indexer and the number of rows the predicate matches necessarily differ.
        // AC-7 defines the expected value as the row count, so a chunks.size() implementation is
        // wrong here by AC-7's own wording -- and this arrangement is what makes the assertion
        // below able to tell the two apart at all.
        UUID documentId = createPendingDocumentWithFile(
                SampleDocumentFixtures.TEXT_FILENAME,
                SampleDocumentFixtures.TEXT_MIME_TYPE,
                SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME));
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
        chunkSeeder.insertChunk(documentId, "이 문서의 이전 색인 시도가 남긴 청크 1건");

        AtomicInteger chunksHandedToIndexer = new AtomicInteger();
        doAnswer(invocation -> {
            chunksHandedToIndexer.set(((List<?>) invocation.getArgument(0)).size());
            return invocation.callRealMethod();
        }).when(chunkIndexer).index(anyList());

        // when
        pipeline.ingest(documentId);

        // then
        int recordedChunkCount = jdbcTemplate.queryForObject(
                "SELECT chunk_count FROM documents WHERE id = ?", Integer.class, documentId);
        int predicateRowCount = chunkRepository.countByDocumentId(documentId);

        assertThat(chunksHandedToIndexer.get())
                .as("sanity: the run really did index at least one chunk")
                .isGreaterThanOrEqualTo(1);
        assertThat(predicateRowCount)
                .as("rows under the document_id predicate = indexed chunks + the pre-existing row")
                .isEqualTo(chunksHandedToIndexer.get() + 1);
        assertThat(recordedChunkCount)
                .as("chunk_count matches the row count AC-7 defines it by")
                .isEqualTo(predicateRowCount)
                .isGreaterThanOrEqualTo(1);
        assertThat(recordedChunkCount)
                .as("chunk_count is NOT chunks.size() -- this run is arranged so the two differ")
                .isNotEqualTo(chunksHandedToIndexer.get());
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] 파싱 단계에서 실패하면 FAILED + 고정 error_message 로 커밋되고 청크는 0건이다")
    void recordsFailedWithFixedMessageWhenParsingFails() {
        // given: a PENDING row whose original file was never written. Since review T8 L-1 the
        // missing path is rejected by the pipeline's own regular-file check rather than by
        // TikaDocumentReader opening the resource, but the outcome is unchanged and is the
        // parse-stage failure AC-6 describes
        UUID documentId = createPendingDocumentWithoutFile();

        // when
        pipeline.ingest(documentId);

        // then
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, error_message, chunk_count, indexed_at FROM documents WHERE id = ?",
                documentId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("error_message"))
                .isNotBlank()
                .isEqualTo(DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE);
        assertThat(row.get("chunk_count")).isNull();
        assertThat(row.get("indexed_at")).isNull();
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
        verify(chunkIndexer, never()).index(anyList());
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] 색인 단계에서 실패하면 부분 색인 청크를 먼저 지운 뒤 FAILED 를 기록하고 "
            + "예외 원문은 error_message 로 새지 않는다")
    void deletesPartialChunksBeforeRecordingFailed() {
        // given: an indexer that writes two rows for this document and only then blows up --
        // the partial index AC-6 forbids leaving behind
        UUID documentId = createPendingDocumentWithFile(
                SampleDocumentFixtures.TEXT_FILENAME,
                SampleDocumentFixtures.TEXT_MIME_TYPE,
                SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME));
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
        doAnswer(invocation -> {
            chunkSeeder.insertChunk(documentId, "부분 색인된 청크 1");
            chunkSeeder.insertChunk(documentId, "부분 색인된 청크 2");
            Thread.sleep(PARTIAL_INDEX_VISIBLE_MILLIS);
            throw new IllegalStateException(SIMULATED_INDEX_FAILURE_DETAIL);
        }).when(chunkIndexer).index(anyList());

        // when: a watcher samples (status, chunk row count) together in ONE statement from its own
        // connection, so every sample is an internally consistent snapshot of both
        StateWatcher watcher = new StateWatcher(documentId);
        watcher.start();
        pipeline.ingest(documentId);
        watcher.awaitTerminalSampleAndStop();

        // then: the partial rows really were visible to an outside reader at some point ...
        assertThat(watcher.maxObservedChunkRows())
                .as("watcher really did observe the partial index before compensation")
                .isGreaterThanOrEqualTo(2);
        // ... and no sample ever showed FAILED together with surviving chunks
        assertThat(watcher.samplesWithFailedAndSurvivingChunks())
                .as("samples where status=FAILED while chunks still existed (AC-6 forbids)")
                .isEmpty();
        assertThat(watcher.sawFailed())
                .as("watcher observed the terminal FAILED state")
                .isTrue();

        // then: terminal state per AC-6
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, error_message, chunk_count, indexed_at FROM documents WHERE id = ?",
                documentId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("indexed_at")).isNull();
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();

        // then: error_message is the fixed client string, not the exception's own text (INC-007)
        String errorMessage = (String) row.get("error_message");
        assertThat(errorMessage)
                .isNotBlank()
                .isEqualTo(DocumentIngestionPipeline.INDEX_FAILURE_MESSAGE)
                .doesNotContain(SIMULATED_INDEX_FAILURE_DETAIL)
                .doesNotContain("C:\\")
                .doesNotContain("PSQLException")
                .doesNotContain("java.lang")
                .doesNotContain(documentId.toString());
    }

    @Test
    @DisplayName("[document-ingestion §5] 보상 삭제 자체가 실패하면 문서는 FAILED 인데 청크가 잔존하며, "
            + "그 사실은 error_message 접미로만 표시된다 (AC-6 세 번째 요소의 반례)")
    void leavesChunksBehindWhenTheCompensatingDeleteItselfFails() {
        // given: the index step writes two rows and then fails, and the compensating delete that
        // should remove them fails too (lock wait, statement timeout, or the same connection
        // exhaustion that caused the original failure -- review 2026-08-28 T8 L-5)
        UUID documentId = createPendingDocumentWithFile(
                SampleDocumentFixtures.TEXT_FILENAME,
                SampleDocumentFixtures.TEXT_MIME_TYPE,
                SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME));
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
        doAnswer(invocation -> {
            chunkSeeder.insertChunk(documentId, "보상 삭제가 실패해 잔존할 청크 1");
            chunkSeeder.insertChunk(documentId, "보상 삭제가 실패해 잔존할 청크 2");
            throw new IllegalStateException(SIMULATED_INDEX_FAILURE_DETAIL);
        }).when(chunkIndexer).index(anyList());
        AtomicBoolean deleteStillArmed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (deleteStillArmed.getAndSet(false)) {
                throw new IllegalStateException("could not delete chunks: statement timeout");
            }
            return invocation.callRealMethod();
        }).when(chunkRepository).deleteByDocumentId(documentId);

        // when
        pipeline.ingest(documentId);

        // then: FAILED is still reached -- stranding the document in EMBEDDING would be permanent,
        // there being no retry path (Spec §7 OQ-3/OQ-8)
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, error_message, chunk_count, indexed_at FROM documents WHERE id = ?",
                documentId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("indexed_at")).isNull();

        // then: AC-6's third element ("청크 0건") does NOT hold on this branch. This assertion
        // deliberately pins the counterexample rather than the AC: it is tagged §5, not AC-6, so it
        // can never be counted as AC-6 evidence (review 2026-08-28 T8 Medium-4). The rows survive
        // with no live document to explain them, and neither search axis joins {@code documents}
        // -- hybrid-rag opened T13 + AC-15/AC-16 for exactly that consequence. If someone later
        // makes this branch delete the chunks after all, this test is the thing that will notice
        assertThat(chunkRepository.countByDocumentId(documentId))
                .as("chunks surviving a FAILED document whose compensating delete failed")
                .isEqualTo(2);

        // then: the only signal that "0건" was not achieved is the fixed suffix on error_message,
        // which stays free of the exception's own text (INC-007)
        assertThat((String) row.get("error_message"))
                .isNotBlank()
                .isEqualTo(DocumentIngestionPipeline.INDEX_FAILURE_MESSAGE
                        + DocumentIngestionPipeline.CLEANUP_FAILED_SUFFIX)
                .doesNotContain(SIMULATED_INDEX_FAILURE_DETAIL)
                .doesNotContain("statement timeout")
                .doesNotContain("PSQLException")
                .doesNotContain(documentId.toString());
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] 청크가 0건이면 임베딩을 시도하지 않고 고정 error_message 로 FAILED 된다")
    void failsWithEmptyContentMessage() {
        // given: a real file whose text is whitespace only -- TokenTextSplitter#doSplit returns an
        // empty list for blank text (spring-ai-commons:2.0.1 source), so this is the genuine
        // "no indexable text" path, not a stubbed one
        UUID documentId = createPendingDocumentWithFile(
                "blank.txt", SampleDocumentFixtures.TEXT_MIME_TYPE, WHITESPACE_ONLY_CONTENT);

        // when
        pipeline.ingest(documentId);

        // then
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, error_message, chunk_count FROM documents WHERE id = ?", documentId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("error_message"))
                .isNotBlank()
                .isEqualTo(DocumentIngestionPipeline.EMPTY_CONTENT_FAILURE_MESSAGE);
        assertThat(row.get("chunk_count")).isNull();
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
        // the early guard exists so an empty document never pays for an embedding round trip
        verify(chunkIndexer, never()).index(anyList());
    }

    @Test
    @DisplayName("[document-ingestion:AC-6][document-ingestion:AC-7] 색인이 한 행도 쓰지 못하면 INDEXED 가 아니라 "
            + "고정 error_message 로 FAILED 된다")
    void failsWithEmptyIndexMessageWhenIndexingWroteNoRow() {
        // given: an indexer that reports success without writing anything
        UUID documentId = createPendingDocumentWithFile(
                SampleDocumentFixtures.TEXT_FILENAME,
                SampleDocumentFixtures.TEXT_MIME_TYPE,
                SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME));
        doAnswer(invocation -> null).when(chunkIndexer).index(anyList());

        // when
        pipeline.ingest(documentId);

        // then: AC-7 forbids an INDEXED document with zero chunks, so this must land on FAILED
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, error_message, chunk_count, indexed_at FROM documents WHERE id = ?",
                documentId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("chunk_count")).isNull();
        assertThat(row.get("indexed_at")).isNull();
        assertThat((String) row.get("error_message"))
                .isNotBlank()
                .isEqualTo(DocumentIngestionPipeline.EMPTY_INDEX_FAILURE_MESSAGE);
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
    }

    /**
     * [Purpose] Creates a committed PENDING {@code documents} row and writes its original file at
     *   {@code upload-dir/{id}} -- the layout {@link DocumentIngestionPipeline} resolves (DD-2).
     */
    private UUID createPendingDocumentWithFile(String filename, String mimeType, byte[] content) {
        UUID documentId = createPendingRow(filename, mimeType, (long) content.length);
        try {
            Files.write(uploadDir.resolve(documentId.toString()), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage test upload " + documentId, e);
        }
        return documentId;
    }

    /**
     * [Purpose] Creates a committed PENDING row whose original file is deliberately absent, so the
     *   Tika read fails -- the parse-stage failure of AC-6.
     */
    private UUID createPendingDocumentWithoutFile() {
        return createPendingRow("missing-original.txt", SampleDocumentFixtures.TEXT_MIME_TYPE, 11L);
    }

    private UUID createPendingRow(String filename, String mimeType, long sizeBytes) {
        Document document = documentRepository.saveAndFlush(new Document(filename, mimeType, sizeBytes));
        trackedDocumentIds.add(document.getId());
        return document.getId();
    }

    /**
     * [Purpose] Reads {@code documents.status} the way an outside observer would -- another
     *   thread, its own raw JDBC connection, no Spring transaction in scope.
     * [Caution] Called only at points where the pipeline holds no pooled connection, so this never
     *   pushes concurrent borrowers past the class [Caution]'s budget.
     */
    private String statusOf(UUID documentId) {
        return onSeparateConnection(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT status FROM documents WHERE id = ?")) {
                statement.setObject(1, documentId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : null;
                }
            }
        });
    }

    /**
     * [Purpose] Runs {@code callback} on a fresh thread holding its own {@link DataSource}
     *   connection and returns its result, so a read cannot accidentally join whatever
     *   transaction/connection the calling code is using.
     */
    private <T> T onSeparateConnection(ConnectionCallback<T> callback) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread observer = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                result.set(callback.run(connection));
            } catch (Exception e) {
                failure.set(e);
            }
        }, "ledger-observer");
        observer.start();
        try {
            observer.join(OBSERVER_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Observer thread interrupted", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Observer connection failed", failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ConnectionCallback<T> {
        T run(Connection connection) throws SQLException;
    }

    /** [Purpose] One consistent (status, chunk row count) sample taken in a single statement. */
    private record Sample(String status, long chunkRows) {
    }

    /**
     * [Purpose] Background sampler for {@link #deletesPartialChunksBeforeRecordingFailed()}: keeps
     *   reading {@code documents.status} and this document's chunk row count <i>in one statement</i>
     *   from its own connection, so each sample is a consistent snapshot of both.
     * [Rationale] AC-6's ordering requirement (an observer that sees FAILED must already see zero
     *   chunks) is about what an outside reader can ever witness, so the check has to be made by
     *   an outside reader rather than by a mock's interaction order (which
     *   {@link DocumentIngestionPipelineStateMachineTest} already covers at unit level).
     * [Caution] Sampling proves the two things it directly witnesses -- that orphaned chunks really
     *   existed, and that the terminal FAILED state was seen with zero chunks -- and that it did
     *   not catch the forbidden combination in between; it cannot by itself exclude a violation
     *   shorter than one poll interval. Do not restate it in the ledger as an exhaustive proof.
     */
    private final class StateWatcher {

        private static final String SAMPLE_SQL =
                "SELECT d.status, (SELECT COUNT(*) FROM vector_store_bgem3 v "
                        + "WHERE v.metadata->>'document_id' = ?) AS chunk_rows "
                        + "FROM documents d WHERE d.id = ?";

        private final UUID documentId;
        private final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Exception> failure = new AtomicReference<>();
        private final Thread thread;
        private volatile boolean running = true;

        private StateWatcher(UUID documentId) {
            this.documentId = documentId;
            this.thread = new Thread(this::pollUntilStopped, "ledger-watcher");
        }

        private void start() {
            thread.start();
        }

        private void pollUntilStopped() {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(SAMPLE_SQL)) {
                while (running) {
                    statement.setString(1, documentId.toString());
                    statement.setObject(2, documentId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            samples.add(new Sample(resultSet.getString("status"),
                                    resultSet.getLong("chunk_rows")));
                        }
                    }
                    Thread.sleep(WATCHER_POLL_INTERVAL_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                failure.set(e);
            }
        }

        private void awaitTerminalSampleAndStop() {
            long deadline = System.currentTimeMillis() + WATCHER_TERMINAL_WAIT_MILLIS;
            while (System.currentTimeMillis() < deadline && !sawFailed() && failure.get() == null) {
                sleepQuietly(WATCHER_POLL_INTERVAL_MILLIS);
            }
            running = false;
            try {
                thread.join(OBSERVER_JOIN_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failure.get() != null) {
                throw new IllegalStateException("Watcher connection failed", failure.get());
            }
        }

        private boolean sawFailed() {
            return snapshot().stream().anyMatch(sample -> "FAILED".equals(sample.status()));
        }

        private long maxObservedChunkRows() {
            return snapshot().stream().mapToLong(Sample::chunkRows).max().orElse(0L);
        }

        private List<Sample> samplesWithFailedAndSurvivingChunks() {
            return snapshot().stream()
                    .filter(sample -> "FAILED".equals(sample.status()) && sample.chunkRows() > 0)
                    .toList();
        }

        private List<Sample> snapshot() {
            synchronized (samples) {
                return List.copyOf(samples);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
