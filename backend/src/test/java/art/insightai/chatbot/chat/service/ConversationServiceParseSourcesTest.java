package art.insightai.chatbot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;

/**
 * [Purpose] Verifies {@link ConversationService#parseSources}'s hybrid-rag T11 (BL-008) addition
 *   in isolation: parsing {@code messages.sources}' stored JSON text back into {@link
 *   ChatChunk.Source} entries (hybrid-rag:AC-12 read side) — with {@link ConversationRepository}/
 *   {@link MessageRepository} both mocked (unused by this method), no Spring context, no DB. This
 *   class does not exercise the full HTTP/DB round trip (that is {@code
 *   ConversationControllerIntegrationTest}'s scope, owned by dev-qa-tester per this project's role
 *   split) — it only pins {@link ConversationService#parseSources}'s three branches: a non-{@code
 *   null} JSON array round-trips to the identical {@link ChatChunk.Source} list, a {@code null}
 *   stored value stays {@code null} (never {@code []}), and malformed JSON falls back to {@code
 *   null} without throwing.
 * [Rationale] {@link ObjectMapper} is a real instance here, not a mock — every assertion below
 *   depends on actual JSON parsing behavior, which a mocked mapper (returning {@code null} for
 *   every unstubbed call) could not exercise meaningfully. Moved here (T11 재작업 1회차,
 *   maintainability Medium-1) from {@code
 *   art.insightai.chatbot.chat.api.ConversationControllerMessageResponseTest}, which tested the
 *   same three branches through {@code ConversationController#parseSources} before that method
 *   moved to {@link ConversationService} — see this class's own [Rationale] for why.
 */
class ConversationServiceParseSourcesTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConversationService conversationService = new ConversationService(
            conversationRepository, messageRepository, TestAppPropertiesFactory.valid(), objectMapper);

    @Test
    @DisplayName("[hybrid-rag:AC-12] messages.sources 에 저장된 JSON 배열을 파싱하면 ChatService 가 "
            + "직렬화했을 값과 동일한 ChatChunk.Source 목록이 된다")
    void parsesStoredSourcesJsonIntoIdenticalSourceList() {
        // given: the exact JSON text ChatService#serializeSources would have produced for one
        //   citation entry
        List<ChatChunk.Source> expected = List.of(
                new ChatChunk.Source(UUID.randomUUID().toString(), "휴가정책.pdf", 0.42, "이 문서는...", 3));
        String storedJson = objectMapper.writeValueAsString(expected);
        Message assistantRow = messageWithSources("assistant", "연차는 15일입니다", storedJson);

        // when
        List<ChatChunk.Source> actual = conversationService.parseSources(assistantRow);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-12] messages.sources 가 null 인 행(user 턴 등)은 파싱 결과도 "
            + "null 이다 -- 빈 배열로 대체하지 않는다")
    void nullStoredSourcesStayNullNotEmptyArray() {
        // given: a 'user' row, which never has sources
        Message userRow = messageWithSources("user", "휴가는 며칠이야?", null);

        // when
        List<ChatChunk.Source> actual = conversationService.parseSources(userRow);

        // then
        assertThat(actual).isNull();
    }

    @Test
    @DisplayName("(무태그 -- 방어적 폴백 가드, 대응 AC 없음) messages.sources 에 손상된 JSON 이 저장되어 "
            + "있어도 예외를 던지지 않고 null 로 대체한다")
    void malformedStoredSourcesJsonFallsBackToNullWithoutThrowing() {
        // given
        Message corruptRow = messageWithSources("assistant", "일부 답변", "{not-valid-json");

        // when / then: does not throw
        List<ChatChunk.Source> actual = conversationService.parseSources(corruptRow);

        assertThat(actual).isNull();
    }

    private static Message messageWithSources(String role, String content, String sourcesJson) {
        return new Message(CONVERSATION_ID, role, content, null, null, null, null, sourcesJson, "stop");
    }
}
