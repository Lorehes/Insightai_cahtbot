package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.common.lang.Language;
import art.insightai.chatbot.common.lang.LanguageDetector;

/**
 * [Purpose] One judged case's per-item language-match record (AC-11's "문항별 기록") — the case's
 *   position in the judged list, its declared question language, the language
 *   {@link LanguageDetector} detected in the answer, and whether the two agree.
 * [Rationale] {@code answerLanguage} is deliberately a detector output captured here rather than
 *   an {@code EvalResponse} field of its own — Spec §5.3 mandates the same
 *   {@link LanguageDetector} implementation serve both question-language decision (prompt
 *   rendering) and answer-language matching (AC-11), and {@link EvalReportAggregator#aggregateByLanguage}
 *   is the one place that runs {@code LanguageDetector.detect(response.answer())} and records the
 *   result here — this record itself performs no detection.
 * [Caution] {@code answerLanguage} may be {@link Language#UNKNOWN}; unlike {@link
 *   Language#orDefault()}'s question-side fallback, an undetectable answer is left as {@code
 *   UNKNOWN} and therefore counted as {@code matched=false} — Spec AD-10 reserves the UNKNOWN ->
 *   KO fallback for the question side only.
 * [AC] multilingual-support:AC-11
 */
public record CaseLanguageMatch(
        int caseIndex,
        Language questionLanguage,
        Language answerLanguage,
        boolean matched) {

    /**
     * [Purpose] Rejects a negative index or a null language at construction time.
     */
    public CaseLanguageMatch {
        if (caseIndex < 0) {
            throw new IllegalArgumentException("caseIndex must not be negative");
        }
        if (questionLanguage == null) {
            throw new IllegalArgumentException("questionLanguage must not be null");
        }
        if (answerLanguage == null) {
            throw new IllegalArgumentException("answerLanguage must not be null");
        }
    }
}
