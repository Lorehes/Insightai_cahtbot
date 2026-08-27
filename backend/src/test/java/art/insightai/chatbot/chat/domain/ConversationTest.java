package art.insightai.chatbot.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Verifies {@link Conversation}'s construction behavior. No Spring context or
 *   database is involved; this is pure object behavior.
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
}
