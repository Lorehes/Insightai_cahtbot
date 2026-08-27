package art.insightai.chatbot.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * [Purpose] Verifies conversation CRUD (chat Spec §5 A-11, T6) end-to-end through the real
 *   servlet filter chain: {@code POST /api/conversations} (chat:AC-13, AC-23, AC-43),
 *   {@code GET /api/conversations} (chat:AC-14) and {@code GET /api/conversations/{id}/messages}
 *   (chat:AC-15), plus one direct check of the ownership guard {@link
 *   art.insightai.chatbot.chat.service.ConversationService#createConversation} and its siblings
 *   share ({@code ConversationNotFoundException} -> 404 via {@code GlobalExceptionHandler}).
 * [Rationale] Follows {@code SessionControllerIntegrationTest}'s pattern: {@link
 *   AutoConfigureMockMvc} over {@link IntegrationTestBase} so the real {@code
 *   FilterRegistrationBean<AnonymousSessionFilter>} and {@code SessionContext} wiring run, and
 *   {@link JsonPath} instead of an autowired {@code ObjectMapper} bean (Spring Boot 4's default
 *   Jackson auto-configuration wires {@code tools.jackson.databind.ObjectMapper}, not the classic
 *   {@code com.fasterxml.jackson.databind.ObjectMapper} — see that class's Javadoc). "Session A"
 *   and "session B" (AC-13/14's wording) are two independent {@code GET /api/session} calls
 *   through the real filter, not two directly-inserted {@code users} rows — this exercises the
 *   actual cookie-to-userId resolution path a production request would take, not just the
 *   repository layer underneath it. {@code app.session.max-anonymous-users} is pinned high via
 *   {@link TestPropertySource}, mirroring {@code SessionControllerIntegrationTest}'s own
 *   reasoning, since this class also calls {@code GET /api/session} to mint sessions and the
 *   shared ParadeDB container's {@code users} table accumulates across this module's other
 *   integration test classes. AC-14's {@code updated_at} ordering and soft-delete exclusion, and
 *   AC-15's {@code created_at} ordering, are pinned deterministically by writing explicit
 *   timestamps directly via {@link JdbcTemplate} after the row exists (neither {@link Conversation}
 *   nor {@link Message} exposes a setter for those columns by design — see their own Javadocs) —
 *   relying on {@code OffsetDateTime.now()} call-order alone across two back-to-back saves would
 *   assert real behavior only by accident of clock resolution.
 * [Caution] Cleanup deletes {@code conversations} rows before {@code users} rows (the {@code
 *   conversations.user_id} FK has no {@code ON DELETE CASCADE}, unlike {@code
 *   messages.conversation_id} — see {@code V1__init.sql}), through {@link JdbcTemplate} directly
 *   rather than the repositories under test, mirroring {@code MessageRepositoryIntegrationTest}'s
 *   reasoning: a save/find bug under test must not also break teardown. The bonus 404 test at the
 *   bottom deliberately carries no {@code [chat:AC-17]} prefix — AC-17 covers three endpoints
 *   ({@code GET .../messages}, {@code DELETE}, {@code POST .../messages}) across T6 and T7, and
 *   only the first exists yet; marking AC-17 verified from one endpoint would misrepresent the
 *   evidence ledger. It exists only to confirm the ownership-guard mechanism this card built
 *   ({@code ConversationService#requireOwnedConversation}) behaves as designed, not to close AC-17.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.session.max-anonymous-users=100000")
class ConversationControllerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AppProperties appProperties;

    private final List<UUID> insertedConversationIds = new ArrayList<>();
    private final List<UUID> insertedUserIds = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedRows() {
        for (UUID conversationId : insertedConversationIds) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (UUID userId : insertedUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        insertedConversationIds.clear();
        insertedUserIds.clear();
    }

    @Test
    @DisplayName("[chat:AC-13] 세션 A로 POST /api/conversations 를 호출하면 생성된 행의 user_id가 "
            + "세션 A의 사용자 id와 같고 응답 본문에 그 대화 id가 실린다")
    void createsConversationOwnedByCallingSessionUser() throws Exception {
        // given
        Session sessionA = establishSession();

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isCreated())
                .andReturn();

        // then: the response body carries a conversation id
        UUID conversationId = readConversationId(result);
        insertedConversationIds.add(conversationId);
        assertThat(conversationId).isNotNull();

        // then: the persisted row's user_id equals session A's resolved user
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM conversations WHERE id = ?", UUID.class, conversationId);
        assertThat(ownerId).isEqualTo(sessionA.userId());

        // then: the response carries the full A-11 field set (T6 review 2026-08-27 Low-3),
        //   with title null (A-12 is T9's scope) and ragEnabled defaulted to false since no
        //   request body was sent
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body).containsKeys("id", "title", "model", "ragEnabled", "createdAt", "updatedAt");
        assertThat(body.get("title")).isNull();
        assertThat(body.get("ragEnabled")).isEqualTo(false);
        assertThat((String) body.get("createdAt")).isNotBlank();
        assertThat((String) body.get("updatedAt")).isNotBlank();
    }

    @Test
    @DisplayName("[chat:AC-14] 세션 A와 세션 B가 각각 대화를 만든 뒤 세션 A로 GET /api/conversations 를 호출하면 "
            + "세션 B의 대화 id는 결과에 없고 세션 A의 미삭제 대화만 updated_at 내림차순으로 반환된다")
    void listsOnlyOwnNonDeletedConversationsInUpdatedAtDescendingOrder() throws Exception {
        // given: session A owns THREE visible conversations (c1, c2, c3, saved in that order)
        //   plus one soft-deleted one; session B owns one conversation that must never appear in
        //   session A's list. created_at is pinned to increasing values matching insertion order
        //   (c1 < c2 < c3), while updated_at is pinned to the NON-MONOTONIC permutation c2 > c1 >
        //   c3. With three rows and a non-monotonic updated_at ordering, the expected result
        //   [c2, c1, c3] disagrees with every plausible wrong implementation simultaneously:
        //   created_at ASC would give [c1, c2, c3], created_at DESC [c3, c2, c1], insertion order
        //   [c1, c2, c3], insertion order reversed [c3, c2, c1] -- none of those equal [c2, c1,
        //   c3]. Two visible rows cannot rule this out (see chat:AC-14 review High-1, round 2:
        //   any 2-permutation always coincides with some column/direction combination, so a
        //   created_at-ASC or unordered-by-id implementation could still pass).
        Session sessionA = establishSession();
        Session sessionB = establishSession();

        Conversation c1 = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        Conversation c2 = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        Conversation c3 = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        Conversation softDeleted = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        Conversation othersConversation = saveConversation(sessionB.userId(), "upstage/solar-pro4", false);

        OffsetDateTime base = OffsetDateTime.now().minusMinutes(30);
        updateConversationCreatedAt(c1.getId(), base);
        updateConversationCreatedAt(c2.getId(), base.plusMinutes(1));
        updateConversationCreatedAt(c3.getId(), base.plusMinutes(2));
        updateConversationUpdatedAt(c1.getId(), base.plusMinutes(20));
        updateConversationUpdatedAt(c2.getId(), base.plusMinutes(25));
        updateConversationUpdatedAt(c3.getId(), base.plusMinutes(10));
        updateConversationUpdatedAt(softDeleted.getId(), base.plusMinutes(30));
        softDeleteConversation(softDeleted.getId());

        // when
        MvcResult result = mockMvc.perform(get("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isOk())
                .andReturn();

        // then: exactly the three non-deleted, own conversations in updated_at DESC order
        //   [c2, c1, c3] -- see the [given] comment above for why this permutation is the only
        //   one consistent with updated_at DESC and no other plausible ordering
        List<Map<String, Object>> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        List<String> ids = body.stream().map(entry -> (String) entry.get("id")).toList();
        assertThat(ids).containsExactly(
                c2.getId().toString(), c1.getId().toString(), c3.getId().toString());
        assertThat(ids).doesNotContain(
                softDeleted.getId().toString(), othersConversation.getId().toString());
    }

    @Test
    @DisplayName("[chat:AC-15] GET /api/conversations/{id}/messages 는 메시지를 created_at 오름차순으로 반환하고 "
            + "각 항목이 role·content·createdAt을 포함한다")
    void returnsMessagesInCreatedAtAscendingOrderWithExpectedFields() throws Exception {
        // given: three messages persisted out of chronological order, then pinned to explicit,
        //   distinct created_at values via direct JDBC (Message has no setter for it -- by design)
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);

        Message thirdInserted = saveMessage(conversation.getId(), "assistant", "third reply");
        Message firstInserted = saveMessage(conversation.getId(), "user", "first question");
        Message secondInserted = saveMessage(conversation.getId(), "assistant", "second reply");

        OffsetDateTime base = OffsetDateTime.now().minusMinutes(30);
        updateMessageCreatedAt(firstInserted.getId(), base);
        updateMessageCreatedAt(secondInserted.getId(), base.plusMinutes(1));
        updateMessageCreatedAt(thirdInserted.getId(), base.plusMinutes(2));

        // when
        MvcResult result = mockMvc.perform(get("/api/conversations/{id}/messages", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isOk())
                .andReturn();

        // then: created_at ascending order, regardless of insertion/id order
        List<Map<String, Object>> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        List<String> contents = body.stream().map(entry -> (String) entry.get("content")).toList();
        assertThat(contents).containsExactly("first question", "second reply", "third reply");

        // then: every entry carries role, content and createdAt
        assertThat(body).allSatisfy(entry -> {
            assertThat(entry).containsKeys("role", "content", "createdAt");
            assertThat((String) entry.get("role")).isNotBlank();
            assertThat((String) entry.get("createdAt")).isNotBlank();
        });
    }

    @Test
    @DisplayName("[chat:AC-23] model을 지정해 대화를 생성하면 그 값이 conversations.model에 저장된다")
    void storesSpecifiedModelWhenProvided() throws Exception {
        // given: pick a catalog entry that is NOT the default id at runtime (T6 review
        //   2026-08-27 Low-4) instead of a hardcoded literal, so this test still proves the
        //   request value overrides the default even if application.yaml's catalog changes
        Session sessionA = establishSession();
        String defaultModel = appProperties.models().defaultId();
        String specifiedModel = appProperties.models().items().stream()
                .map(AppProperties.Models.ModelEntry::id)
                .filter(id -> !id.equals(defaultModel))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "app.models.items must contain at least one non-default entry for this test"));

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"" + specifiedModel + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        // then
        UUID conversationId = readConversationId(result);
        insertedConversationIds.add(conversationId);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.model"))
                .isEqualTo(specifiedModel);
        String storedModel = jdbcTemplate.queryForObject(
                "SELECT model FROM conversations WHERE id = ?", String.class, conversationId);
        assertThat(storedModel).isEqualTo(specifiedModel);
    }

    @Test
    @DisplayName("[chat:AC-23] model을 지정하지 않고 대화를 생성하면 AC-21의 기본 모델이 conversations.model에 저장된다")
    void storesDefaultModelWhenNotProvided() throws Exception {
        // given
        Session sessionA = establishSession();
        String expectedDefault = appProperties.models().defaultId();

        // when: no request body at all -- ConversationController's @RequestBody(required = false)
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isCreated())
                .andReturn();

        // then
        UUID conversationId = readConversationId(result);
        insertedConversationIds.add(conversationId);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.model"))
                .isEqualTo(expectedDefault);
        String storedModel = jdbcTemplate.queryForObject(
                "SELECT model FROM conversations WHERE id = ?", String.class, conversationId);
        assertThat(storedModel).isEqualTo(expectedDefault);
    }

    @Test
    @DisplayName("타인 소유 대화에 GET .../messages 를 호출하면 404를 반환한다 "
            + "(T6이 만든 소유권 가드 메커니즘 확인 -- AC-17 전체 검증은 T7 소관)")
    void returnsNotFoundForMessagesOfAnotherSessionsConversation() throws Exception {
        // given
        Session sessionA = establishSession();
        Session sessionB = establishSession();
        Conversation othersConversation = saveConversation(sessionB.userId(), "upstage/solar-pro4", false);

        // when / then
        mockMvc.perform(get("/api/conversations/{id}/messages", othersConversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("model이 64자를 초과하면 POST /api/conversations 는 400을 반환한다 "
            + "(T6 리뷰 2026-08-27 Medium-1 확인 -- 관련 AC 없음, 원장 갱신 대상 아님)")
    void rejectsCreationWhenModelExceedsMaxLength() throws Exception {
        // given
        Session sessionA = establishSession();
        String tooLongModel = "a".repeat(65);

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"" + tooLongModel + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        // then: RFC 9457 ProblemDetail body carries status 400
        Integer problemStatus = JsonPath.read(result.getResponse().getContentAsString(), "$.status");
        assertThat(problemStatus).isEqualTo(400);
    }

    @Test
    @DisplayName("[chat:AC-43] 사용자의 미삭제 대화 수가 app.session.max-conversations-per-user 상한에 도달하면 "
            + "그 다음 POST /api/conversations 는 403을 반환하고 conversations 행이 생성되지 않는다")
    void rejectsCreationWhenConversationQuotaReached() throws Exception {
        // given: seed exactly the configured limit directly through the repository (bypassing
        //   HTTP) so this test's runtime does not depend on repeating the real endpoint that
        //   number of times, and stays correct if app.session.max-conversations-per-user changes
        Session sessionA = establishSession();
        int maxConversationsPerUser = appProperties.session().maxConversationsPerUser();
        for (int i = 0; i < maxConversationsPerUser; i++) {
            saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        }

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isForbidden())
                .andReturn();

        // then: RFC 9457 ProblemDetail body carries status 403, and no new row was inserted
        Integer problemStatus = JsonPath.read(result.getResponse().getContentAsString(), "$.status");
        assertThat(problemStatus).isEqualTo(403);
        Long conversationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversations WHERE user_id = ? AND deleted_at IS NULL",
                Long.class, sessionA.userId());
        assertThat(conversationCount).isEqualTo(maxConversationsPerUser);
    }

    /**
     * [Purpose] Persists a {@link Conversation} directly through {@link ConversationRepository}
     *   (bypassing HTTP) so setup for AC-14/AC-15/the ownership test does not depend on {@code
     *   POST /api/conversations}' own behavior under test elsewhere in this class, and tracks the
     *   id for {@link #cleanUpInsertedRows()}.
     */
    private Conversation saveConversation(UUID userId, String model, boolean ragEnabled) {
        Conversation conversation = conversationRepository.save(new Conversation(userId, model, ragEnabled));
        insertedConversationIds.add(conversation.getId());
        return conversation;
    }

    /**
     * [Purpose] Persists a {@link Message} directly through {@link MessageRepository}; cleanup is
     *   handled by the owning conversation's {@code ON DELETE CASCADE} (see class-level
     *   [Caution]), so no separate tracking list is needed for message ids.
     */
    private Message saveMessage(UUID conversationId, String role, String content) {
        return messageRepository.save(
                new Message(conversationId, role, content, null, null, null, null, null, null));
    }

    private void updateConversationUpdatedAt(UUID conversationId, OffsetDateTime updatedAt) {
        jdbcTemplate.update("UPDATE conversations SET updated_at = ? WHERE id = ?", updatedAt, conversationId);
    }

    /**
     * [Purpose] Pins {@code created_at} directly via JDBC ({@link Conversation} has no setter for
     *   it -- by design, see that class's [Rationale]) so AC-14's fixture can make insertion
     *   order, {@code created_at} order and {@code updated_at} order disagree deterministically.
     */
    private void updateConversationCreatedAt(UUID conversationId, OffsetDateTime createdAt) {
        jdbcTemplate.update("UPDATE conversations SET created_at = ? WHERE id = ?", createdAt, conversationId);
    }

    private void softDeleteConversation(UUID conversationId) {
        jdbcTemplate.update("UPDATE conversations SET deleted_at = now() WHERE id = ?", conversationId);
    }

    private void updateMessageCreatedAt(Long messageId, OffsetDateTime createdAt) {
        jdbcTemplate.update("UPDATE messages SET created_at = ? WHERE id = ?", createdAt, messageId);
    }

    /**
     * [Purpose] Extracts {@code $.id} from a {@code POST /api/conversations} response body.
     */
    private UUID readConversationId(MvcResult result) throws Exception {
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return UUID.fromString(id);
    }

    /**
     * [Purpose] Mints one anonymous session via a real, cookie-less {@code GET /api/session} call
     *   (mirrors {@code SessionControllerIntegrationTest#issuesNewSessionAndCreatesUserRowWhenNoCookiePresent}),
     *   tracking the created {@code users} row for {@link #cleanUpInsertedRows()}.
     */
    private Session establishSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/session")).andReturn();
        String userIdString = JsonPath.read(result.getResponse().getContentAsString(), "$.userId");
        UUID userId = UUID.fromString(userIdString);
        insertedUserIds.add(userId);
        String cookieValue = extractCookieValue(result.getResponse().getHeader("Set-Cookie"));
        return new Session(userId, cookieValue);
    }

    private static String extractCookieValue(String setCookieHeader) {
        String firstAttribute = setCookieHeader.split(";", 2)[0];
        return firstAttribute.substring(firstAttribute.indexOf('=') + 1);
    }

    /** [Purpose] One minted anonymous session: its resolved user id and its {@code chatbot_sid} cookie value. */
    private record Session(UUID userId, String cookieValue) {}
}
