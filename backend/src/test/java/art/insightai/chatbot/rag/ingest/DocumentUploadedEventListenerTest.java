package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import art.insightai.chatbot.rag.domain.DocumentUploadedEvent;

/**
 * [Purpose] Unit tests for {@link DocumentUploadedEventListener}: that it hands the uploaded
 *   document's id to {@link DocumentIngestionPipeline} <i>through the ingestion executor</i> and
 *   nothing more, that it only runs after the upload transaction commits, and that a rejected
 *   submission ends the document in a terminal FAILED state (document-ingestion:AC-16).
 * [Rationale] Both of the listener's structural properties fail silently when wrong, which is why
 *   they are asserted rather than trusted. {@code @TransactionalEventListener(AFTER_COMMIT)} is
 *   skipped without any exception or log when its conditions are not met (Tech Review NR-3), and
 *   running the pipeline on the calling thread would break document-ingestion:AC-1 ("응답 시점에
 *   파싱·임베딩은 수행되지 않는다") without any functional assertion elsewhere noticing.
 *   <p>
 *   The asynchrony assertion changed shape in the T8 rework (review 2026-08-28 Medium-1). It used
 *   to read {@code @Async}'s annotation attributes by reflection, because that annotation was the
 *   whole mechanism. Now that the listener submits to an injected {@link AsyncTaskExecutor}, the
 *   same property is verifiable <b>behaviourally</b> -- the collaborator is a mock, so a run that
 *   touched the pipeline on the caller's thread is directly observable -- which is strictly
 *   stronger than reading an annotation: it also covers "submitted, but the wrong work" and
 *   "submitted and additionally run inline".
 * [Caution] <b>What this file does and does not prove about AC-1.</b> Spec §4's AC-1 cell records
 *   that the full check the AC describes -- spy the real {@code DocumentParser} bean and assert
 *   zero invocations at the moment the 202 is returned -- needs a Spring context and a real
 *   request, so it lives in dev-qa-tester's {@code DocumentUploadAsyncHandoffIntegrationTest}, not
 *   here. What this file establishes is the mechanism that makes such a spy assertion hold.
 *   <p>
 *   Likewise AC-16 is only <b>partly</b> covered here. This file can show the two halves that are
 *   this class's own doing -- terminal FAILED and a fixed, non-blank {@code error_message} -- but
 *   the AC's third element ("해당 document_id 의 청크가 vector_store_bgem3 에 0건") is a statement
 *   about a real table, so it belongs to the integration test that provokes an actual queue
 *   overflow. Do not read a green run of this class as AC-16 being satisfied.
 *   <p>
 *   <b>One case here is a counterexample, not evidence.</b>
 *   {@link #neverThrowsOutOfTheListenerEvenWhenRecordingTheFailureFails()} pins the one branch on
 *   which AC-16's core prohibition ("문서는 PENDING 에 남지 않고") does <i>not</i> hold -- {@code markFailed}
 *   failing on its own -- and is therefore tagged {@code [document-ingestion §5]} so it can never be
 *   counted as AC-16 evidence (review 2026-08-28 T8 round2 Medium-1, same treatment as
 *   {@code DocumentIngestionPipelineDbIntegrationTest#leavesChunksBehindWhenTheCompensatingDeleteItselfFails}).
 *   The implementation choice it pins is the accepted one -- rethrowing would hand the failure to
 *   the framework's {@code catch (Throwable)} and leave no trace at all -- and the review required
 *   no code change; what the test adds is that the ERROR log stays and cannot be quietly removed.
 *   The Spec §4 AC-16 cell carries the matching note.
 */
@ExtendWith(MockitoExtension.class)
class DocumentUploadedEventListenerTest {

    @Mock
    private DocumentIngestionPipeline pipeline;

    @Mock
    private AsyncTaskExecutor ingestExecutor;

    @Mock
    private DocumentStatusTransitioner statusTransitioner;

    private DocumentUploadedEventListener listener() {
        return new DocumentUploadedEventListener(pipeline, ingestExecutor, statusTransitioner);
    }

