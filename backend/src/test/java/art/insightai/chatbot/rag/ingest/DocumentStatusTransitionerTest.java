package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentRepository;
import art.insightai.chatbot.rag.domain.DocumentStatus;

/**
 * [Purpose] Unit tests for {@link DocumentStatusTransitioner} -- the component that turns one
 *   requested status change into one immediately committed {@code documents} update (Tech Review
 *   AD-3).
 * [Rationale] The repository is mocked, so these tests characterize the class's decision logic
 *   (which document is loaded, which transition is applied, when a transition is skipped) without a
 *   database. Whether the commit really is independent -- i.e. whether the intermediate states are
 *   externally observable while the pipeline runs -- is a property of the transaction manager, not
 *   of this code, so it cannot be proven here; the closest a unit test can get is asserting the
 *   {@code REQUIRES_NEW} propagation metadata that AD-3 depends on, which the last test does. The
 *   real "상태가 순서대로 기록된다" evidence for document-ingestion:AC-5 is the pipeline integration
 *   test against a live database, exactly as Spec §4's AC-5 cell warns.
 * [Caution] Assertions read the state off the {@link Document} instance handed back by the mocked
 *   repository rather than off a {@code save()} argument captor: with a real repository the entity
 *   is already managed and the update happens by dirty checking, so the instance -- not the
 *   {@code save()} call -- is the thing that actually carries the change.
 */
@ExtendWith(MockitoExtension.class)
class DocumentStatusTransitionerTest {

    @Mock
    private DocumentRepository documentRepository;

    @Test
    @DisplayName("[document-ingestion:AC-5] transitionTo 는 PENDING 문서를 PARSING 으로 옮기고 저장한다")
    void transitionsPendingDocumentToParsing() {
        // given
        Document document = new Document("sample-document.txt", "text/plain", 10L);
        UUID documentId = document.getId();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        DocumentStatusTransitioner transitioner = new DocumentStatusTransitioner(documentRepository);

        // when
        transitioner.transitionTo(documentId, DocumentStatus.PARSING);

        // then
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PARSING);
        verify(documentRepository).save(document);
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] transitionTo 는 역행 전이를 거부하고 저장하지 않는다")
    void rejectsBackwardTransitionWithoutSaving() {
        // given
        Document document = new Document("sample-document.txt", "text/plain", 10L);
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);
        UUID documentId = document.getId();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        DocumentStatusTransitioner transitioner = new DocumentStatusTransitioner(documentRepository);

        // when / then: the allow/deny table lives in DocumentStatus.canTransitionTo, and this class
        // must not carry a second copy of it
        assertThatThrownBy(() -> transitioner.transitionTo(documentId, DocumentStatus.PARSING))
                .isInstanceOf(IllegalStateException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.EMBEDDING);
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] markIndexed 는 INDEXED 로 옮기면서 chunk_count 와 indexed_at 을 함께 기록한다")
    void marksIndexedWithChunkCountAndTimestamp() {
        // given
        Document document = new Document("sample-document.txt", "text/plain", 10L);
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);
        UUID documentId = document.getId();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        DocumentStatusTransitioner transitioner = new DocumentStatusTransitioner(documentRepository);
        OffsetDateTime before = OffsetDateTime.now();

        // when
        transitioner.markIndexed(documentId, 3);

        // then
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getChunkCount()).isEqualTo(3);
        assertThat(document.getIndexedAt()).isNotNull().isAfterOrEqualTo(before);
        verify(documentRepository).save(document);
    }

    @Test
    @DisplayName("[document-ingestion:AC-7] markIndexed 는 chunk_count 가 0 이면 거부한다 (청크 0건 문서는 INDEXED 가 될 수 없다)")
    void refusesToMarkIndexedWithZeroChunks() {
        // given
        Document document = new Document("empty.txt", "text/plain", 0L);
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);
        UUID documentId = document.getId();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        DocumentStatusTransitioner transitioner = new DocumentStatusTransitioner(documentRepository);

        // when / then: AC-7 requires an INDEXED document to hold at least one chunk, so the
        // zero-chunk document has to end up somewhere else -- see DocumentIngestionPipeline
        assertThatThrownBy(() -> transitioner.markIndexed(documentId, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.EMBEDDING);
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] markFailed 는 FAILED 로 옮기고 error_message 를 기록한다")
    void marksFailedWithErrorMessage() {
        // given
        Document document = new Document("sample-document.txt", "text/plain", 10L);
        document.transitionTo(DocumentStatus.PARSING);
        UUID documentId = document.getId();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        DocumentStatusTransitioner transitioner = new DocumentStatusTransitioner(documentRepository);

        // when
        transitioner.markFailed(documentId, DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE);

        // then
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getErrorMessage())
                .isEqualTo(DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE)
                .isNotBlank();
        verify(documentRepository).save(document);
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] markFailed 는 이미 INDEXED 인 문서를 건드리지 않고 예외도 던지지 않는다")
    void skipsAlreadyTerminalDocumentWithoutThrowing() {
        // given
        Document document = new Document("sample-document.txt", "text/plain", 10L);
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);
        document.markIndexed(2, OffsetDateTime.now());
        UUID documentId = document.getId();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        DocumentStatusTransitioner transitioner = new DocumentStatusTransitioner(documentRepository);

        // when: called from a catch block, so throwing here would mask the original failure
        transitioner.markFailed(documentId, DocumentIngestionPipeline.INDEX_FAILURE_MESSAGE);

        // then
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getErrorMessage()).isNull();
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] markFailed 는 문서가 이미 삭제됐으면 조용히 건너뛴다")
    void skipsDeletedDocumentWithoutThrowing() {
        // given
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());
        DocumentStatusTransitioner transitioner = new DocumentStatusTransitioner(documentRepository);

        // when
        transitioner.markFailed(documentId, DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE);

        // then
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[document-ingestion §5] 세 전이 메서드 모두 REQUIRES_NEW 로 선언돼 있다 (AD-3: 중간 상태가 즉시 커밋돼야 한다)")
    void allTransitionMethodsDeclareRequiresNewPropagation() throws Exception {
        // given
        Method[] methods = {
                DocumentStatusTransitioner.class.getMethod(
                        "transitionTo", UUID.class, DocumentStatus.class),
                DocumentStatusTransitioner.class.getMethod("markIndexed", UUID.class, int.class),
                DocumentStatusTransitioner.class.getMethod("markFailed", UUID.class, String.class),
        };

        // when / then: without REQUIRES_NEW these would join the caller's transaction (or none),
        // and AC-5's intermediate states would never become externally observable
        for (Method method : methods) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional)
                    .as("%s must be @Transactional", method.getName())
                    .isNotNull();
            assertThat(transactional.propagation())
                    .as("%s must use REQUIRES_NEW", method.getName())
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
