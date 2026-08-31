package art.insightai.chatbot.eval.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Purpose] Verifies {@link EvalReportRow#passed()}'s AC-9a boundary values directly, independent
 *   of {@link EvalReportAggregator} — the 10/10 + 13/15 pass rule and its two adjacent failing
 *   boundaries (Spec Test Case U9, 2026-08-26 denominator revision to 10/10), plus the
 *   fail-closed denominator guard (2026-08-28 review Medium-2) and the compact constructor's
 *   invariant checks (2026-08-28 review L7).
 */
class EvalReportRowTest {

    private static EvalReportRow row(int answerCorrect, int foundTotal, int notFoundCorrect, int notFoundTotal) {
        return new EvalReportRow(
                answerCorrect, answerCorrect, foundTotal,
                notFoundCorrect, notFoundTotal, 0,
                0L, BigDecimal.ZERO, 0);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-9a] not_found 10/10 + answer 13/15 이면 통과한다")
    void passesAtTheExactBoundary() {
        // given: the exact minimum passing combination
        EvalReportRow row = row(13, 15, 10, 10);

        // when / then
        assertThat(row.passed()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-9a] not_found 10/10 이어도 answer 12/15 이면 미통과한다")
    void failsWhenAnswerCorrectIsOneBelowTheBoundary() {
        // given: not-found is perfect but answer falls one below 13/15
        EvalReportRow row = row(12, 15, 10, 10);

        // when / then
        assertThat(row.passed()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-9a] answer 15/15 이어도 not_found 9/10 이면 미통과한다 — 비율 완화 없음")
    void failsWhenNotFoundIsNotPerfectEvenIfAnswerIsPerfect() {
        // given: not-found correctness is not 100%, even though answer is a perfect 15/15
        EvalReportRow row = row(15, 15, 9, 10);

        // when / then: AC-9a requires 100% on the not-found axis, no ratio allowed
        assertThat(row.passed()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-9a] 분모가 15/10 이 아니면 비율상 통과 조건을 충족해도 미통과한다 — fail-closed")
    void failsClosedWhenDenominatorsDoNotMatchTheFixedEvalSetSize() {
        // given: 2026-08-28 review Medium-2's exact repro — 8/9 found-correct (ratio-wise a pass,
        // 8*15 >= 13*9) and a perfect but short not-found axis (9/9, not the fixed 10)
        EvalReportRow row = new EvalReportRow(8, 8, 9, 9, 9, 0, 0L, BigDecimal.ZERO, 0);

        // when / then: denominators don't match the fixed 15/10 eval-set size, so this fails
        // regardless of how favorable the numerators look
        assertThat(row.passed()).isFalse();
    }

    @Test
    @DisplayName("음수 카운트는 컴팩트 생성자에서 즉시 예외로 거부된다")
    void rejectsNegativeCounts() {
        // given / when / then
        assertThatThrownBy(() -> new EvalReportRow(-1, 0, 0, 0, 0, 0, 0L, BigDecimal.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("answerCorrect 가 foundTotal 을 초과하면 컴팩트 생성자에서 즉시 예외로 거부된다")
    void rejectsAnswerCorrectExceedingFoundTotal() {
        // given / when / then
        assertThatThrownBy(() -> new EvalReportRow(2, 0, 1, 0, 0, 0, 0L, BigDecimal.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("notFoundCorrect 가 notFoundTotal 을 초과하면 컴팩트 생성자에서 즉시 예외로 거부된다")
    void rejectsNotFoundCorrectExceedingNotFoundTotal() {
        // given / when / then
        assertThatThrownBy(() -> new EvalReportRow(0, 0, 0, 2, 1, 0, 0L, BigDecimal.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("hallucinationCount 가 notFoundTotal 을 초과하면 컴팩트 생성자에서 즉시 예외로 거부된다")
    void rejectsHallucinationCountExceedingNotFoundTotal() {
        // given / when / then
        assertThatThrownBy(() -> new EvalReportRow(0, 0, 0, 0, 1, 2, 0L, BigDecimal.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-19a] 제외 대상 건수 필드에 값이 있어도 판정 유보가 아니다")
    void excludedByStatusAloneDoesNotDeferJudgment() {
        // given: a row whose only contamination signal is the per-run excluded-by-status
        // snapshot (field 1) — the Spec is explicit that this alone is evidence the filter is
        // working, not a reason to defer judgment
        EvalReportRow row = new EvalReportRow(
                13, 13, 15, 10, 10, 0, 0L, BigDecimal.ZERO, 0,
                List.of(new EvalExcludedDocumentStatusCount("FAILED", 3)), 0, List.of());

        // when / then: the actual discriminating point is judgmentDeferred() — whether
        // excludedByStatus() returns what it was given is a pass-through, no judgment involved
        // (2026-08-30 review Low-4/correctness-Low-1). Value-preservation of excludedByStatus is
        // already exercised meaningfully by EvalReportAggregatorTest's aggregate(cases,
        // excludedByStatus) test, which builds the list through real aggregation rather than
        // handing it straight back.
        assertThat(row.judgmentDeferred()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-19a] 회수 청크 중 INDEXED 외 상태가 1건이라도 있으면 판정 유보다")
    void retrievedNonIndexedChunkDefersJudgment() {
        // given: field 2 (chunks actually returned by retrieval) has 1 non-INDEXED chunk
        EvalReportRow row = new EvalReportRow(
                13, 13, 15, 10, 10, 0, 0L, BigDecimal.ZERO, 0,
                List.of(), 1, List.of());

        // when / then
        assertThat(row.judgmentDeferred()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-20a] finish_reason 분포에 length 1건이 있으면 판정 유보이고, null 은 별도 값으로 집계되어 행에서 빠지지 않는다")
    void lengthFinishReasonDefersJudgmentAndNullIsItsOwnBucket() {
        // given: two buckets — a null finish reason (unrecorded) and a "length" finish reason
        EvalReportRow row = new EvalReportRow(
                13, 13, 15, 10, 10, 0, 0L, BigDecimal.ZERO, 0,
                List.of(), 0, List.of(new FinishReasonCount(null, 2), new FinishReasonCount("length", 1)));

        // when / then: the actual discriminating point is judgmentDeferred() — asserting that
        // finishReasonCounts() contains what it was given back is a pass-through, no judgment
        // involved (2026-08-30 review Low-4/correctness-Low-1). Null-bucket preservation under
        // real aggregation is already exercised meaningfully by
        // EvalReportAggregatorTest#finishReasonDistributionIncludesLengthAndPreservesNullBucket.
        assertThat(row.judgmentDeferred()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-20a] finish_reason 분포에 length 가 없으면 그 신호만으로는 판정 유보가 아니다")
    void noLengthFinishReasonDoesNotDeferJudgmentOnItsOwn() {
        // given: only a "stop" bucket, no "length"
        EvalReportRow row = new EvalReportRow(
                13, 13, 15, 10, 10, 0, 0L, BigDecimal.ZERO, 0,
                List.of(), 0, List.of(new FinishReasonCount("stop", 25)));

        // when / then
        assertThat(row.judgmentDeferred()).isFalse();
    }

    @Test
    @DisplayName("excludedByStatus 가 null 이면 컴팩트 생성자에서 즉시 예외로 거부된다")
    void rejectsNullExcludedByStatus() {
        // given / when / then
        assertThatThrownBy(() -> new EvalReportRow(0, 0, 0, 0, 0, 0, 0L, BigDecimal.ZERO, 0, null, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("finishReasonCounts 가 null 이면 컴팩트 생성자에서 즉시 예외로 거부된다")
    void rejectsNullFinishReasonCounts() {
        // given / when / then
        assertThatThrownBy(() -> new EvalReportRow(0, 0, 0, 0, 0, 0, 0L, BigDecimal.ZERO, 0, List.of(), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("retrievedNonIndexedChunkCount 가 음수이면 컴팩트 생성자에서 즉시 예외로 거부된다")
    void rejectsNegativeRetrievedNonIndexedChunkCount() {
        // given / when / then
        assertThatThrownBy(() -> new EvalReportRow(0, 0, 0, 0, 0, 0, 0L, BigDecimal.ZERO, 0, List.of(), -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("레거시 9-인자 생성자는 오염 지표 필드를 빈 값/0 으로 채운다")
    void legacyNineArgConstructorDefaultsContaminationFieldsToEmpty() {
        // given: the pre-existing 9-arg shape every AC-6b/AC-8/AC-9a test uses
        EvalReportRow row = new EvalReportRow(13, 13, 15, 10, 10, 0, 100L, BigDecimal.ZERO, 0);

        // when / then: "no contamination observed" defaults, and the row is never deferred
        assertThat(row.excludedByStatus()).isEmpty();
        assertThat(row.retrievedNonIndexedChunkCount()).isZero();
        assertThat(row.finishReasonCounts()).isEmpty();
        assertThat(row.judgmentDeferred()).isFalse();
    }
}
