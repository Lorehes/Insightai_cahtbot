package art.insightai.chatbot.eval.evalset;

import java.util.List;

/**
 * [Purpose] One item of the eval-set YAML (Spec appendix schema) — either a "found" question,
 *   whose answer must cite a source and contain given strings, or a "not-found" question, whose
 *   correct answer is a refusal.
 * [Rationale] Tech Review D-3 fixes two distinct judgement rules for these two kinds of questions.
 *   A sealed interface makes {@link EvalSetLoader}'s schema validation (AC-2) exhaustive and lets
 *   downstream cards (E3 judge, D-3) pattern-match without null checks on expectSource/expectContains.
 * [AC] eval-usage-dashboard:AC-1
 * [AC] eval-usage-dashboard:AC-2
 */
public sealed interface EvalCase permits EvalCase.Found, EvalCase.NotFound {

    String question();

    /**
     * [Purpose] A "found" question — expectSource must appear among the response's source
     *   filenames and every string in expectContains must appear in the answer body (D-3).
     */
    record Found(String question, String expectSource, List<String> expectContains) implements EvalCase {
    }

    /**
     * [Purpose] A "not-found" question (YAML {@code expect: NOT_FOUND}) — the correct answer is a
     *   refusal with no sources (D-3 not_found_correct).
     */
    record NotFound(String question) implements EvalCase {
    }
}
