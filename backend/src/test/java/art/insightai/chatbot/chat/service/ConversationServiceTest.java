package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.ConversationNotFoundException;
import art.insightai.chatbot.chat.domain.InvalidModelException;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.domain.MessageQuotaExceededException;
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

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link ConversationService}'s guard/whitelist logic: {@code
 *   createConversation}'s model whitelist check (T7), {@code prepareMessageSend}'s ownership +
 *   model whitelist guard (T7), and {@code deleteConversation}/{@code updateRagEnabled}'s shared
 *   ownership guard plus their own field mutations (chat Spec §5 A-3, A-8, A-11, D-6, T7/T8),
 *   with {@link ConversationRepository}/{@link MessageRepository} mocked. Integration-level
 *   coverage of the full HTTP path is dev-qa-tester's scope.
 * [Rationale] {@link TestAppPropertiesFactory#validModels()}'s three-entry catalog
 *   ({@code upstage/solar-pro4}, {@code openai/gpt-5-mini}, {@code anthropic/claude-haiku-4.5})
 *   backs every case here so a nonexistent id (e.g. {@code "not-a-real-model"}) is unambiguously
 *   outside the catalog. The T8 cases below reuse {@code
 *   findByIdAndUserIdAndDeletedAtIsNull(...) -> Optional.empty()} as the mock setup for "not
 *   found" regardless of whether the [given] comment frames the scenario as cross-session
 *   (chat:AC-17) or already-soft-deleted (chat:AC-41) — {@link ConversationService}'s guard
 *   cannot and deliberately does not distinguish the two at this layer (see {@code
 *   ConversationNotFoundException}'s [Rationale]); which real-world cause applies is only
 *   distinguishable against a real database, which is dev-qa-tester's integration-test scope.
 * [Post-Pass cleanup, dev-qa-tester 2026-08-27, T9 round-2 review correctness L3] {@link
 *   #persistUserTurnAssignsTitleAndTouchesUpdatedAtWhenTitleUnset}/{@link
 *   #persistUserTurnSkipsTitleAssignmentWhenTitleAlreadySet}/{@link
 *   #persistAssistantTurnTouchesUpdatedAtAndSavesMessage} close a gap the round-2 review found:
 *   before this addition, no test anywhere in the suite asserted that {@link
 *   ConversationService#persistUserTurn}/{@link ConversationService#persistAssistantTurn} actually
 *   call {@link ConversationRepository#assignTitleIfAbsent}/{@link
 *   ConversationRepository#touchUpdatedAt} — {@code ChatServiceTest} only verifies {@code
 *   ChatService} delegates to these two methods, never what they do once called, and deleting
 *   their {@code assignTitleIfAbsent}/{@code touchUpdatedAt} lines left all 221 tests green. These
 *   three tests mock {@link ConversationRepository} directly (this class's existing style) so a
 *   missing call fails at the exact call site chat Tech Review A-12 names, independent of whatever
 *   {@code conversations.updated_at} ends up as after a full HTTP round trip (that broader,
 *   less-precise check is {@code ConversationControllerIntegrationTest}'s
 *   {@code persistsExactlyOneUserAndOneAssistantRowMatchingStreamedContent}, chat:AC-29). {@link
 *   #enforceMessageQuotaAllowsSendWhenUserRowCountIsBelowTheLimit}/{@link
 *   #enforceMessageQuotaThrowsWhenUserRowCountReachesTheLimit} add the same direct-mock coverage
 *   for {@link ConversationService#enforceMessageQuota} (chat:AC-45), whose real threshold logic
 *   was previously exercised only through {@code ChatServiceTest}'s mocked-{@code
 *   ConversationService} unit test and {@code ConversationControllerIntegrationTest}'s full HTTP
 *   round trip, neither of which pins down this method's own boundary (== limit still throws,
 *   one below does not) in isolation.
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

    @Test
    @DisplayName("[chat:AC-41] 소프트 삭제된 대화면 getMessages 가 ConversationNotFoundException 을 던진다 "
            + "(T6이 만든 메서드지만 AC-41은 T8 소관 -- 이 카드가 처음으로 단위 계층에서 확인한다)")
    void rejectsGetMessagesForSoftDeletedConversation() {
        // given: findByIdAndUserIdAndDeletedAtIsNull excludes soft-deleted rows by construction,
        //   so a soft-deleted id resolves to the same empty Optional as "not found"/"unowned"
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getMessages(conversationId, userId))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("[chat:AC-41] 소프트 삭제된 대화면 prepareMessageSend 가 ConversationNotFoundException 을 던진다 "
            + "(T7이 만든 메서드지만 AC-41은 T8 소관 -- 이 카드가 처음으로 단위 계층에서 확인한다)")
    void rejectsMessageSendForSoftDeletedConversation() {
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
    @DisplayName("[chat:AC-16] 소유한 대화에 deleteConversation 을 호출하면 소프트 삭제되어 저장되고 "
            + "물리 삭제는 호출되지 않는다")
    void softDeletesOwnedConversationWithoutPhysicalDelete() {
        // given: a real (non-mocked) Conversation so softDelete()'s actual field mutation is
        //   observable, not just a verified Mockito interaction
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Conversation conversation = new Conversation(userId, "upstage/solar-pro4", false);
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.deleteConversation(conversationId, userId);

        // then: deletedAt is now set and the mutated instance was handed to save(), never delete
        assertThat(conversation.getDeletedAt()).isNotNull();
        verify(conversationRepository).save(conversation);
        verify(conversationRepository, never()).delete(any());
        verify(conversationRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("[chat:AC-17] 대화가 세션 소유가 아니면 deleteConversation 이 "
            + "ConversationNotFoundException 을 던지고 save 를 호출하지 않는다")
    void rejectsDeleteForUnownedConversation() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.deleteConversation(conversationId, userId))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    @DisplayName("소유한 대화에 updateRagEnabled 를 호출하면 값이 갱신되어 저장된 대화를 반환한다 "
            + "(PATCH 엔드포인트 자체를 검증하는 고유 AC 번호가 없음 -- chat Spec §5 A-11/D-6 계약, 관련 AC 없음, "
            + "chat T7 리뷰 correctness High-1의 acceptsCatalogModelOnCreate 선례와 동일한 이유로 태그 없음)")
    void updatesRagEnabledOnOwnedConversation() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Conversation conversation = new Conversation(userId, "upstage/solar-pro4", false);
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Conversation result = service.updateRagEnabled(conversationId, userId, true);

        // then
        assertThat(result.isRagEnabled()).isTrue();
        verify(conversationRepository).save(conversation);
    }

    @Test
    @DisplayName("[chat:AC-41] 소프트 삭제된 대화면 updateRagEnabled 가 ConversationNotFoundException 을 "
            + "던지고 save 를 호출하지 않는다")
    void rejectsRagEnabledUpdateForSoftDeletedConversation() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId))
                .thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.updateRagEnabled(conversationId, userId, true))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    @DisplayName("[chat:AC-29] persistUserTurn 은 titleIfUnset 이 있으면 그 값으로 제목을 지정하고 "
            + "updated_at 을 갱신한 뒤 user 메시지를 저장한다 (T9 review round-2 correctness L3 -- 이전에는 "
            + "이 두 리포지토리 호출을 단언하는 테스트가 하나도 없어 ConversationService.java의 "
            + "assignTitleIfAbsent/touchUpdatedAt 두 줄을 지워도 221건 전부 통과했다)")
    void persistUserTurnAssignsTitleAndTouchesUpdatedAtWhenTitleUnset() {
        // given
        UUID conversationId = UUID.randomUUID();
        Message userMessage = new Message(
                conversationId, Message.ROLE_USER, "안녕", null, null, null, null, null, null);

        // when
        service.persistUserTurn("안녕", userMessage);

        // then
        verify(conversationRepository).assignTitleIfAbsent(conversationId, "안녕");
        verify(conversationRepository).touchUpdatedAt(eq(conversationId), any(OffsetDateTime.class));
        verify(messageRepository).save(userMessage);
    }

    @Test
    @DisplayName("[chat:AC-29] persistUserTurn 은 titleIfUnset 이 null 이면 제목 지정을 건너뛰고 "
            + "updated_at 갱신과 user 메시지 저장만 수행한다 (제목이 이미 있는, 두 번째 메시지 이후의 흔한 경우)")
    void persistUserTurnSkipsTitleAssignmentWhenTitleAlreadySet() {
        // given
        UUID conversationId = UUID.randomUUID();
        Message userMessage = new Message(
                conversationId, Message.ROLE_USER, "두 번째 메시지", null, null, null, null, null, null);

        // when
        service.persistUserTurn(null, userMessage);

        // then
        verify(conversationRepository, never()).assignTitleIfAbsent(any(), any());
        verify(conversationRepository).touchUpdatedAt(eq(conversationId), any(OffsetDateTime.class));
        verify(messageRepository).save(userMessage);
    }

    @Test
    @DisplayName("[chat:AC-29] persistAssistantTurn 은 updated_at 을 갱신하고 assistant 메시지를 "
            + "저장한다 (T9 review round-2 correctness L3)")
    void persistAssistantTurnTouchesUpdatedAtAndSavesMessage() {
        // given
        UUID conversationId = UUID.randomUUID();
        Message assistantMessage = new Message(conversationId, Message.ROLE_ASSISTANT, "응답",
                "upstage/solar-pro4", null, null, null, null, "stop");

        // when
        service.persistAssistantTurn(assistantMessage);

        // then
        verify(conversationRepository).touchUpdatedAt(eq(conversationId), any(OffsetDateTime.class));
        verify(messageRepository).save(assistantMessage);
    }

    @Test
    @DisplayName("[chat:AC-45] 대화의 user 메시지 행 수가 상한보다 하나 적으면 enforceMessageQuota 가 "
            + "예외를 던지지 않는다 (T9 review round-2 correctness L3 -- 이전에는 이 메서드 자신의 경계값을 "
            + "리포지토리 모킹으로 직접 고정해 확인하는 단위 테스트가 없었다)")
    void enforceMessageQuotaAllowsSendWhenUserRowCountIsBelowTheLimit() {
        // given
        UUID conversationId = UUID.randomUUID();
        long maxMessagesPerConversation = APP_PROPERTIES.session().maxMessagesPerConversation();
        when(messageRepository.countByConversationIdAndRole(conversationId, Message.ROLE_USER))
                .thenReturn(maxMessagesPerConversation - 1);

        // when / then
        assertThatCode(() -> service.enforceMessageQuota(conversationId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[chat:AC-45] 대화의 user 메시지 행 수가 상한에 정확히 도달하면 enforceMessageQuota 가 "
            + "MessageQuotaExceededException 을 던진다")
    void enforceMessageQuotaThrowsWhenUserRowCountReachesTheLimit() {
        // given
        UUID conversationId = UUID.randomUUID();
        long maxMessagesPerConversation = APP_PROPERTIES.session().maxMessagesPerConversation();
        when(messageRepository.countByConversationIdAndRole(conversationId, Message.ROLE_USER))
                .thenReturn(maxMessagesPerConversation);

        // when / then
        assertThatThrownBy(() -> service.enforceMessageQuota(conversationId))
                .isInstanceOf(MessageQuotaExceededException.class);
    }
}
