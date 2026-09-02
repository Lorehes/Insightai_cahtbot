package art.insightai.chatbot.eval.evalset;

import art.insightai.chatbot.common.lang.Language;

import java.util.List;

/**
 * [Purpose] One item of the eval-set YAML (Spec appendix schema) — either a "found" question,
 *   whose answer must cite a source and contain given strings, or a "not-found" question, whose
 *   correct answer is a refusal.
 * [Rationale] Tech Review D-3 fixes two distinct judgement rules for these two kinds of questions.
 *   A sealed interface makes {@link EvalSetLoader}'s schema validation (AC-2) exhaustive and lets
 *   downstream cards (E3 judge, D-3) pattern-match without null checks on expectSource/expectContains.
 *   AD-9 adds the {@code lang} component (defaulted to {@link Language#KO} by
 *   {@link EvalSetLoader}) so AC-8/AC-9/AC-11 can group and judge cases per question language.
 * [AC] eval-usage-dashboard:AC-1
 * [AC] eval-usage-dashboard:AC-2
 * [AC] multilingual-support:AC-7
 */
public sealed interface EvalCase permits EvalCase.Found, EvalCase.NotFound {

    String question();

    Language lang();

    /**
     * [Purpose] A "found" question — expectSource must appear among the response's source
     *   filenames and every string in expectContains must appear in the answer body (D-3).
     */
    record Found(String question, String expectSource, List<String> expectContains, Language lang) implements EvalCase {

        /**
         * [Purpose] Legacy 3-arg constructor kept for existing callers that predate AD-9; defaults
         *   {@code lang} to {@link Language#KO}.
         */
        public Found(String question, String expectSource, List<String> expectContains) {
            this(question, expectSource, expectContains, Language.KO);
        }
    }

    /**
     * [Purpose] A "not-found" question (YAML {@code expect: NOT_FOUND}) — the correct answer is a
     *   refusal with no sources (D-3 not_found_correct).
     */
    record NotFound(String question, Language lang) implements EvalCase {

        /**
         * [Purpose] Legacy 1-arg constructor kept for existing callers that predate AD-9; defaults
         *   {@code lang} to {@link Language#KO}.
         */
        public NotFound(String question) {
            this(question, Language.KO);
        }
    }
}
