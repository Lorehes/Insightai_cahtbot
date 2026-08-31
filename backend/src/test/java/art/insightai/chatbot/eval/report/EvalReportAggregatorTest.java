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

    private static JudgedCase found(
            boolean answerCorrect, boolean sourceCorrect, long latencyMs, BigDecimal costUsd,
            int retrievedNonIndexedChunkCount, String finishReason) {
        return new JudgedCase(
                new EvalResponse("답변", List.of(), latencyMs, costUsd),
                new JudgeResult.Found(answerCorrect, sourceCorrect),
                retrievedNonIndexedChunkCount, finishReason);
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

    @Test
    @DisplayName("[eval-usage-dashboard:AC-19a] 제외 대상 건수 필드는 값이 있어도 판정 유보가 아니고, 회수 청크 쪽 1건은 판정 유보다 — 필드1(제외 대상)은 상태값별 건수로 표시된다")
    void excludedByStatusIsNotDeferralButRetrievedNonIndexedChunkIs() {
        // given: a per-run excluded-by-status snapshot with two statuses (U15's "값별 건수
        // 표시" requirement), plus one judged case whose retrieval actually returned 1
        // non-INDEXED chunk
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, BigDecimal.valueOf(0.001), 1, null),
                notFound(true, false, 80L, BigDecimal.valueOf(0.0005)));
        List<EvalExcludedDocumentStatusCount> excludedByStatus = List.of(
                new EvalExcludedDocumentStatusCount("FAILED", 2),
                new EvalExcludedDocumentStatusCount("PENDING", 1));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases, excludedByStatus);

        // then: field 1 passes through untouched and is not, by itself, a deferral reason
        assertThat(row.excludedByStatus()).containsExactlyInAnyOrder(
                new EvalExcludedDocumentStatusCount("FAILED", 2),
                new EvalExcludedDocumentStatusCount("PENDING", 1));
        // and: field 2 (1 non-INDEXED chunk actually retrieved) alone defers judgment
        assertThat(row.retrievedNonIndexedChunkCount()).isEqualTo(1);
        assertThat(row.judgmentDeferred()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-20a] finish_reason 분포에 length 1건이 포함되면 판정 유보이고, null 이 별도 값으로 집계되어 행에서 누락되지 않는다")
    void finishReasonDistributionIncludesLengthAndPreservesNullBucket() {
        // given: 3 cases — one with a null finish reason (never recorded), one "STOP" (mixed
        // case, as chat's ChatChunk normalization or an SDK enum constant might produce), and
        // one "length" that must trigger judgment deferral
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, BigDecimal.valueOf(0.001), 0, null),
                found(true, true, 100L, BigDecimal.valueOf(0.001), 0, "STOP"),
                found(true, true, 100L, BigDecimal.valueOf(0.001), 0, "length"));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases, List.of());

        // then: three distinct buckets, null preserved as its own value, "STOP" lowered to "stop"
        assertThat(row.finishReasonCounts()).containsExactlyInAnyOrder(
                new FinishReasonCount(null, 1),
                new FinishReasonCount("stop", 1),
                new FinishReasonCount("length", 1));
        assertThat(row.judgmentDeferred()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-20a] length 가 0건이면 finish_reason 분포만으로는 판정 유보가 아니다")
    void noLengthCaseDoesNotDeferJudgmentViaFinishReasonDistribution() {
        // given: every case finished with "stop", none with "length"
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, BigDecimal.valueOf(0.001), 0, "stop"),
                found(true, true, 100L, BigDecimal.valueOf(0.001), 0, "stop"));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases, List.of());

        // then
        assertThat(row.finishReasonCounts()).containsExactly(new FinishReasonCount("stop", 2));
        assertThat(row.judgmentDeferred()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-19a] retrievedNonIndexedChunkCount 은 케이스별 개수의 총합이다 — 최댓값도 건수(0/1)도 아니다")
    void retrievedNonIndexedChunkCountSumsAcrossMultipleContributingCases() {
        // given: three found cases contributing 2, 3, and 0 non-INDEXED chunks respectively.
        // The true sum (5) differs from both a cardinality mutant (count>0?1:0 -> 1+1+0=2) and a
        // Math.max mutant (max(2,3,0)=3), so either mutation is caught here (2026-08-30 review
        // Medium-2 — a single-contributor fixture elsewhere in this file could not distinguish
        // "sum" from "count" or "max").
        List<JudgedCase> cases = List.of(
                found(true, true, 100L, BigDecimal.valueOf(0.001), 2, null),
                found(true, true, 100L, BigDecimal.valueOf(0.001), 3, null),
                found(true, true, 100L, BigDecimal.valueOf(0.001), 0, null));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases, List.of());

        // then: true sum is 5, not 2 (a "did any case contribute" count) and not 3 (max)
        assertThat(row.retrievedNonIndexedChunkCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("아무 오염 신호도 없는 판정이면 새 필드는 모두 비어 있거나 0이고 판정 유보가 아니다")
    void aggregateOneArgOverloadYieldsNoContaminationSignals() {
        // given: the pre-existing 1-arg overload every AC-6b/AC-8/AC-9a test in this file uses
        List<JudgedCase> cases = List.of(found(true, true, 100L, BigDecimal.valueOf(0.001)));

        // when
        EvalReportRow row = EvalReportAggregator.aggregate(cases);

        // then
        assertThat(row.excludedByStatus()).isEmpty();
        assertThat(row.retrievedNonIndexedChunkCount()).isZero();
        assertThat(row.finishReasonCounts()).containsExactly(new FinishReasonCount(null, 1));
        assertThat(row.judgmentDeferred()).isFalse();
    }
}
