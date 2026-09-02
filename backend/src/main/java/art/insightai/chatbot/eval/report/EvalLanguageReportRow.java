package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.common.lang.Language;

/**
 * [Purpose] One language-scoped report row (AC-10) — {@code language}'s found/source/not-found
 *   accuracy and hallucination rate, packaged as the existing {@link EvalReportRow} rather than
 *   re-derived fields, plus how many of this language's cases had a matching answer language
 *   ({@code answerLanguageMatchCount}, AC-11's per-language rollup) out of how many cases this
 *   language had in total ({@code caseTotal}).
 * [Rationale] AD-10 keeps this purely additive: {@code metrics} wraps {@link EvalReportRow}
 *   instead of duplicating its fields, so {@link EvalReportAggregator#aggregate(java.util.List)}
 *   is reused verbatim per language instead of re-implementing AC-8's four metrics a second time.
 *   {@code caseTotal} is carried as its own field — even though it always equals
 *   {@code metrics.foundTotal() + metrics.notFoundTotal()} — so a reader of this row does not have
 *   to sum two other fields to know how many cases this language had.
 * [Caution] Unlike {@link EvalReportRow}, this row carries no {@code passed()}/pass-fail verdict
 *   (AD-11) — {@link EvalReportRow#passed()}'s AC-9a gate is deliberately left untouched and
 *   scoped elsewhere (T9) to the {@code Language.KO} subset only.
 * [AC] multilingual-support:AC-10
 */
public record EvalLanguageReportRow(
        Language language,
        EvalReportRow metrics,
        int answerLanguageMatchCount,
        int caseTotal) {

    /**
     * [Purpose] Rejects an impossible row at construction time instead of letting a bad count
     *   silently reach a report — a null {@code language}/{@code metrics}, a negative count, or a
     *   match count exceeding its own case total.
     */
    public EvalLanguageReportRow {
        if (language == null) {
            throw new IllegalArgumentException("language must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (answerLanguageMatchCount < 0 || caseTotal < 0) {
            throw new IllegalArgumentException("answerLanguageMatchCount and caseTotal must not be negative");
        }
        if (answerLanguageMatchCount > caseTotal) {
            throw new IllegalArgumentException(
                    "answerLanguageMatchCount (%d) cannot exceed caseTotal (%d)"
                            .formatted(answerLanguageMatchCount, caseTotal));
        }
    }
}
