package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.common.lang.Language;
import art.insightai.chatbot.common.lang.LanguageDetector;
import art.insightai.chatbot.eval.judge.JudgeResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [Purpose] Aggregates one combination's judged eval cases into a single {@link EvalReportRow}
 *   (AC-8), with denominators derived from the actual case counts rather than hardcoded 15/10, and
 *   folds in the two contamination signals (AC-19a, AC-20a) needed to mark a row judgment-deferred.
 * [Rationale] Tech Review D-3's {@code JudgeResult.NotFound} carries {@code notFoundCorrect} and
 *   {@code hallucination} as two independently-false-capable fields (a third, uncounted state
 *   exists — refusal phrase present but sources non-empty). AC-6b explicitly forbids deriving
 *   {@code not_found_correct} as the complement of {@code hallucination}: doing so would silently
 *   count that third state as a pass. This aggregator tallies both fields directly off
 *   {@link JudgeResult.NotFound}, never from one another.
 * [AC] eval-usage-dashboard:AC-6b
 * [AC] eval-usage-dashboard:AC-8
 * [AC] eval-usage-dashboard:AC-19a
 * [AC] eval-usage-dashboard:AC-20a
 * [AC] multilingual-support:AC-10
 * [AC] multilingual-support:AC-11
 */
public final class EvalReportAggregator {

    /**
     * [Purpose] The fixed row order {@link #aggregateByLanguage} emits — every eval-set-valid
     *   language (AD-9: KO/EN/ZH/JA, {@link Language#UNKNOWN} excluded since no eval-set case may
     *   declare it) — so AC-10's "4개 언어 행이 각각 존재" holds even for a language with zero cases
     *   in this run.
     */
    private static final List<Language> LANGUAGE_REPORT_ORDER =
            List.of(Language.KO, Language.EN, Language.ZH, Language.JA);

    private EvalReportAggregator() {
    }

    /**
     * [Purpose] Legacy 1-arg shape kept for every pre-existing call site in
     *   {@code EvalReportAggregatorTest} that has no per-run excluded-chunk snapshot to supply
     *   (AC-6b/AC-8 fixtures predate AC-19a and are indifferent to it).
     * [Rationale] Delegates to the 2-arg overload with an empty {@code excludedByStatus} — "no
     *   snapshot supplied" — so those tests keep verifying AC-6b/AC-8/AC-9a exactly as before
     *   without being touched by this card (E6 card note: "순수 추가만").
     */
    public static EvalReportRow aggregate(List<JudgedCase> judgedCases) {
        return aggregate(judgedCases, List.of());
    }

    /**
     * [Purpose] Builds one report row from all judged cases of a single mode x rerank combination,
     *   plus the per-run excluded-chunk-by-status snapshot (AC-19a field 1) that E5 takes once per
     *   sweep run rather than once per case.
     * [Rationale] {@code avg_latency_ms} is rounded with {@link Math#round(double)} (half-up,
     *   towards positive infinity — the only direction that matters since latencies are
     *   non-negative) applied to the arithmetic mean of every case's latency; found and not-found
     *   cases both contribute, since AC-8 defines the average over "전체 케이스" without splitting
     *   by question kind. {@code total_cost_usd} sums only the non-null, non-negative {@code
     *   costUsd} values (D-6, and the same rule usage:AC-17 uses); an empty or all-excluded input
     *   yields exactly {@link BigDecimal#ZERO}, not null, so a report row is never ambiguous
     *   between "no cost data" and "zero cost". Every excluded value — null or negative — is
     *   tallied in {@code missingCostCount} instead. A negative {@code costUsd} is reused under the
     *   same counter rather than a separate one (L3, 2026-08-28 review): both cases mean "this
     *   entry's cost is not usable data", and downstream (report display, AC-9a gating) treats them
     *   identically — there is nothing a caller would do differently for "cost was null" versus
     *   "cost was negative", so a second counter would only add a field nobody reads differently.
     *   {@code retrievedNonIndexedChunkCount} sums {@link JudgedCase#retrievedNonIndexedChunkCount()}
     *   across every case (AC-19a field 2) — a per-case count, unlike {@code excludedByStatus} which
     *   is passed straight through untouched since it is already a single per-run snapshot.
     *   {@code finishReasonCounts} groups by {@code lower(finishReason)} (AC-20a) using a {@link
     *   LinkedHashMap} so a {@code null} finish reason is kept as its own bucket (map key {@code
     *   null} is legal in {@code LinkedHashMap}, unlike {@code TreeMap}) instead of being dropped,
     *   and insertion order is preserved for a deterministic row.
     * [Caution] An empty {@code judgedCases} list yields an all-zero row (avgLatencyMs=0,
     *   totalCostUsd=ZERO, every count/denominator 0, no finish-reason buckets). {@link
     *   EvalReportRow#passed()} on such a row now evaluates to {@code false} (foundTotal=0 and
     *   notFoundTotal=0 both fail the fixed 25-item eval-set check, 2026-08-28 review Medium-2) —
     *   do not rely on this method to validate eval-set completeness beyond that fail-closed guard.
     */
    public static EvalReportRow aggregate(
            List<JudgedCase> judgedCases, List<EvalExcludedDocumentStatusCount> excludedByStatus) {
        int foundTotal = 0;
        int answerCorrect = 0;
        int sourceCorrect = 0;
        int notFoundTotal = 0;
        int notFoundCorrect = 0;
        int hallucinationCount = 0;
        long totalLatencyMs = 0;
        BigDecimal totalCostUsd = BigDecimal.ZERO;
        int missingCostCount = 0;
        int retrievedNonIndexedChunkCount = 0;
        Map<String, Long> finishReasonTally = new LinkedHashMap<>();

        for (JudgedCase judgedCase : judgedCases) {
            totalLatencyMs += judgedCase.response().latencyMs();
            retrievedNonIndexedChunkCount += judgedCase.retrievedNonIndexedChunkCount();

            String finishReason = judgedCase.finishReason();
            String normalizedFinishReason = finishReason == null ? null : finishReason.toLowerCase(Locale.ROOT);
            finishReasonTally.merge(normalizedFinishReason, 1L, Long::sum);

            BigDecimal cost = judgedCase.response().costUsd();
            if (cost == null || cost.signum() < 0) {
                missingCostCount++;
            } else {
                totalCostUsd = totalCostUsd.add(cost);
            }

            switch (judgedCase.result()) {
                case JudgeResult.Found found -> {
                    foundTotal++;
                    if (found.answerCorrect()) {
                        answerCorrect++;
                    }
                    if (found.sourceCorrect()) {
                        sourceCorrect++;
                    }
                }
                case JudgeResult.NotFound notFound -> {
                    notFoundTotal++;
                    if (notFound.notFoundCorrect()) {
                        notFoundCorrect++;
                    }
                    if (notFound.hallucination()) {
                        hallucinationCount++;
                    }
                }
            }
        }

        long avgLatencyMs = judgedCases.isEmpty()
                ? 0L
                : Math.round((double) totalLatencyMs / judgedCases.size());

        List<FinishReasonCount> finishReasonCounts = finishReasonTally.entrySet().stream()
                .map(entry -> new FinishReasonCount(entry.getKey(), entry.getValue()))
                .toList();

        return new EvalReportRow(
                answerCorrect, sourceCorrect, foundTotal,
                notFoundCorrect, notFoundTotal, hallucinationCount,
                avgLatencyMs, totalCostUsd, missingCostCount,
                excludedByStatus, retrievedNonIndexedChunkCount, finishReasonCounts);
    }

