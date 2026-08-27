package art.insightai.chatbot.usage;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.user.User;
import art.insightai.chatbot.user.UserRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Exercises {@link UsageService#summarize} against the live ParadeDB container so the
 *   {@code FILTER (WHERE ...)} clauses and period-filter branches {@link UsageRepository} writes
 *   as raw SQL are actually verified against real rows — Spec Test Case I3 and the
 *   eval-usage-dashboard:AC-14/AC-16/AC-17 rows all depend on real aggregation, not on the SQL
 *   string being well-formed. A prior {@code UsageRepositoryTest} covered {@code buildPeriodFilter}
 *   /{@code mapTotals}/{@code mapModelUsage} with a mocked {@link JdbcTemplate}/{@code ResultSet};
 *   it was deleted (review 2026-08-27 Medium-4 made those three helpers {@code private}, so a
 *   same-package mock test can no longer call them) and its coverage folded into this class —
 *   see {@link #includesMessageExactlyAtTheLowerBound} and
 *   {@link #totalCostIsExactlyZeroNotNullWhenEveryMessageInThePeriodIsMissingCost} for the
 *   boundary/COALESCE cases that test previously exercised in isolation.
 * [Rationale] Each test builds its own {@link User} + {@link Conversation} + {@link Message} rows
 *   (same FK chain {@code MessageRepositoryIntegrationTest} establishes) and reads back through
 *   {@link UsageService}, the same entry point the T2 controller card will call — this catches
 *   drift between {@link UsageRepository}'s hand-written SQL and the {@code messages} schema that
 *   unit tests with a mocked {@link ResultSet} cannot. {@code UsageRepository} has no
 *   conversation-scoped filter (Tech Review D-8: this is an admin-wide aggregate by design), so an
 *   assertion against an unbounded or "now"-relative period would silently absorb rows written by
 *   any other Spec card's concurrently running integration test against the same shared
 *   {@code chatbot-db} container. Every test here instead backdates its rows into a disjoint,
 *   far-future period (year 2099, one distinct month per test) that no application code or other
 *   test has a reason to write to, so the assertions are exact regardless of what else is running
 *   or what "today" happens to be (this file was first written on 2026-08-26 — using a period
 *   anywhere in August 2026 as a boundary, as an earlier draft did, would have collided with
 *   every other integration test's default {@code createdAt = OffsetDateTime.now()} row). {@link
 *   Message}'s constructor always stamps {@code createdAt = OffsetDateTime.now()} with no setter,
 *   so every row's {@code created_at} is moved into its test's period with a direct {@link
 *   JdbcTemplate} {@code UPDATE} after {@code save} — bypassing JPA here is intentional (test-only)
 *   and mirrors this class's own cleanup, which already goes through {@link JdbcTemplate} rather
 *   than the repositories under test.
 * [Caution] A {@code role='user'} row is seeded alongside every {@code role='assistant'} row in
 *   each test (mirroring a real turn) specifically so a naive query without the repository's
 *   {@code WHERE role = 'assistant'} filter would fail these assertions — the two roles carry very
 *   different token/cost shapes in production ({@code Message} class-level [Caution]: the 'user'
 *   row never has {@code model}/token/cost data), so a role leak would show up as a wrong count
 *   or a wrong sum, not silently pass.
 */
class UsageIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UsageService usageService;

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
    @DisplayName("[eval-usage-dashboard:AC-14] 기간 밖 assistant 메시지는 UsageService 집계에서 제외된다")
    void excludesMessagesOutsideThePeriod() {
        // given: a conversation with three assistant messages -- one inside [from, to), one
        //   before `from`, and one exactly at `to` (the half-open upper bound must exclude it) --
        //   plus a paired 'user' row per turn to confirm the role filter also holds under a live
        //   query, not just the mocked mapper. The period lives in a dedicated, collision-free
        //   month (class-level [Rationale]) rather than near "today" or "now"
        UUID conversationId = createUserAndConversation();
        OffsetDateTime from = OffsetDateTime.parse("2099-01-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-02-01T00:00:00+09:00");

        Long inRangeId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 100, 50,
                new BigDecimal("0.0010"));
        backdateMessage(inRangeId, OffsetDateTime.parse("2099-01-15T00:00:00+09:00"));

        Long beforeFromId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 200, 90,
                new BigDecimal("0.0020"));
        backdateMessage(beforeFromId, OffsetDateTime.parse("2098-12-31T23:59:59+09:00"));

        Long atToId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 300, 120,
                new BigDecimal("0.0030"));
        backdateMessage(atToId, to);

        // when
        UsageSummary summary = usageService.summarize(from, to);

        // then: only the in-range row counts, not the row before `from` nor the row exactly at
        //   the exclusive upper bound `to`
        assertThat(summary.totalRequests()).isEqualTo(1L);
        assertThat(summary.promptTokens()).isEqualTo(100L);
        assertThat(summary.completionTokens()).isEqualTo(50L);
        assertThat(summary.totalCostUsd()).isEqualByComparingTo("0.0010");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-14] created_at이 from과 정확히 같은 메시지는 하한 포함으로 집계된다")
    void includesMessageExactlyAtTheLowerBound() {
        // given: the half-open period is [from, to) -- a message stamped exactly at `from`
        //   must be INCLUDED. Neither AC-14 test in this file previously exercised this edge:
        //   excludesMessagesOutsideThePeriod's in-range row sits well inside the period and its
        //   boundary row is at the *upper* (exclusive) bound only (review 2026-08-27 Low-1)
        OffsetDateTime from = OffsetDateTime.parse("2099-07-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-07-02T00:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long atFromId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 150, 60,
                new BigDecimal("0.0015"));
        backdateMessage(atFromId, from);

        // when
        UsageSummary summary = usageService.summarize(from, to);

        // then
        assertThat(summary.totalRequests()).isEqualTo(1L);
        assertThat(summary.totalCostUsd()).isEqualByComparingTo("0.0015");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-14] 소프트 삭제된 대화의 assistant 메시지도 집계에 포함된다")
    void includesMessagesFromASoftDeletedConversation() {
        // given: UsageRepository queries `messages` directly with no join to `conversations`,
        //   so it never filters on `deleted_at` -- this is intentional (chat Tech Review A-3:
        //   soft delete is a repository-level *query* condition applied by conversation
        //   listing/lookup, it does not erase or hide the underlying rows from other
        //   consumers), but was previously undocumented on this class and unverified by any
        //   test (review 2026-08-27 Low-2)
        OffsetDateTime from = OffsetDateTime.parse("2099-05-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-05-02T00:00:00+09:00");
        OffsetDateTime withinPeriod = OffsetDateTime.parse("2099-05-01T12:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long assistantId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 80, 30,
                new BigDecimal("0.0008"));
        backdateMessage(assistantId, withinPeriod);
        softDeleteConversation(conversationId);

        // when
        UsageSummary summary = usageService.summarize(from, to);

        // then
        assertThat(summary.totalRequests()).isEqualTo(1L);
        assertThat(summary.totalCostUsd()).isEqualByComparingTo("0.0008");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-16] 서로 다른 모델 2종의 byModel 합계가 totals와 일치한다")
    void aggregatesByModelAndMatchesTotals() {
        // given: two assistant messages under different models, backdated into their own
        //   dedicated period (class-level [Rationale]) and queried with that exact period so the
        //   result is exact regardless of what else is running against the shared `messages` table
        OffsetDateTime from = OffsetDateTime.parse("2099-03-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-03-02T00:00:00+09:00");
        OffsetDateTime withinPeriod = OffsetDateTime.parse("2099-03-01T12:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long soLarId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 700, 300,
                new BigDecimal("1.0000"));
        backdateMessage(soLarId, withinPeriod);
        Long bgeId = saveAssistantTurn(conversationId, "baai/bge-m3", 300, 100,
                new BigDecimal("0.2300"));
        backdateMessage(bgeId, withinPeriod);

        // when
        UsageSummary summary = usageService.summarize(from, to);

        // then: two distinct model rows, ordered by model name (buildPeriodFilter contract), and
        //   their per-field sums reconcile exactly with the top-level totals for the same period
        List<ModelUsage> byModel = summary.byModel();
        assertThat(byModel).extracting(ModelUsage::model)
                .containsExactly("baai/bge-m3", "upstage/solar-pro4");

        long summedRequests = byModel.stream().mapToLong(ModelUsage::requests).sum();
        long summedPromptTokens = byModel.stream().mapToLong(ModelUsage::promptTokens).sum();
        long summedCompletionTokens = byModel.stream().mapToLong(ModelUsage::completionTokens).sum();
        BigDecimal summedCost = byModel.stream().map(ModelUsage::costUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(summedRequests).isEqualTo(summary.totalRequests());
        assertThat(summedPromptTokens).isEqualTo(summary.promptTokens());
        assertThat(summedCompletionTokens).isEqualTo(summary.completionTokens());
        assertThat(summedCost).isEqualByComparingTo(summary.totalCostUsd());
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-17] cost_usd가 null인 메시지는 totalCostUsd에서 제외되고 missingCostCount에 반영된다")
    void excludesNullCostFromTotalAndCountsMissingCost() {
        // given: three assistant messages -- two with a null cost_usd (R12: OpenRouter SDK
        //   dropped usage.cost) and one with a real cost -- all counted as requests regardless.
        //   Backdated into their own dedicated period for the same isolation reason as the AC-14
        //   and AC-16 tests above (class-level [Rationale])
        OffsetDateTime from = OffsetDateTime.parse("2099-04-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-04-02T00:00:00+09:00");
        OffsetDateTime withinPeriod = OffsetDateTime.parse("2099-04-01T12:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long nullCost1Id = saveAssistantTurn(conversationId, "upstage/solar-pro4", 100, 40, null);
        backdateMessage(nullCost1Id, withinPeriod);
        Long nullCost2Id = saveAssistantTurn(conversationId, "upstage/solar-pro4", 110, 45, null);
        backdateMessage(nullCost2Id, withinPeriod);
        Long realCostId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 120, 50,
                new BigDecimal("0.0500"));
        backdateMessage(realCostId, withinPeriod);

        // when
        UsageSummary summary = usageService.summarize(from, to);

        // then: the null-cost rows are invisible to the sum but not to the request count, and
        //   missingCostCount is the only place their absence is recorded (R12's point exactly)
        assertThat(summary.totalRequests()).isEqualTo(3L);
        assertThat(summary.totalCostUsd()).isEqualByComparingTo("0.0500");
        assertThat(summary.missingCostCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-17] 기간 내 assistant 메시지 전건이 costUsd=null이면 totalCostUsd는 null이 아닌 정확히 0이고 missingCostCount가 전건 수와 같다")
    void totalCostIsExactlyZeroNotNullWhenEveryMessageInThePeriodIsMissingCost() {
        // given: R12 realized -- every assistant row in the period is missing cost_usd, not a
        //   mix like excludesNullCostFromTotalAndCountsMissingCost above. This is exactly Spec
        //   Test Case U14's scenario and the one COALESCE(SUM(...) FILTER (...), 0) must prove
        //   itself on: with zero rows matching the FILTER, SUM alone would return SQL NULL, and
        //   only the COALESCE keeps totalCostUsd surfacing as 0 rather than null. The mixed-case
        //   test above always has at least one non-null cost row, so it cannot catch a
        //   regression that drops the COALESCE (review 2026-08-27 Medium-3)
        OffsetDateTime from = OffsetDateTime.parse("2099-06-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2099-06-02T00:00:00+09:00");
        OffsetDateTime withinPeriod = OffsetDateTime.parse("2099-06-01T12:00:00+09:00");
        UUID conversationId = createUserAndConversation();
        Long firstNullCostId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 100, 40, null);
        backdateMessage(firstNullCostId, withinPeriod);
        Long secondNullCostId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 110, 45, null);
        backdateMessage(secondNullCostId, withinPeriod);
        Long thirdNullCostId = saveAssistantTurn(conversationId, "upstage/solar-pro4", 120, 50, null);
        backdateMessage(thirdNullCostId, withinPeriod);

        // when
        UsageSummary summary = usageService.summarize(from, to);

        // then: totalCostUsd must be a real, non-null zero -- a bug that let SUM's SQL NULL
        //   leak through would make this field null instead of 0, silently breaking any caller
        //   that formats it as currency
        assertThat(summary.totalRequests()).isEqualTo(3L);
        assertThat(summary.totalCostUsd()).isNotNull();
        assertThat(summary.totalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.missingCostCount()).isEqualTo(3L);
    }

    /**
     * [Purpose] Persists a {@code role='user'} row followed by a {@code role='assistant'} row for
     *   one turn (mirroring {@code ChatService}'s real insert order, {@code Message} class-level
     *   [Caution]), and returns the assistant row's id for callers that need to
     *   {@link #backdateMessage} it afterward.
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
     *   place its rows in a fixed, collision-free period instead of wall-clock "now" — {@link
     *   Message} has no setter for this column (see class-level [Rationale]).
     */
    private void backdateMessage(Long messageId, OffsetDateTime createdAt) {
        jdbcTemplate.update("UPDATE messages SET created_at = ? WHERE id = ?", createdAt, messageId);
    }

    /**
     * [Purpose] Marks a conversation as soft-deleted via raw SQL for {@link
     *   #includesMessagesFromASoftDeletedConversation} -- {@link Conversation} exposes no
     *   setter for {@code deleted_at} because the deletion path (chat Tech Review A-3) belongs
     *   to a card this Spec does not touch, same reasoning as {@link #backdateMessage}.
     */
    private void softDeleteConversation(UUID conversationId) {
        jdbcTemplate.update("UPDATE conversations SET deleted_at = ? WHERE id = ?",
                OffsetDateTime.now(), conversationId);
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
