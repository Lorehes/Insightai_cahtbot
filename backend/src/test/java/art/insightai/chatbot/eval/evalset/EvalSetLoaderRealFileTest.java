package art.insightai.chatbot.eval.evalset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Purpose] Loads the real, production eval-set file ({@code {DOC_ROOT}/docs/eval/eval-set.yaml})
 *   and verifies the exact 25/15/10 split that {@link EvalSetLoaderTest}'s classpath fixtures
 *   deliberately do not cover (that class's own {@code @DisplayName} says so — see card E2's
 *   dev-log). Card E1 wrote the real 20-item file after R4 (company profile document) was
 *   resolved on 2026-08-25; a 2026-08-26 follow-up (user-approved, post-review) appended 5 more
 *   NOT_FOUND cases to strengthen hallucination coverage, bringing the total to 25 (15 found +
 *   10 not-found). This test is what promotes eval-usage-dashboard:AC-1 from "loader logic only"
 *   to fully verified.
 * [Rationale] Spec §7 OQ-6 places the eval-set outside the repository at {@code {DOC_ROOT}}, and
 *   Tech Review D-9 requires filesystem-dependent eval tests to carry {@code @Tag("eval")} so the
 *   default {@code ./mvnw test} build (no {@code Z:} drive dependency) stays green even when the
 *   mapped drive is disconnected -- {@code pom.xml}'s {@code excludedGroups=eval} already excludes
 *   this class from ordinary builds; {@code -Peval} is required to run it.
 * [Caution] This does not read a classpath fixture — it reads an absolute Windows path outside
 *   the repo. If {@code Z:} is not mapped, this test fails with an {@link EvalSetValidationException}
 *   wrapping an {@code IOException}, which is an environment problem, not a code defect.
 * [AC] eval-usage-dashboard:AC-1
 */
@Tag("eval")
class EvalSetLoaderRealFileTest {

    private static final Path REAL_EVAL_SET_PATH =
            Path.of("Z:/Work/02.insight-chat/docs/eval/eval-set.yaml");

    @Test
    @DisplayName("[eval-usage-dashboard:AC-1] 실제 평가셋 파일(eval-set.yaml)을 로드하면 정확히 25개 항목이 있는 질문 15개와 없는 질문 10개로 나뉜다")
    void loadsRealEvalSetFileWithExact25_15_10Split() {
        // given: the real, R4-resolved production eval-set file (not a test fixture)
        // when
        EvalSet evalSet = EvalSetLoader.load(REAL_EVAL_SET_PATH);

        // then: the exact numeric condition AC-1 requires, which the fixture-based tests in
        // EvalSetLoaderTest explicitly do not check
        assertThat(evalSet.cases()).hasSize(25);
        assertThat(evalSet.foundCases()).hasSize(15);
        assertThat(evalSet.notFoundCases()).hasSize(10);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-1] 실제 평가셋의 모든 항목이 같은 출처 파일과 실제 회사명을 담고 있다 (인코딩·파일 오염 파수꾼)")
    void realEvalSetItemsCarryExpectedSourceFileAndContent() {
        // given/when: correctness 재검토 Low-3 — 개수만 보면 파일이 CP949로 깨지거나
        // expect_source가 실제 업로드 파일명과 달라져도(그 순간 document-ingestion AC-11
        // 대조가 전량 FAIL) 이 테스트 세트는 조용히 통과한다. 최소한의 내용 단언을 더한다.
        EvalSet evalSet = EvalSetLoader.load(REAL_EVAL_SET_PATH);

        List<String> distinctSources = evalSet.foundCases().stream()
                .map(EvalCase.Found::expectSource)
                .distinct()
                .toList();

        // then
        assertThat(distinctSources)
                .as("모든 'found' 항목은 같은 문서 하나만 인용해야 한다")
                .containsExactly("2026_인사이트에이아이_회사소개서_20260819.pdf");
        assertThat(evalSet.cases())
                .as("한글이 CP949 등으로 깨져 로드되면 이 리터럴과 더 이상 일치하지 않는다")
                .anySatisfy(c -> assertThat(c.question()).contains("대표이사"));
    }
}
