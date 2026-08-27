package art.insightai.chatbot.eval.judge;

import art.insightai.chatbot.eval.evalset.EvalCase;

/**
 * [Purpose] Pure judgement logic for one {@link EvalCase} against one {@link EvalResponse} —
 *   no LLM call, no database, no chatbot pipeline dependency. Card E5 (sweep runner) will call
 *   {@link #judge(EvalCase, EvalResponse)} once the real hybrid-rag pipeline exists to produce
 *   {@link EvalResponse} instances; until then this class is exercised only with hand-built
 *   {@link EvalResponse} fixtures in tests.
 * [Rationale] Tech Review D-3 fixes every rule this class implements:
 *   {@code answer_correct} = every {@code expectContains} string is a substring of the answer
 *   (no normalization); {@code source_correct} = {@code expectSource} is among
 *   {@code sourceFilenames}; {@code not_found_correct} = the refusal phrase is present AND
 *   {@code sourceFilenames} is empty; {@code hallucination} = the refusal phrase is absent.
 *   The refusal-phrase match itself is deterministic, not an LLM-as-judge call: hybrid-rag
 *   Spec D-T8 already commits the application (not the LLM) to emitting this exact fixed string
 *   whenever the zero-result "not found" path is taken (hybrid-rag:AC-5), so a plain substring
 *   check on {@link #REJECTION_PHRASE} is reliable — there is no free-form LLM wording to guess
 *   at. This resolves Spec OQ-4 (AND of phrase match + empty sources) without inventing a
 *   keyword list.
 * [Caution] {@link #REJECTION_PHRASE} is an independent copy of the refusal string hybrid-rag
 *   card T9 will actually emit, not a compile-time dependency (D-2 keeps eval isolated from
 *   hybrid-rag on purpose). When T9 is implemented, its emitted string MUST be diffed against
 *   this constant by hand — a single character mismatch makes every NOT_FOUND case silently
 *   misjudge {@code hallucination=true} (the refusal-phrase check in
 *   {@link #judgeNotFound(EvalCase.NotFound, EvalResponse)} would never match).
 * [AC] eval-usage-dashboard:AC-3
 * [AC] eval-usage-dashboard:AC-4
 * [AC] eval-usage-dashboard:AC-5
 * [AC] eval-usage-dashboard:AC-6a
 */
public final class EvalJudge {

    /**
     * [Rationale] Tech Review D-3: hybrid-rag Spec AC-5's fixed refusal string, trailing period
     *   excluded from the match per D-3's explicit note.
     * [Caution] This is an independent copy, not sourced from hybrid-rag code (D-2 forbids a
     *   compile-time dependency on the hybrid-rag module). It must be re-verified against the
     *   actual string hybrid-rag T9 emits once T9 is implemented — a one-character drift makes
     *   every NOT_FOUND case silently misjudge {@code hallucination=true}.
     */
    static final String REJECTION_PHRASE = "제공된 문서에서 찾을 수 없습니다";

    private EvalJudge() {
    }

    /**
     * [Purpose] Judges either kind of {@link EvalCase}, dispatching to the matching rule set.
     */
    public static JudgeResult judge(EvalCase evalCase, EvalResponse response) {
        return switch (evalCase) {
            case EvalCase.Found found -> judgeFound(found, response);
            case EvalCase.NotFound notFound -> judgeNotFound(notFound, response);
        };
    }

    /**
     * [Purpose] AC-3/AC-4: scores a "found" question. {@code answerCorrect} and
     *   {@code sourceCorrect} are computed independently and never collapsed, so a caller can
     *   tell a wrong-answer FAIL apart from a right-answer-wrong-citation FAIL (AC-4).
     */
    public static JudgeResult.Found judgeFound(EvalCase.Found evalCase, EvalResponse response) {
        boolean answerCorrect = evalCase.expectContains().stream().allMatch(response.answer()::contains);
        boolean sourceCorrect = response.sourceFilenames().contains(evalCase.expectSource());
        return new JudgeResult.Found(answerCorrect, sourceCorrect);
    }

    /**
     * [Purpose] AC-5/AC-6: scores a "not-found" question per the D-3 truth table, including the
     *   uncounted third state (refusal phrase present but {@code sourceFilenames} non-empty),
     *   where neither {@code notFoundCorrect} nor {@code hallucination} is set.
     */
    public static JudgeResult.NotFound judgeNotFound(EvalCase.NotFound evalCase, EvalResponse response) {
        boolean refused = response.answer().contains(REJECTION_PHRASE);
        boolean sourcesEmpty = response.sourceFilenames().isEmpty();
        boolean notFoundCorrect = refused && sourcesEmpty;
        boolean hallucination = !refused;
        return new JudgeResult.NotFound(notFoundCorrect, hallucination);
    }
}
