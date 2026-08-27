package art.insightai.chatbot.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.user.User;
import art.insightai.chatbot.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Verifies {@link Message} actually round-trips through the live ParadeDB container --
 *   review follow_up Low-1 flagged that no test exercised {@code sources}' {@code jsonb} column
 *   against a real INSERT, which is exactly the gap that hid the VARCHAR-vs-jsonb binding bug
 *   fixed alongside this test (see {@link Message}'s class-level [Rationale]).
 * [Rationale] {@code messages.conversation_id} and {@code conversations.user_id} are both
 *   {@code NOT NULL} foreign keys (V1__init.sql), so each test first persists a {@link User} and
 *   a {@link Conversation} through their own repositories to satisfy those constraints -- this
 *   mirrors production insert order (a message never exists without its conversation).
 *   {@link Message#id} uses {@code GenerationType.IDENTITY}, which Hibernate cannot batch: the
 *   INSERT this test targets runs synchronously inside {@link MessageRepository#save} (each
 *   Spring Data repository method call is its own transaction, committed by the time control
 *   returns here), so no explicit flush is needed -- an explicit {@code entityManager.flush()}
 *   call from this test method would in fact throw {@code TransactionRequiredException}, since no
 *   transaction is active once {@code save} returns. {@link EntityManager#clear()} afterward
 *   detaches the persistence context before re-reading via {@code findById}, so the assertions
 *   exercise a genuine round trip through JDBC (read from the DB row, not the first-level cache)
 *   rather than merely returning the same in-memory instance that was just written.
 * [Caution] Cleanup deletes the conversation row directly (its {@code ON DELETE CASCADE} removes
 *   the message rows too) before the user row, mirroring {@code ChunkRepositoryIntegrationTest}'s
 *   independent-cleanup approach: cleanup goes through {@link JdbcTemplate}, not the repositories
 *   under test, so a save/find bug cannot also break teardown and leak rows into other cards'
 *   sessions against the same shared {@code infra} container.
 */
class MessageRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID insertedConversationId;
    private UUID insertedUserId;

    @AfterEach
    void cleanUpInsertedRows() {
        if (insertedConversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", insertedConversationId);
        }
        if (insertedUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", insertedUserId);
        }
        insertedConversationId = null;
        insertedUserId = null;
    }

    @Test
    @DisplayName("sources가 빈 JSON 배열('[]')인 메시지를 저장하고 재조회하면 값이 그대로다")
    void roundTripsEmptyJsonArraySources() {
        // given: a conversation to attach the message to, and an assistant row whose sources
        //   column holds the empty-array literal ChatService writes when no RAG chunks were used
        UUID conversationId = createUserAndConversation();
        Message message = new Message(conversationId, "assistant", "안녕하세요, 무엇을 도와드릴까요?",
                "upstage/solar-pro4", 12, 34, new BigDecimal("0.00042"), "[]", "stop");

        // when: saved (IDENTITY generation forces the INSERT to run synchronously here -- this is
        //   the exact statement the fixed bug used to reject) and re-read from a cleared context
        Message saved = messageRepository.save(message);
        entityManager.clear();
        Message reloaded = messageRepository.findById(saved.getId()).orElseThrow();

        // then: the round-tripped row carries the same JSON literal back
        assertThat(reloaded.getSources()).isEqualTo("[]");
        assertThat(reloaded.getContent()).isEqualTo("안녕하세요, 무엇을 도와드릴까요?");
    }

    @Test
    @DisplayName("sources가 항목을 포함한 JSON 배열인 메시지를 저장하고 재조회하면 값이 그대로다")
    void roundTripsNonEmptyJsonArraySources() {
        // given: an assistant row whose sources column holds a populated JSON array (structural
        //   shape only -- the actual hybrid-rag document reference schema belongs to that Spec)
        UUID conversationId = createUserAndConversation();
        String sourcesJson = "[{\"documentId\":\"11111111-1111-1111-1111-111111111111\"}]";
        Message message = new Message(conversationId, "assistant", "문서를 참고해 답변했습니다",
                "upstage/solar-pro4", 20, 15, new BigDecimal("0.00013"), sourcesJson, "stop");

        // when
        Message saved = messageRepository.save(message);
        entityManager.clear();
        Message reloaded = messageRepository.findById(saved.getId()).orElseThrow();

        // then: jsonb canonicalizes whitespace on the way in (Postgres re-serializes the value,
        //   e.g. adding a space after ':'), so this compares ignoring whitespace rather than the
        //   exact byte-for-byte literal -- the bug this test guards against was a rejected INSERT
        //   (a driver type error), not whitespace fidelity, which jsonb never promises anyway
        assertThat(reloaded.getSources()).isEqualToIgnoringWhitespace(sourcesJson);
    }

    /**
     * [Purpose] Persists the minimal {@link User} + {@link Conversation} chain a {@link Message}
     *   requires under the FK constraints, tracking both ids for {@link #cleanUpInsertedRows()}.
     */
    private UUID createUserAndConversation() {
        User user = userRepository.save(new User("anonymous", null, null));
        insertedUserId = user.getId();
        Conversation conversation = conversationRepository.save(
                new Conversation(user.getId(), "upstage/solar-pro4", false));
        insertedConversationId = conversation.getId();
        return conversation.getId();
    }
}
