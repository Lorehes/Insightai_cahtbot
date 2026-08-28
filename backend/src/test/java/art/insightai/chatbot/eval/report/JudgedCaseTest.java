package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.eval.judge.EvalResponse;
import art.insightai.chatbot.eval.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Purpose] Verifies {@link JudgedCase#toString()} does not embed the full {@link
 *   EvalResponse#answer()} body (L2, 2026-08-28 review) — the override closes a log-exposure path
 *   before E5 (sweep runner) starts logging {@code JudgedCase} instances.
 */
class JudgedCaseTest {

    @Test
    @DisplayName("toString() 에는 답변 전문이 포함되지 않고 길이만 노출된다")
    void toStringDoesNotContainTheFullAnswerBody() {
        // given: an answer long enough that its presence in toString() would be unmistakable
        String answer = "이것은 사내 문서에서 발췌한 민감한 내부 정보를 포함하는 매우 긴 답변 본문입니다.";
        JudgedCase judgedCase = new JudgedCase(
                new EvalResponse(answer, List.of("doc.pdf"), 100L, BigDecimal.valueOf(0.001)),
                new JudgeResult.Found(true, true));

        // when
        String rendered = judgedCase.toString();

        // then: the full answer text is absent, but its length is present
        assertThat(rendered).doesNotContain(answer);
        assertThat(rendered).contains("answer.length=" + answer.length());
    }
}
