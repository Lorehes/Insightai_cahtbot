package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.eval.judge.EvalResponse;
import art.insightai.chatbot.eval.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Purpose] Verifies {@link EvalReportAggregator}'s in-memory aggregation rules (AC-6b, AC-8) —
 *   hand-built {@link JudgedCase} fixtures pairing {@link EvalResponse} and {@link JudgeResult},
 *   no LLM call, no sweep runner (that is E5's concern).
 */
class EvalReportAggregatorTest {

    private static JudgedCase found(boolean answerCorrect, boolean sourceCorrect, long latencyMs, BigDecimal costUsd) {
        return new JudgedCase(
                new EvalResponse("답변", List.of(), latencyMs, costUsd),
                new JudgeResult.Found(answerCorrect, sourceCorrect));
    }

    private static JudgedCase notFound(boolean notFoundCorrect, boolean hallucination, long latencyMs, BigDecimal costUsd) {
        return new JudgedCase(
                new EvalResponse("답변", List.of(), latencyMs, costUsd),
                new JudgeResult.NotFound(notFoundCorrect, hallucination));
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-8] 15개 found + 10개 not-found 전부 정답이면 6개 지표가 모두 채워진다")
    void aggregatesAllSixMetricsWhenEveryCaseIsCorrect() {
        // given: 15 found cases (all correct) + 10 not-found cases (all correct)
        List<JudgedCase> cases = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            cases.add(found(true, true, 100L, BigDecimal.valueOf(0.001)));
        }
        for (int i = 0; i < 10; i++) {
            cases.add(notFound(true, false, 100L, BigDecimal.valueOf(0.0005)));
        }

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then
        assertThat(row.answerCorrect()).isEqualTo(15);
        assertThat(row.sourceCorrect()).isEqualTo(15);
        assertThat(row.foundTotal()).isEqualTo(15);
        assertThat(row.notFoundCorrect()).isEqualTo(10);
        assertThat(row.notFoundTotal()).isEqualTo(10);
        assertThat(row.hallucinationCount()).isEqualTo(0);
        assertThat(row.avgLatencyMs()).isEqualTo(100L);
        assertThat(row.totalCostUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.001 * 15 + 0.0005 * 10));
        assertThat(row.missingCostCount()).isEqualTo(0);
        assertThat(row.passed()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-6b] hallucination=true 건수가 hallucination_count 에 정확히 반영된다")
    void hallucinationCountReflectsFlaggedCasesExactly() {
        // given: 4 not-found cases — 2 flagged as hallucination, 2 not
        List<JudgedCase> cases = List.of(
                notFound(false, true, 80L, BigDecimal.valueOf(0.0005)),
                notFound(false, true, 80L, BigDecimal.valueOf(0.0005)),
                notFound(true, false, 80L, BigDecimal.valueOf(0.0005)),
                notFound(true, false, 80L, BigDecimal.valueOf(0.0005)));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then
        assertThat(row.hallucinationCount()).isEqualTo(2);
        assertThat(row.notFoundCorrect()).isEqualTo(2);
        assertThat(row.notFoundTotal()).isEqualTo(4);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-6b] 거절문구+sources 비어있지 않은 경계 상태는 notFoundCorrect·hallucination 어느 쪽에도 집계되지 않는다")
    void doesNotCountTheUncountedThirdStateAsEitherCorrectOrHallucination() {
        // given: D-3's third state — both notFoundCorrect and hallucination are false on the
        // JudgeResult itself (this is what EvalJudge already returns for that boundary; here we
        // verify the aggregator does not derive notFoundCorrect as the complement of hallucination)
        List<JudgedCase> cases = List.of(
                notFound(false, false, 80L, BigDecimal.valueOf(0.0005)));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then: neither counter picks up this case
        assertThat(row.notFoundCorrect()).isEqualTo(0);
        assertThat(row.hallucinationCount()).isEqualTo(0);
        assertThat(row.notFoundTotal()).isEqualTo(1);
        assertThat(row.passed()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-8] avg_latency_ms 는 전체 케이스 평균을 반올림한 값이다")
    void averageLatencyIsRoundedHalfUp() {
        // given: latencies 100, 101, 101 -> mean = 100.666..., rounds up to 101
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, BigDecimal.valueOf(0.001)),
                found(true, true, 101L, BigDecimal.valueOf(0.001)),
                found(true, true, 101L, BigDecimal.valueOf(0.001)));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then
        assertThat(row.avgLatencyMs()).isEqualTo(101L);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-8] costUsd 가 null 인 케이스는 합계에서 제외되고 missingCostCount 로 노출된다")
    void excludesNullCostFromTotalAndCountsMissingCost() {
        // given: 3 cases, 1 with null cost
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, BigDecimal.valueOf(0.001)),
                found(true, true, 100L, null),
                notFound(true, false, 100L, BigDecimal.valueOf(0.0005)));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then
        assertThat(row.totalCostUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.0015));
        assertThat(row.missingCostCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-8] 전 건 costUsd 가 null 이면 total_cost_usd 는 null 이 아니라 정확히 0이다")
    void totalCostIsExactlyZeroNotNullWhenEveryCaseIsMissingCost() {
        // given: every case has a null cost
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, null),
                found(true, true, 100L, null),
                notFound(true, false, 100L, null));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then
        assertThat(row.totalCostUsd()).isNotNull();
        assertThat(row.totalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.missingCostCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-9a] 빈 입력을 집계한 행은 미통과다 — fail-closed")
    void aggregatingAnEmptyListProducesARowThatDoesNotPass() {
        // given: no judged cases at all (2026-08-28 review Medium-2's other repro —
        // previously this all-zero row passed because 0 >= 0)

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(List.of());

        // then
        assertThat(row.passed()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-8] found 13건(전부 정답) 집계 시 foundTotal 은 하드코딩된 15가 아니라 실제 판정 개수 13이고, 고정 규모(15)에 못미쳐 passed() 는 미통과다")
    void foundTotalReflectsTheActualJudgedCountNotAHardcodedFifteen() {
        // given: 13 found cases (all correct) + 10 not-found cases (all correct) — a judged run
        // short of the fixed 15-found eval-set size, with a not-found axis that is otherwise
        // perfect (2026-08-28 review Medium-1's repro: without EvalReportRow#passed()'s
        // fail-closed guard, 10/10 not-found + 13>=13 answer-correct would read as a pass, and
        // without this assertion on foundTotal a hardcoded foundTotal=15 in the aggregator would
        // go undetected since the only other foundTotal assertion in this file happens to use an
        // input of exactly 15)
        List<JudgedCase> cases = new java.util.ArrayList<>();
        for (int i = 0; i < 13; i++) {
            cases.add(found(true, true, 100L, BigDecimal.valueOf(0.001)));
        }
        for (int i = 0; i < 10; i++) {
            cases.add(notFound(true, false, 100L, BigDecimal.valueOf(0.0005)));
        }

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then: foundTotal is genuinely derived (13, not a hardcoded 15) — and the fail-closed
        // guard still rejects this row because foundTotal (13) does not match the fixed
        // eval-set size (15), even though the numerators alone would read as a pass (10/10
        // not-found + 13/13 answer-correct)
        assertThat(row.foundTotal()).isEqualTo(13);
        assertThat(row.passed()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-8] 음수 costUsd 는 합계에서 제외되고 missingCostCount 로 계상된다")
    void excludesNegativeCostFromTotalAndCountsMissingCost() {
        // given: 2 cases, 1 with a negative (unusable) cost
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, BigDecimal.valueOf(0.001)),
                found(true, true, 100L, BigDecimal.valueOf(-0.5)));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then: L3, 2026-08-28 review — negative cost is treated the same as null cost
        assertThat(row.totalCostUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.001));
        assertThat(row.missingCostCount()).isEqualTo(1);
    }
}
