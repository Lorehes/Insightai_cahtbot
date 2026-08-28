package art.insightai.chatbot.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Verifies {@link Conversation}'s construction behavior and its {@code
 *   softDelete}/{@code updateRagEnabled} mutators (T8). No Spring context or database is
 *   involved; this is pure object behavior.
 */
class ConversationTest {

    @Test
    @DisplayName("생성 직후 title은 비어 있고 createdAt과 updatedAt이 동일하며 deletedAt은 null이다")
    void newConversationStartsWithNoTitleAndMatchingTimestamps() {
        // given: a user id and a chosen model
        UUID userId = UUID.randomUUID();

        // when: a conversation is constructed
        Conversation conversation = new Conversation(userId, "upstage/solar-pro4", true);

        // then: title is unset (A-12 fills it later), timestamps match, not soft-deleted, and
        //   id waits for Hibernate to assign it on insert
        assertThat(conversation.getId()).isNull();
        assertThat(conversation.getUserId()).isEqualTo(userId);
        assertThat(conversation.getModel()).isEqualTo("upstage/solar-pro4");
        assertThat(conversation.isRagEnabled()).isTrue();
        assertThat(conversation.getTitle()).isNull();
        assertThat(conversation.getCreatedAt()).isEqualTo(conversation.getUpdatedAt());
        assertThat(conversation.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("[chat:AC-16] softDelete 호출 시 deletedAt이 채워지고 다른 필드는 바뀌지 않는다")
    void softDeleteSetsDeletedAtOnly() {
        // given: a fresh, non-deleted conversation
        UUID userId = UUID.randomUUID();
        Conversation conversation = new Conversation(userId, "upstage/solar-pro4", false);
        OffsetDateTime beforeCall = OffsetDateTime.now();
        OffsetDateTime createdAtBefore = conversation.getCreatedAt();
        OffsetDateTime updatedAtBefore = conversation.getUpdatedAt();
        String titleBefore = conversation.getTitle();

        // when
        conversation.softDelete();

        // then: deletedAt is now set to (approximately) now, and unrelated fields are untouched
        assertThat(conversation.getDeletedAt()).isNotNull();
        assertThat(conversation.getDeletedAt()).isAfterOrEqualTo(beforeCall);
        assertThat(conversation.getUserId()).isEqualTo(userId);
        assertThat(conversation.getModel()).isEqualTo("upstage/solar-pro4");
        assertThat(conversation.isRagEnabled()).isFalse();
        // then: pins softDelete()'s own [Caution] -- "does not touch updatedAt" -- which had no
        //   assertion anywhere before this (T8 review round 1 correctness Low-4); createdAt/title
        //   have no setter this method could reach either, but were likewise unasserted
        assertThat(conversation.getCreatedAt()).isEqualTo(createdAtBefore);
        assertThat(conversation.getUpdatedAt()).isEqualTo(updatedAtBefore);
        assertThat(conversation.getTitle()).isEqualTo(titleBefore);
    }

    @Test
    @DisplayName("updateRagEnabled 호출 시 ragEnabled 값이 그대로 반영된다 (PATCH 엔드포인트 자체를 검증하는 "
            + "고유 AC 번호가 없음 -- chat Spec §5 A-11/D-6 계약, 관련 AC 없음)")
    void updateRagEnabledReflectsNewValue() {
        // given: a conversation created with ragEnabled=false
        Conversation conversation = new Conversation(UUID.randomUUID(), "upstage/solar-pro4", false);
        OffsetDateTime createdAtBefore = conversation.getCreatedAt();
        OffsetDateTime updatedAtBefore = conversation.getUpdatedAt();
        String titleBefore = conversation.getTitle();

        // when: flipped to true
        conversation.updateRagEnabled(true);

        // then
        assertThat(conversation.isRagEnabled()).isTrue();

        // when: flipped back to false
        conversation.updateRagEnabled(false);

        // then
        assertThat(conversation.isRagEnabled()).isFalse();

        // then: pins updateRagEnabled()'s own [Caution] -- "does not touch updatedAt, same
        //   reasoning as softDelete()" -- which had no assertion anywhere before this (T8 review
        //   round 1 correctness Low-4)
        assertThat(conversation.getCreatedAt()).isEqualTo(createdAtBefore);
        assertThat(conversation.getUpdatedAt()).isEqualTo(updatedAtBefore);
        assertThat(conversation.getTitle()).isEqualTo(titleBefore);
    }
}