    @Test
    @DisplayName("[document-ingestion:AC-1] 리스너는 파이프라인을 호출 스레드에서 돌리지 않고 ingestExecutor 에 제출만 한다")
    void submitsToTheIngestExecutorInsteadOfRunningOnTheCallingThread() {
        // given
        UUID documentId = UUID.randomUUID();

        // when
        listener().onDocumentUploaded(new DocumentUploadedEvent(documentId));

        // then: nothing ran yet -- AFTER_COMMIT alone would have parsed inline before the 202
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(ingestExecutor).execute(task.capture());
        verifyNoInteractions(pipeline, statusTransitioner);

        // and: the submitted task is the ingestion of exactly that document
        task.getValue().run();
        verify(pipeline).ingest(documentId);
        verifyNoMoreInteractions(pipeline);
    }

    @Test
    @DisplayName("[document-ingestion:AC-1] 리스너는 AFTER_COMMIT 단계에서만 동작한다 (커밋 전에는 파싱이 시작될 수 없다)")
    void runsOnlyAfterTheUploadTransactionCommits() throws Exception {
        // given
        Method method = listenerMethod();

        // when
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("[document-ingestion:AC-16] 큐 포화로 제출이 거부되면 문서를 FAILED 로 종결한다 (PENDING 고착 금지)")
    void marksTheDocumentFailedWhenTheExecutorRejectsTheTask() {
        // given: AbortPolicy rejection, wrapped by ThreadPoolTaskExecutor exactly as in production
        UUID documentId = UUID.randomUUID();
        doThrow(new TaskRejectedException("queue is full", new RejectedExecutionException()))
                .when(ingestExecutor).execute(any(Runnable.class));

        // when
        listener().onDocumentUploaded(new DocumentUploadedEvent(documentId));

        // then: the document must not be left PENDING -- it is neither INDEXED nor retryable and
        // it would keep holding an app.ingest.max-document-count slot forever
        verify(statusTransitioner).markFailed(
                documentId, DocumentUploadedEventListener.SUBMISSION_REJECTED_MESSAGE);
        verifyNoInteractions(pipeline);
    }

    @Test
    @DisplayName("[document-ingestion:AC-16] 거부 시 기록되는 error_message 는 비어 있지 않은 고정 문자열이며 예외 내용을 노출하지 않는다")
    void recordsAFixedNonBlankErrorMessageThatLeaksNothingFromTheException() {
        // given: a rejection whose message carries executor internals, as the real one does
        UUID documentId = UUID.randomUUID();
        String executorInternals = "ThreadPoolTaskExecutor in active state did not accept task";
        doThrow(new TaskRejectedException(executorInternals, new RejectedExecutionException()))
                .when(ingestExecutor).execute(any(Runnable.class));

        // when
        listener().onDocumentUploaded(new DocumentUploadedEvent(documentId));

        // then: error_message is served verbatim by the unauthenticated admin list (AC-11), so it
        // may not carry exception text (INC-007)
        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        verify(statusTransitioner).markFailed(eq(documentId), errorMessage.capture());
        assertThat(errorMessage.getValue())
                .isNotBlank()
                .isEqualTo(DocumentUploadedEventListener.SUBMISSION_REJECTED_MESSAGE)
                .doesNotContain(executorInternals)
                .doesNotContain("ThreadPoolTaskExecutor");
    }

    /**
     * [Purpose] Pins the branch where the FAILED record itself fails: the listener logs and returns
     *   instead of rethrowing.
     * [Rationale] An exception escaping here is discarded by
     *   {@code TransactionSynchronizationUtils.invokeAfterCompletion}'s {@code catch (Throwable)},
     *   so rethrowing would replace a logged failure with an invisible one. The review accepted
     *   this choice and required no code change.
     * [Caution] <b>This case is a counterexample to document-ingestion:AC-16, not evidence for
     *   it.</b> What it describes is a document that stayed PENDING -- exactly what AC-16's core
     *   prohibition forbids. The rest of AC-16 (terminal FAILED, non-blank fixed
     *   {@code error_message}, zero chunks) is filled in by the two sibling cases above and by
     *   {@code DocumentUploadAsyncHandoffIntegrationTest#marksTheDocumentFailedWithoutChunksWhenTheIngestionQueueIsFull},
     *   all of which assume {@code markFailed} succeeds. Hence the {@code [document-ingestion §5]}
     *   tag: tagging it {@code [document-ingestion:AC-16]} would let a green run that <i>describes</i>
     *   an AC-16 violation be harvested as AC-16 evidence (the global convention's evidence-ledger rule
     *   derives the ledger mechanically from tags). Inverting this case -- making the listener rethrow -- breaks
     *   nothing visible: the document still stays PENDING, but the ERROR log disappears too, which
     *   is the only observation of that state that exists.
     */
    @Test
    @DisplayName("[document-ingestion §5] FAILED 기록마저 실패하면 문서는 PENDING 에 남지만 예외를 밖으로 던지지 않는다 "
            + "(AC-16 핵심 금지의 반례 — 로그로만 관측된다)")
    void neverThrowsOutOfTheListenerEvenWhenRecordingTheFailureFails() {
        // given
        UUID documentId = UUID.randomUUID();
        doThrow(new TaskRejectedException("queue is full", new RejectedExecutionException()))
                .when(ingestExecutor).execute(any(Runnable.class));
        doThrow(new IllegalStateException("connection pool exhausted"))
                .when(statusTransitioner).markFailed(any(), any());

        // when / then: an exception escaping here is discarded by
        // TransactionSynchronizationUtils.invokeAfterCompletion's catch (Throwable), so throwing
        // would only replace a logged failure with an invisible one
        assertThatCode(() -> listener().onDocumentUploaded(new DocumentUploadedEvent(documentId)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[document-ingestion §5] 파이프라인이 던진 예외는 제출된 태스크 밖으로 새어나가지 않는다")
    void swallowsAndLogsAnExceptionEscapingTheSubmittedTask() {
        // given: the pipeline converts document-level failures itself, so anything thrown here is
        // its own failure (e.g. the FAILED-recording step failing) -- it must not reach the raw
        // worker thread's default uncaught-exception handler
        UUID documentId = UUID.randomUUID();
        doThrow(new IllegalStateException("recording FAILED itself failed"))
                .when(pipeline).ingest(documentId);

        // when
        listener().onDocumentUploaded(new DocumentUploadedEvent(documentId));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(ingestExecutor).execute(task.capture());

        // then
        assertThatCode(() -> task.getValue().run()).doesNotThrowAnyException();
        verify(statusTransitioner, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-1] DocumentUploadService 는 파싱·임베딩 협력자를 하나도 주입받지 않는다")
    void uploadServiceHoldsNoIngestionCollaborator() {
        // given
        Class<?>[] forbidden = {
                DocumentParser.class, DocumentChunker.class, ChunkMetadataBuilder.class,
                ChunkIndexer.class, DocumentIngestionPipeline.class,
                EmbeddingModel.class, VectorStore.class,
        };

        // when
        Class<?>[] declared = Arrays.stream(DocumentUploadService.class.getDeclaredFields())
                .map(Field::getType)
                .toArray(Class<?>[]::new);

        // then: the upload path cannot invoke a parsing collaborator it does not hold, so the only
        // remaining route to one is the AFTER_COMMIT listener asserted above, which submits the
        // work to ingestExecutor rather than running it
        for (Class<?> type : forbidden) {
            assertThat(declared)
                    .as("DocumentUploadService must not depend on %s", type.getSimpleName())
                    .noneMatch(type::isAssignableFrom);
        }
    }

    private Method listenerMethod() throws NoSuchMethodException {
        return DocumentUploadedEventListener.class
                .getMethod("onDocumentUploaded", DocumentUploadedEvent.class);
    }
}
