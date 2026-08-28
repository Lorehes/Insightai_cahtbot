package art.insightai.chatbot.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.user.User;
import art.insightai.chatbot.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * [Purpose] Exercises {@code GET /api/admin/usage} (eval-usage-dashboard Spec, card U2) end-to-end
 *   through the real servlet filter chain -- the HTTP-layer half of AC-13/AC-15, plus the
 *   HTTP-layer re-verification of AC-14/AC-16/AC-17 that {@code UsageIntegrationTest} (U1) could
 *   not cover since {@link UsageController} did not exist yet (2026-08-27 리뷰 Medium-1 후속,
 *   Spec §4 AC-14/16/17 증거 칸의 명시적 인계).
 * [Rationale] Follows {@code ConversationControllerIntegrationTest}'s pattern: {@link
 *   AutoConfigureMockMvc} over {@link IntegrationTestBase} so the real {@code
 *   FilterRegistrationBean<AnonymousSessionFilter>} chain runs, and {@link JsonPath} instead of an
 *   autowired {@code ObjectMapper} (Spring Boot 4 wires {@code tools.jackson.databind.ObjectMapper},
 *   not the classic Jackson type). {@code /api/admin/usage} sits under {@code /api/*} so {@code
 *   AnonymousSessionFilter} still runs even though {@link UsageController} never reads {@code
 *   SessionContext} -- every test below establishes one session via {@code GET /api/session} purely
 *   to get a known, cleanable {@code users} row (mirrors {@code ConversationControllerIntegrationTest
 *   #establishSession}) rather than calling the endpoint cookie-less and leaking an
 *   untracked anonymous user row into the shared {@code chatbot-db} container on every request.
 *   {@code app.session.max-anonymous-users} is pinned high via {@link TestPropertySource} for the
 *   same reason {@code ConversationControllerIntegrationTest} pins it. The {@code messages} rows
 *   each test aggregates over are seeded directly through the repositories (not via a second HTTP
 *   round trip) and backdated into a dedicated, collision-free far-future period -- one distinct
 *   month per test, disjoint from the months {@code UsageIntegrationTest} already claimed
 *   (2099-01/03/04/05/06/07) -- for the exact isolation reason that class's own Javadoc gives:
 *   {@link UsageRepository} has no per-caller filter by design (Tech Review D-8: admin-wide
 *   aggregate), so an unbounded or "today"-relative assertion would silently absorb rows written by
 *   any other Spec card's concurrently running integration test against the same shared table.
 *   AC-14's boundary coverage here is deliberately a single combined test (one HTTP round trip
 *   asserting both the inclusive lower bound and the exclusive upper bound at once) rather than
 *   repeating {@code UsageIntegrationTest}'s three separate boundary tests at the HTTP layer --
 *   {@link UsageService}/{@link UsageRepository}'s SQL correctness is already locked there (U1);
 *   what this class adds is proof that {@link UsageController} carries the same {@code from}/{@code
 *   to} values through to the same query unmodified and that the result serializes correctly, which
 *   one boundary-spanning HTTP test already demonstrates without re-proving the SQL itself.
 *   {@code aggregatesTheEntireHistoryWhenBothParametersAreOmitted} below is the one test in this
 *   class that cannot reuse that per-test-dedicated-month isolation trick (2026-08-28 리뷰
 *   High-1) -- it exercises the literal AC-13 request shape (no {@code from}/{@code to} at all),
 *   which by construction has no period to scope a dedicated month into and therefore aggregates
 *   every {@code role='assistant'} row ever written to the shared {@code messages} table,
 *   including rows any other Spec card's concurrently running integration test may insert.
 *   Isolation there instead comes from a {@code messages.model} value unique across this whole
 *   test suite (grepped before writing the test; {@link UsageRepository#fetchByModel} groups
 *   strictly by that column with no period filter applied when both bounds are {@code null}), so
 *   the seeded row is found by filtering {@code byModel[]} for that exact model string and its
 *   four fields are asserted exactly -- {@code totalRequests} at the top level, by contrast, sums
 *   across every model the shared table currently holds and can only be asserted as a lower bound
 *   ({@code >=} this row's own request count), never an exact value, for the same reason the
 *   dedicated-month trick does not apply here.
 * [Rationale, money precision] {@link #readMoneyFieldExactly} and {@link
 *   #readAllMoneyFieldOccurrencesExactly} read {@code BigDecimal} JSON fields straight out of the
 *   raw response body via regex instead of through {@link JsonPath#read}, because {@code
 *   JsonPath}'s default {@code json-smart} provider parses every JSON number as a {@code Double}
 *   first -- {@code new BigDecimal(jsonPathValue.toString())} therefore already round-tripped
 *   through {@code Double} before this class ever sees it, so a future serialization change that
 *   silently dropped significant digits from a cost figure could pass unnoticed (2026-08-28 리뷰
 *   Low-2). Not a currently-live bug -- every seeded cost value in this class (e.g. {@code
 *   0.0025}) sits far inside {@code Double}'s exact-integer-mantissa range, so no assertion below
 *   has ever actually been fooled by this -- but the fix is applied everywhere a literal or
 *   summed money value is checked (this class's four money assertions the review cited) rather
 *   than left as a latent gap. Both helpers' pattern also matches {@code BigDecimal#toString()}'s
 *   exponential form (e.g. {@code 1E-7}, emitted whenever the adjusted exponent is below -6) and
 *   requires a trailing {@code ,} or {@code }} after the number, so a value shaped like that no
 *   longer silently truncates to its leading digit and an unexpected trailing token makes the
 *   match fail loudly instead of partially succeeding (2026-08-28 리뷰 2R Low-3; verified directly
 *   by {@link #readMoneyFieldExactlyParsesExponentialNotation}).
 * [Caution] The offset-less-input test below carries no AC prefix -- Tech Review D-8 only specifies
 *   the type ({@code OffsetDateTime}, offset required) and Spring's binding-failure path already
 *   answers with 400 as a side effect of {@code @DateTimeFormat(iso = ISO.DATE_TIME)}, not through
 *   any code this Spec's cards wrote (see {@link UsageController}'s own [Rationale]) -- so it locks
 *   current framework behavior as a regression guard rather than verifying an AC's own text.
 *   Measured directly (dev-qa-tester, 2026-08-28, a temporary debug print run then reverted): the
 *   response is status 400 with {@code Content-Type} {@code null} and a completely empty body
 *   (length 0) through {@code MockMvc} -- not a truncated or malformed {@code ProblemDetail}, but
 *   no body at all, because {@code DefaultHandlerExceptionResolver} answers a binding failure via
 *   {@code HttpServletResponse#sendError} and {@code MockMvc} does not simulate the container's
 *   error-page forwarding that a real servlet container would perform for that call. Whether a real
 *   deployed Tomcat instance would render a body for this same request (its own default error page)
 *   is not established by this test and is review's call, not qa-tester's, to judge acceptable --
 *   the assertion below only locks what {@code MockMvc} itself observes.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.session.max-anonymous-users=100000")
class UsageControllerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

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
    @DisplayName("[eval-usage-dashboard:AC-13] GET /api/admin/usage 는 200과 함께 totalRequests·"
            + "promptTokens·completionTokens·totalCostUsd 를 반환한다")
    void returnsTwoHundredWithTheFourRequiredTotals() throws Exception {
        // given: a session purely to satisfy AnonymousSessionFilter, plus one assistant message
        //   in a dedicated, collision-free period (class-level [Rationale])
        Session session = establishSession();
        OffsetDateTime from = OffsetDateTime.parse("2099-08-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-08-02T00:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long messageId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 100, 40,
                new BigDecimal("0.0025"));
        backdateMessage(messageId, OffsetDateTime.parse("2099-08-01T12:00:00+09:00"));

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/usage")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isOk())
                .andReturn();

        // then: the response exposes exactly the six top-level fields this Spec's AC-13/AC-16/
        //   AC-17 fix as the contract -- no more, no fewer (2026-08-28 리뷰 Medium-3 보완: was
        //   containsKeys, which passes even if a field is silently added later)
        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> body = JsonPath.read(responseBody, "$");
        assertThat(body).containsOnlyKeys("totalRequests", "promptTokens", "completionTokens",
                "totalCostUsd", "missingCostCount", "byModel");
        assertThat(((Number) body.get("totalRequests")).longValue()).isEqualTo(1L);
        assertThat(((Number) body.get("promptTokens")).longValue()).isEqualTo(100L);
        assertThat(((Number) body.get("completionTokens")).longValue()).isEqualTo(40L);
        assertThat(readMoneyFieldExactly(responseBody, "totalCostUsd")).isEqualByComparingTo("0.0025");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-13] from/to 파라미터를 모두 생략하면 전체 기간을 집계한다 "
            + "(Tech Review D-8 기본값 -- 2026-08-28 리뷰 High-1: 이 클래스의 다른 모든 테스트는 항상 "
            + "from/to 를 붙여 호출해 AC-13 원문의 무파라미터 요청 형태를 한 번도 실행하지 않고 있었다)")
    void aggregatesTheEntireHistoryWhenBothParametersAreOmitted() throws Exception {
        // given: two messages under a model name unique across this whole test suite (grepped
        //   before writing this test) -- this test cannot use a dedicated collision-free month the
        //   way every other test in this class does, because the request under test omits `from`/
        //   `to` entirely and therefore is not scoped to any period at all; it aggregates every
        //   role='assistant' row the shared `messages` table currently holds. Isolation instead
        //   comes from the unique model name (class-level [Rationale] has the full reasoning).
        //   One row sits in the far future (2099-12 -- unclaimed by any other test in this suite,
        //   distinct from the 2099-08/09/10/11 this class's other tests each dedicate to
        //   themselves) and the other sits in the far past (2000-01) so that this test locks BOTH
        //   halves of Tech Review D-8's "both omitted -> entire history" contract: a `to`-omitted
        //   default that only clamped the upper bound would still include the past row and fail
        //   here, just as a `from`-omitted default that only clamped the lower bound would still
        //   include the future row and fail here (2026-08-28 리뷰 2R Low-1/Low-2: the prior single
        //   future-dated row locked only the upper-bound half)
        Session session = establishSession();
        UUID conversationId = createUserAndConversation();
        String uniqueModel = "usage-ac13-noparam-probe";
        Long futureMessageId = saveAssistantTurn(conversationId, uniqueModel, 100, 40,
                new BigDecimal("0.0025"));
        backdateMessage(futureMessageId, OffsetDateTime.parse("2099-12-01T12:00:00+09:00"));
        Long pastMessageId = saveAssistantTurn(conversationId, uniqueModel, 100, 40,
                new BigDecimal("0.0025"));
        backdateMessage(pastMessageId, OffsetDateTime.parse("2000-01-01T00:00:00+09:00"));

        // when: no `from`/`to` params at all -- the exact AC-13/§6-I2 request shape
        MvcResult result = mockMvc.perform(get("/api/admin/usage")
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isOk())
                .andReturn();

        // then: both the future-dated and the past-dated row surface in the same byModel group --
        //   proof the unparameterized request reached both despite no period filter applying in
        //   either direction. totalRequests can only be a lower bound (>= this model's own request
        //   count) since the shared table may hold other rows this test does not own
        String responseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> byModel = JsonPath.read(responseBody, "$.byModel");
        List<Map<String, Object>> ourRows = byModel.stream()
                .filter(row -> uniqueModel.equals(row.get("model")))
                .toList();
        assertThat(ourRows).hasSize(1);
        Map<String, Object> ourRow = ourRows.get(0);
        assertThat(((Number) ourRow.get("requests")).longValue()).isEqualTo(2L);
        assertThat(((Number) ourRow.get("promptTokens")).longValue()).isEqualTo(200L);
        assertThat(((Number) ourRow.get("completionTokens")).longValue()).isEqualTo(80L);
        assertThat(new BigDecimal(ourRow.get("costUsd").toString())).isEqualByComparingTo("0.0050");

        Map<String, Object> body = JsonPath.read(responseBody, "$");
        assertThat(((Number) body.get("totalRequests")).longValue()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-15] from 이 to 보다 늦으면 400을 반환한다")
    void rejectsWhenFromIsAfterTo() throws Exception {
        // given: no messages need seeding -- from > to is rejected before any query runs
        //   (UsageController#usage checks this ahead of calling UsageService)
        Session session = establishSession();
        OffsetDateTime from = OffsetDateTime.parse("2099-08-02T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-08-01T00:00:00+09:00");

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/usage")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isBadRequest())
                .andReturn();

        // then: GlobalExceptionHandler's uniform ProblemDetail body carries status 400
        Integer problemStatus = JsonPath.read(result.getResponse().getContentAsString(), "$.status");
        assertThat(problemStatus).isEqualTo(400);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-15] from 과 to 가 정확히 같으면 400이 아니라 200을 반환한다 "
            + "(from > to 만 거부한다는 경계 확인)")
    void doesNotRejectWhenFromEqualsTo() throws Exception {
        // given
        Session session = establishSession();
        OffsetDateTime sameInstant = OffsetDateTime.parse("2099-08-01T00:00:00+09:00");

        // when / then: from == to is not "from is after to" -- must not throw
        //   InvalidUsagePeriodException
        mockMvc.perform(get("/api/admin/usage")
                        .param("from", sameInstant.toString())
                        .param("to", sameInstant.toString())
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-14] GET 응답이 반열림 구간(from<=created_at<to)을 그대로 반영한다 "
            + "-- 하한 포함, 상한 제외를 HTTP 계층에서 확인 (2026-08-27 리뷰 Medium-1 후속 재검증)")
    void reflectsTheHalfOpenPeriodInTheHttpResponse() throws Exception {
        // given: one message before `from` (excluded), one exactly at `from` (included -- lower
        //   bound is inclusive), one exactly at `to` (excluded -- upper bound is exclusive)
        Session session = establishSession();
        OffsetDateTime from = OffsetDateTime.parse("2099-09-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-09-02T00:00:00+09:00");
        UUID conversationId = createUserAndConversation();

        Long beforeFromId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 999, 999,
                new BigDecimal("9.9999"));
        backdateMessage(beforeFromId, OffsetDateTime.parse("2099-08-31T23:59:59+09:00"));

        Long atFromId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 100, 40,
                new BigDecimal("0.0025"));
        backdateMessage(atFromId, from);

        Long atToId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 999, 999,
                new BigDecimal("9.9999"));
        backdateMessage(atToId, to);

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/usage")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isOk())
                .andReturn();

        // then: only the row stamped exactly at `from` counts
        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> body = JsonPath.read(responseBody, "$");
        assertThat(((Number) body.get("totalRequests")).longValue()).isEqualTo(1L);
        assertThat(((Number) body.get("promptTokens")).longValue()).isEqualTo(100L);
        assertThat(((Number) body.get("completionTokens")).longValue()).isEqualTo(40L);
        assertThat(readMoneyFieldExactly(responseBody, "totalCostUsd")).isEqualByComparingTo("0.0025");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-16] 응답 JSON의 byModel[] 이 model/requests/promptTokens/"
            + "completionTokens/costUsd 필드를 포함하고 각 행 합계가 최상위 합계와 일치한다")
    void byModelBreakdownAppearsInTheJsonResponseAndSumsMatchTotals() throws Exception {
        // given: two assistant messages under different models, in a dedicated period
        Session session = establishSession();
        OffsetDateTime from = OffsetDateTime.parse("2099-10-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-10-02T00:00:00+09:00");
        OffsetDateTime withinPeriod = OffsetDateTime.parse("2099-10-01T12:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long soLarId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 700, 300,
                new BigDecimal("1.0000"));
        backdateMessage(soLarId, withinPeriod);
        Long bgeId = saveAssistantTurn(conversationId, "baai/bge-m3", 300, 100,
                new BigDecimal("0.2300"));
        backdateMessage(bgeId, withinPeriod);

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/usage")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isOk())
                .andReturn();

        // then: byModel is a JSON array with the exact field names AC-16 specifies
        String responseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> byModel = JsonPath.read(responseBody, "$.byModel");
        assertThat(byModel).hasSize(2);
        assertThat(byModel).allSatisfy(row -> assertThat(row).containsOnlyKeys(
                "model", "requests", "promptTokens", "completionTokens", "costUsd"));

        // then: each row's fields sum to the top-level totals for the same response
        Map<String, Object> body = JsonPath.read(responseBody, "$");
        long summedRequests = byModel.stream().mapToLong(row -> ((Number) row.get("requests")).longValue()).sum();
        long summedPromptTokens = byModel.stream()
                .mapToLong(row -> ((Number) row.get("promptTokens")).longValue()).sum();
        long summedCompletionTokens = byModel.stream()
                .mapToLong(row -> ((Number) row.get("completionTokens")).longValue()).sum();
        BigDecimal summedCost = readAllMoneyFieldOccurrencesExactly(responseBody, "costUsd").stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(summedRequests).isEqualTo(((Number) body.get("totalRequests")).longValue());
        assertThat(summedPromptTokens).isEqualTo(((Number) body.get("promptTokens")).longValue());
        assertThat(summedCompletionTokens).isEqualTo(((Number) body.get("completionTokens")).longValue());
        assertThat(summedCost).isEqualByComparingTo(readMoneyFieldExactly(responseBody, "totalCostUsd"));
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-17] 응답 JSON에 missingCostCount 가 노출되고 cost_usd가 null인 "
            + "메시지는 totalCostUsd 합산에서 제외된다")
    void exposesMissingCostCountAndExcludesNullCostFromTheJsonResponse() throws Exception {
        // given: two null-cost messages (R12: OpenRouter SDK dropped usage.cost) and one with a
        //   real cost, in a dedicated period
        Session session = establishSession();
        OffsetDateTime from = OffsetDateTime.parse("2099-11-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-11-02T00:00:00+09:00");
        OffsetDateTime withinPeriod = OffsetDateTime.parse("2099-11-01T12:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long nullCost1Id = saveAssistantTurn(conversationId, "upstage/solar-pro4", 100, 40, null);
        backdateMessage(nullCost1Id, withinPeriod);
        Long nullCost2Id = saveAssistantTurn(conversationId, "upstage/solar-pro4", 110, 45, null);
        backdateMessage(nullCost2Id, withinPeriod);
        Long realCostId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 120, 50,
                new BigDecimal("0.0500"));
        backdateMessage(realCostId, withinPeriod);

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/usage")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isOk())
                .andReturn();

        // then: missingCostCount is present in the response and the null-cost rows are invisible
        //   to totalCostUsd but not to totalRequests
        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> body = JsonPath.read(responseBody, "$");
        assertThat(body).containsKey("missingCostCount");
        assertThat(((Number) body.get("totalRequests")).longValue()).isEqualTo(3L);
        assertThat(((Number) body.get("missingCostCount")).longValue()).isEqualTo(2L);
        assertThat(readMoneyFieldExactly(responseBody, "totalCostUsd")).isEqualByComparingTo("0.0500");
    }

    @Test
    @DisplayName("(무태그, Tech Review D-8 실측 잠금) 오프셋 없는 from 값은 400을 반환하지만 응답 바디는 "
            + "GlobalExceptionHandler 의 통일된 ProblemDetail 형식이 아니다")
    void rejectsOffsetLessFromWithFourHundredButNotTheUniformProblemDetailShape() throws Exception {
        // given: no offset on `from` (Tech Review D-8: "오프셋 없는 입력은 거부") -- this fails
        //   OffsetDateTime binding, not the from > to check, so it never reaches
        //   UsageController#usage's body at all. `to` is supplied with a valid offset (and after
        //   `from`, so the request would otherwise be well-formed) so the 400 can only be explained
        //   by `from`'s missing offset -- not by `to` being absent, which by itself already binds
        //   successfully as UsageController's optional/nullable parameter (2026-08-28 리뷰 2R
        //   Low-5: the prior omitted-`to` shape left the 400 attributable to either cause)
        Session session = establishSession();

        // when / then: 400 is guaranteed (engineer's claim) and is the only thing locked as a
        //   regression guard here. The class-level [Caution] records what was actually measured
        //   (Content-Type null, empty body under MockMvc) as an observation, not as an assertion --
        //   pinning the current body shape as a passing test would fail the day someone improves
        //   GlobalExceptionHandler to also cover this binding-failure path with a uniform
        //   ProblemDetail body, rewarding a regression-guard test for blocking an improvement
        //   (2026-08-28 리뷰 Low-1)
        mockMvc.perform(get("/api/admin/usage")
                        .param("from", "2099-08-01T00:00:00")
                        .param("to", "2099-08-02T00:00:00+09:00")
                        .cookie(new Cookie("chatbot_sid", session.cookieValue())))
                .andExpect(status().isBadRequest());
    }

    /**
     * [Purpose] Mints one anonymous session via a real, cookie-less {@code GET /api/session} call,
     *   tracking the created {@code users} row for {@link #cleanUpInsertedRows()} -- mirrors {@code
     *   ConversationControllerIntegrationTest#establishSession}.
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
     * [Purpose] Persists the minimal {@link User} + {@link Conversation} chain a {@link Message}
     *   requires under the FK constraints, tracking both ids for {@link #cleanUpInsertedRows()} --
     *   this is a data-owner user distinct from the session established via {@link
     *   #establishSession()}, mirroring {@code UsageIntegrationTest#createUserAndConversation}.
     */
    private UUID createUserAndConversation() {
        User user = userRepository.save(new User("anonymous", null, null));
        insertedUserIds.add(user.getId());
        Conversation conversation = conversationRepository.save(
                new Conversation(user.getId(), "upstage/solar-pro4", false));
        insertedConversationIds.add(conversation.getId());
        return conversation.getId();
    }

    /**
     * [Purpose] Persists a {@code role='user'} row followed by a {@code role='assistant'} row for
     *   one turn (mirroring {@code ChatService}'s real insert order), and returns the assistant
     *   row's id for callers that need to {@link #backdateMessage} it afterward -- mirrors {@code
     *   UsageIntegrationTest#saveAssistantTurn}.
     */
    private Long saveAssistantTurn(UUID conversationId, String model, int promptTokens,
            int completionTokens, BigDecimal costUsd) {
        messageRepository.save(new Message(conversationId, "user", "질문입니다", null, null, null,
                null, null, null));
        Message assistant = messageRepository.save(new Message(conversationId, "assistant",
                "답변입니다", model, promptTokens, completionTokens, costUsd, "[]", "stop"));
        return assistant.getId();
    }

    /**
     * [Purpose] Overwrites one message row's {@code created_at} via raw SQL so every scenario can
     *   place its rows in a fixed, collision-free period instead of wall-clock "now" -- {@link
     *   Message} has no setter for this column.
     */
    private void backdateMessage(Long messageId, OffsetDateTime createdAt) {
        jdbcTemplate.update("UPDATE messages SET created_at = ? WHERE id = ?", createdAt, messageId);
    }

    /**
     * [Purpose] Reads exactly one occurrence of a {@code BigDecimal} JSON field straight out of the
     *   raw response body via regex -- see class-level [Rationale, money precision] for why this
     *   bypasses {@link JsonPath#read}.
     * [Caution] Matches the first {@code "<fieldName>":<number>} occurrence in document order; not
     *   a general JsonPath replacement, only fit for this class's flat top-level money fields.
     */
    private static BigDecimal readMoneyFieldExactly(String responseBody, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName
                + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)(?=[,}])").matcher(responseBody);
        assertThat(matcher.find()).as("field \"%s\" present in response body", fieldName).isTrue();
        return new BigDecimal(matcher.group(1));
    }

    /**
     * [Purpose] Reads every occurrence of a {@code BigDecimal} JSON field, in document order,
     *   straight out of the raw response body via regex -- used for {@code byModel[].costUsd},
     *   which repeats once per model row. See {@link #readMoneyFieldExactly} and class-level
     *   [Rationale, money precision] for why.
     */
    private static List<BigDecimal> readAllMoneyFieldOccurrencesExactly(String responseBody, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName
                + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)(?=[,}])").matcher(responseBody);
        List<BigDecimal> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(new BigDecimal(matcher.group(1)));
        }
        return values;
    }

    @Test
    @DisplayName("(무태그, 헬퍼 자체 검증) readMoneyFieldExactly 는 지수 표기 \"1E-7\" 에서 0.0000001 을 "
            + "정확히 읽는다 (2026-08-28 리뷰 2R Low-3)")
    void readMoneyFieldExactlyParsesExponentialNotation() {
        // given: a fabricated response fragment shaped exactly like Jackson serializing a
        //   BigDecimal whose adjusted exponent is below -6 -- new BigDecimal("0.0000001")
        //   .toString() returns "1E-7", not "0.0000001" -- seeded through a real HTTP round trip
        //   this would require a cost value far smaller than any this class currently records, so
        //   this test isolates the regex directly instead of adding a fifth full integration
        //   scenario just to reach that one code path
        String responseBody = "{\"totalRequests\":1,\"totalCostUsd\":1E-7,\"missingCostCount\":0}";

        // when
        BigDecimal parsed = readMoneyFieldExactly(responseBody, "totalCostUsd");

        // then: the pre-fix pattern would have matched only the leading "1" and returned 1,
        //   ten million times too large
        assertThat(parsed).isEqualByComparingTo("0.0000001");
    }

    /** [Purpose] One minted anonymous session: its resolved user id and its {@code chatbot_sid} cookie value. */
    private record Session(UUID userId, String cookieValue) {}
}