    /**
     * [Purpose] Builds the language-scoped view of one sweep run's judged cases (AD-10): one
     *   {@link EvalLanguageReportRow} per {@link #LANGUAGE_REPORT_ORDER} language (AC-10) plus a
     *   {@link CaseLanguageMatch} for every case in {@code judgedCases}, in input order (AC-11).
     * [Rationale] Per-language rows reuse the 1-arg {@link #aggregate(List)} overload directly —
     *   AD-10 says to reuse {@code aggregate} as-is, and {@code excludedByStatus} (AC-19a field 1)
     *   is a whole-run snapshot, not something that can be split per language, so there is no
     *   per-language value to pass as the 2-arg overload's second parameter. A language with no
     *   cases in this run still gets a row: {@link #aggregate(List)} on an empty list returns a
     *   well-defined all-zero {@link EvalReportRow} (see its class Javadoc Caution), so no special
     *   casing is needed to satisfy AC-10's "4개 언어 행이 각각 존재". The answer-language match is
     *   computed here, once per case, with {@link LanguageDetector#detect(String)} on {@code
     *   judgedCase.response().answer()} — AD-10 requires the aggregator to compute this itself
     *   rather than accept a pre-computed value, since no sweep runner exists yet to have computed
     *   one.
     * [Caution] {@code caseIndex} on each {@link CaseLanguageMatch} is the case's position in the
     *   input {@code judgedCases} list, not a position within its language group — AC-11 asks for
     *   a per-case record of the full judged set, not a per-language-group one.
     * [AC] multilingual-support:AC-10
     * [AC] multilingual-support:AC-11
     */
    public static EvalLanguageReport aggregateByLanguage(List<JudgedCase> judgedCases) {
        List<CaseLanguageMatch> caseMatches = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < judgedCases.size(); caseIndex++) {
            JudgedCase judgedCase = judgedCases.get(caseIndex);
            Language questionLanguage = judgedCase.questionLanguage();
            Language answerLanguage = LanguageDetector.detect(judgedCase.response().answer());
            caseMatches.add(new CaseLanguageMatch(
                    caseIndex, questionLanguage, answerLanguage, questionLanguage == answerLanguage));
        }

        List<EvalLanguageReportRow> rows = new ArrayList<>();
        for (Language language : LANGUAGE_REPORT_ORDER) {
            List<JudgedCase> languageCases = judgedCases.stream()
                    .filter(judgedCase -> judgedCase.questionLanguage() == language)
                    .toList();
            int answerLanguageMatchCount = (int) caseMatches.stream()
                    .filter(match -> match.questionLanguage() == language && match.matched())
                    .count();
            rows.add(new EvalLanguageReportRow(
                    language, aggregate(languageCases), answerLanguageMatchCount, languageCases.size()));
        }

        return new EvalLanguageReport(rows, caseMatches);
    }
}
