package art.insightai.chatbot.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Verifies {@link Message}'s construction behavior for both shapes A-4 describes: the
 *   'user' row (most fields unknown yet) and the 'assistant' row (all fields known). No Spring
 *   context or database is involved; this is pure object behavior.
 */
class MessageTest {

    @Test
    @DisplayName("user 역할 메시지는 conversationId·role·content·createdAt만 채워지고 나머지는 null이다")
    void userRoleMessageLeavesUsageFieldsNull() {
        // given: a conversation id and the user's raw text
        UUID conversationId = UUID.randomUUID();

        // when: constructing the 'user' row as ChatService does before calling the LLM (A-4 step ①)
        Message message = new Message(conversationId, "user", "안녕하세요", null, null, null, null, null, null);

        // then: id waits for the DB identity column, createdAt is stamped, and every field
        //   unknown at this point in the flow is null
        assertThat(message.getId()).isNull();
        assertThat(message.getConversationId()).isEqualTo(conversationId);
        assertThat(message.getRole()).isEqualTo("user");
        assertThat(message.getContent()).isEqualTo("안녕하세요");
        assertThat(message.getCreatedAt()).isNotNull();
        assertThat(message.getModel()).isNull();
        assertThat(message.getPromptTokens()).isNull();
        assertThat(message.getCompletionTokens()).isNull();
        assertThat(message.getCostUsd()).isNull();
        assertThat(message.getSources()).isNull();
        assertThat(message.getFinishReason()).isNull();
    }

    @Test
    @DisplayName("assistant 역할 메시지는 모델·토큰·비용·finishReason이 함께 채워진다")
    void assistantRoleMessageCarriesUsageFields() {
        // given: a conversation id and the full set of usage data known only after the stream ends
        UUID conversationId = UUID.randomUUID();
        BigDecimal cost = new BigDecimal("0.00042");

        // when: constructing the 'assistant' row as ChatService does after the stream completes
        //   (A-4 step ④)
        Message message = new Message(conversationId, "assistant", "안녕하세요, 무엇을 도와드릴까요?",
                "upstage/solar-pro4", 12, 34, cost, "[]", "stop");

        // then: every field is set as passed, and cost_usd is the exact BigDecimal given (no
        //   floating-point rounding, per global CLAUDE.md §7.2-4)
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getModel()).isEqualTo("upstage/solar-pro4");
        assertThat(message.getPromptTokens()).isEqualTo(12);
        assertThat(message.getCompletionTokens()).isEqualTo(34);
        assertThat(message.getCostUsd()).isEqualByComparingTo(cost);
        assertThat(message.getSources()).isEqualTo("[]");
        assertThat(message.getFinishReason()).isEqualTo("stop");
    }
}
