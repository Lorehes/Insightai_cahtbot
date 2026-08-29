package art.insightai.chatbot.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.support.CapturingStatementInspector;
import art.insightai.chatbot.support.CapturingStatementInspectorConfig;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.support.StubChatModel;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 *   share ({@code ConversationNotFoundException} -> 404 via {@code GlobalExceptionHandler}). T7's
 *   additions (dev-qa-tester, 2026-08-27) cover {@code POST /api/conversations/{id}/messages}'
 *   entry guard: cross-session 404 with zero LLM calls (chat:AC-18) and the model whitelist 400
 *   on both the conversation-creation and message-send paths (chat:AC-22), asserting against the
 *   shared {@link StubChatModel} bean's {@code capturedPrompts()} for the "zero LLM calls" half of
 *   each claim rather than trusting the empty {@code Flux<ChatChunk>} response body alone. T8's
 *   additions (dev-qa-tester, 2026-08-27) cover {@code DELETE /api/conversations/{id}}'s
 *   soft-delete contract — {@code deleted_at} stamped, both the {@code conversations} row and its
 *   {@code messages} rows survive (chat:AC-16) — and {@code PATCH /api/conversations/{id}}'s 404
 *   for an already-soft-deleted conversation (chat:AC-41, joining the pre-existing GET/POST 404s
 *   below), plus one new test that finally closes chat:AC-17 end-to-end: all three of {@code GET
 *   .../messages}/{@code DELETE}/{@code POST .../messages} 404 for another session's conversation,
 *   with that conversation's row left completely untouched across all three attempts (see
 *   [Caution] below for how this reshapes the two pre-existing partial-coverage tests' tagging).
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
 *   ({@code GET .../messages}, {@code DELETE}, {@code POST .../messages}) across T6/T7/T8, and
 *   only the first two of those three exist as of T7 ({@code DELETE} is T8's scope); marking
 *   AC-17 verified from partial endpoint coverage would misrepresent the evidence ledger. The T7
 *   test below ({@code rejectsMessageSendForAnotherSessionsConversationWithoutCallingLlm}) follows
 *   this same precedent for the same reason: it carries only the {@code [chat:AC-18]} prefix, not
 *   {@code [chat:AC-17]}, even though it exercises the same ownership guard against the
 *   {@code POST .../messages} endpoint — dev-qa-tester (2026-08-27, T7) left AC-17 itself at ⬜
 *   pending T8's {@code DELETE}-endpoint case. AC-18's own text ("AC-17의 POST 호출에서 LLM 호출이
 *   발생하지 않는다") is a standalone, fully-testable claim about that one scenario regardless of
 *   AC-17's overall closure, so it is tagged and counted normally. T8 (dev-qa-tester, 2026-08-27)
 *   closes chat:AC-17 with {@code
 *   rejectsAllThreeEndpointsForAnotherSessionsConversationLeavingItsRowsUnchanged}, one test
 *   exercising all three endpoints against a single shared victim conversation end-to-end (T7
 *   review round 2, correctness Medium-1: a Spec-header exception for partial-coverage tags was
 *   the alternative considered and rejected in favor of this — one fully-covering test is simpler
 *   than a documented carve-out in the tagging rule). The two tests above stay deliberately
 *   untagged even now: they predate this closure, are not needed for the AC-17 ledger entry, and
 *   each still earns its keep for a narrower reason ({@code
 *   returnsNotFoundForMessagesOfAnotherSessionsConversation} is T6's original guard-mechanism
 *   probe; {@code rejectsMessageSendForAnotherSessionsConversationWithoutCallingLlm} is chat:AC-18's
 *   own dedicated evidence, LLM-call assertion included). The pre-existing {@code [chat:AC-17]}
 *   tag on {@code ConversationServiceTest#rejectsMessageSendForUnownedConversation} (a Mockito unit
 *   test of {@code prepareMessageSend}'s guard alone) is left in place, not removed: it is a true,
 *   if partial, positive for AC-17's {@code POST} clause specifically — unlike the AC-18/AC-22
 *   mistagging T7 review round 1 caught (High-1), it was never testing an unrelated claim — and
 *   multiple tagged tests contributing partial angles to one AC already is this Spec's norm (see
 *   e.g. chat:AC-9 across {@code AnonymousUserServiceTest}/{@code AnonymousSessionFilterTest}/
 *   {@code SessionControllerIntegrationTest}). Once this class's end-to-end test above closes
 *   AC-17 on its own, that unit test's tag stops being the self-contradiction T7 review round 2
 *   flagged (Medium-1: a passing tagged test with the ledger manually held at ⬜) because the
 *   ledger now genuinely is ✅. T8 review round 1 (dev-qa-tester, 2026-08-27) added two more
 *   tests for {@code PATCH}: {@code updatesRagEnabledForOwnConversationAndReturnsUpdatedFields}
 *   (success path, still untagged -- {@code ConversationTest#updateRagEnabledReflectsNewValue}
 *   already established there is no AC number for this endpoint's own contract, chat Spec §5
 *   A-11/D-6) and {@code rejectsPatchForAnotherSessionsNonDeletedConversationLeavingItUnchanged}
 *   (cross-session 404 against a live, not-yet-deleted conversation). At the time neither AC-17
 *   nor AC-41 covered that second case, so it shipped untagged per the T8 review round 1 report's
 *   D-2; the user approved a new {@code chat:AC-44} for exactly this scenario shortly after (AC-17
 *   left unrevised on purpose -- it had just closed end-to-end across T6/T7/T8, and appending a
 *   {@code PATCH} clause to it would have reopened that closure), so this test now carries the
 *   {@code [chat:AC-44]} prefix -- its logic is byte-for-byte unchanged from the untagged version,
 *   only the {@code @DisplayName} changed. {@code AC-41} still covers only the soft-deleted-target
 *   case ({@code deleted_at} not null forces 404 regardless of {@code userId}, so it cannot detect
 *   a broken ownership check); {@code AC-44} covers only the live, wrong-owner case -- the two do
 *   not overlap.
 *   The latter closes a real gap: the only {@code patch(...)} call that existed before targeted an
 *   already-soft-deleted conversation, where {@code deleted_at IS NOT NULL} alone already forces
 *   {@code findByIdAndUserIdAndDeletedAtIsNull} to return empty regardless of which {@code userId}
 *   the controller passes in, so that test alone could never catch a wrong {@code userId} (or a
 *   missing ownership check) reaching that query (T8 review round 1 correctness/security
 *   Medium-1) -- conversely, only the new success-path test can catch that same wrong-{@code
 *   userId} mutation turning the legitimate owner's own request into a false 404. Round 1 also
 *   widened {@code rejectsAllThreeEndpointsForAnotherSessionsConversationLeavingItsRowsUnchanged}'s
 *   final assertions from three named columns to a full row-for-row snapshot of both {@code
 *   conversations} and {@code messages} via {@link #fetchConversationRow}/{@link
 *   #fetchMessageRows} (correctness Low-3(a): the old three-column check could not have caught a
 *   mutation to e.g. {@code title}/{@code model}/{@code rag_enabled} or an existing message's
 *   {@code content}), and replaced four inline duplicates of {@link #fetchUpdatedAt}/{@link
 *   #fetchMessageCount}'s SQL in {@code
 *   rejectsMessageSendForAnotherSessionsConversationWithoutCallingLlm} with calls to those helpers
 *   (maintainability Low-5). Round 2 (dev-qa-tester, 2026-08-27) adds one more untagged test,
 *   {@code patchAndDeleteEachOmitTheOtherMutatorsColumnFromTheirUpdateSetClause}, closing round 2
 *   correctness Low-1: the {@code @DynamicUpdate} fix above had been verified only by a single
 *   manual {@code -Dlogging.level.org.hibernate.SQL=DEBUG} run, so removing the annotation still
 *   left the full 198/198 suite green. That test asserts directly on Hibernate's generated SQL via
 *   {@link art.insightai.chatbot.support.CapturingStatementInspector} instead of parsing log
 *   output (see that class's own Javadoc for how it is wired in). Round 2 also replaced the last
 *   inline duplicate of {@link #fetchMessageCount}'s SQL (round 2 maintainability Low-3), added
 *   {@link #fetchRagEnabled} and routed three inline duplicates of its SQL through it (round 2
 *   maintainability Low-4), and folded a redundant {@link #fetchDeletedAt} call into the
 *   already-fetched {@link #fetchConversationRow} snapshot above (round 2 performance Low-7).
 * [T9 additions, dev-qa-tester 2026-08-27] Seven new tests cover the actual streamed SSE body
 *   {@code ChatService} (T9) now produces for a successful {@code POST .../messages} call:
 *   {@code Content-Type}/JSON-object-per-line shape (chat:AC-24), leading-space preservation
 *   across chunk boundaries (chat:AC-25), UTF-8 byte-for-byte Korean fidelity (chat:AC-26),
 *   {@code conversationId} on every chunk (chat:AC-27), {@code finishReason} only on the last
 *   chunk (chat:AC-28), the persisted {@code messages} rows (chat:AC-29), and the mid-stream
 *   error path (chat:AC-34) — driven end-to-end through {@link #mockMvc} via the documented
 *   {@code request().asyncStarted()}/{@code asyncDispatch(...)} pattern (see {@link
 *   #performMessageSendAndAwaitStream}'s own Javadoc), not a hand-built {@code ChatClient} as
 *   {@code ChatServiceTest} (T9's own unit-level coverage) uses. As of this addition, all seven
 *   fail with the identical {@code jakarta.persistence.TransactionRequiredException} — {@code
 *   ChatService#recordUserMessage} calls {@link ConversationRepository}'s {@code @Modifying}
 *   bulk updates with no {@code @Transactional} boundary anywhere in the call chain (unlike every
 *   mutating {@code ConversationService} method, T6-T8's own established pattern) — a genuine
 *   implementation defect (not a test-authoring one) reported to dev-backend-engineer rather than
 *   fixed here (qa-tester does not modify production code); see chat Spec §4's AC-24/AC-29
 *   evidence cells and this card's dev-log entry for the full diagnosis. The AC-17/AC-18 rows
 *   above ({@link #rejectsAllThreeEndpointsForAnotherSessionsConversationLeavingItsRowsUnchanged},
 *   {@link #rejectsMessageSendForAnotherSessionsConversationWithoutCallingLlm}) were re-run
 *   unchanged and still pass — the entry guard rejects before ever reaching {@code ChatService},
 *   so this defect does not affect them; see chat Spec §4's AC-17/AC-18 evidence cells for why
 *   that re-run is exactly what closes out this card's "판별력 0" caveats from T7/T8.
 * [T9 round-2 rework additions, dev-qa-tester 2026-08-27] Review round 1 findings: correctness
 *   L1 narrowed the AC-24/AC-34 Content-Type assertions from {@code isCompatibleWith} (also
 *   accepts a {@code text} wildcard subtype or the fully-open wildcard media type) to {@code
 *   equalsTypeAndSubtype}; maintainability L11 fixed
 *   a stale {@code ChatController} reference in {@link #performMessageSendAndAwaitStream}'s
 *   Javadoc (that class no longer exists as of this card). Correctness L6 (a terminal signal
 *   arriving on a worker thread other than the request thread was never exercised by any test) is
 *   instead closed at {@code ChatServiceTest}'s level, not here: an early attempt at an
 *   HTTP-layer version of that test (scripting {@code StubChatModel} to deliver on {@code
 *   Schedulers.boundedElastic()}) proved that {@link #performMessageSendAndAwaitStream}'s MockMvc
 *   async-dispatch pattern does not reliably wait for a genuinely cross-thread {@code Flux}
 *   completion the way {@code Flux#block()} does -- the persisting write raced past this class's
 *   {@code @AfterEach} cleanup and corrupted sibling tests with a foreign-key violation on an
 *   already-deleted conversation. {@code ChatServiceTest} tests the same claim directly against
 *   the {@code Flux} with {@code .block()}, which does not have this gap. One new test was added
 *   here: {@code rejectsMessageOverMaxLengthWithoutCallingLlmOrPersisting}
 *   (security L8, a {@code @Size(max=4000)} regression lock that had no test before). Two more,
 *   deliberately untagged, close out D-4 (the user's decision that a per-message {@code model}
 *   override must actually reach the LLM call and the persisted {@code messages.model} column, not
 *   just pass validation): {@code sendMessageUsesAndPersistsTheRequestedModelOverride} and {@code
 *   sendMessageWithoutModelOverrideUsesConversationsStoredModel}, both reading {@link
 *   StubChatModel#capturedPrompts()}'s {@code Prompt.getOptions().getModel()} -- the model Spring
 *   AI's {@code ChatClient} actually merged into the outgoing {@link
 *   org.springframework.ai.chat.prompt.Prompt}, not a value this test asserts by trusting the
 *   response body alone. Finally, {@code rejectsMessageSendWhenConversationMessageQuotaReached}
 *   is the newly user-approved {@code chat:AC-45}'s HTTP/DB-level evidence (security Medium-3,
 *   user decision D-3① — a per-conversation message ceiling, T4's chat:AC-42/T6's chat:AC-43
 *   pattern one level down): 403, an INC-007 check that the response never echoes the backing
 *   {@code app.session.max-messages-per-conversation} config key, and zero new {@code messages}
 *   rows/LLM calls. {@code ChatServiceTest#propagatesMessageQuotaExceededWithoutCallingLlmOrPersistingAnything}
 *   is this AC's unit-level half.
 * [D-1 fix, dev-qa-tester 2026-08-28] {@code createsConversationOwnedByCallingSessionUser}'s
 *   {@code ragEnabled} assertion flipped from {@code false} to {@code true}: chat Spec §7 OQ-14
 *   (resolved 2026-08-25) always specified a default of {@code true} for an omitted {@code
 *   ragEnabled}, but {@code ConversationService#createConversation}'s fallback defaulted it to
 *   {@code false} until this fix, and this class's own assertion had been pinned to that {@code
 *   false} output since T6 review round 2 (Low-3) — locking the mismatch into the evidence
 *   ledger as "verified behavior" rather than catching it (surfaced as T8 review correctness
 *   Medium-2, resolved by user decision D-1; see {@code docs/harness/state.md} and {@code
 *   docs/review/2026-08-27_chat-T8.md}). New test {@code respectsExplicitRagEnabledFalseOnCreation}
 *   locks the other half of the same fallback expression (an explicit {@code false} must still be
 *   honored, not silently promoted to the new default) — deliberately untagged, since no AC's
 *   text covers {@code ragEnabled}'s default/explicit-value contract specifically (AC-13 only
 *   covers ownership, AC-23 only {@code model}); follows the same untagged-contract-test
 *   precedent as {@code updatesRagEnabledForOwnConversationAndReturnsUpdatedFields} above.
 * [T10 additions, dev-qa-tester 2026-08-28] Two new tests close the gap dev-backend-engineer's own
 *   T10 handoff explicitly flagged as unverified by its own unit-level coverage: {@code
 *   secondMessageInSameConversationPromptIncludesFirstTurnFromARealDbRoundTrip} (chat:AC-30) sends
 *   two real {@code POST .../messages} requests for the same conversation through this class's
 *   normal MockMvc/StubChatModel/live-ParadeDB stack -- unlike {@code
 *   ChatServiceTest#secondMessageInSameConversationIncludesFirstTurnInThePrompt}, {@code
 *   MessageChatMemoryRepository} is never mocked here, so the second call's history genuinely comes
 *   from a real {@code SELECT} against {@code messages} the first call's {@code
 *   performMessageSendAndAwaitStream} already committed -- and additionally asserts the second
 *   turn's own question appears in the outgoing {@link Prompt} exactly once, locking in {@code
 *   ChatService.streamReply}'s removal of a separate {@code .user(userMessage)} call (see that
 *   method's own [Rationale]) against a silent reintroduction that would double-send this turn's
 *   question to the LLM. {@code
 *   noChatMemoryHistoryTableExistsAfterMigrationStartupAndOneRealConversation} (chat:AC-31) is a
 *   pure regression lock, not a "currently broken" probe -- chat Spec Tech Review R-5's own
 *   correction already confirms {@code pom.xml} carries no {@code
 *   spring-ai-starter-model-chat-memory-repository-jdbc} dependency, so {@code
 *   JdbcChatMemoryRepository}'s autoconfiguration never triggers today; this test exists so that if
 *   that dependency is ever reintroduced, the resulting extra table is caught immediately instead of
 *   silently diverging from {@code messages} (the audited single source of truth, Tech Review A-4).
 *   It queries {@code information_schema.tables} for a case-insensitive substring match rather than
 *   one hardcoded exact table name, and first proves the same query mechanism actually finds this
 *   project's own real {@code messages}/{@code conversations} tables -- so a "0 matches" result for
 *   the chat-memory patterns cannot be misread as "the query itself sees nothing" (this project
 *   independently hit that exact false-negative shape three times in one day: a {@code grep} that
 *   silently skipped 27 lines, a {@code curl} call that omitted a header and hid a real defect, and
 *   a wrong element selector reporting a false failure).
 * [T11 additions, dev-backend-engineer 2026-08-29] Two new tests cover this card's token/cost
 *   wire contract end-to-end: {@link #streamedUsageTokensMatchPersistedAssistantRow} (chat:AC-32)
 *   and {@link #streamedUsageCostSurvivesSerializationAndDbRoundTripExactly} (chat:AC-33), both
 *   driven through the real filter chain + live ParadeDB + shared {@link StubChatModel}, unlike
 *   {@code ChatServiceTest}'s mocked-fluent-chain unit coverage of the same two ACs.
 * [T11 rework additions, dev-qa-tester 2026-08-29, 1R Block review/2026-08-29_chat-T11.md High-1/
 *   L1] {@link #streamedUsageCostSurvivesSerializationAndDbRoundTripExactly}'s scripted cost was
 *   {@code BigDecimal("2.25E-6")} — an exact boundary value where {@link
 *   java.math.BigDecimal#toString()} and {@code toPlainString()} produce byte-identical output
 *   (unscaled 225, scale 8, adjusted exponent exactly -6, the cutoff {@code toString()}'s own
 *   contract uses to switch to scientific notation), so the two byte-level assertions this test
 *   makes on the raw SSE body passed regardless of whether {@code
 *   spring.jackson.write.write-bigdecimal-as-plain} was configured, misspelled, or deleted
 *   entirely — directly reproduced by deleting that property from {@code application.yaml} and
 *   re-running this test alone: still PASS (see this card's dev-log entry for the exact command
 *   and restoration confirmation). Replaced the scripted cost with {@code BigDecimal("4.8E-7")} —
 *   a value the S1 spike's own real, paid-call measurement produced (state.md:547,
 *   {@code upstream_inference_completions_cost=4.8E-7}), whose adjusted exponent is -7, one past
 *   the cutoff, so its default {@code toString()} genuinely does emit {@code "4.8E-7"} — and
 *   updated the byte assertions to {@code contains("0.00000048")}/
 *   {@code doesNotContainIgnoringCase("e-7")}; re-ran the same deletion experiment against this
 *   new value and confirmed the test now genuinely fails without the property, then restored
 *   {@code application.yaml} (not this class's file to own) and re-confirmed PASS. The earlier
 *   {@code "2.25E-6"} boundary case was not kept alongside it — a second scripted cost would only
 *   duplicate the same "response == DB" assertions this test already makes with a value proven to
 *   carry no discriminative power for the one property this test exists to lock, so removing it
 *   loses no coverage this class did not already have elsewhere (unit-level exponential-notation
 *   handling is separately covered by {@code ChatServiceTest#lastChunkUsageCostMatchesPersistedAssistantRowExactly},
 *   which never touches the SSE byte layer and keeps its own {@code "2.25E-6"} literal). Also
 *   fixed L1 in the same test: the prior {@code usage.get("cost").toString()} read the JSON value
 *   through {@link com.jayway.jsonpath.JsonPath}'s {@code Double}-backed parsing (see {@link
 *   #parseSseChunks}), which can only round-trip through a {@code double} and so structurally
 *   cannot detect a precision loss introduced at the JSON-encoding step — replaced with {@link
 *   #extractLastRawCostToken}, which slices the {@code "cost":} token directly out of the raw SSE
 *   response bytes before any JSON-to-Java-object parsing occurs.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.session.max-anonymous-users=100000")
@Import(CapturingStatementInspectorConfig.class)
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

    @Autowired
    private StubChatModel stubChatModel;

    @Autowired
    private CapturingStatementInspector capturingStatementInspector;

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
        //   with title null (A-12 is T9's scope) and ragEnabled defaulted to true since no
        //   request body was sent (D-1, 2026-08-28: chat Spec §7 OQ-14 always specified this
        //   default; ConversationService#createConversation's fallback was fixed to match --
        //   see that method's own [Rationale] for the null-vs-explicit-false distinction)
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body).containsKeys("id", "title", "model", "ragEnabled", "createdAt", "updatedAt");
        assertThat(body.get("title")).isNull();
        assertThat(body.get("ragEnabled")).isEqualTo(true);
        assertThat((String) body.get("createdAt")).isNotBlank();
        assertThat((String) body.get("updatedAt")).isNotBlank();
    }

    @Test
    @DisplayName("(무태그, D-1 -- chat Spec §7 OQ-14: null과 명시적 false를 다른 분기로 처리하는 것이 이번 수정의 "
            + "핵심이므로 회귀 잠금 목적으로 추가) 명시적으로 ragEnabled:false를 보내 대화를 생성하면 "
            + "응답과 conversations.rag_enabled가 모두 false로 저장된다")
    void respectsExplicitRagEnabledFalseOnCreation() throws Exception {
        // given
        Session sessionA = establishSession();

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ragEnabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn();

        // then: the response echoes the caller's explicit choice, not the null-fallback default
        UUID conversationId = readConversationId(result);
        insertedConversationIds.add(conversationId);
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body.get("ragEnabled")).isEqualTo(false);

        // then: the persisted row reflects the same explicit false, not just the response body
        Boolean ragEnabledAfter = fetchRagEnabled(conversationId);
        assertThat(ragEnabledAfter).isFalse();
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

    @Test
    @DisplayName("[chat:AC-18] 타인 소유 대화에 POST /api/conversations/{id}/messages 를 호출하면 404를 반환하고 "
            + "LLM 호출이 발생하지 않으며 세션 B의 conversations·messages 행이 변하지 않는다 "
            + "(AC-17 전체 -- GET/DELETE/POST 세 엔드포인트 -- 검증은 DELETE가 추가되는 T8 소관)")
    void rejectsMessageSendForAnotherSessionsConversationWithoutCallingLlm() throws Exception {
        // given: session B owns a conversation that already has one message, so "unchanged" has
        //   something concrete to compare against instead of trivially staying at zero
        Session sessionA = establishSession();
        Session sessionB = establishSession();
        Conversation othersConversation = saveConversation(sessionB.userId(), "upstage/solar-pro4", false);
        saveMessage(othersConversation.getId(), "user", "already here");
        stubChatModel.reset();

        OffsetDateTime updatedAtBefore = fetchUpdatedAt(othersConversation.getId());
        Long messageCountBefore = fetchMessageCount(othersConversation.getId());

        // when
        mockMvc.perform(post("/api/conversations/{id}/messages", othersConversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\"}"))
                .andExpect(status().isNotFound());

        // then: session B's conversations.updated_at and messages row count are unchanged --
        //   this still does NOT cover title/deleted_at/model or existing messages.content, so it
        //   is not a byte-for-byte snapshot (2026-08-27 chat T7 리뷰 correctness Low-1). The old
        //   comment here used to say a full snapshot "is recommended when AC-17 is finally
        //   confirmed in T8" -- T8 is this card, and that recommendation was fulfilled not in this
        //   test but in rejectsAllThreeEndpointsForAnotherSessionsConversationLeavingItsRows-
        //   Unchanged below, which now does a full conversations+messages row comparison for
        //   AC-17 itself (T8 review round 1 correctness Low-3(a)). This narrower, pre-existing
        //   check stays as three fields here because this endpoint's code path performs no writes
        //   at all either way, so the residual risk is negligible; it now reads through
        //   fetchUpdatedAt/fetchMessageCount instead of duplicating their SQL inline (T8 review
        //   round 1 maintainability Low-5)
        OffsetDateTime updatedAtAfter = fetchUpdatedAt(othersConversation.getId());
        Long messageCountAfter = fetchMessageCount(othersConversation.getId());
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
        assertThat(messageCountAfter).isEqualTo(messageCountBefore);

        // then: the shared StubChatModel recorded zero prompts -- structural proof of zero LLM calls
        assertThat(stubChatModel.capturedPrompts()).isEmpty();
    }

    @Test
    @DisplayName("[chat:AC-22] 카탈로그에 없는 model로 POST /api/conversations 를 호출하면 "
            + "400을 반환하고 conversations 행이 생성되지 않는다")
    void rejectsConversationCreationWithUnknownModel() throws Exception {
        // given
        Session sessionA = establishSession();
        Long conversationCountBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversations WHERE user_id = ?", Long.class, sessionA.userId());

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"not-a-real-model\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        // then: RFC 9457 ProblemDetail body carries status 400, and no row was inserted
        Integer problemStatus = JsonPath.read(result.getResponse().getContentAsString(), "$.status");
        assertThat(problemStatus).isEqualTo(400);
        Long conversationCountAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversations WHERE user_id = ?", Long.class, sessionA.userId());
        assertThat(conversationCountAfter).isEqualTo(conversationCountBefore);
    }

    @Test
    @DisplayName("[chat:AC-22] 카탈로그에 없는 model로 POST /api/conversations/{id}/messages 를 호출하면 "
            + "400을 반환하고 LLM 호출이 발생하지 않으며 messages 행이 생성되지 않는다")
    void rejectsMessageSendWithUnknownModelWithoutCallingLlmOrPersisting() throws Exception {
        // given: the conversation itself was created with a valid model -- only the per-message
        //   override in this request is invalid
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations/{id}/messages", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"model\":\"not-a-real-model\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        // then: RFC 9457 ProblemDetail body carries status 400
        Integer problemStatus = JsonPath.read(result.getResponse().getContentAsString(), "$.status");
        assertThat(problemStatus).isEqualTo(400);

        // then: no messages row was written for this conversation
        Long messageCount = fetchMessageCount(conversation.getId());
        assertThat(messageCount).isEqualTo(0L);

        // then: the shared StubChatModel recorded zero prompts -- structural proof of zero LLM calls
        assertThat(stubChatModel.capturedPrompts()).isEmpty();
    }

    @Test
    @DisplayName("[chat:AC-45] 대화의 user 메시지 행 수가 app.session.max-messages-per-conversation 상한에 "
            + "도달하면 다음 POST .../messages 는 403을 반환하고 LLM 호출이 발생하지 않으며 messages 행이 "
            + "생성되지 않는다 (T9 review round 1 security Medium-3; user decision D-3①)")
    void rejectsMessageSendWhenConversationMessageQuotaReached() throws Exception {
        // given: seed exactly the configured limit of 'user' rows directly through the repository
        //   (bypassing HTTP), mirroring chat:AC-43's rejectsCreationWhenConversationQuotaReached
        //   one level down (conversations -> messages)
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        int maxMessagesPerConversation = appProperties.session().maxMessagesPerConversation();
        for (int i = 0; i < maxMessagesPerConversation; i++) {
            saveMessage(conversation.getId(), Message.ROLE_USER, "이전 메시지 " + i);
        }
        stubChatModel.reset();

        // when
        MvcResult result = mockMvc.perform(post("/api/conversations/{id}/messages", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"한 번 더\"}"))
                .andExpect(status().isForbidden())
                .andReturn();

        // then: RFC 9457 ProblemDetail body carries status 403
        String responseBody = result.getResponse().getContentAsString();
        Integer problemStatus = JsonPath.read(responseBody, "$.status");
        assertThat(problemStatus).isEqualTo(403);

        // then: the response never leaks the backing config key (INC-007 -- the exact pattern T6
        //   review round 2 caught for app.session.max-conversations-per-user=N leaking into a
        //   client-visible message)
        assertThat(responseBody).doesNotContain("max-messages-per-conversation");
        assertThat(responseBody).doesNotContain("app.session");

        // then: no new messages row was inserted, and the LLM was never called
        assertThat(fetchMessageCount(conversation.getId())).isEqualTo((long) maxMessagesPerConversation);
        assertThat(stubChatModel.capturedPrompts()).isEmpty();
    }

    @Test
    @DisplayName("[chat:AC-45] role='assistant' 행이 상한보다 많이 섞여 있어도 role='user' 행만 상한 "
            + "판정에 들어간다 -- user 행이 상한보다 하나 적으면 assistant 행 수와 무관하게 200이 반환된다 "
            + "(T9 review round-2 correctness L7 -- 이전 시딩은 전부 user 행이라 "
            + "countByConversationIdAndRole(id, \"user\") 를 count(*) 로 바꿔도 이 AC의 다른 테스트가 "
            + "그 회귀를 잡지 못했다)")
    void countsOnlyUserRoleMessagesTowardTheQuotaEvenWhenAssistantRowsOutnumberTheLimit() throws Exception {
        // given: one fewer than the limit of 'user' rows, but several more than the limit of
        //   'assistant' rows -- if enforceMessageQuota ever counted every role (count(*)) instead
        //   of role='user' only, this seed alone would already sit at/over the limit and the call
        //   below would 403 instead of succeeding
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        int maxMessagesPerConversation = appProperties.session().maxMessagesPerConversation();
        for (int i = 0; i < maxMessagesPerConversation - 1; i++) {
            saveMessage(conversation.getId(), Message.ROLE_USER, "이전 질문 " + i);
        }
        for (int i = 0; i < maxMessagesPerConversation + 5; i++) {
            saveMessage(conversation.getId(), Message.ROLE_ASSISTANT, "이전 답변 " + i);
        }
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("ok"), "stop", new StubChatModel.Usage(1, 1, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "마지막 질문");

        // then: the request succeeded and actually streamed -- the quota check only ever counts
        //   'user' rows, which were still one short of the limit before this call
        List<Map<String, Object>> chunks = parseSseChunks(readBodyAsUtf8(result));
        assertThat(concatenateDeltas(chunks)).isEqualTo("ok");

        // then: the LLM was actually invoked -- the strongest possible confirmation that this
        //   request was not rejected (a 403 rejection calls the LLM zero times, per the sibling
        //   test above)
        assertThat(stubChatModel.capturedPrompts()).hasSize(1);
    }

    @Test
    @DisplayName("[chat:AC-16] DELETE /api/conversations/{id} 호출 후 그 대화의 deleted_at이 null이 아니게 되고 "
            + "conversations·messages 행은 그대로 남는다(물리 삭제 없음)")
    void softDeletesConversationLeavingConversationAndMessageRowsIntact() throws Exception {
        // given: session A owns a conversation with two messages
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        saveMessage(conversation.getId(), "user", "hello");
        saveMessage(conversation.getId(), "assistant", "hi there");

        // when
        mockMvc.perform(delete("/api/conversations/{id}", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // then: deleted_at is now set -- a soft delete, not a physical one
        assertThat(fetchDeletedAt(conversation.getId())).isNotNull();

        // then: the conversations row itself still exists
        Long conversationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ?", Long.class, conversation.getId());
        assertThat(conversationCount).isEqualTo(1L);

        // then: both messages rows survive untouched -- no physical DELETE ever ran against the
        //   conversations row, so messages.conversation_id's ON DELETE CASCADE never fires
        assertThat(fetchMessageCount(conversation.getId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("[chat:AC-17] 세션 B의 대화 id로 세션 A가 GET .../messages · DELETE · POST .../messages 를 "
            + "호출하면 셋 다 404를 반환하고 세션 B의 conversations·messages 행이 변하지 않는다")
    void rejectsAllThreeEndpointsForAnotherSessionsConversationLeavingItsRowsUnchanged() throws Exception {
        // given: session B owns a conversation (not yet soft-deleted) with one existing message;
        //   session A never legitimately touches either
        Session sessionA = establishSession();
        Session sessionB = establishSession();
        Conversation othersConversation = saveConversation(sessionB.userId(), "upstage/solar-pro4", false);
        saveMessage(othersConversation.getId(), "user", "already here");
        Map<String, Object> conversationRowBefore = fetchConversationRow(othersConversation.getId());
        assertThat(conversationRowBefore.get("deleted_at")).isNull();
        List<Map<String, Object>> messageRowsBefore = fetchMessageRows(othersConversation.getId());

        // when / then: GET .../messages
        mockMvc.perform(get("/api/conversations/{id}/messages", othersConversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isNotFound());

        // when / then: DELETE
        mockMvc.perform(delete("/api/conversations/{id}", othersConversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isNotFound());

        // when / then: POST .../messages
        mockMvc.perform(post("/api/conversations/{id}/messages", othersConversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\"}"))
                .andExpect(status().isNotFound());

        // then: none of the three attempts left any trace on session B's row -- a full before/
        //   after snapshot of every conversations column and every messages row, not just
        //   deleted_at/updated_at/message count (T8 review round 1 correctness Low-3(a): the old
        //   three-field comparison could not have caught a mutation to e.g. title/model/
        //   rag_enabled or an existing message's content)
        assertThat(fetchConversationRow(othersConversation.getId())).isEqualTo(conversationRowBefore);
        assertThat(fetchMessageRows(othersConversation.getId())).isEqualTo(messageRowsBefore);
    }

    @Test
    @DisplayName("[chat:AC-41] 소프트 삭제된 대화에 GET .../messages · POST .../messages · PATCH 를 "
            + "호출하면 모두 404를 반환한다")
    void rejectsAllThreeEndpointsForSoftDeletedConversation() throws Exception {
        // given: a genuinely soft-deleted conversation (deleted_at stamped directly via JDBC,
        //   mirroring production's own DELETE endpoint effect -- see softDeleteConversation)
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        softDeleteConversation(conversation.getId());

        // when / then: GET .../messages
        mockMvc.perform(get("/api/conversations/{id}/messages", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isNotFound());

        // when / then: POST .../messages
        mockMvc.perform(post("/api/conversations/{id}/messages", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\"}"))
                .andExpect(status().isNotFound());

        // when / then: PATCH -- and no side effect slipped through despite the 404
        mockMvc.perform(patch("/api/conversations/{id}", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ragEnabled\":true}"))
                .andExpect(status().isNotFound());
        Boolean ragEnabledAfter = fetchRagEnabled(conversation.getId());
        assertThat(ragEnabledAfter).isFalse();
    }

    // chat:AC-44의 판별력이 이 테스트에 의존한다 -- 삭제 시 AC-44가 공허해진다 (T8 round2 리뷰
    // correctness Low-2, follow_up: 이 테스트 자체는 무태그이고 AC-44는
    // rejectsPatchForAnotherSessionsNonDeletedConversationLeavingItUnchanged가 달고 있지만, 그
    // 테스트의 실패 조건 -- 세션 A가 세션 B 소유 대화에 PATCH해 404를 받는 것 -- 은 세션 A가 자기
    // 대화에 PATCH하면 무엇이 성공하는지를 이 테스트가 별도로 증명해야만 대비할 반례가 성립한다.
    // 즉 이 성공 경로 테스트가 없으면 AC-44의 404 단언이 "PATCH 자체가 항상 404"인 경우와 구분되지
    // 않아 판별력을 잃는다.
    @Test
    @DisplayName("세션 A가 자기 대화에 PATCH {ragEnabled:true} 를 호출하면 200과 {id, ragEnabled} 를 반환하고 "
            + "rag_enabled 가 실제로 반영된다 (PATCH 성공 경로 자체를 검증하는 고유 AC 번호가 없음 -- "
            + "chat Spec §5 A-11/D-6 계약, ConversationTest#updateRagEnabledReflectsNewValue와 같은 "
            + "무태그 선례를 따름. T8 리뷰 재작업 correctness/security Medium-1)")
    void updatesRagEnabledForOwnConversationAndReturnsUpdatedFields() throws Exception {
        // given: session A owns a conversation created with ragEnabled=false
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);

        // when
        MvcResult result = mockMvc.perform(patch("/api/conversations/{id}", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ragEnabled\":true}"))
                .andExpect(status().isOk())
                .andReturn();

        // then: the response body is exactly {id, ragEnabled} with the new value -- no prior test
        //   asserted this endpoint's success response shape at all
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body.keySet()).containsExactlyInAnyOrder("id", "ragEnabled");
        assertThat((String) body.get("id")).isEqualTo(conversation.getId().toString());
        assertThat(body.get("ragEnabled")).isEqualTo(true);

        // then: the persisted row itself reflects the new value, not just the response body
        Boolean ragEnabledAfter = fetchRagEnabled(conversation.getId());
        assertThat(ragEnabledAfter).isTrue();
    }

    @Test
    @DisplayName("[chat:AC-44] 세션 B 소유의 미삭제 대화에 세션 A가 PATCH 를 호출하면 404를 반환하고 세션 B의 "
            + "rag_enabled는 바뀌지 않는다 (AC-17 원문은 GET·DELETE·POST만 다루고 AC-41은 소프트 삭제 케이스만 "
            + "다뤄 PATCH의 교차 세션·미삭제 케이스가 비어 있던 것을, 2026-08-27 사용자가 chat:AC-44 신설을 "
            + "승인해 메움 -- T8 리뷰 재작업 correctness/security Medium-1, D-2. 이 테스트는 처음엔 태그 없이 "
            + "추가했다가 이번에 태그만 부착했고 테스트 로직 자체는 변경하지 않았다)")
    void rejectsPatchForAnotherSessionsNonDeletedConversationLeavingItUnchanged() throws Exception {
        // given: session B owns a conversation that is NOT soft-deleted -- this is exactly what
        //   rejectsAllThreeEndpointsForSoftDeletedConversation's PATCH case cannot prove: once
        //   deleted_at is non-null, findByIdAndUserIdAndDeletedAtIsNull already returns empty
        //   regardless of which userId is passed in, so that test alone cannot catch a broken or
        //   missing ownership check ever reaching that query (T8 review round 1 correctness/
        //   security Medium-1)
        Session sessionA = establishSession();
        Session sessionB = establishSession();
        Conversation othersConversation = saveConversation(sessionB.userId(), "upstage/solar-pro4", false);
        assertThat(fetchDeletedAt(othersConversation.getId())).isNull();

        // when
        mockMvc.perform(patch("/api/conversations/{id}", othersConversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ragEnabled\":true}"))
                .andExpect(status().isNotFound());

        // then: session B's row is untouched -- still not soft-deleted, ragEnabled unchanged
        assertThat(fetchDeletedAt(othersConversation.getId())).isNull();
        Boolean ragEnabledAfter = fetchRagEnabled(othersConversation.getId());
        assertThat(ragEnabledAfter).isFalse();
    }

    @Test
    @DisplayName("PATCH /api/conversations/{id}가 발행하는 conversations UPDATE 문의 SET 절에 deleted_at이 없고, "
            + "DELETE /api/conversations/{id}가 발행하는 UPDATE 문의 SET 절에 rag_enabled가 없다 "
            + "(관련 AC 없음, 원장 갱신 대상 아님 -- T8 round2 리뷰 correctness Low-1: Conversation의 "
            + "@DynamicUpdate(T8 round1 High-1이 요구한 PATCH/DELETE 겹침 lost update 수정)가 지금까지 1회성 "
            + "수동 SQL 로그 관측에만 묶여 있었던 것을 자동 회귀로 고정한다. @DynamicUpdate를 지우면 이 테스트가 "
            + "실제로 실패함을 확인했다)")
    void patchAndDeleteEachOmitTheOtherMutatorsColumnFromTheirUpdateSetClause() throws Exception {
        // given: two of session A's own conversations, one target per endpoint under test
        Session sessionA = establishSession();
        Conversation patchTarget = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        Conversation deleteTarget = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        capturingStatementInspector.reset();

        // when: PATCH flips ragEnabled only
        mockMvc.perform(patch("/api/conversations/{id}", patchTarget.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ragEnabled\":true}"))
                .andExpect(status().isOk());

        // when: DELETE soft-deletes a different conversation
        mockMvc.perform(delete("/api/conversations/{id}", deleteTarget.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue())))
                .andExpect(status().isNoContent());

        // then: exactly one conversations UPDATE was issued per call, in call order, and each
        //   SET clause carries only the column its own mutator touched -- without
        //   @DynamicUpdate both statements would instead be the same static UPDATE naming every
        //   updatable column (title, model, rag_enabled, updated_at, deleted_at), so this
        //   assertion fails the moment that annotation is removed
        List<String> conversationsUpdates = capturingStatementInspector.capturedStatements().stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("update conversations"))
                .toList();
        assertThat(conversationsUpdates).hasSize(2);
        assertThat(conversationsUpdates.get(0))
                .containsIgnoringCase("rag_enabled")
                .doesNotContainIgnoringCase("deleted_at");
        assertThat(conversationsUpdates.get(1))
                .containsIgnoringCase("deleted_at")
                .doesNotContainIgnoringCase("rag_enabled");
    }

    @Test
    @DisplayName("[chat:AC-24] POST .../messages 응답의 Content-Type이 text/event-stream이고 "
            + "모든 data: 라인이 JSON 객체로 파싱된다 (raw 문자열 라인 0건)")
    void sendMessageRespondsWithEventStreamContentTypeAndEveryDataLineIsAJsonObject() throws Exception {
        // given
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("안녕", "하세요"), "stop", new StubChatModel.Usage(3, 2, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "안녕");

        // then: Content-Type is exactly text/event-stream (type/subtype), regardless of any
        //   charset parameter appended -- equalsTypeAndSubtype, not isCompatibleWith, because the
        //   latter also accepts text/* or */* and would not fail if the controller regressed to a
        //   wildcard-compatible type that is not actually text/event-stream (round-2 rework
        //   correctness L1)
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        MediaType actualContentType = MediaType.parseMediaType(result.getResponse().getContentType());
        assertThat(actualContentType.equalsTypeAndSubtype(MediaType.TEXT_EVENT_STREAM)).isTrue();

        // then: every SSE "data" field value is JSON-object syntax, not a raw/bare string --
        //   CLAUDE.md fact #6's D1 bug would surface here as an unquoted bare word (neither valid
        //   JSON nor a JSON object)
        List<String> dataFieldValues = extractSseDataFieldValues(readBodyAsUtf8(result));
        assertThat(dataFieldValues).isNotEmpty();
        for (String value : dataFieldValues) {
            assertThat(value).as("SSE data field value must be a JSON object: %s", value)
                    .startsWith("{")
                    .endsWith("}");
            Map<String, Object> parsed = JsonPath.read(value, "$");
            assertThat(parsed).containsKey("conversationId");
        }
    }

    @Test
    @DisplayName("[chat:AC-36] POST .../messages 응답의 Cache-Control이 no-store와 no-transform을 "
            + "동시에 실어보내고, 두 개의 별도 Cache-Control 헤더로 쪼개지지 않는다")
    void sendMessageResponseCacheControlCarriesBothNoStoreAndNoTransform() throws Exception {
        // given: AnonymousSessionFilter stamps a plain "no-store" on every /api/* response
        //   (addHeader) before this controller ever runs -- this test proves the controller's own
        //   setHeader call replaces that single value rather than appending a second header
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("안녕", "하세요"), "stop", new StubChatModel.Usage(3, 2, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "안녕");

        // then: exactly one Cache-Control header, carrying both directives -- this is the
        //   necessary condition for chat:AC-36 (a header actually leaves the server telling an
        //   intermediary/browser proxy not to buffer-compress this response); it cannot by itself
        //   prove the browser-observed defect is fixed (see this class's own dev-log entry for
        //   that limit -- MockMvc never gzips a response, so it cannot reproduce the compression
        //   defect this header suppresses)
        assertThat(result.getResponse().getHeaders("Cache-Control")).hasSize(1);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store, no-transform");
    }

    @Test
    @DisplayName("[chat:AC-25] 선행 공백을 가진 토큰(오늘/ 금요일/ 점검)을 스트리밍하면 delta 를 이어붙인 "
            + "문자열이 원문과 정확히 일치한다 (공백 유실 0)")
    void streamedDeltasConcatenateToExactOriginalTextWithLeadingSpacesPreserved() throws Exception {
        // given: CLAUDE.md fact #6's exact regression scenario -- a naive raw-string SSE write
        //   would lose the space before "금요일"/"점검" to the SSE "data:" field's one-leading-
        //   space-stripping rule; wrapping each token in a JSON object (as ChatChunk does) keeps
        //   the space inside a quoted JSON string, immune to that rule
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(
                List.of("오늘", " 금요일", " 점검"), "stop", new StubChatModel.Usage(4, 3, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "점검 일정 알려줘");

        // then
        List<Map<String, Object>> chunks = parseSseChunks(readBodyAsUtf8(result));
        String concatenated = concatenateDeltas(chunks);
        assertThat(concatenated).isEqualTo("오늘 금요일 점검");
    }

    @Test
    @DisplayName("[chat:AC-26] 스트리밍된 한국어 문자열이 응답 수신 후 원문과 코드포인트 단위로 일치한다 (mojibake 0)")
    void streamedKoreanTextMatchesOriginalCodePointForCodePoint() throws Exception {
        // given: tokens split mid-sentence (not only at natural word boundaries) so a charset
        //   mishandling anywhere in the write path would show up as corrupted characters, not
        //   just an unlucky split point
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        String expected = "안녕하세요, 오늘은 8월 27일 점검이 있습니다.";
        stubChatModel.enqueueTokens(
                List.of("안녕하세요", ", 오늘은 ", "8월 27일", " 점검이 있습니다."), "stop",
                new StubChatModel.Usage(5, 8, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "점검 안내해줘");

        // then: decode the raw bytes as UTF-8 directly, independent of whatever character
        //   encoding the response metadata itself claims (CLAUDE.md fact #7:
        //   produces=...;charset=UTF-8 has no effect on this endpoint, so this assertion must not
        //   lean on the framework having declared the right charset)
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        List<Map<String, Object>> chunks = parseSseChunks(body);
        String concatenated = concatenateDeltas(chunks);

        assertThat(concatenated).isEqualTo(expected);
        // explicit code-point-level comparison, matching the AC's literal wording (redundant with
        // the String#equals above for BMP-only Hangul text, but names the exact failure mode)
        assertThat(concatenated.codePoints().boxed().toList())
                .isEqualTo(expected.codePoints().boxed().toList());
    }

    @Test
    @DisplayName("[chat:AC-27] 스트림의 모든 청크가 요청한 conversationId 를 동일하게 담고 있다")
    void everyStreamedChunkCarriesTheRequestedConversationId() throws Exception {
        // given
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(
                List.of("hello", " world"), "stop", new StubChatModel.Usage(2, 2, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "hi");

        // then
        List<Map<String, Object>> chunks = parseSseChunks(readBodyAsUtf8(result));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.get("conversationId")).isEqualTo(conversation.getId().toString()));
    }

    @Test
    @DisplayName("[chat:AC-28] 스트림의 마지막 청크만 finishReason 을 가지고 그 이후 data: 라인이 없다")
    void onlyTheLastStreamedChunkCarriesFinishReasonAndNothingFollowsIt() throws Exception {
        // given
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(
                List.of("hello", " world"), "stop", new StubChatModel.Usage(2, 2, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "hi");

        // then: no non-terminal chunk carries finishReason
        List<Map<String, Object>> chunks = parseSseChunks(readBodyAsUtf8(result));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).get("finishReason")).isNull();
        }

        // then: the last chunk carries finishReason -- and since `chunks` is derived from the
        //   complete, fully-awaited response body (performMessageSendAndAwaitStream blocks until
        //   the stream finishes), this element being last in the list IS the proof that no data:
        //   line follows it
        Map<String, Object> lastChunk = chunks.get(chunks.size() - 1);
        assertThat(lastChunk.get("finishReason")).isEqualTo("stop");
    }

    @Test
    @DisplayName("[chat:AC-29] 요청 1건 처리 후 messages 에 user 1행과 assistant 1행이 생기고 "
            + "assistant content 가 스트리밍된 delta 를 이어붙인 문자열과 같다")
    void persistsExactlyOneUserAndOneAssistantRowMatchingStreamedContent() throws Exception {
        // given
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(
                List.of("오늘", " 금요일", " 점검"), "stop", new StubChatModel.Usage(4, 3, BigDecimal.ZERO));
        // captured before the call so the title-assignment/updated_at-bump check below (T9 review
        //   round-2 correctness L3) has a concrete "before" state to compare against -- title
        //   starts null (saveConversation never sets it) and updated_at starts at insertion time
        OffsetDateTime updatedAtBefore = fetchUpdatedAt(conversation.getId());

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "점검 일정 알려줘");

        // then: the response itself streamed the expected text
        List<Map<String, Object>> chunks = parseSseChunks(readBodyAsUtf8(result));
        String concatenated = concatenateDeltas(chunks);
        assertThat(concatenated).isEqualTo("오늘 금요일 점검");

        // then: exactly one user row and one assistant row were persisted, in that order
        //   (bigserial id ascending == insertion order, matching Tech Review A-4's call order),
        //   and the assistant row's content matches what the client actually reconstructed above
        List<Map<String, Object>> messageRows = fetchMessageRows(conversation.getId());
        assertThat(messageRows).hasSize(2);

        Map<String, Object> userRow = messageRows.get(0);
        assertThat(userRow.get("role")).isEqualTo("user");
        assertThat(userRow.get("content")).isEqualTo("점검 일정 알려줘");

        Map<String, Object> assistantRow = messageRows.get(1);
        assertThat(assistantRow.get("role")).isEqualTo("assistant");
        assertThat(assistantRow.get("content")).isEqualTo(concatenated);

        // then: conversations.title was assigned from the first user message and updated_at moved
        //   forward from before the call (Tech Review A-12/OQ-4; T9 review round-2 correctness L3
        //   -- before this addition, no test anywhere asserted either
        //   ConversationRepository.assignTitleIfAbsent/touchUpdatedAt call actually ran, so
        //   ConversationService.persistUserTurn could silently drop both lines without failing
        //   any of the 221 tests then in the suite). This checks the full HTTP-to-DB round trip;
        //   ConversationServiceTest's own persistUserTurn*/persistAssistantTurn* tests pin down
        //   which of the two call sites (user turn vs. assistant turn) is responsible in
        //   isolation, which this end-to-end check alone cannot distinguish.
        Map<String, Object> conversationRowAfter = fetchConversationRow(conversation.getId());
        assertThat(conversationRowAfter.get("title")).isEqualTo("점검 일정 알려줘");
        assertThat(fetchUpdatedAt(conversation.getId())).isAfter(updatedAtBefore);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("[chat:AC-32] 응답의 usage.prompt_tokens/completion_tokens 값이 assistant "
            + "messages 행의 prompt_tokens/completion_tokens 와 각각 같다")
    void streamedUsageTokensMatchPersistedAssistantRow() throws Exception {
        // given
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("답변"), "stop", new StubChatModel.Usage(59, 4, new BigDecimal("2.25E-6")));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "질문");

        // then: the SSE response's terminal chunk carries the scripted token counts
        List<Map<String, Object>> chunks = parseSseChunks(readBodyAsUtf8(result));
        Map<String, Object> usage = (Map<String, Object>) chunks.get(chunks.size() - 1).get("usage");
        assertThat(usage).isNotNull();
        assertThat(((Number) usage.get("prompt_tokens")).intValue()).isEqualTo(59);
        assertThat(((Number) usage.get("completion_tokens")).intValue()).isEqualTo(4);

        // then: the persisted assistant row carries the identical token counts
        List<Map<String, Object>> messageRows = fetchMessageRows(conversation.getId());
        Map<String, Object> assistantRow = messageRows.get(1);
        assertThat(((Number) assistantRow.get("prompt_tokens")).intValue()).isEqualTo(59);
        assertThat(((Number) assistantRow.get("completion_tokens")).intValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("[chat:AC-33] 응답의 usage.cost 값이 assistant messages 행의 cost_usd 와 부동소수 "
            + "반올림 없이 십진 일치하고, 지수 표기 입력이 SSE 직렬화·numeric 컬럼 저장·재조회를 거쳐도 "
            + "보존된다")
    void streamedUsageCostSurvivesSerializationAndDbRoundTripExactly() throws Exception {
        // given: BigDecimal("4.8E-7") is the S1 spike's real, paid-call-measured OpenRouter cost
        //   value itself (state.md:547, upstream_inference_completions_cost=4.8E-7) -- unlike the
        //   earlier "2.25E-6" literal this test used before the 2026-08-29 1R rework (review
        //   High-1), this value's adjusted exponent is -7, one past the -6 cutoff BigDecimal#
        //   toString()'s own contract uses to switch to scientific notation, so its default
        //   toString() genuinely renders as "4.8E-7" -- the byte-level assertions below only have
        //   discriminative power (i.e. can actually fail if write-bigdecimal-as-plain regresses)
        //   because of that; "2.25E-6" sits exactly on the boundary where toString() and
        //   toPlainString() already agree, so it could not have caught that regression (this
        //   class's own [T11 rework additions] Javadoc has the reproduction)
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        BigDecimal scriptedCost = new BigDecimal("4.8E-7");
        stubChatModel.enqueueTokens(List.of("답변"), "stop", new StubChatModel.Usage(59, 4, scriptedCost));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "질문");

        // then: the raw SSE bytes never carry scientific notation -- application.yaml's
        //   spring.jackson.write.write-bigdecimal-as-plain (T11) forces plain-decimal
        //   serialization; without it this BigDecimal would round-trip through Jackson as
        //   "4.8E-7" instead
        String body = readBodyAsUtf8(result);
        assertThat(body).doesNotContainIgnoringCase("e-7");
        assertThat(body).contains("0.00000048");

        // then: the JSON usage.cost value is numerically the exact scripted BigDecimal -- read
        //   directly off the raw SSE bytes (2026-08-29 1R rework, review L1), not through
        //   parseSseChunks()'s JsonPath-backed Map (JsonPath parses JSON numbers as Double, so
        //   funneling that back through BigDecimal(String) cannot detect a precision loss that
        //   already happened at the JSON-encoding step)
        BigDecimal responseCost = extractLastRawCostToken(body);
        assertThat(responseCost).isEqualByComparingTo(scriptedCost);

        // then: the persisted assistant row's cost_usd, read back from PostgreSQL's numeric
        //   column, is the exact same value as the response -- proving the exponential-notation
        //   input survives both the SSE JSON round trip and the DB round trip (chat:AC-33's
        //   "응답 값 = DB 행의 값")
        List<Map<String, Object>> messageRows = fetchMessageRows(conversation.getId());
        Map<String, Object> assistantRow = messageRows.get(1);
        BigDecimal persistedCost = (BigDecimal) assistantRow.get("cost_usd");
        assertThat(persistedCost).isEqualByComparingTo(scriptedCost);
        assertThat(persistedCost).isEqualByComparingTo(responseCost);
    }

    @Test
    @DisplayName("[chat:AC-30] 같은 conversationId 로 2번째 메시지를 보내면 두 번째 호출의 프롬프트에 "
            + "1번째 턴의 user·assistant 메시지가 순서대로 포함되고 2번째 턴의 user 메시지는 정확히 1번만 "
            + "실린다 (T10 -- ChatServiceTest#secondMessageInSameConversationIncludesFirstTurnInThePrompt "
            + "와 달리 MessageChatMemoryRepository 를 모킹하지 않는다. 이 테스트의 존재 이유가 실제 DB "
            + "왕복이므로, 두 번째 호출에서 MessageChatMemoryRepository.findByConversationId 가 실제로 "
            + "PostgreSQL 에서 1번째 턴의 두 행을 읽어오는지가 판별 대상이다)")
    void secondMessageInSameConversationPromptIncludesFirstTurnFromARealDbRoundTrip() throws Exception {
        // given: one conversation, two turns' worth of scripted stub responses queued up front --
        //   StubChatModel's script queue is FIFO (that class's own Javadoc), so the first script
        //   below answers the first POST and the second answers the second POST
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("1번째 답"), "stop", new StubChatModel.Usage(2, 2, BigDecimal.ZERO));
        stubChatModel.enqueueTokens(List.of("2번째 답"), "stop", new StubChatModel.Usage(2, 2, BigDecimal.ZERO));

        // when: first turn -- performMessageSendAndAwaitStream blocks until the whole SSE stream
        //   (including the terminal chunk) has been written, which is what lets ChatService's
        //   doOnComplete hook persist the 'assistant' row before this method returns (see that
        //   method's own Javadoc; the AC-29 test above relies on the same guarantee). If the stream
        //   were only partially consumed, the second call below would read an incomplete history
        //   from the DB and this test would fail for the wrong reason
        performMessageSendAndAwaitStream(sessionA, conversation.getId(), "1번째 질문");
        assertThat(fetchMessageCount(conversation.getId())).isEqualTo(2L);

        // when: second turn, same conversationId -- MessageChatMemoryRepository (T10, the real
        //   bean, never mocked here) reads messages/conversations directly from the same live
        //   PostgreSQL connection this test's own JdbcTemplate assertions use
        performMessageSendAndAwaitStream(sessionA, conversation.getId(), "2번째 질문");

        // then: exactly two prompts reached the LLM, in call order -- the second one is this
        //   test's real subject
        List<Prompt> prompts = stubChatModel.capturedPrompts();
        assertThat(prompts).hasSize(2);

        // then: the FIRST turn's prompt is not empty either -- correctness Medium-3 (T10 review
        //   round 1, 2026-08-28): before this addition, nothing anywhere asserted prompts.get(0),
        //   so a MessageChatMemoryRepository.findByConversationId defect that returns an empty
        //   list for a conversation's very first turn (e.g. an off-by-one guard shaped like
        //   "if (rows.size() <= 1) return List.of();") would leave the LLM's first-ever prompt
        //   empty on every single conversation -- production's most common path, since every
        //   conversation starts with exactly one turn -- while this same test's second-turn
        //   assertions below still passed (the 2nd call always sees >=2 rows), and StubChatModel
        //   replies from a fixed script regardless of prompt content, so no other test in this
        //   class would have caught it either. Deliberately left untagged (not [chat:AC-30]):
        //   AC-30's own text only requires the SECOND message's prompt to include the first turn
        //   -- it says nothing about the first turn's own prompt, so tagging this assertion would
        //   make the evidence ledger claim more than the AC text actually requires.
        List<org.springframework.ai.chat.messages.Message> firstPromptMessages = prompts.get(0).getInstructions();
        assertThat(firstPromptMessages).hasSize(1);
        assertThat(firstPromptMessages)
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("1번째 질문");

        List<org.springframework.ai.chat.messages.Message> secondPromptMessages = prompts.get(1).getInstructions();

        // then: exactly 3 messages -- 1st turn's user, 1st turn's assistant, 2nd turn's user, in
        //   that order. ChatService's own [Rationale] documents exactly why: the 'user' row ①
        //   INSERT happens before ② the history read, so chatMemory.get() already ends with this
        //   turn's own question, and .messages(history) alone (no separate .user(...) call)
        //   supplies it
        assertThat(secondPromptMessages).hasSize(3);
        assertThat(secondPromptMessages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(secondPromptMessages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(secondPromptMessages.get(2)).isInstanceOf(UserMessage.class);
        assertThat(secondPromptMessages)
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("1번째 질문", "1번째 답", "2번째 질문");

        // then: the 2nd turn's own question appears exactly once -- a regression lock against
        //   ChatService reintroducing a separate .user(userMessage) call alongside
        //   .messages(history), which would send this turn's question to the LLM twice (see
        //   ChatService.streamReply's own [Rationale]: DefaultChatClientUtils.toChatClientRequest
        //   appends .messages(...) then .user(...) as two independent, additive steps)
        long secondTurnUserOccurrences = secondPromptMessages.stream()
                .filter(message -> message instanceof UserMessage && "2번째 질문".equals(message.getText()))
                .count();
        assertThat(secondTurnUserOccurrences).isEqualTo(1L);
    }

    @Test
    @DisplayName("[chat:AC-31] 마이그레이션·기동·대화 1건 수행 후에도 messages 외의 대화 이력 테이블(예: "
            + "SPRING_AI_CHAT_MEMORY류 이름)이 존재하지 않는다 (T10 -- chat Spec Tech Review R-5 정정: "
            + "spring-ai-starter-model-chat-memory-repository-jdbc 가 pom.xml에 없어 "
            + "JdbcChatMemoryRepository 자동 구성의 트리거 자체가 없으므로, 이 테스트는 '지금 깨졌는지'가 "
            + "아니라 '앞으로 그 의존성이 다시 추가돼도 곧바로 잡히도록' 고정하는 회귀 테스트다)")
    void noChatMemoryHistoryTableExistsAfterMigrationStartupAndOneRealConversation() throws Exception {
        // given: perform one real conversation end-to-end -- migration+startup already happened
        //   by the time this @SpringBootTest context is up (IntegrationTestBase), so this call
        //   supplies the AC text's remaining "대화 1건 수행" precondition
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("답변"), "stop", new StubChatModel.Usage(1, 1, BigDecimal.ZERO));
        performMessageSendAndAwaitStream(sessionA, conversation.getId(), "질문");
        assertThat(fetchMessageCount(conversation.getId())).isEqualTo(2L);

        // then: the query mechanism itself can find real tables -- proven FIRST, against this
        //   project's own known tables, so a "0" result below cannot be misread as "the query saw
        //   nothing" instead of "the table genuinely does not exist" (this project hit exactly
        //   that false-negative shape three times in one day per this card's own instructions: a
        //   grep that silently skipped 27 lines, a curl call that omitted a header and hid a real
        //   defect, and a wrong element selector reporting a false failure)
        assertThat(countTablesMatching("messages")).isEqualTo(1L);
        assertThat(countTablesMatching("conversations")).isEqualTo(1L);

        // then: the '%' wildcard itself actually matches a substring, not just an exact literal --
        //   correctness Low-2 (T10 review round 1, 2026-08-28): the two controls immediately above
        //   never exercise a '%' character, so on their own they would still pass even if
        //   countTablesMatching's LIKE pattern were silently broken in a way that degrades every
        //   '%...%' pattern to zero matches (e.g. a driver/JDBC escaping bug, or the '%' getting
        //   stripped before reaching the query) -- the "0 results" assertions below would then be
        //   a false negative (query sees nothing) misread as a true one (table genuinely absent),
        //   the exact failure shape this test's own comment above already guards against for plain
        //   literals. "essage" is a substring of "messages" (an already-confirmed real table) but
        //   is not the whole table name, so this only passes if '%' partial matching genuinely works
        assertThat(countTablesMatching("%essage%")).isEqualTo(1L);

        // then: no table whose name contains "chat_memory" exists, case-insensitively -- Spring
        //   AI's own default JdbcChatMemoryRepository schema names its table SPRING_AI_CHAT_MEMORY
        //   (this AC's own text cites that exact example), but this check intentionally does not
        //   hardcode that one literal spelling: a substring LIKE match, lower-cased on both sides,
        //   still catches it (and close variants) without depending on Spring AI never changing
        //   casing/prefix conventions across a future version bump
        assertThat(countTablesMatching("%chat_memory%")).isEqualTo(0L);

        // then: a second, independent pattern -- Spring AI's autoconfigure modules ship every
        //   vendor table (chat memory, vector store) under this same "spring_ai" prefix -- so this
        //   regression lock does not depend solely on the literal substring "chat_memory"
        //   surviving verbatim in some future Spring AI release
        assertThat(countTablesMatching("spring_ai%")).isEqualTo(0L);
    }

    @Test
    @DisplayName("(무태그, D-4 -- 사용자 결정: 요청 모델을 실제 호출에 반영, T9 review round 1 "
            + "correctness Medium-1) 메시지 전송 요청에 model을 지정하면 그 모델이 실제 LLM 호출과 "
            + "assistant messages.model에 반영된다")
    void sendMessageUsesAndPersistsTheRequestedModelOverride() throws Exception {
        // given: the conversation's own stored model is the catalog default, and the request
        //   overrides it with a different catalog entry, so a bug that ignores the override and
        //   silently falls back to the conversation's stored model cannot hide behind both values
        //   coinciding
        Session sessionA = establishSession();
        String defaultModel = appProperties.models().defaultId();
        String overrideModel = appProperties.models().items().stream()
                .map(AppProperties.Models.ModelEntry::id)
                .filter(id -> !id.equals(defaultModel))
                .findFirst()
                .orElseThrow();
        Conversation conversation = saveConversation(sessionA.userId(), defaultModel, false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("응답"), "stop", new StubChatModel.Usage(1, 1, BigDecimal.ZERO));

        // when
        MvcResult result = performMessageSendAndAwaitStream(
                sessionA, conversation.getId(), "질문", overrideModel);

        // then: the model actually captured in the outgoing call is the override, not the
        //   conversation's stored default
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubChatModel.capturedPrompts()).hasSize(1);
        String calledModel = stubChatModel.capturedPrompts().getFirst().getOptions().getModel();
        assertThat(calledModel).isEqualTo(overrideModel);

        // then: the persisted assistant row attributes the generation to the model actually
        //   called, not the conversation's stored default (D-4's "오귀속 제거")
        List<Map<String, Object>> messageRows = fetchMessageRows(conversation.getId());
        assertThat(messageRows.get(1).get("model")).isEqualTo(overrideModel);
    }

    @Test
    @DisplayName("(무태그, D-4) 메시지 전송 요청에 model을 지정하지 않으면 대화의 저장된 모델이 실제 "
            + "LLM 호출과 assistant messages.model에 사용된다")
    void sendMessageWithoutModelOverrideUsesConversationsStoredModel() throws Exception {
        // given: the conversation's stored model is deliberately NOT the catalog default, so a
        //   bug that silently falls back to the catalog default instead of reading
        //   conversation.getModel() cannot hide behind both values coinciding
        Session sessionA = establishSession();
        String defaultModel = appProperties.models().defaultId();
        String nonDefaultModel = appProperties.models().items().stream()
                .map(AppProperties.Models.ModelEntry::id)
                .filter(id -> !id.equals(defaultModel))
                .findFirst()
                .orElseThrow();
        Conversation conversation = saveConversation(sessionA.userId(), nonDefaultModel, false);
        stubChatModel.reset();
        stubChatModel.enqueueTokens(List.of("응답"), "stop", new StubChatModel.Usage(1, 1, BigDecimal.ZERO));

        // when: no "model" field in the request body
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "질문");

        // then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubChatModel.capturedPrompts()).hasSize(1);
        String calledModel = stubChatModel.capturedPrompts().getFirst().getOptions().getModel();
        assertThat(calledModel).isEqualTo(nonDefaultModel);

        List<Map<String, Object>> messageRows = fetchMessageRows(conversation.getId());
        assertThat(messageRows.get(1).get("model")).isEqualTo(nonDefaultModel);
    }

    @Test
    @DisplayName("메시지가 4001자(상한 초과)면 400을 반환하고 messages 행이 생성되지 않으며 "
            + "LLM 호출이 발생하지 않는다 (round-2 재작업 security L8 -- @Size(max=4000) 회귀 잠금 부재)")
    void rejectsMessageOverMaxLengthWithoutCallingLlmOrPersisting() throws Exception {
        // given
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        String tooLong = "가".repeat(4001);

        // when: rejected synchronously (no async dispatch needed -- @Valid fails before the
        //   controller method body, let alone the Flux, ever runs)
        mockMvc.perform(post("/api/conversations/{id}/messages", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", sessionA.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        // then: no messages row was written, and the shared StubChatModel recorded zero prompts
        assertThat(fetchMessageCount(conversation.getId())).isZero();
        assertThat(stubChatModel.capturedPrompts()).isEmpty();
    }

    @Test
    @DisplayName("[chat:AC-34] 스트림 도중 오류가 발생하면 오류 청크 1건 후 정상 종료하고 "
            + "HTML 오류 페이지나 절단된 응답이 아니다")
    void streamingErrorEmitsOneErrorChunkThenTerminatesCleanly() throws Exception {
        // given
        Session sessionA = establishSession();
        Conversation conversation = saveConversation(sessionA.userId(), "upstage/solar-pro4", false);
        stubChatModel.reset();
        stubChatModel.enqueueTokensThenError(
                List.of("일부 답"), new RuntimeException("upstream responded with api-key=sk-secret-12345"));

        // when
        MvcResult result = performMessageSendAndAwaitStream(sessionA, conversation.getId(), "질문");

        // then: still a normal 200 SSE response -- not a 5xx HTML error page, not a dropped
        //   connection (chat Spec Tech Review A-7's onErrorResume converts the failure into a
        //   normal terminal chunk before Spring MVC ever sees an onError signal)
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        MediaType actualContentType = MediaType.parseMediaType(result.getResponse().getContentType());
        // equalsTypeAndSubtype, not isCompatibleWith -- see the AC-24 test above for why
        // (round-2 rework correctness L1)
        assertThat(actualContentType.equalsTypeAndSubtype(MediaType.TEXT_EVENT_STREAM)).isTrue();
        String body = readBodyAsUtf8(result);
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("<html");

        // then: exactly one chunk carries the error finishReason, and it is the stream's last
        //   chunk -- a truly truncated/cut-off response would fail JSON parsing in parseSseChunks
        //   before reaching this assertion at all
        List<Map<String, Object>> chunks = parseSseChunks(body);
        assertThat(chunks).isNotEmpty();
        List<Map<String, Object>> errorChunks = chunks.stream()
                .filter(chunk -> "error".equals(chunk.get("finishReason")))
                .toList();
        assertThat(errorChunks).hasSize(1);
        assertThat(chunks.get(chunks.size() - 1)).isEqualTo(errorChunks.get(0));

        // then: the error chunk's delta never leaks the underlying exception message (INC-007)
        assertThat(String.valueOf(errorChunks.get(0).get("delta"))).doesNotContain("sk-secret-12345");

        // then: the assistant row was still persisted with the partial content + finishReason
        //   (chat Spec §7 OQ-6: errors are recorded, not discarded)
        List<Map<String, Object>> messageRows = fetchMessageRows(conversation.getId());
        assertThat(messageRows).hasSize(2);
        Map<String, Object> assistantRow = messageRows.get(1);
        assertThat(assistantRow.get("role")).isEqualTo("assistant");
        assertThat(assistantRow.get("content")).isEqualTo("일부 답");
        assertThat(assistantRow.get("finish_reason")).isEqualTo("error");
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

    /**
     * [Purpose] Reads {@code conversations.deleted_at} directly (T8) -- {@code null} for a live
     *   conversation, non-null once soft-deleted. Shared by the chat:AC-16/AC-17 tests and the
     *   untagged cross-session PATCH test below (T8 review round 1) so each can assert this
     *   column before and after its HTTP call(s).
     */
    private OffsetDateTime fetchDeletedAt(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM conversations WHERE id = ?", OffsetDateTime.class, conversationId);
    }

    /**
     * [Purpose] Reads {@code conversations.updated_at} directly (T8) -- used by the chat:AC-18
     *   test above to prove a rejected cross-session {@code POST .../messages} call leaves this
     *   column untouched (T8 review round 1 maintainability Low-5: this call replaces what used
     *   to be an inline duplicate of the same SQL literal in that test).
     */
    private OffsetDateTime fetchUpdatedAt(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM conversations WHERE id = ?", OffsetDateTime.class, conversationId);
    }

    /**
     * [Purpose] Reads {@code conversations.rag_enabled} directly (T8 round 2) -- used by the
     *   PATCH tests above to prove a toggle call did or did not reach the persisted row. Follows
     *   the same pattern as {@link #fetchDeletedAt}/{@link #fetchUpdatedAt} (T8 review round 2
     *   maintainability Low-4: replaces three inline duplicates of the same SQL literal, two of
     *   them added in round 2 itself).
     */
    private Boolean fetchRagEnabled(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT rag_enabled FROM conversations WHERE id = ?", Boolean.class, conversationId);
    }

    /**
     * [Purpose] Counts {@code messages} rows for a conversation directly (T8) -- used by the
     *   chat:AC-16 test above to prove the {@code messages} table survives a soft delete, and by
     *   the chat:AC-18 test to prove a rejected cross-session {@code POST .../messages} call
     *   writes no new row (T8 review round 1 maintainability Low-5: the latter call replaces what
     *   used to be an inline duplicate of the same SQL literal in that test).
     */
    private Long fetchMessageCount(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?", Long.class, conversationId);
    }

    /**
     * [Purpose] Counts {@code information_schema.tables} rows in the {@code public} schema whose
     *   (lower-cased) name matches {@code likePatternLowercase} -- chat:AC-31 (T10).
     * [Rationale] Case-insensitive via {@code lower()} on both sides so a vendor-created table
     *   using any casing convention (e.g. Spring AI's own {@code SPRING_AI_CHAT_MEMORY}) is still
     *   caught regardless of how PostgreSQL happened to fold its identifier. Passing a pattern with
     *   no {@code %}/{@code _} wildcard characters (e.g. {@code "messages"}) degrades to an exact,
     *   case-insensitive match -- the same method doubles as this test's positive control for the
     *   real tables it already knows exist.
     */
    private Long countTablesMatching(String likePatternLowercase) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND lower(table_name) LIKE lower(?)",
                Long.class, likePatternLowercase);
    }

    /**
     * [Purpose] Reads every column of a single {@code conversations} row as a {@link Map} (T8
     *   review round 1 correctness Low-3(a)) -- used by the chat:AC-17 test above for a full
     *   before/after snapshot instead of naming individual columns, so a mutation to any column
     *   (not only the ones a test happens to name, e.g. {@code title}/{@code model}/{@code
     *   rag_enabled}) fails the comparison.
     */
    private Map<String, Object> fetchConversationRow(UUID conversationId) {
        return jdbcTemplate.queryForMap("SELECT * FROM conversations WHERE id = ?", conversationId);
    }

    /**
     * [Purpose] Reads every column of every {@code messages} row for a conversation, ordered by
     *   {@code id} for a stable comparison order (T8 review round 1 correctness Low-3(a)) -- pairs
     *   with {@link #fetchConversationRow} for the chat:AC-17 test's full before/after snapshot,
     *   covering existing messages' {@code content} too.
     */
    private List<Map<String, Object>> fetchMessageRows(UUID conversationId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM messages WHERE conversation_id = ? ORDER BY id", conversationId);
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

    /**
     * [Purpose] Drives {@code POST /api/conversations/{id}/messages} (T9) through MockMvc's
     *   documented two-step async pattern -- {@code request().asyncStarted()} then {@code
     *   asyncDispatch(...)} -- and returns the result once the entire SSE stream has been fully
     *   written, so every caller can read a complete response body.
     * [Rationale] {@code ConversationController}'s {@code Flux<ChatChunk>} return type is adapted by
     *   Spring MVC's {@code ReactiveTypeHandler} into an {@code SseEmitter}, which {@code
     *   ResponseBodyEmitterReturnValueHandler} wires to a {@code DeferredResult} under the hood
     *   (verified directly against the {@code spring-webmvc:7.0.9} sources) -- the same
     *   completion mechanism any other async controller method uses, so the standard MockMvc
     *   async test idiom applies unchanged even though this endpoint streams multiple chunks
     *   rather than a single deferred value.
     */
    private MvcResult performMessageSendAndAwaitStream(Session session, UUID conversationId, String message)
            throws Exception {
        return performMessageSendAndAwaitStream(session, conversationId, message, null);
    }

    /**
     * [Purpose] Same as the three-argument overload, but also lets a caller set the request
     * body's optional {@code model} field (D-4: 요청 모델을 실제 호출에 반영) -- {@code null} omits
     * the field entirely, matching the three-argument overload's existing behavior exactly.
     */
    private MvcResult performMessageSendAndAwaitStream(
            Session session, UUID conversationId, String message, String model) throws Exception {
        String body = model == null
                ? "{\"message\":\"" + message + "\"}"
                : "{\"message\":\"" + message + "\",\"model\":\"" + model + "\"}";
        MvcResult started = mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .cookie(new Cookie("chatbot_sid", session.cookieValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn();
    }

    private static String readBodyAsUtf8(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * [Purpose] Extracts each SSE event's "data" field value from a raw response body, applying
     *   the same "strip at most one leading U+0020 SPACE, and only that one" rule a spec-
     *   compliant SSE client (e.g. browser {@code EventSource}) applies.
     * [Rationale] A blanket {@code trim()}/{@code strip()} here would silently absorb the exact
     *   leading-space-loss bug chat:AC-25 exists to catch (CLAUDE.md fact #6) -- verified against
     *   {@code SseEmitter.SseEventBuilderImpl#data}/{@code #build} in the {@code
     *   spring-webmvc:7.0.9} sources, which write {@code "data:" + value + "\n"} with no space
     *   inserted after the colon; the SSE spec's own one-space-stripping rule is a client-side
     *   parsing rule, not something Spring's writer performs, so a test reading the raw bytes
     *   must apply that rule itself to see what a real client would actually receive. Splits on
     *   {@code "\n"} only, matching {@code SseEventBuilderImpl}'s own event terminator (a single
     *   LF, not CRLF).
     */
    private static List<String> extractSseDataFieldValues(String body) {
        List<String> values = new ArrayList<>();
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (!line.startsWith("data:")) {
                continue;
            }
            String value = line.substring("data:".length());
            if (!value.isEmpty() && value.charAt(0) == ' ') {
                value = value.substring(1);
            }
            values.add(value);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseSseChunks(String body) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (String value : extractSseDataFieldValues(body)) {
            chunks.add(JsonPath.read(value, "$"));
        }
        return chunks;
    }

    /**
     * [Purpose] Reads the last {@code "cost":<number>} token out of the raw SSE response bytes
     *   and builds a {@link BigDecimal} directly from that substring (2026-08-29 review L1).
     * [Rationale] {@link #parseSseChunks} hands JSON numbers to {@link JsonPath#read}, which
     *   backs them with {@code Double} — funneling that back through {@code
     *   new BigDecimal(usage.get("cost").toString())} can only ever compare two values that have
     *   both already been rounded to {@code double} precision, so it structurally cannot detect a
     *   precision loss introduced at the JSON-encoding step itself (the exact thing {@code
     *   spring.jackson.write.write-bigdecimal-as-plain} governs). Slicing the literal straight out
     *   of the wire bytes, before any JSON-to-Java-object parsing occurs, avoids that intermediate
     *   {@code double} entirely.
     * [Caution] Assumes exactly one {@code "cost":<number>} token appears in {@code body} — true
     *   for every test built on {@link StubChatModel}'s "usage only on the terminal chunk" shape,
     *   since non-terminal chunks serialize their {@code null} {@code usage} field as
     *   {@code "usage":null} (no nested {@code "cost"} key at all). Takes the last match rather
     *   than requiring exactly one so a future test scripting multiple assistant turns in one body
     *   still resolves to the most recent value, matching this test's own intent.
     */
    private static BigDecimal extractLastRawCostToken(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"cost\":(-?[0-9.eE+-]+)").matcher(body);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }
        assertThat(lastMatch).as("no \"cost\":<number> token found in SSE body").isNotNull();
        return new BigDecimal(lastMatch);
    }

    private static String concatenateDeltas(List<Map<String, Object>> chunks) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> chunk : chunks) {
            Object delta = chunk.get("delta");
            if (delta != null) {
                builder.append(delta);
            }
        }
        return builder.toString();
    }

    /** [Purpose] One minted anonymous session: its resolved user id and its {@code chatbot_sid} cookie value. */
    private record Session(UUID userId, String cookieValue) {}
}
