package art.insightai.chatbot.eval.judge;

import art.insightai.chatbot.eval.evalset.EvalCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Purpose] Verifies {@link EvalJudge}'s pure judgement rules (Tech Review D-3) against
 *   hand-built {@link EvalResponse} fixtures — no LLM call, no chatbot pipeline, matching this
 *   class's own "pure function" design (E5 will supply real responses later).
 */
class EvalJudgeTest {

    private static final EvalCase.Found FOUND_CASE = new EvalCase.Found(
            "인사이트에이아이는 언제 설립됐어?", "2026_인사이트에이아이_회사소개서_20260819.pdf", List.of("2026", "설립"));

    private static final EvalCase.NotFound NOT_FOUND_CASE = new EvalCase.NotFound("이 챗봇이 좋아하는 음식은?");

    @Test
    @DisplayName("[eval-usage-dashboard:AC-3] expect_contains 전부 포함 + 정답 출처 인용 시 PASS")
    void foundCasePassesWhenAnswerAndSourceAreBothCorrect() {
        // given: an answer containing every expectContains string, citing the expected source
        EvalResponse response = new EvalResponse(
                "인사이트에이아이는 2026년에 설립되었습니다.",
                List.of("2026_인사이트에이아이_회사소개서_20260819.pdf"),
                120L,
                BigDecimal.valueOf(0.001));

        // when
        JudgeResult.Found result = EvalJudge.judgeFound(FOUND_CASE, response);

        // then
        assertThat(result.answerCorrect()).isTrue();
        assertThat(result.sourceCorrect()).isTrue();
        assertThat(result.pass()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-3] expect_contains 와 답변의 공백(ASCII·유니코드 구분자) 차이는 무시된다 — "
            + "\"17 명\" 은 \"17명\" 을, \"서초구 반포대로\" 는 \"서초구반포대로\" 를 만족한다 (BL-043 (a), D-3 개정)")
    void foundCaseIgnoresWhitespaceDifferencesOnly() {
        EvalCase.Found spaced = new EvalCase.Found("직원 수?", "doc.pdf", List.of("17명", "서초구 반포대로"));
        EvalResponse response = new EvalResponse(
                "17 명 (정규직 기준)이며 주소는 서초구 반포대로 20길입니다.", List.of("doc.pdf"), 1L, null);

        assertThat(EvalJudge.judgeFound(spaced, response).answerCorrect()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-3] 공백 외의 차이(대소문자·구두점·글자 삽입)는 여전히 FAIL 이다 — 정규화는 공백만 접는다")
    void foundCaseStillFailsOnAnyNonWhitespaceDifference() {
        EvalCase.Found expect = new EvalCase.Found("q", "doc.pdf", List.of("17명", "plan@insightai.art"));
        assertThat(EvalJudge.judgeFound(expect,
                new EvalResponse("17개 사업, 명단은 plan@insightai.art", List.of("doc.pdf"), 1L, null)).answerCorrect())
                .as("characters of the expected string separated by other characters do not match").isFalse();
        assertThat(EvalJudge.judgeFound(expect,
                new EvalResponse("17명, PLAN@INSIGHTAI.ART", List.of("doc.pdf"), 1L, null)).answerCorrect())
                .as("no case folding").isFalse();
        assertThat(EvalJudge.judgeFound(expect,
                new EvalResponse("17.명 plan@insightai.art", List.of("doc.pdf"), 1L, null)).answerCorrect())
                .as("no punctuation stripping").isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-3] expect_contains 중 1개라도 누락되면 FAIL")
    void foundCaseFailsWhenAnExpectContainsStringIsMissing() {
        // given: the answer is missing "설립" even though it cites the right source
        EvalResponse response = new EvalResponse(
                "인사이트에이아이는 2026년에 시작되었습니다.",
                List.of("2026_인사이트에이아이_회사소개서_20260819.pdf"),
                120L,
                BigDecimal.valueOf(0.001));

        // when
        JudgeResult.Found result = EvalJudge.judgeFound(FOUND_CASE, response);

        // then
        assertThat(result.answerCorrect()).isFalse();
        assertThat(result.pass()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-4] 답변 내용은 맞지만 sources에 정답 출처가 없으면 answerCorrect=true, sourceCorrect=false로 분리 기록된다")
    void foundCaseRecordsAnswerAndSourceCorrectnessSeparatelyWhenSourceIsMissing() {
        // given: answer text is fully correct, but the cited source list omits expectSource
        EvalResponse response = new EvalResponse(
                "인사이트에이아이는 2026년에 설립되었습니다.",
                List.of("다른-문서.pdf"),
                120L,
                BigDecimal.valueOf(0.001));

        // when
        JudgeResult.Found result = EvalJudge.judgeFound(FOUND_CASE, response);

        // then: the two axes are distinct — this is exactly the split AC-4 requires
        assertThat(result.answerCorrect()).isTrue();
        assertThat(result.sourceCorrect()).isFalse();
        assertThat(result.pass()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-5] 거절 문구를 포함하고 sources가 비어 있으면 NOT_FOUND PASS")
    void notFoundCasePassesWhenAnswerRefusesAndCitesNoSources() {
        // given: the fixed refusal phrase (hybrid-rag AC-5), no sources
        EvalResponse response = new EvalResponse(
                "제공된 문서에서 찾을 수 없습니다.",
                List.of(),
                80L,
                BigDecimal.valueOf(0.0005));

        // when
        JudgeResult.NotFound result = EvalJudge.judgeNotFound(NOT_FOUND_CASE, response);

        // then
        assertThat(result.notFoundCorrect()).isTrue();
        assertThat(result.hallucination()).isFalse();
        assertThat(result.pass()).isTrue();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-5][eval-usage-dashboard:AC-6a] 거절 문구는 있으나 sources가 비어 있지 않으면 어느 쪽으로도 계상되지 않는다")
    void notFoundCaseCountsNeitherWhenRefusalPhrasePresentButSourcesNonEmpty() {
        // given: D-3's uncounted third state — refusal text present, but sources non-empty
        EvalResponse response = new EvalResponse(
                "제공된 문서에서 찾을 수 없습니다.",
                List.of("무관한-문서.pdf"),
                80L,
                BigDecimal.valueOf(0.0005));

        // when
        JudgeResult.NotFound result = EvalJudge.judgeNotFound(NOT_FOUND_CASE, response);

        // then
        assertThat(result.notFoundCorrect()).isFalse();
        assertThat(result.hallucination()).isFalse();
        assertThat(result.pass()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-6a] 거절하지 않고 사실을 진술하면 hallucination=true로 집계된다")
    void notFoundCaseFlagsHallucinationWhenAnswerAssertsAFactInsteadOfRefusing() {
        // given: the model states a fact instead of refusing
        EvalResponse response = new EvalResponse(
                "이 챗봇이 가장 좋아하는 음식은 김치찌개입니다.",
                List.of(),
                80L,
                BigDecimal.valueOf(0.0005));

        // when
        JudgeResult.NotFound result = EvalJudge.judgeNotFound(NOT_FOUND_CASE, response);

        // then
        assertThat(result.notFoundCorrect()).isFalse();
        assertThat(result.hallucination()).isTrue();
        assertThat(result.pass()).isFalse();
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-3][eval-usage-dashboard:AC-5] judge()는 EvalCase 종류에 따라 올바른 판정 규칙으로 위임한다")
    void judgeDispatchesToTheMatchingRuleSetByCaseType() {
        // given
        EvalResponse foundResponse = new EvalResponse(
                "인사이트에이아이는 2026년에 설립되었습니다.",
                List.of("2026_인사이트에이아이_회사소개서_20260819.pdf"),
                120L,
                BigDecimal.valueOf(0.001));
        EvalResponse notFoundResponse = new EvalResponse("제공된 문서에서 찾을 수 없습니다.", List.of(), 80L, BigDecimal.valueOf(0.0005));

        // when
        JudgeResult foundResult = EvalJudge.judge(FOUND_CASE, foundResponse);
        JudgeResult notFoundResult = EvalJudge.judge(NOT_FOUND_CASE, notFoundResponse);

        // then
        assertThat(foundResult).isInstanceOf(JudgeResult.Found.class);
        assertThat(foundResult.pass()).isTrue();
        assertThat(notFoundResult).isInstanceOf(JudgeResult.NotFound.class);
        assertThat(notFoundResult.pass()).isTrue();
    }
}
