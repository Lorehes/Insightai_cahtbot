package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import art.insightai.chatbot.support.AnonymousUserCleanup;
import art.insightai.chatbot.support.IntegrationTestBase;

/**
 * [Purpose] Owns every observation about the upload/ingestion handoff boundary that is only a fact
 *   while the {@code ingestExecutor} pool is deliberately held still. Two AC live here:
 *   <ul>
 *     <li>document-ingestion:AC-1 — at the moment {@code POST /api/admin/documents} returns 202,
 *     neither the real {@link DocumentParser} nor the real {@link ChunkIndexer} bean has been
 *     invoked, and the committed {@code documents} row carries no completion state
 *     ({@code status='PENDING'}, {@code indexed_at}/{@code chunk_count} null, zero chunks in
 *     {@code vector_store_bgem3}). Parsing then really does happen, afterwards, on an
 *     {@code ingest-} thread rather than the request thread.</li>
 *     <li>document-ingestion:AC-16 — when the pool refuses the ingestion task because its bounded
 *     queue is full, the document does not stay PENDING: it reaches terminal FAILED with a
 *     non-blank fixed {@code error_message} and zero chunks.</li>
 *   </ul>
 * [Rationale] Until T8 there was no listener at all, so the ledger's AC-1 cell could only offer a
 *   structural substitute ({@code DocumentUploadService} declares no parsing collaborator to mock)
 *   and recorded itself as 부분 증명 with no regression discriminating power -- an implementation
 *   that parsed inline and threw the result away would have passed. That substitute is replaced
 *   here: {@link DocumentParser} is a genuine bean in the request's reachable object graph now, so
 *   {@code verify(never())} is a real interaction check against a real collaborator.
 *   <p>
 *   <b>The response-time checks are made deterministic, not raced.</b> Both {@code ingestExecutor}
 *   worker threads (AD-11 fixes the pool at core=max=2) are occupied by barrier tasks before the
 *   upload, so the listener's task can only sit in the queue while the response is produced. Any
 *   implementation that parsed on the request thread -- inline in {@code upload()}, or via an
 *   {@code AFTER_COMMIT} listener that ran {@link DocumentIngestionPipeline} directly instead of
 *   submitting it to {@code ingestExecutor} -- would invoke the spy before the barrier is released
 *   and fail here. It does <b>not</b> catch AD-11's forbidden {@code CallerRunsPolicy} (review
 *   2026-08-28 T8 L-7: an earlier version of this Javadoc and of the Spec §4 AC-1 cell claimed it
 *   did, which was an overstatement -- the barrier occupies the two workers but leaves all 50 queue
 *   slots free, so no rejection occurs and no rejection policy is exercised). The regression guard
 *   for the policy is {@code IngestAsyncConfigTest}'s explicit {@code AbortPolicy} assertion; the
 *   guard for the policy's <i>consequence</i> is this class's AC-16 case, which does fill the queue.
 *   <p>
 *   These four DB assertions moved here from {@code DocumentUploadControllerIntegrationTest}
 *   (review 2026-08-28 T8 Medium-3, option (a)). That test asserted the same response-time state
 *   without holding the pool, which was a fact about the codebase only while no listener existed;
 *   after T8 it became a statement that the test thread happened to outrun a worker. Barrier
 *   ownership is not duplicated -- it lives in this class alone.
 *   <p>
 *   Polling {@code documents.status} with {@link JdbcTemplate} is used for the asynchronous half
 *   rather than adding a waiting library, per this card's no-new-dependency constraint.
 * [Caution] This test really does index into the shared {@code chatbot-db} container, so it waits
 *   for the document to reach a terminal state before {@code @AfterEach} deletes the row, its
 *   chunks and its upload file -- releasing the barrier without that wait would let a worker touch
 *   a {@code @TempDir} file JUnit had already removed, and would leave that worker's chunks behind
 *   in the shared {@code vector_store_bgem3} with no {@code documents} row to explain them (both
 *   search axes read that table without joining {@code documents}, so orphans are retrievable).
 *   Do not add a second concurrent DB observer here: {@link IngestAsyncConfig}'s [Caution] budget
 *   allows the one ingest worker plus this test thread, and no more.
 *   <p>
 *   <b>Connection budget on the rejection branch.</b> {@link DocumentUploadedEventListener}'s
 *   [Caution] records that the request thread briefly holds <b>two</b> pooled connections there --
 *   the committed-but-not-yet-cleaned-up upload transaction plus {@code markFailed}'s
 *   {@code REQUIRES_NEW} -- against {@code maximum-pool-size: 3}. That is why the tasks used to
 *   occupy the workers and to fill the queue must not touch the database: they are latch waits and
 *   no-ops. Give either of them a JDBC statement and the AC-16 case fails deterministically after
 *   {@code connection-timeout: 5000}, which would look like flakiness and is not.
 *   <p>
 *   <b>AC-16 is a three-element sentence and this class carries all three, but not symmetrically.</b>
 *   Terminal FAILED and the non-blank fixed {@code error_message} are asserted as behaviour of the
 *   listener. "청크 0건" is asserted as an observed fact, and it holds for a structural reason the
 *   listener's own [Caution] spells out -- rejection happens before the pipeline runs at all and
 *   the upload path never writes to {@code vector_store_bgem3}, so no chunk for that id can exist
 *   yet. Whoever adds a re-index or retry path (Spec §7 OQ-3/OQ-8) makes it possible for a rejected
 *   submission to concern a document that already has chunks, and this assertion will then be
 *   passing for a reason that no longer applies to production; it must be re-derived, not trusted.
 * [AC] document-ingestion:AC-1, document-ingestion:AC-16
 */
