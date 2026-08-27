package art.insightai.chatbot.eval.judge;

/**
 * [Purpose] The outcome of judging one {@code EvalCase} against an {@link EvalResponse} —
 *   split by question kind because "found" and "not-found" questions are scored on different
 *   axes (Tech Review D-3).
 * [Rationale] A sealed interface mirrors {@code EvalCase}'s Found/NotFound split so
 *   {@link EvalJudge} and the future reporter (E4) can pattern-match exhaustively instead of
 *   cramming both axes into one ambiguous boolean. {@code Found} keeps {@code answerCorrect} and
 *   {@code sourceCorrect} as two separate fields per AC-4 — collapsing them into a single
 *   "correct" boolean would lose exactly the distinction AC-4 requires to be recorded.
 */
public sealed interface JudgeResult permits JudgeResult.Found, JudgeResult.NotFound {

    /**
     * [Purpose] Whether this case counts as a pass for the report's overall pass rate (AC-8/AC-9,
     *   E4's concern) — {@code Found} requires both axes to be correct; {@code NotFound} requires
     *   only the refusal judgement.
     */
    boolean pass();

    /**
     * [Purpose] Result of judging an {@code EvalCase.Found} (AC-3, AC-4). {@code answerCorrect}
     *   and {@code sourceCorrect} are recorded separately so a content-correct-but-uncited answer
     *   is distinguishable from a wrong answer (AC-4).
     * [AC] eval-usage-dashboard:AC-3
     * [AC] eval-usage-dashboard:AC-4
     */
    record Found(boolean answerCorrect, boolean sourceCorrect) implements JudgeResult {
        @Override
        public boolean pass() {
            return answerCorrect && sourceCorrect;
        }
    }

    /**
     * [Purpose] Result of judging an {@code EvalCase.NotFound} (AC-5, AC-6a). {@code notFoundCorrect}
     *   and {@code hallucination} are not complements of each other: Tech Review D-3 defines a third,
     *   uncounted state (refusal phrase present but {@code sources} non-empty) where both are false.
     * [AC] eval-usage-dashboard:AC-5
     * [AC] eval-usage-dashboard:AC-6a
     */
    record NotFound(boolean notFoundCorrect, boolean hallucination) implements JudgeResult {
        @Override
        public boolean pass() {
            return notFoundCorrect;
        }
    }
}
