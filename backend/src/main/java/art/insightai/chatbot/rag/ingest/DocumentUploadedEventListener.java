package art.insightai.chatbot.rag.ingest;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import art.insightai.chatbot.rag.domain.DocumentUploadedEvent;

/**
 * [Purpose] Bridges the upload request to the background ingestion pipeline: when (and only when)
 *   the transaction that created a {@code documents} row has committed, hands that document's id
 *   to {@link DocumentIngestionPipeline} on the {@code ingestExecutor} pool -- and, when that pool
 *   refuses the task, closes the document out as FAILED instead of leaving it PENDING forever.
 * [Rationale] Tech Review AD-2. Two properties are load-bearing here and neither is redundant:
 *   <ul>
 *     <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} removes the race where a
 *     background thread starts before the row it needs is visible -- without it the pipeline could
 *     read the document before {@code DocumentUploadService#upload}'s transaction commits, or act
 *     on a document whose transaction ultimately rolled back.</li>
 *     <li>Submitting to {@code ingestExecutor} instead of running inline is what makes
 *     document-ingestion:AC-1 hold: the upload response must be returned before any parsing or
 *     embedding happens. {@code AFTER_COMMIT} alone would still run the pipeline <i>synchronously
 *     on the request thread</i>, after commit but before the response is written -- the caller
 *     would then wait out the whole parse/embed round trip. Tech Review AD-11's ban on
 *     {@code CallerRunsPolicy} exists for the same reason: a rejected task must fail, never
 *     quietly fall back onto the request thread.</li>
 *   </ul>
 *   <p>
 *   <b>Why the executor is injected and submitted to explicitly, rather than declaring
 *   {@code @Async(ingestExecutor)} on the listener method.</b> This was rewritten (review
 *   2026-08-28, T8 Medium-1) because {@code @Async} makes the one failure that matters here
 *   <i>unobservable and unhandleable</i>. Confirmed against the 7.0.9 sources, not inferred:
 *   {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor#execute} turns the
 *   pool's {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} rejection into a
 *   {@link org.springframework.core.task.TaskRejectedException} (:382-390); with {@code @Async}
 *   that throw happens inside {@code AsyncExecutionAspectSupport#doSubmit}, i.e. on the calling
 *   thread but <i>outside</i> the annotated method's own body, so the method can never catch its
 *   own rejection. The calling thread is the AFTER_COMMIT callback, and
 *   {@code TransactionSynchronizationUtils#invokeAfterCompletion} wraps every synchronization call
 *   in {@code catch (Throwable)} (:196-208 -- AFTER_COMMIT listeners run from
 *   {@code TransactionalApplicationListenerSynchronization.PlatformSynchronization#afterCompletion}
 *   (:115-124), not from {@code afterCommit}). The rejection therefore vanished into one framework
 *   log line: the uploader still got 202 + {@code status:"PENDING"}, the document reached neither
 *   INDEXED nor FAILED, and it kept holding an {@code app.ingest.max-document-count} slot with no
 *   API able to reclaim it. Explicit submission keeps the exception inside this method, where it
 *   can be converted into the terminal state document-ingestion:AC-16 requires. Two lesser
 *   benefits: a wrong executor bean name now fails at startup rather than at first submission, and
 *   an exception escaping the task gets a log line owned by this class instead of Spring's generic
 *   async uncaught-exception handler.
 *   <p>
 *   {@code AbortPolicy} is kept exactly as AD-11 fixed it. The bug was never the rejection itself
 *   -- a bounded queue that refuses work is the intended back-pressure -- it was that the refusal
 *   was silent.
 * [Side effects] Submits a task to {@code ingestExecutor} (see {@link DocumentIngestionPipeline}
 *   for what that task then writes) or, on rejection, commits a FAILED {@code documents} row via
 *   {@link DocumentStatusTransitioner#markFailed}.
 * [Caution] A {@code @TransactionalEventListener} is <b>silently skipped</b> -- no exception, no
 *   log -- when the event is published outside an active transaction (Tech Review NR-3). The
 *   symptom is "upload returns 202 but the document stays PENDING forever". The publish site,
 *   {@code DocumentUploadService#upload}, is annotated {@code @Transactional} for exactly this
 *   reason; that annotation must not be removed. For the same "silently swallowed" reason, nothing
 *   in this class may be allowed to throw: an exception leaving an AFTER_COMMIT callback is
 *   discarded by the framework, so this is the last place where a log line can still be produced.
 *   <p>
 *   <b>Connection budget.</b> The rejection branch runs while the upload transaction has committed
 *   but has <i>not</i> yet been cleaned up ({@code AbstractPlatformTransactionManager#processCommit}
 *   calls {@code triggerAfterCompletion} at :838 and {@code cleanupAfterCompletion} only at :846),
 *   so its JDBC connection is still bound to this thread; {@code markFailed}'s
 *   {@code REQUIRES_NEW} then borrows a second one. On that branch the request thread holds
 *   <b>2</b> connections briefly, not 1 -- the arithmetic in {@link IngestAsyncConfig}'s [Caution]
 *   ("2 workers + 1 test thread = 3") has no room for it. An integration test that provokes a
 *   rejection must therefore keep the two occupying workers connection-free (a latch barrier
 *   does), or it will hit {@code connection-timeout} deterministically.
 * [AC] document-ingestion:AC-1, document-ingestion:AC-5, document-ingestion:AC-16
 */