@AutoConfigureMockMvc
class DocumentUploadAsyncHandoffIntegrationTest extends IntegrationTestBase {

    private static final byte[] ALLOWED_CONTENT =
            "인사이트에이아이 사내 문서 샘플. 비동기 인제스천 핸드오프 검증용 본문입니다."
                    .getBytes(StandardCharsets.UTF_8);

    private static final long BARRIER_HOLD_TIMEOUT_SECONDS = 30L;

    private static final long PARSE_WAIT_TIMEOUT_SECONDS = 30L;

    private static final long TERMINAL_WAIT_MILLIS = 60_000L;

    private static final long DRAIN_WAIT_MILLIS = 30_000L;

    private static final long POLL_INTERVAL_MILLIS = 50L;

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void overrideUploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.ingest.upload-dir", () -> uploadDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    @Qualifier(IngestAsyncConfig.INGEST_EXECUTOR_BEAN_NAME)
    private ThreadPoolTaskExecutor ingestExecutor;

    @MockitoSpyBean
    private DocumentParser documentParser;

    @MockitoSpyBean
    private ChunkIndexer chunkIndexer;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    /**
     * [Purpose] Deletes the anonymous {@code users} rows this class's own uploads create, so a
     *   local dev database does not accumulate them run after run (BL-039).
     * [Rationale] {@code SessionFilterConfig} registers the anonymous-session filter on
     *   {@code /api/*}, so every cookie-less {@code MockMvc} upload here mints a new user row,
     *   while {@link #cleanUpInsertedRows()} only removes documents, chunks and the stored file.
     *   Measured before this was added: 716 -&gt; 718 for this class's two tests, matching its two
     *   uploads exactly. BL-034 fixed the same leak in six {@code rag/api} classes but scoped
     *   itself by domain package, which is why this class -- the only other direct subclass that
     *   actually leaks -- was missed; the leak follows &quot;does it go over HTTP&quot;, not the
     *   package it lives in.
     * [Caution] A fresh instance per test (not a field initializer) because
     *   {@link AnonymousUserCleanup} accumulates tracked ids and is not reusable across tests.
     */
    private AnonymousUserCleanup anonymousUserCleanup;

