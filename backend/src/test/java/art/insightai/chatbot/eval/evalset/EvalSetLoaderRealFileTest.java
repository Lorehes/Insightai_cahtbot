package art.insightai.chatbot.eval.evalset;

import art.insightai.chatbot.common.lang.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Purpose] Loads the real, production eval-set file ({@code {DOC_ROOT}/docs/eval/eval-set.yaml})
 *   and verifies the exact 15/10 split that {@link EvalSetLoaderTest}'s classpath fixtures
 *   deliberately do not cover (that class's own {@code @DisplayName} says so — see card E2's
 *   dev-log). Card E1 wrote the real 20-item file after R4 (company profile document) was
 *   resolved on 2026-08-25; a 2026-08-26 follow-up (user-approved, post-review) appended 5 more
 *   NOT_FOUND cases to strengthen hallucination coverage, bringing the total to 25 (15 found +
 *   10 not-found). This test is what promotes eval-usage-dashboard:AC-1 from "loader logic only"
 *   to fully verified.
 *   Card T9 (multilingual-support, 2026-09-02): T8 appended 21 more items (en/zh/ja, {@code lang}
 *   key set) to the same production file, bringing the total to 46. AC-9a's 15/10 gate (AD-11 ②)
 *   stays scoped to the original {@code Language.KO} subset — {@link
 *   #koSubsetOfRealEvalSetFileHasExact15_10Split()} is the AC-1 test, narrowed to that subset and
 *   renamed accordingly; two new methods cover the multilingual additions themselves (AC-7, AC-8).
 * [Rationale] Spec §7 OQ-6 places the eval-set outside the repository at {@code {DOC_ROOT}}, and
 *   Tech Review D-9 requires filesystem-dependent eval tests to carry {@code @Tag("eval")} so the
 *   default {@code ./mvnw test} build (no {@code Z:} drive dependency) stays green even when the
 *   mapped drive is disconnected -- {@code pom.xml}'s {@code excludedGroups=eval} already excludes
 *   this class from ordinary builds; {@code -Peval} is required to run it.
 * [Caution] This does not read a classpath fixture — it reads an absolute Windows path outside
 *   the repo. If {@code Z:} is not mapped, this test fails with an {@link EvalSetValidationException}
 *   wrapping an {@code IOException}, which is an environment problem, not a code defect.
 * [AC] eval-usage-dashboard:AC-1
 * [AC] multilingual-support:AC-7
 * [AC] multilingual-support:AC-8
 */
@Tag("eval")
class EvalSetLoaderRealFileTest {

    private static final Path REAL_EVAL_SET_PATH =
            Path.of("Z:/Work/02.insight-chat/docs/eval/eval-set.yaml");

    @Test
    @DisplayName("[eval-usage-dashboard:AC-1] 실제 평가셋 파일(eval-set.yaml)의 ko 부분집합은 정확히 있는 질문 15개와 없는 질문 10개로 나뉜다 (전체 46건 중)")
    void koSubsetOfRealEvalSetFileHasExact15_10Split() {
        // given: the real, R4-resolved production eval-set file, now carrying T8's 21 en/zh/ja
        // items alongside the original 25 ko items (no lang key -> Language.KO, AC-7)
        // when
        EvalSet evalSet = EvalSetLoader.load(REAL_EVAL_SET_PATH);
        List<EvalCase.Found> koFound = evalSet.foundCases().stream()
                .filter(c -> c.lang() == Language.KO)
                .toList();
        List<EvalCase.NotFound> koNotFound = evalSet.notFoundCases().stream()
                .filter(c -> c.lang() == Language.KO)
                .toList();

        // then: the exact numeric condition AC-1 requires, narrowed to the ko subset per AD-11 ②
        // -- the fixture-based tests in EvalSetLoaderTest explicitly do not check this
        assertThat(evalSet.cases()).as("전체 항목 수 (ko 25 + en/zh/ja 각 7)").hasSize(46);
        assertThat(koFound).hasSize(15);
        assertThat(koNotFound).hasSize(10);
    }

    @Test
    @DisplayName("[multilingual-support:AC-7] lang 키가 없는 기존 25개 항목은 전부 ko로 로드된다")
    void itemsWithoutLangKeyLoadAsKorean() {
        // given/when: the original 25 items predate T8 and were never touched by it (T8 note 4)
        EvalSet evalSet = EvalSetLoader.load(REAL_EVAL_SET_PATH);
        long koCount = evalSet.cases().stream().filter(c -> c.lang() == Language.KO).count();

        // then
        assertThat(koCount).as("lang 키 없는 기존 항목 수 (ko로 기본 로드)").isEqualTo(25);
    }

    @Test
    @DisplayName("[multilingual-support:AC-8] 실제 평가셋의 en/zh/ja 문항이 각각 있는 질문 5건 이상, 없는 질문 2건 이상 존재한다")
    void realEvalSetHasEnZhJaCasesWithMinimumCounts() {
        // given/when
        EvalSet evalSet = EvalSetLoader.load(REAL_EVAL_SET_PATH);

        // then
        for (Language lang : List.of(Language.EN, Language.ZH, Language.JA)) {
            long foundCount = evalSet.foundCases().stream().filter(c -> c.lang() == lang).count();
            long notFoundCount = evalSet.notFoundCases().stream().filter(c -> c.lang() == lang).count();
            assertThat(foundCount).as("%s found 문항 수", lang).isGreaterThanOrEqualTo(5);
            assertThat(notFoundCount).as("%s not_found 문항 수", lang).isGreaterThanOrEqualTo(2);
        }
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
