package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * [Purpose] Forces {@link DocumentDeleteService#delete} and {@link DocumentIngestionPipeline#ingest}
 *   to interleave in each of the two orders {@link DocumentIngestLockRegistry} exists to
 *   serialize, and shows both orders converge on the same outcome: zero {@code documents} rows
 *   and zero orphaned {@code vector_store_bgem3} chunks for the id under test
 *   (document-ingestion:AC-17, review 2026-08-30 T10 High-1, user-approved fix 1). A third test,
 *   {@link #deleteCallsUnlockOnlyAfterItsTransactionHasAlreadyCommitted()}, checks a narrower and
 *   different claim that the two interleaving scenarios do not by themselves establish: that
 *   {@link DocumentDeleteService#delete}'s own {@code unlock()} call happens strictly after its
 *   delete transaction has committed, not merely that the lock exists (2R High-1 rework -- see that
 *   method's own [Caution] "ordering claim").
 * [Rationale] {@link DocumentIngestionPipelineDbIntegrationTest} already establishes the technique
 *   this class reuses -- drive the real, synchronous production method and observe committed state
 *   from outside via a live ParadeDB container -- but that class never runs two collaborators on two
 *   threads at once. This class adds exactly that: each scenario below pauses one side mid
 *   critical-section with a {@link CountDownLatch}-gated {@code doAnswer} on a
 *   {@link MockitoSpyBean}, so the interleaving is <b>forced</b>, not merely likely under a
 *   {@code Thread.sleep}. The other side's blocking is itself asserted by polling
 *   {@link Thread#getState()} for {@link Thread.State#WAITING} -- the state an uncontended
 *   {@link java.util.concurrent.locks.ReentrantLock#lock()} park produces -- rather than assumed
 *   from a fixed delay; a thread that terminates instead of ever reaching {@code WAITING} is
 *   treated as a failed interleaving, not a slow one (see {@link #awaitThreadState}).
 *   <p>
 *   <b>Round 3 re-run (review coordinator instruction, 2026-08-30, R4a-1..4) -- both probes redone
 *   from scratch.</b> {@link DocumentIngestLockRegistry} was rewritten in round 3 (never-evicted
 *   {@code ConcurrentHashMap} &rarr; fixed 64-slot stripe array, High-1) and {@link
 *   DocumentDeleteService#delete} switched its own acquisition from the unbounded {@link
 *   DocumentIngestLockRegistry#lock} to the bounded {@link DocumentIngestLockRegistry#tryLock}
 *   (Medium-1), so the round-2 probe log below (including its recorded SHA-256 values) describes a
 *   file state that no longer exists -- re-running rather than trusting the old log was the point.
 *   Before either probe, running this suite once against the round-3 code with <em>no</em> mutation
 *   surfaced a real harness gap (not a production defect): this class's own instrumentation --
 *   {@link #awaitThreadState} accepting only {@link Thread.State#WAITING}, and the pause hook in
 *   {@link #deleteAcquiresLockFirstAndPipelineSelfCleansAfterLosing()} stubbing only {@link
 *   DocumentIngestLockRegistry#lock} -- was still written for the round-1/round-2 {@code lock()}-based
 *   {@link DocumentDeleteService#delete}. Fixed (qa-tester, round 3 rework, both fixes below apply
 *   regardless of which acquisition method {@code delete()} happens to call, so they also work
 *   unmodified across mutation probe 2 below, which temporarily reverts {@code delete()} to the
 *   {@code lock()}-based shape again): {@code awaitThreadState} now also accepts {@link
 *   Thread.State#TIMED_WAITING} (the state {@code tryLock(timeout, unit)}'s internal {@code
 *   LockSupport.parkNanos} produces, as opposed to {@code lock()}'s unbounded {@code
 *   LockSupport.park}); the pause hook in {@code deleteAcquiresLockFirstAndPipelineSelfCleansAfterLosing}
 *   is now armed on both {@code lock(documentId)} and {@code tryLock(eq(documentId), anyLong(),
 *   any())}, guarded by the same {@code deleteHookArmed} flag so only the first of the two that
 *   actually fires pauses delete. With both fixes in place, this class passes 3/3 against the
 *   round-3 code unmutated -- the baseline mutation probes 1 and 2 below start from.
 *   <p>
 *   <b>Mutation probe 1 -- lock/unlock no-op.</b> {@link DocumentIngestLockRegistry}'s pre-mutation
 *   SHA-256: {@code 18dbec93d2cec7d4076e0531be4a15016431ab1ac4d3254cfc935701ed189110}. {@link
 *   DocumentIngestLockRegistry#lock} and {@link DocumentIngestLockRegistry#unlock} were temporarily
 *   replaced with no-op bodies and the whole class was re-run: {@link
 *   #pipelineAcquiresLockFirstAndDeleteWaitsThenCompletesCleanly()} and {@link
 *   #deleteAcquiresLockFirstAndPipelineSelfCleansAfterLosing()} both failed (2 of 3) --
 *   the former at {@code awaitThreadState} timing out with no WAITING/TIMED_WAITING ever observed,
 *   the latter at {@code awaitLatch} timing out waiting for {@code deleteHoldsLock} -- while {@link
 *   #deleteCallsUnlockOnlyAfterItsTransactionHasAlreadyCommitted()} <b>passed</b>, exactly as round
 *   2 found: that test does not depend on the lock actually blocking anything, only on {@code
 *   unlock()} being called after commit, which a no-op lock body does not disturb. The file was
 *   then restored and its SHA-256 re-verified equal to the value above.
 *   <p>
 *   <b>Mutation probe 2 -- revert {@code delete()} to round 1's {@code @Transactional} shape.</b>
 *   {@link DocumentDeleteService}'s pre-mutation SHA-256:
 *   {@code 6fb04f5ddf248a79d0bf27a5426206d0fca8cdfd5736fbee46255ebeee9b9b29}. {@code delete()} was
 *   temporarily reverted to a {@code @Transactional}-annotated method with the unbounded {@code
 *   ingestLockRegistry.lock()} at the top and {@code finally { ingestLockRegistry.unlock() }}
 *   inside the method body -- exactly the bug 2R High-1 found -- and the whole class was re-run
 *   again. Exactly {@link #deleteCallsUnlockOnlyAfterItsTransactionHasAlreadyCommitted()} failed (1
 *   of 3), at the "documents row ... was already absent at the exact moment unlock() ran" assertion
 *   -- the row was still present through the independent connection because the {@code
 *   @Transactional} proxy had not committed yet when the reverted method's own {@code finally}
 *   called {@code unlock()}. The two interleaving scenarios both still passed under this same
 *   mutation (proof that this round's dual lock()/tryLock() pause hook and the WAITING/TIMED_WAITING
 *   broadening above did not accidentally make those two tests insensitive to this mutation either),
 *   which is exactly why the third test is needed: the interleaving scenarios alone cannot tell
 *   round 1's shape apart from round 3's fix, only {@link
 *   #deleteCallsUnlockOnlyAfterItsTransactionHasAlreadyCommitted()} can. The file was then restored
 *   and its SHA-256 re-verified equal to the value above.
 *   See {@code dev-log/backend.jsonl} document-ingestion-T10 (round 3, final rework) entry for the
 *   exact commands.
 * [Caution] <b>Connection budget.</b> Like {@link DocumentIngestionPipelineDbIntegrationTest}'s own
 *   [Caution], no method here is {@code @Transactional} and at most two threads (the test thread
 *   plus one helper) ever hold a borrowed connection at the same instant within a scenario -- the
 *   paused side holds none while parked on the {@link CountDownLatch}/lock, only the running side
 *   does. Do not add a third concurrently-running helper without re-checking this budget against
 *   {@code IngestAsyncConfig}'s documented arithmetic.
 *   <p>
 *   Every latch await below is <b>bounded</b> ({@link #AWAIT_TIMEOUT_SECONDS}), including inside
 *   the {@code doAnswer} hooks themselves -- an unbounded await there would hang the paused
 *   background thread forever if a later assertion in the test body fails first (JUnit does not
 *   kill leaked non-daemon threads), which would strand a borrowed connection for the rest of the
 *   suite. Both scenarios also join their threads and assert liveness in a {@code finally} block for
 *   the same reason.
 *   <p>
 *   {@code app.ingest.upload-dir} is redirected to a JUnit {@link TempDir}, mirroring
 *   {@code DocumentIngestionPipelineDbIntegrationTest}'s and {@code
 *   DocumentDeleteControllerIntegrationTest}'s own [Caution] (review 2026-08-28 High-2, INC-010) --
 *   this class must never touch a developer's real {@code infra/.env} {@code UPLOAD_DIR}.
 */
class DocumentIngestConcurrencyIntegrationTest extends IntegrationTestBase {

    private static final long AWAIT_TIMEOUT_SECONDS = 10L;

    private static final long POLL_INTERVAL_MILLIS = 10L;

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void overrideUploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.ingest.upload-dir", () -> uploadDir.toString());
    }

    @Autowired
    private DocumentDeleteService documentDeleteService;

    @Autowired
    private DocumentIngestionPipeline pipeline;

    @Autowired
    private DocumentRepository documentRepository;

    @MockitoSpyBean
    private ChunkRepository chunkRepository;

    @MockitoSpyBean
    private ChunkIndexer chunkIndexer;

    @MockitoSpyBean
    private DocumentIngestLockRegistry ingestLockRegistry;

    @MockitoSpyBean
    private DocumentStatusTransitioner statusTransitioner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<UUID> trackedDocumentIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanUpAnySurvivingState() {
        for (UUID documentId : trackedDocumentIds) {
            chunkRepository.deleteByDocumentId(documentId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            try {
                Files.deleteIfExists(uploadDir.resolve(documentId.toString()));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clean up test upload " + documentId, e);
            }
        }
        trackedDocumentIds.clear();
    }

    @Test
    @DisplayName("[document-ingestion:AC-17] 삭제가 락을 먼저 잡으면 파이프라인은 락 획득 후 문서 소멸을 감지해 "
            + "스스로 청크를 정리하고, 최종 상태에서 documents 행도 vector_store_bgem3 청크도 남지 않는다")
    void deleteAcquiresLockFirstAndPipelineSelfCleansAfterLosing() throws InterruptedException {
        // given: a real PENDING row with a real fixture file, so the pipeline has genuine text to
        // parse/chunk/index before it ever reaches the lock
        UUID documentId = createPendingDocumentWithFile();

        // given: delete's own lock acquisition is instrumented to pause immediately AFTER really
        // acquiring the mutex, but BEFORE delete's very next step (deleting the original file). This
        // is deliberately earlier than pausing on the chunk-delete call would be: the file must
        // still be on disk when the pipeline thread below reaches DocumentParser, or the pipeline
        // would fail at the unrelated "file missing" path instead of ever reaching its own lock
        // acquisition -- which would make this test pass or fail for the wrong reason regardless of
        // whether AC-17's lock works at all. The real acquisition happens first
        // ({@code invocation.callRealMethod()}), so delete genuinely holds the mutex while paused.
        // (review 2026-08-30 T10 round 3, qa-tester rework) DocumentDeleteService#delete now
        // acquires its stripe via tryLock(timeout, unit) rather than the unbounded lock() round
        // 1/2 used (round 3 High-1/Medium-1) -- this hook is armed on BOTH methods, guarded by the
        // same deleteHookArmed flag, so it pauses delete immediately after its real acquisition
        // succeeds regardless of which of the two acquisition methods the production code under
        // test currently calls. This also keeps mutation probe 2 below (which temporarily reverts
        // delete() to round 1's lock()-based shape) exercising the same pause point without
        // needing a second, probe-specific stub.
        CountDownLatch deleteHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        AtomicBoolean deleteHookArmed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (deleteHookArmed.getAndSet(false)) {
                deleteHoldsLock.countDown();
                releaseDelete.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        }).when(ingestLockRegistry).lock(documentId);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (deleteHookArmed.getAndSet(false)) {
                deleteHoldsLock.countDown();
                releaseDelete.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        }).when(ingestLockRegistry).tryLock(eq(documentId), anyLong(), any(TimeUnit.class));

        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> runCatching(
                () -> documentDeleteService.delete(documentId), deleteFailure), "ac17-delete-first");
        AtomicReference<Throwable> pipelineFailure = new AtomicReference<>();
        Thread pipelineThread = new Thread(() -> runCatching(
                () -> pipeline.ingest(documentId), pipelineFailure), "ac17-pipeline-loses");

        try {
            // when: delete starts, acquires the (uncontended) lock, and pauses immediately -- before
            // it has deleted the file, the chunks, or the row
            deleteThread.start();
            awaitLatch(deleteHoldsLock, "delete acquired the lock and paused mid critical section");

            // when: the pipeline starts concurrently. It can freely parse/chunk/transition to
            // EMBEDDING (the documents row still exists -- delete has not removed it yet), but its
            // own lock acquisition, immediately before indexing, must block on the SAME lock delete
            // is holding. This block is the property AC-17 exists to guarantee, and the mutation
            // probe in this class's Javadoc confirms it is this lock -- not incidental scheduling --
            // that produces it.
            pipelineThread.start();
            awaitThreadState(pipelineThread,
                    "pipeline blocked waiting for delete's lock before it could index a single chunk");

            // when: delete is allowed to finish -- it deletes the file, the (still empty) chunk
            // set, and the documents row, then releases the lock
            releaseDelete.countDown();
        } finally {
            deleteThread.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
            pipelineThread.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
        }

        assertThat(deleteThread.isAlive()).as("delete thread terminated").isFalse();
        assertThat(pipelineThread.isAlive()).as("pipeline thread terminated").isFalse();
        assertThat(deleteFailure.get()).as("delete() threw nothing").isNull();
        assertThat(pipelineFailure.get()).as("ingest() threw nothing (AC-17: it must swallow this, not fail)").isNull();

        // then: the pipeline really did reach its own indexing step (acquired the lock only after
        // delete released it, wrote chunks, then discovered the row gone) -- not an early return
        // that would make the "self-cleans" claim vacuous
        verify(chunkIndexer, times(1)).index(anyList());

        // then: the pipeline took the self-cleanup branch specifically -- neither terminal status
        // transition ever ran (review 2026-08-30 round 2 correctness Medium-1). Without this pair
        // of never()s, this test cannot tell the self-cleanup branch from the pre-existing
        // compensateAndFail() path, which happens to leave the same final row/chunk state (FAILED
        // would still delete the chunks it wrote) -- deleting the self-cleanup branch outright left
        // this test green before this fix.
        verify(statusTransitioner, never()).markIndexed(any(), anyInt());
        verify(statusTransitioner, never()).markFailed(any(), any());

        // then: no trace survives either the document row or its chunks
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM documents WHERE id = ?", Integer.class, documentId))
                .as("documents row")
                .isEmpty();
        assertThat(chunkRepository.countByDocumentId(documentId))
                .as("chunks under this document_id, including any the pipeline wrote and then discarded")
                .isZero();
        assertThat(countOrphanedChunksFor(documentId))
                .as("rows matching ChunkRepository#deleteOrphaned's own predicate for this id")
                .isZero();
    }

    @Test
    @DisplayName("[document-ingestion:AC-17] 파이프라인이 락을 먼저 잡으면 삭제는 락 해제까지 대기했다가 정상적으로 "
            + "파일·청크·행 3종을 모두 삭제하고, 최종 상태에서 documents 행도 vector_store_bgem3 청크도 남지 않는다")
    void pipelineAcquiresLockFirstAndDeleteWaitsThenCompletesCleanly() throws InterruptedException {
        // given
        UUID documentId = createPendingDocumentWithFile();

        // given: the pipeline's own indexing call is instrumented to pause right after it has
        // acquired the lock (the lock is taken immediately before this call in
        // DocumentIngestionPipeline#ingest) and before it writes a single chunk row
        CountDownLatch pipelineHoldsLock = new CountDownLatch(1);
        CountDownLatch releasePipeline = new CountDownLatch(1);
        doAnswer(invocation -> {
            pipelineHoldsLock.countDown();
            releasePipeline.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(chunkIndexer).index(anyList());

        AtomicReference<Throwable> pipelineFailure = new AtomicReference<>();
        Thread pipelineThread = new Thread(() -> runCatching(
                () -> pipeline.ingest(documentId), pipelineFailure), "ac17-pipeline-first");
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> runCatching(
                () -> documentDeleteService.delete(documentId), deleteFailure), "ac17-delete-waits");

        try {
            // when: the pipeline starts, runs parse/chunk/EMBEDDING, acquires the (uncontended)
            // lock, and pauses just before indexing
            pipelineThread.start();
            awaitLatch(pipelineHoldsLock, "pipeline acquired the lock and paused before indexing");

            // when: delete starts concurrently. Its own findById succeeds (the row still exists --
            // the pipeline has not touched it yet), so it proceeds straight to the SAME lock the
            // pipeline is holding, where it must block
            deleteThread.start();
            awaitThreadState(deleteThread,
                    "delete blocked waiting for the pipeline's lock before touching the file/row");

            // when: the pipeline is allowed to finish -- it indexes the chunks, observes the row
            // still present (delete has not run yet), marks INDEXED, and releases the lock
            releasePipeline.countDown();
        } finally {
            pipelineThread.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
            deleteThread.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
        }

        assertThat(pipelineThread.isAlive()).as("pipeline thread terminated").isFalse();
        assertThat(deleteThread.isAlive()).as("delete thread terminated").isFalse();
        assertThat(pipelineFailure.get()).as("ingest() threw nothing").isNull();
        assertThat(deleteFailure.get()).as("delete() threw nothing").isNull();

        // then: the pipeline really did index the chunks (its own critical section actually ran
        // while delete was blocked waiting for the lock), not skip straight through -- delete's own
        // completion is a separate fact, checked below via the row/chunk/file assertions rather than
        // by this line (review 2026-08-30 T10 round 2 correctness Low-1)
        verify(chunkIndexer, times(1)).index(anyList());

        // then: no trace survives either the document row or its chunks
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM documents WHERE id = ?", Integer.class, documentId))
                .as("documents row")
                .isEmpty();
        assertThat(chunkRepository.countByDocumentId(documentId))
                .as("chunks under this document_id, indexed by the pipeline then removed by delete")
                .isZero();
        assertThat(countOrphanedChunksFor(documentId))
                .as("rows matching ChunkRepository#deleteOrphaned's own predicate for this id")
                .isZero();
        assertThat(uploadDir.resolve(documentId.toString()))
                .as("original file deleted by DocumentDeleteService#delete")
                .doesNotExist();
    }

    @Test
    @DisplayName("[document-ingestion:AC-17] delete() 는 unlock 을 트랜잭션 커밋이 끝난 뒤에만 호출한다 -- "
            + "unlock 시점에 별도 커넥션으로 조회해도 documents 행·청크가 이미 사라져 있다")
    void deleteCallsUnlockOnlyAfterItsTransactionHasAlreadyCommitted() {
        // given: an existing document with a real chunk row, so this test can check both traces
        // (not just the documents row) at the exact instant unlock() runs. No second thread is
        // involved here on purpose -- unlike the two interleaving scenarios above, this test
        // isolates the commit-before-unlock ordering claim itself, independent of whether a
        // concurrent pipeline is present at all (DocumentDeleteService's own class-level [Caution],
        // "Test hook for the ordering claim", route (1): spy DocumentIngestLockRegistry and query
        // through an independent connection inside the stubbed unlock()).
        UUID documentId = createPendingDocumentWithFile();
        jdbcTemplate.update(
                "INSERT INTO vector_store_bgem3 (content, metadata, embedding) "
                        + "VALUES (?, json_build_object('document_id', ?::text)::json, ?::vector)",
                "chunk for unlock-after-commit ordering probe", documentId.toString(),
                ChunkSeeder.dummyEmbeddingLiteral());

        AtomicBoolean unlockObserved = new AtomicBoolean(false);
        AtomicBoolean rowAbsentAtUnlockTime = new AtomicBoolean(false);
        AtomicBoolean chunksAbsentAtUnlockTime = new AtomicBoolean(false);
        doAnswer(invocation -> {
            // then (mid-call, before the real unlock runs): read documents/vector_store_bgem3
            // through jdbcTemplate -- a connection independent of delete()'s own persistence
            // context (a different EntityManager/connection than the one TransactionTemplate#execute
            // used to commit). TransactionTemplate#execute commits synchronously and returns before
            // DocumentDeleteService#delete's own finally block calls unlock(), so a fresh
            // read-committed query at this exact point can only see the row/chunks already absent
            // if that ordering actually holds -- an old @Transactional-proxy implementation would
            // still see them present here, because the proxy's own commit has not happened yet.
            boolean rowExists = !jdbcTemplate.queryForList(
                    "SELECT 1 FROM documents WHERE id = ?", Integer.class, documentId).isEmpty();
            int chunkCount = chunkRepository.countByDocumentId(documentId);
            rowAbsentAtUnlockTime.set(!rowExists);
            chunksAbsentAtUnlockTime.set(chunkCount == 0);
            unlockObserved.set(true);
            // the real unlock() still has to run, or the lock leaks for the rest of the suite
            return invocation.callRealMethod();
        }).when(ingestLockRegistry).unlock(documentId);

        // when
        documentDeleteService.delete(documentId);

        // then
        assertThat(unlockObserved.get()).as("unlock() was actually invoked for this id").isTrue();
        assertThat(rowAbsentAtUnlockTime.get())
                .as("documents row, read through an independent connection, was already absent at "
                        + "the exact moment unlock() ran -- proving the delete's commit happened "
                        + "strictly before the lock was released")
                .isTrue();
        assertThat(chunksAbsentAtUnlockTime.get())
                .as("vector_store_bgem3 chunks for this document_id were already absent at the same "
                        + "moment, for the same reason")
                .isTrue();
    }

    /**
     * [Purpose] Runs {@code action} on the calling thread and records any {@link Throwable} into
     *   {@code failure} instead of letting it terminate the thread silently -- {@link Thread}'s
     *   default uncaught-exception behaviour only logs, which this class's assertions on
     *   {@code deleteFailure}/{@code pipelineFailure} would otherwise never see.
     */
    private static void runCatching(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    /**
     * [Purpose] Blocks the test thread until {@code latch} reaches zero or {@link
     *   #AWAIT_TIMEOUT_SECONDS} elapses, failing the test with {@code description} in the latter
     *   case rather than silently proceeding against un-arranged state.
     */
    private static void awaitLatch(CountDownLatch latch, String description) throws InterruptedException {
        boolean reached = latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(reached).as("expected to observe: " + description).isTrue();
    }

    /**
     * [Purpose] Polls {@code thread}'s state until it is {@link Thread.State#WAITING} or {@link
     *   Thread.State#TIMED_WAITING} -- the two states an {@code await()}/{@code lock()} park can
     *   produce -- rather than assuming a fixed delay is long enough.
     * [Rationale] This is the assertion the mutation probe in this class's Javadoc targets: without
     *   {@link DocumentIngestLockRegistry} actually enforcing exclusion, the polled thread runs to
     *   completion instead of ever parking, which this method distinguishes from "still running,
     *   just slow" by failing immediately on {@link Thread.State#TERMINATED} instead of waiting out
     *   the full timeout. <b>Both states accepted (review 2026-08-30 T10 round 3, qa-tester
     *   rework):</b> a thread parked in the unbounded {@link
     *   java.util.concurrent.locks.ReentrantLock#lock()} that {@link DocumentIngestionPipeline}
     *   still uses reports {@link Thread.State#WAITING}, but a thread parked in {@link
     *   DocumentIngestLockRegistry#tryLock} (the bounded acquisition {@link
     *   DocumentDeleteService#delete} now uses, round 3 High-1/Medium-1) reports {@link
     *   Thread.State#TIMED_WAITING} instead -- {@code LockSupport.parkNanos} rather than {@code
     *   LockSupport.park} underneath, even while genuinely blocked on the same {@link
     *   java.util.concurrent.locks.ReentrantLock}. Accepting only {@code WAITING} (round 1/round 2's
     *   original check) made {@link #pipelineAcquiresLockFirstAndDeleteWaitsThenCompletesCleanly}
     *   time out here even though delete was correctly blocked -- the test's own instrumentation, not
     *   a production defect (surfaced running this suite for real ahead of this round's mutation
     *   probes, before either probe below was applied).
     */
    private static void awaitThreadState(Thread thread, String description) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (state == Thread.State.TERMINATED) {
                fail(description + " -- terminated before reaching WAITING/TIMED_WAITING "
                        + "(the mutual exclusion this test asserts did not hold)");
            }
            sleepQuietly(POLL_INTERVAL_MILLIS);
        }
        fail(description + " -- timed out waiting for WAITING/TIMED_WAITING, last observed state was "
                + thread.getState());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * [Purpose] Counts rows in {@code vector_store_bgem3} matching exactly {@link
     *   ChunkRepository}'s own {@code deleteOrphaned} predicate ({@code document_id} present but no
     *   matching {@code documents} row), scoped to one document id -- an independent, direct-SQL
     *   check that does not go through the class under test.
     */
    private long countOrphanedChunksFor(UUID documentId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store_bgem3 v "
                        + "WHERE v.metadata->>'document_id' = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id::text = v.metadata->>'document_id')",
                Long.class, documentId.toString());
        return count != null ? count : 0L;
    }

    /**
     * [Purpose] Creates a committed PENDING {@code documents} row and writes its original file at
     *   {@code upload-dir/{id}}, mirroring {@code DocumentIngestionPipelineDbIntegrationTest}'s own
     *   helper of the same shape.
     */
    private UUID createPendingDocumentWithFile() {
        Document document = documentRepository.saveAndFlush(new Document(
                SampleDocumentFixtures.TEXT_FILENAME, SampleDocumentFixtures.TEXT_MIME_TYPE,
                (long) SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME).length));
        UUID documentId = document.getId();
        trackedDocumentIds.add(documentId);
        try {
            Files.write(uploadDir.resolve(documentId.toString()),
                    SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage test upload " + documentId, e);
        }
        return documentId;
    }
}
