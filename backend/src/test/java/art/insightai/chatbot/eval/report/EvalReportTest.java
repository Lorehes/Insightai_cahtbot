package art.insightai.chatbot.eval.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Purpose] Verifies {@link EvalReport#anyCombinationPassed()} — AC-9a's "1개 이상이면 통과" rule
 *   evaluated across multiple rows, which no single {@link EvalReportRow} can answer alone.
 */
class EvalReportTest {

    private static EvalReportRow row(int answerCorrect, int foundTotal, int notFoundCorrect, int notFoundTotal) {
        return new EvalReportRow(
                answerCorrect, answerCorrect, foundTotal,
                notFoundCorrect, notFoundTotal, 0,
                0L, BigDecimal.ZERO, 0);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-9a] 4개 행 중 1개만 통과해도 전체가 통과로 집계된다")
    void anyCombinationPassedIsTrueWhenOneRowOutOfFourPasses() {
        // given: 3 failing combinations, 1 passing combination
        EvalReport report = new EvalReport(List.of(
                row(10, 15, 10, 10),
                row(11, 15, 10, 10),
                row(13, 15, 9, 10),
                row(13, 15, 10, 10)));

        // when / then
        assertThat(report.anyCombinationPassed()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-9a] 통과한 행이 하나도 없으면 전체가 미통과로 집계된다")
    void anyCombinationPassedIsFalseWhenNoRowPasses() {
        // given: every combination fails
        EvalReport report = new EvalReport(List.of(
                row(10, 15, 10, 10),
                row(11, 15, 10, 10),
                row(13, 15, 9, 10)));

        // when / then
        assertThat(report.anyCombinationPassed()).isFalse();
    }

    @Test
    @DisplayName("생성 후 원본 리스트를 변경해도 anyCombinationPassed() 결과가 바뀌지 않는다")
    void isNotAffectedByMutationOfTheOriginalListAfterConstruction() {
        // given: a mutable backing list with a single failing row
        List<EvalReportRow> backing = new ArrayList<>(List.of(row(10, 15, 10, 10)));
        EvalReport report = new EvalReport(backing);

        // when: the caller mutates the list it originally passed in
        backing.add(row(13, 15, 10, 10));

        // then: the report's verdict was fixed at construction time (L1, 2026-08-28 review)
        assertThat(report.anyCombinationPassed()).isFalse();
    }
}
