package art.insightai.chatbot.chat.repository;

import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link MessageChatMemoryRepository}'s own mapping/windowing/no-op contract in
 *   isolation (Mockito, no Spring context, no DB) — chat Spec Tech Review A-4, T10.
 * [Rationale] These tests support chat:AC-30 and chat:AC-31 but are not themselves that evidence:
 *   AC-30's actual "LLM에 전달된 프롬프트에 1번째 턴이 포함된다" claim needs a captured {@code Prompt},
 *   which is {@code ChatServiceTest#secondMessageInSameConversationIncludesFirstTurnInThePrompt}'s
 *   job (this class only proves the repository half that test's mocked history return value would,
 *   in production, actually come from); AC-31's "이력 테이블이 존재하지 않는다" claim is a DB schema
 *   query no unit test can make. What this class DOES prove, deterministically and in isolation: the
 *   role-to-message-type mapping is correct, the "최근 N건" windowing trims from the *old* end (not
 *   the new one), and {@code saveAll}/{@code deleteByConversationId} truly touch neither
 *   collaborator — the precondition Tech Review A-4's no-op reasoning (see {@link
 *   MessageChatMemoryRepository}'s own class-level [Rationale]) depends on.
 */
@ExtendWith(MockitoExtension.class)
class MessageChatMemoryRepositoryTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Test
    @DisplayName("(무태그 — chat:AC-30 을 지지하는 하위 계층 테스트, 직접 근거는 "
            + "ChatServiceTest#secondMessageInSameConversationIncludesFirstTurnInThePrompt) "
            + "findByConversationId 는 messages 행을 순서 그대로 UserMessage/AssistantMessage 로 매핑한다")
    void mapsMessageRowsToUserAndAssistantMessagesPreservingOrder() {
        // given: three rows, already in the created_at-ascending order
        //   findByConversationIdOrderByCreatedAtAsc contractually returns -- this class trusts that
        //   ordering rather than re-sorting it (see class [Rationale] on MessageRepository reuse)
        MessageChatMemoryRepository repository = new MessageChatMemoryRepository(
                conversationRepository, messageRepository, TestAppPropertiesFactory.valid());
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID)).thenReturn(List.of(
                userRow("1번째 질문"),
                assistantRow("1번째 답"),
                userRow("2번째 질문")));

        // when
        List<org.springframework.ai.chat.messages.Message> history =
                repository.findByConversationId(CONVERSATION_ID.toString());

        // then
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isInstanceOf(UserMessage.class);
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(history.get(2)).isInstanceOf(UserMessage.class);
        assertThat(history).extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("1번째 질문", "1번째 답", "2번째 질문");
    }

    @Test
    @DisplayName("(무태그 — Tech Review A-5 윈도우 크기 적용 자체를 검증. AC-30 원문은 \"1번째 턴 포함\"만 "
            + "요구해 이 트리밍 동작 자체에는 대응 AC가 없다) 전체 행 수가 max-messages 를 넘으면 오래된 "
            + "쪽부터 잘라 가장 최근 N건만 오름차순으로 반환한다")
    void windowsToOnlyTheMostRecentMaxMessagesRowsOldestFirst() {
        // given: 5 rows but a window of 2 -- MessageWindowChatMemory#get() applies no window of
        //   its own (confirmed by reading spring-ai-model-2.0.1-sources.jar directly -- see class
        //   [Rationale]), so this repository must be the one place that trims
        AppProperties twoMessageWindow = TestAppPropertiesFactory.builder()
                .chat(new AppProperties.Chat(new AppProperties.Chat.Memory(2), java.time.Duration.ofMinutes(5)))
                .build();
        MessageChatMemoryRepository repository = new MessageChatMemoryRepository(
                conversationRepository, messageRepository, twoMessageWindow);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID)).thenReturn(List.of(
                userRow("m1"), assistantRow("m2"), userRow("m3"), assistantRow("m4"), userRow("m5")));

        // when
        List<org.springframework.ai.chat.messages.Message> history =
                repository.findByConversationId(CONVERSATION_ID.toString());

        // then: only the last 2 of the 5 rows survive, still oldest-of-the-kept-window first
        assertThat(history).extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("m4", "m5");
    }

    @Test
    @DisplayName("(무태그 — 방어적 구현 검증. Message.ROLE_USER/ROLE_ASSISTANT 외 값은 오늘 시점에 "
            + "어디서도 기록되지 않아 도달 불가능하지만, 조용히 잘못 매핑하는 대신 즉시 실패해야 한다는 "
            + "MessageChatMemoryRepository 자신의 [Caution] 주석을 고정한다)")
    void findByConversationIdThrowsForAnUnexpectedRoleValue() {
        // given
        MessageChatMemoryRepository repository = new MessageChatMemoryRepository(
                conversationRepository, messageRepository, TestAppPropertiesFactory.valid());
        Message rowWithUnexpectedRole =
                new Message(CONVERSATION_ID, "system", "무시된 역할", null, null, null, null, null, null);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(rowWithUnexpectedRole));

        // when / then
        assertThatThrownBy(() -> repository.findByConversationId(CONVERSATION_ID.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system");
    }

    @Test
    @DisplayName("(무태그 — Tech Review A-4 table 그대로) findConversationIds 는 "
            + "conversations 의 소프트 삭제되지 않은 id 를 문자열로 변환해 반환한다")
    void findConversationIdsDelegatesToConversationRepositoryAndMapsToStrings() {
        // given
        MessageChatMemoryRepository repository = new MessageChatMemoryRepository(
                conversationRepository, messageRepository, TestAppPropertiesFactory.valid());
        UUID otherConversationId = UUID.randomUUID();
        when(conversationRepository.findIdsByDeletedAtIsNull())
                .thenReturn(List.of(CONVERSATION_ID, otherConversationId));

        // when
        List<String> conversationIds = repository.findConversationIds();

        // then
        assertThat(conversationIds).containsExactlyInAnyOrder(
                CONVERSATION_ID.toString(), otherConversationId.toString());
    }

    @Test
    @DisplayName("(무태그 — Tech Review A-4 no-op 근거 1·2·3 의 전제 조건 고정) saveAll 은 "
            + "messageRepository/conversationRepository 어느 쪽도 호출하지 않는다")
    void saveAllTouchesNeitherRepository() {
        // given
        MessageChatMemoryRepository repository = new MessageChatMemoryRepository(
                conversationRepository, messageRepository, TestAppPropertiesFactory.valid());

        // when: a non-empty list, to prove the no-op holds even with realistic input
        repository.saveAll(CONVERSATION_ID.toString(), List.of(new UserMessage("무시되어야 하는 메시지")));

        // then
        verifyNoInteractions(messageRepository, conversationRepository);
    }

    @Test
    @DisplayName("(무태그 — Tech Review A-3 위임 경계 고정) deleteByConversationId 는 "
            + "messageRepository/conversationRepository 어느 쪽도 호출하지 않는다 "
            + "(삭제는 ConversationService 의 소프트 삭제 소관)")
    void deleteByConversationIdTouchesNeitherRepository() {
        // given
        MessageChatMemoryRepository repository = new MessageChatMemoryRepository(
                conversationRepository, messageRepository, TestAppPropertiesFactory.valid());

        // when
        repository.deleteByConversationId(CONVERSATION_ID.toString());

        // then
        verifyNoInteractions(messageRepository, conversationRepository);
    }

    private static Message userRow(String content) {
        return new Message(CONVERSATION_ID, Message.ROLE_USER, content, null, null, null, null, null, null);
    }

    private static Message assistantRow(String content) {
        return new Message(
                CONVERSATION_ID, Message.ROLE_ASSISTANT, content, "upstage/solar-pro4", null, null, null, null,
                "stop");
    }
}