@Component
public class DocumentUploadedEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadedEventListener.class);

    /**
     * [Purpose] Client-visible {@code error_message} recorded when {@code ingestExecutor} refuses
     *   the ingestion task (document-ingestion:AC-16).
     * [Rationale] A fixed English string, never {@code exception.getMessage()}: this value is
     *   returned verbatim by the unauthenticated {@code GET /api/admin/documents}
     *   (document-ingestion:AC-11, Spec §7 OQ-7), and a {@code TaskRejectedException} message
     *   embeds the executor's internal {@code toString()} (pool sizes, queue state, thread names).
     *   Same shape as {@link DocumentIngestionPipeline#PARSE_FAILURE_MESSAGE} and INC-007. The
     *   exception itself is logged at ERROR at the catch site.
     * [Caution] It is worded as a queue condition rather than a permanent defect because the
     *   operator's remedy is to re-upload later; there is no retry path (Spec §7 OQ-8).
     */
    static final String SUBMISSION_REJECTED_MESSAGE =
            "ingestion could not be started because the ingestion queue was full";

    private final DocumentIngestionPipeline pipeline;
    private final AsyncTaskExecutor ingestExecutor;
    private final DocumentStatusTransitioner statusTransitioner;

    public DocumentUploadedEventListener(
            DocumentIngestionPipeline pipeline,
            @Qualifier(IngestAsyncConfig.INGEST_EXECUTOR_BEAN_NAME) AsyncTaskExecutor ingestExecutor,
            DocumentStatusTransitioner statusTransitioner) {
        this.pipeline = pipeline;
        this.ingestExecutor = ingestExecutor;
        this.statusTransitioner = statusTransitioner;
    }

    /**
     * [Purpose] Starts ingestion for a freshly committed upload, or records the document as FAILED
     *   when the ingestion pool refuses to take the task.
     * [Rationale] {@link RejectedExecutionException} rather than
     *   {@link org.springframework.core.task.TaskRejectedException} is caught because the latter
     *   <i>extends</i> the former (verified in {@code spring-core:7.0.9}); catching the supertype
     *   covers both the wrapped Spring form and a bare JDK rejection, should this ever be handed a
     *   plain {@code Executor}.
     * [Side effects] See class-level [Side effects].
     * [Caution] This method establishes only part of document-ingestion:AC-16. Of the AC's three
     *   elements it enforces two directly -- terminal FAILED and a non-blank {@code error_message}
     *   -- while the third ("청크 0건") holds here for a structural reason rather than by anything
     *   this code does: rejection happens before the pipeline has run at all, and the upload path
     *   itself never writes to {@code vector_store_bgem3}, so no chunk for that id can exist yet.
     *   That argument is about the <i>absence of a writer</i>, so it stops holding the moment
     *   something else submits ingestion for a document that already has chunks (a re-index or
     *   retry path, neither of which exists today -- Spec §7 OQ-3/OQ-8). Whoever adds one must
     *   delete that document's chunks on this branch, the way {@link DocumentIngestionPipeline}
     *   already does for its own failures before recording FAILED.
     * [AC] document-ingestion:AC-1, document-ingestion:AC-5, document-ingestion:AC-16
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        UUID documentId = event.documentId();
        try {
            ingestExecutor.execute(() -> runIngestion(documentId));
            log.debug("업로드 커밋 후 인제스천 태스크 제출 (documentId={})", documentId);
        } catch (RejectedExecutionException e) {
            log.error("인제스천 큐 포화로 태스크 제출이 거부되어 문서를 FAILED 로 종결 (documentId={})",
                    documentId, e);
            recordSubmissionRejected(documentId);
        }
    }

    /**
     * [Purpose] Runs the pipeline on an {@code ingest-} thread and keeps any escaping exception
     *   from reaching the raw thread's default uncaught-exception handler.
     * [Rationale] Preserves what {@code @Async void} used to do (Spring's
     *   {@code SimpleAsyncUncaughtExceptionHandler} logged and swallowed) while making it explicit
     *   and greppable. {@link DocumentIngestionPipeline#ingest} converts document-level failures
     *   into a FAILED row itself, so anything arriving here is the pipeline's <i>own</i> failure --
     *   in practice the FAILED-recording step failing (review L-5: the compensation path borrows a
     *   connection right after a failure that may itself have been a connection timeout). That
     *   case leaves the document non-terminal, so this log line is the only signal it happened; it
     *   is deliberately worded differently from the pipeline's own "문서 인제스천 실패" ERROR so the
     *   two can be told apart in a log search.
     */
    private void runIngestion(UUID documentId) {
        try {
            pipeline.ingest(documentId);
        } catch (RuntimeException e) {
            log.error("인제스천 태스크가 예외로 종료되어 문서가 비종결 상태로 남을 수 있음 (documentId={})",
                    documentId, e);
        }
    }

    /**
     * [Purpose] Records the terminal FAILED state for a task the pool refused.
     * [Caution] {@code markFailed} opens its own {@code REQUIRES_NEW} transaction and can therefore
     *   fail on its own (connection exhaustion is a plausible cause of a saturated queue in the
     *   first place). Letting that failure propagate would hand it back to the framework's
     *   {@code catch (Throwable)} and leave no trace at all, so it is logged here instead -- in
     *   that case the document really does stay PENDING, and that fact must be visible.
     * [AC] document-ingestion:AC-16
     */
    private void recordSubmissionRejected(UUID documentId) {
        try {
            statusTransitioner.markFailed(documentId, SUBMISSION_REJECTED_MESSAGE);
        } catch (RuntimeException e) {
            log.error("제출 거부의 FAILED 기록마저 실패하여 문서가 PENDING 에 남음 (documentId={})",
                    documentId, e);
        }
    }
}
