package art.insightai.chatbot.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Verifies the allow/deny table behind {@link DocumentStatus#canTransitionTo}
 *   in isolation, before any entity or persistence machinery is involved.
 */
class DocumentStatusTest {

    @Test
    @DisplayName("[document-ingestion:AC-5] 정방향 순서(PENDING->PARSING->EMBEDDING->INDEXED)는 각 단계가 허용된다")
    void allowsForwardChainStepByStep() {
        // given: the four forward-chain states in order
        // when / then: each step to the immediate next state is allowed
        assertThat(DocumentStatus.PENDING.canTransitionTo(DocumentStatus.PARSING)).isTrue();
        assertThat(DocumentStatus.PARSING.canTransitionTo(DocumentStatus.EMBEDDING)).isTrue();
        assertThat(DocumentStatus.EMBEDDING.canTransitionTo(DocumentStatus.INDEXED)).isTrue();
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] 단계를 건너뛰는 전이는 거부된다")
    void rejectsSkippingAStage() {
        // given: PENDING, two stages before INDEXED
        // when: attempting to jump straight to EMBEDDING or INDEXED
        // then: both are rejected
        assertThat(DocumentStatus.PENDING.canTransitionTo(DocumentStatus.EMBEDDING)).isFalse();
        assertThat(DocumentStatus.PENDING.canTransitionTo(DocumentStatus.INDEXED)).isFalse();
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] 역행 전이는 거부된다")
    void rejectsRegression() {
        // given: states further along the forward chain
        // when: attempting to move back to an earlier state
        // then: all are rejected
        assertThat(DocumentStatus.PARSING.canTransitionTo(DocumentStatus.PENDING)).isFalse();
        assertThat(DocumentStatus.EMBEDDING.canTransitionTo(DocumentStatus.PARSING)).isFalse();
        assertThat(DocumentStatus.INDEXED.canTransitionTo(DocumentStatus.EMBEDDING)).isFalse();
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] 진행 중인 상태(PENDING/PARSING/EMBEDDING)는 언제든 FAILED로 전이할 수 있다")
    void allowsFailureFromAnyInProgressState() {
        // given: the three non-terminal states
        // when / then: each can transition directly to FAILED
        assertThat(DocumentStatus.PENDING.canTransitionTo(DocumentStatus.FAILED)).isTrue();
        assertThat(DocumentStatus.PARSING.canTransitionTo(DocumentStatus.FAILED)).isTrue();
        assertThat(DocumentStatus.EMBEDDING.canTransitionTo(DocumentStatus.FAILED)).isTrue();
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] INDEXED와 FAILED는 종결 상태로 더 이상 전이할 수 없다")
    void treatsIndexedAndFailedAsTerminal() {
        // given: the two terminal states
        // when / then: no outgoing transition is allowed, including into FAILED or self
        for (DocumentStatus target : DocumentStatus.values()) {
            assertThat(DocumentStatus.INDEXED.canTransitionTo(target)).isFalse();
            assertThat(DocumentStatus.FAILED.canTransitionTo(target)).isFalse();
        }
    }
}
