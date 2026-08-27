package art.insightai.chatbot.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Verifies {@link Document}'s own state-mutation behavior — construction defaults
 *   and the transition/mark* methods that sit on top of {@link DocumentStatus}. No Spring
 *   context or database is involved; this is pure object behavior.
 */
class DocumentTest {

    @Test
    @DisplayName("생성 직후 문서는 PENDING 상태이고 id·createdAt이 즉시 채워져 있으며 title은 비어 있다")
    void newDocumentStartsPendingWithIdAndCreatedAtAssigned() {
        // given / when: a document is constructed with just filename/mimeType/sizeBytes
        Document document = new Document("report.pdf", "application/pdf", 1024L);

        // then: id and createdAt are already assigned (no flush needed), status is PENDING,
        //   and title is left unset per Tech Review AD-8
        assertThat(document.getId()).isNotNull();
        assertThat(document.getCreatedAt()).isNotNull();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(document.getTitle()).isNull();
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] 허용된 순서대로 전이하면 상태가 갱신된다")
    void transitionsThroughAllowedForwardChain() {
        // given: a fresh PENDING document
        Document document = new Document("report.pdf", "application/pdf", 1024L);

        // when: transitioning through the full allowed forward chain
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);
        document.transitionTo(DocumentStatus.INDEXED);

        // then: the final state reflects the last transition
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] 허용되지 않은 전이를 시도하면 예외가 발생하고 상태는 바뀌지 않는다")
    void rejectsDisallowedTransition() {
        // given: a fresh PENDING document
        Document document = new Document("report.pdf", "application/pdf", 1024L);

        // when: attempting to skip straight to EMBEDDING
        // then: an IllegalStateException is thrown and the status is left unchanged
        assertThatThrownBy(() -> document.transitionTo(DocumentStatus.EMBEDDING))
                .isInstanceOf(IllegalStateException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING);
    }

    @Test
    @DisplayName("markFailed 호출 시 상태가 FAILED이고 error_message가 채워진다")
    void markFailedSetsStatusAndErrorMessage() {
        // given: a document mid-pipeline
        Document document = new Document("report.pdf", "application/pdf", 1024L);
        document.transitionTo(DocumentStatus.PARSING);

        // when: the parsing stage fails
        document.markFailed("Tika parse error: unsupported encoding");

        // then: status is FAILED and error_message is non-empty
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getErrorMessage()).isEqualTo("Tika parse error: unsupported encoding");
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] markIndexed 호출 시 상태가 INDEXED이고 chunk_count·indexed_at이 채워진다")
    void markIndexedSetsStatusChunkCountAndIndexedAt() {
        // given: a document that has completed parsing and embedding
        Document document = new Document("report.pdf", "application/pdf", 1024L);
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);
        OffsetDateTime completedAt = OffsetDateTime.now();

        // when: indexing completes with 5 chunks
        document.markIndexed(5, completedAt);

        // then: status, chunk_count and indexed_at all reflect completion
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getChunkCount()).isEqualTo(5);
        assertThat(document.getIndexedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("markFailed(null) 호출 시 IllegalArgumentException이 발생하고 상태는 바뀌지 않는다")
    void markFailedRejectsNullErrorMessage() {
        // given: a document mid-pipeline
        Document document = new Document("report.pdf", "application/pdf", 1024L);
        document.transitionTo(DocumentStatus.PARSING);

        // when / then: a null error message is rejected before the transition applies
        assertThatThrownBy(() -> document.markFailed(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PARSING);
    }

    @Test
    @DisplayName("markFailed(\"\") 호출 시 IllegalArgumentException이 발생하고 상태는 바뀌지 않는다")
    void markFailedRejectsBlankErrorMessage() {
        // given: a document mid-pipeline
        Document document = new Document("report.pdf", "application/pdf", 1024L);
        document.transitionTo(DocumentStatus.PARSING);

        // when / then: a blank (whitespace-only) error message is rejected the same way
        assertThatThrownBy(() -> document.markFailed("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PARSING);
    }

    @Test
    @DisplayName("markIndexed(0, ...) 호출 시 IllegalArgumentException이 발생하고 상태는 바뀌지 않는다")
    void markIndexedRejectsNonPositiveChunkCount() {
        // given: a document that has completed parsing and embedding
        Document document = new Document("report.pdf", "application/pdf", 1024L);
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);

        // when / then: a chunk_count below 1 violates AC-7 and is rejected
        assertThatThrownBy(() -> document.markIndexed(0, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.EMBEDDING);
    }

    @Test
    @DisplayName("markIndexed(n, null) 호출 시 예외가 발생하고 상태는 바뀌지 않는다")
    void markIndexedRejectsNullIndexedAt() {
        // given: a document that has completed parsing and embedding
        Document document = new Document("report.pdf", "application/pdf", 1024L);
        document.transitionTo(DocumentStatus.PARSING);
        document.transitionTo(DocumentStatus.EMBEDDING);

        // when / then: a null indexed_at violates AC-5 and is rejected
        assertThatThrownBy(() -> document.markIndexed(5, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.EMBEDDING);
    }
}