    @BeforeEach
    void createAnonymousUserCleanup() {
        anonymousUserCleanup = new AnonymousUserCleanup(jdbcTemplate);
    }

    /**
     * [Purpose] Removes the tracked anonymous users in its own hook.
     * [Rationale] Deliberately separate from {@link #cleanUpInsertedRows()} rather than appended
     *   to it: if the document/chunk/file cleanup throws, a shared hook would skip the user
     *   cleanup and leak anyway. {@code DocumentUploadContainerSizeLimitIntegrationTest} already
     *   uses this split, and BL-034's round-2 review recommended the remaining classes match it.
     *   The two hooks are order-independent -- {@code documents} does not reference {@code users}.
     */
    @AfterEach
    void cleanUpAnonymousUsers() {
        anonymousUserCleanup.cleanUp();
    }

    @AfterEach
    void cleanUpInsertedRows() {
        for (UUID documentId : insertedDocumentIds) {
            chunkRepository.deleteByDocumentId(documentId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            try {
                Files.deleteIfExists(uploadDir.resolve(documentId.toString()));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete test upload " + documentId, e);
            }
        }
        insertedDocumentIds.clear();
    }

    @Test
    @DisplayName("[document-ingestion:AC-1] 업로드 202 응답 시점에 파싱·임베딩 협력자는 한 번도 호출되지 않고 "
            + "행에 색인 흔적도 없으며, 파싱은 그 뒤 ingestExecutor 스레드에서 일어난다")
    void doesNotInvokeParsingCollaboratorsBeforeTheUploadResponse() throws Exception {
        // given: both ingest worker threads are held, so a queued ingestion task cannot start
        // while the response is being produced -- this is what makes the never() check below a
        // fact rather than a race
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch workersOccupied = new CountDownLatch(ingestExecutor.getCorePoolSize());
        UUID documentId = null;
        AtomicReference<String> parsingThreadName = new AtomicReference<>();
        CountDownLatch parsed = new CountDownLatch(1);
        String requestThreadName = Thread.currentThread().getName();

        // the try starts at the submission, not after the occupancy assertion: a failure there
        // used to skip the countDown and pin both workers for BARRIER_HOLD_TIMEOUT_SECONDS,
        // spilling the symptom into whatever ran next (review 2026-08-28 T8 L-9)
        occupyIngestWorkers(releaseWorkers, workersOccupied);
        try {
            assertThat(workersOccupied.await(BARRIER_HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("both ingestExecutor workers are occupied before the upload")
                    .isTrue();

            // given: record which thread eventually parses
            doAnswer(invocation -> {
                parsingThreadName.set(Thread.currentThread().getName());
                try {
                    return invocation.callRealMethod();
                } finally {
                    parsed.countDown();
                }
            }).when(documentParser).parse(any());

            MockMultipartFile file =
                    new MockMultipartFile("file", "handoff.txt", "text/plain", ALLOWED_CONTENT);

            // when
            MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                    .andExpect(status().isAccepted())
                    .andReturn();
            anonymousUserCleanup.track(result);

            // then: the 202 body is the PENDING handoff acknowledgement
            String responseBody = result.getResponse().getContentAsString();
            assertThat((String) JsonPath.read(responseBody, "$.status")).isEqualTo("PENDING");
            assertThat((String) JsonPath.read(responseBody, "$.filename")).isEqualTo("handoff.txt");
            documentId = UUID.fromString(JsonPath.read(responseBody, "$.id"));
            insertedDocumentIds.add(documentId);

            // then: AC-1's own wording -- no parsing, no embedding, at response time
            verify(documentParser, never()).parse(any());
            verify(chunkIndexer, never()).index(anyList());

            // then: and nothing has been written on the document's behalf either (moved here from
            // DocumentUploadControllerIntegrationTest, review 2026-08-28 T8 Medium-3)
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, indexed_at, chunk_count FROM documents WHERE id = ?", documentId);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("indexed_at")).isNull();
            assertThat(row.get("chunk_count")).isNull();
            assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
        } finally {
            releaseWorkers.countDown();
        }

        // then: parsing does happen once the workers are free, and never on the request thread
        assertThat(parsed.await(PARSE_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the queued ingestion really did run after the response")
                .isTrue();
        assertThat(parsingThreadName.get())
                .as("parsing thread")
                .isNotEqualTo(requestThreadName)
                .startsWith(IngestAsyncConfig.THREAD_NAME_PREFIX);

        // then: let the ingestion finish before @AfterEach removes the file it is reading
        assertThat(awaitTerminalStatus(documentId))
                .as("terminal status of the asynchronously ingested document")
                .isEqualTo("INDEXED");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, chunk_count, indexed_at FROM documents WHERE id = ?", documentId);
        assertThat(row.get("indexed_at")).isNotNull();
        assertThat(row.get("chunk_count")).isEqualTo(chunkRepository.countByDocumentId(documentId));
    }

    @Test
    @DisplayName("[document-ingestion:AC-16] ingestExecutor 큐가 가득 차 제출이 거부되면 문서는 PENDING 에 남지 않고 "
            + "고정 error_message 와 함께 FAILED 로 종결되며 청크가 0건이다")
    void marksTheDocumentFailedWithoutChunksWhenTheIngestionQueueIsFull() throws Exception {
        // given: the two workers are held by connection-free barrier tasks and all 50 queue slots
        // are filled with connection-free no-ops, so the listener's submission is the 53rd task and
        // AbortPolicy has no choice but to refuse it
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch workersOccupied = new CountDownLatch(ingestExecutor.getCorePoolSize());
        UUID documentId = null;

        occupyIngestWorkers(releaseWorkers, workersOccupied);
        try {
            assertThat(workersOccupied.await(BARRIER_HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("both ingestExecutor workers are occupied before the queue is filled")
                    .isTrue();
            fillIngestQueue();

            // given: the saturation this test claims to create is asserted, not assumed -- a test
            // that quietly failed to fill the queue would take the happy path and still be green
            // on the FAILED assertions only if the production code were wrong in the opposite way
            assertThat(ingestExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .as("free ingestExecutor queue slots at the moment of upload")
                    .isZero();

            MockMultipartFile file =
                    new MockMultipartFile("file", "rejected.txt", "text/plain", ALLOWED_CONTENT);

            // when: the uploader is unaffected -- the refusal happens after the upload transaction
            // has committed, so the client still gets its 202
            MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                    .andExpect(status().isAccepted())
                    .andReturn();
            anonymousUserCleanup.track(result);
            String responseBody = result.getResponse().getContentAsString();
            assertThat((String) JsonPath.read(responseBody, "$.status")).isEqualTo("PENDING");
            documentId = UUID.fromString(JsonPath.read(responseBody, "$.id"));
            insertedDocumentIds.add(documentId);

            // then: no polling -- the AFTER_COMMIT callback runs on the request thread, so the
            // FAILED row is already committed by the time perform() returns. A PENDING read here
            // is the exact defect this AC was created for (review 2026-08-28 T8 Medium-1), not a
            // "not yet" to be waited out
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, error_message, indexed_at, chunk_count FROM documents WHERE id = ?",
                    documentId);
            assertThat(row.get("status"))
                    .as("a refused submission must not leave the document PENDING")
                    .isEqualTo("FAILED");
            assertThat(row.get("indexed_at")).isNull();

            // then: error_message is non-blank and is the fixed constant, never the exception's own
            // text. This column is served verbatim by the unauthenticated GET /api/admin/documents
            // (AC-11, INC-007), and the rejection actually raised in this very run reads
            //   "ExecutorService in active state did not accept task:
            //    art.insightai.chatbot.rag.ingest.DocumentUploadedEventListener$$Lambda/0x..."
            // -- an internal class name and lambda address. The doesNotContain fragments below are
            // taken from that observed message rather than invented, so they fail on a real leak
            assertThat((String) row.get("error_message"))
                    .isNotBlank()
                    .isEqualTo(DocumentUploadedEventListener.SUBMISSION_REJECTED_MESSAGE)
                    .doesNotContain("ExecutorService")
                    .doesNotContain("did not accept task")
                    .doesNotContain("art.insightai")
                    .doesNotContain(IngestAsyncConfig.THREAD_NAME_PREFIX)
                    .doesNotContain(documentId.toString());

            // then: zero chunks, and the reason is visible -- the pipeline never started at all
            assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
            verify(documentParser, never()).parse(any());
            verify(chunkIndexer, never()).index(anyList());
        } finally {
            releaseWorkers.countDown();
        }

        // then: the queue drains into no-ops, so nothing this test submitted can still be running
        // when @AfterEach removes the row and the upload file
        assertThat(awaitIngestExecutorIdle())
                .as("ingestExecutor queue drained after the barrier was released")
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId))
                .as("FAILED is terminal -- draining the queue must not resurrect the document")
                .isEqualTo("FAILED");
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
    }

    /**
     * [Purpose] Fills every {@code ingestExecutor} worker thread with a task that blocks until
     *   {@code release} is counted down, so the next submission can only be queued.
     * [Caution] These tasks must never touch the database -- see the class [Caution] on the
     *   rejection branch's two-connection window.
     */
    private void occupyIngestWorkers(CountDownLatch release, CountDownLatch occupied) {
        for (int i = 0; i < ingestExecutor.getCorePoolSize(); i++) {
            ingestExecutor.execute(() -> {
                occupied.countDown();
                try {
                    release.await(BARRIER_HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /**
     * [Purpose] Occupies every queue slot with a no-op, so the following submission is rejected.
     * [Caution] Submits exactly {@link IngestAsyncConfig#QUEUE_CAPACITY} tasks. One more would be
     *   rejected here, on the test thread, instead of at the listener where the AC lives.
     */
    private void fillIngestQueue() {
        for (int i = 0; i < IngestAsyncConfig.QUEUE_CAPACITY; i++) {
            ingestExecutor.execute(() -> { });
        }
    }

    /**
     * [Purpose] Polls {@code documents.status} until it reaches a terminal value, returning it.
     * [Rationale] The card forbids introducing a new waiting dependency, so this is the
     *   {@link JdbcTemplate} polling the pipeline's implementer recommended instead.
     */
    private String awaitTerminalStatus(UUID documentId) {
        long deadline = System.currentTimeMillis() + TERMINAL_WAIT_MILLIS;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbcTemplate.queryForObject(
                    "SELECT status FROM documents WHERE id = ?", String.class, documentId);
            if ("INDEXED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            if (!sleepBriefly()) {
                break;
            }
        }
        return status;
    }

    /**
     * [Purpose] Waits until the executor has no queued and no running tasks left.
     * [Rationale] The queue-saturation case leaves 52 tasks behind; letting them outlive the test
     *   method would carry barrier threads into whatever runs next, which is the spill this class
     *   otherwise takes care to prevent.
     */
    private boolean awaitIngestExecutorIdle() {
        long deadline = System.currentTimeMillis() + DRAIN_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (ingestExecutor.getThreadPoolExecutor().getQueue().isEmpty()
                    && ingestExecutor.getActiveCount() == 0) {
                return true;
            }
            if (!sleepBriefly()) {
                break;
            }
        }
        return false;
    }

    /**
     * [Purpose] Sleeps one poll interval, reporting {@code false} if the wait was interrupted so
     *   callers stop looping instead of spinning on an already-set interrupt flag.
     */
    private boolean sleepBriefly() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
