package art.insightai.chatbot.eval.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
}
