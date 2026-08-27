package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.ConversationNotFoundException;
import art.insightai.chatbot.chat.domain.InvalidModelException;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies T7's additions to {@link ConversationService}: {@code createConversation}'s
 *   model whitelist check and {@code prepareMessageSend}'s ownership + model whitelist guard
 *   (chat Spec §5 A-8, A-11, T7), with {@link ConversationRepository}/{@link MessageRepository}
 *   mocked. Integration-level coverage of the full HTTP path is dev-qa-tester's scope.
 * [Rationale] {@link TestAppPropertiesFactory#validModels()}'s three-entry catalog
 *   ({@code upstage/solar-pro4}, {@code openai/gpt-5-mini}, {@code anthropic/claude-haiku-4.5})
 *   backs every case here so a nonexistent id (e.g. {@code "not-a-real-model"}) is unambiguously
 *   outside the catalog.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final AppProperties APP_PROPERTIES = TestAppPropertiesFactory.valid();

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        // Mockito injects @Mock fields via reflection after construction but before each test
        // method, so `service` must be built here, not as an instance field initializer (which
        // would run during construction, before the mocks exist, and NPE on first use).
        service = new ConversationService(conversationRepository, messageRepository, APP_PROPERTIES);
    }

    @Test
    @DisplayName("[chat:AC-22] 카탈로그에 없는 model로 POST /api/conversations 를 호출하면 "
            + "InvalidModelException 을 던지고 conversations 행을 저장하지 않는다")
    void rejectsUnknownModelOnCreate() {
        // given
        UUID userId = UUID.randomUUID();
        when(conversationRepository.countByUserIdAndDeletedAtIsNull(userId)).thenReturn(0L);

        // when / then
        assertThatThrownBy(() -> service.createConversation(userId, "not-a-real-model", null))
                .isInstanceOf(InvalidModelException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    @DisplayName("카탈로그에 있는 model로 POST /api/conversations 를 호출하면 "
            + "그 model 그대로 저장한다 (관련은 있으나 AC-22가 아니라 AC-23 소관 -- 카탈로그 밖 id 거부가 "
            + "아니라 카탈로그 안 id의 정상 저장을 검증하므로 태그 없음, 2026-08-27 chat T7 리뷰 correctness High-1 재작업)")
    void acceptsCatalogModelOnCreate() {
        // given
        UUID userId = UUID.randomUUID();
        when(conversationRepository.countByUserIdAndDeletedAtIsNull(userId)).thenReturn(0L);
        Conversation saved = mock(Conversation.class);
        when(conversationRepository.save(any())).thenReturn(saved);

        // when
        Conversation result = service.createConversation(userId, "openai/gpt-5-mini", null);

        // then
        assertThat(result).isEqualTo(saved);
        verify(conversationRepository).save(any());
    }

    @Test
    @DisplayName("[chat:AC-17] 대화가 세션 소유가 아니면 prepareMessageSend 가 "
            + "ConversationNotFoundException 을 던진다")
    void rejectsMessageSendForUnownedConversation() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.prepareMessageSend(conversationId, userId, null))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("[chat:AC-22] 소유한 대화라도 카탈로그에 없는 model로 메시지를 보내면 "
            + "InvalidModelException 을 던진다")
    void rejectsUnknownModelOnMessageSend() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Conversation conversation = mock(Conversation.class);
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.of(conversation));

        // when / then
        assertThatThrownBy(() -> service.prepareMessageSend(conversationId, userId, "not-a-real-model"))
                .isInstanceOf(InvalidModelException.class);
    }

    @Test
    @DisplayName("소유한 대화이고 model 을 생략하면 prepareMessageSend 가 "
            + "그 대화를 그대로 반환한다 (관련은 있으나 AC-17이 아님 -- AC-17은 세션 B가 세션 A 대화에 접근할 때의 404를 "
            + "요구하는데 이 테스트는 그 반대인 소유자 정상 조회를 검증하므로 태그 없음, "
            + "2026-08-27 chat T7 리뷰 correctness High-1 재작업)")
    void returnsOwnedConversationWhenModelOmitted() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Conversation conversation = mock(Conversation.class);
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.of(conversation));

        // when
        Conversation result = service.prepareMessageSend(conversationId, userId, null);

        // then
        assertThat(result).isEqualTo(conversation);
    }
}
