package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.eval.judge.JudgeResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * [Purpose] Aggregates one combination's judged eval cases into a single {@link EvalReportRow}
 *   (AC-8), with denominators derived from the actual case counts rather than hardcoded 15/10.
 * [Rationale] Tech Review D-3's {@code JudgeResult.NotFound} carries {@code notFoundCorrect} and
 *   {@code hallucination} as two independently-false-capable fields (a third, uncounted state
 *   exists — refusal phrase present but sources non-empty). AC-6b explicitly forbids deriving
 *   {@code not_found_correct} as the complement of {@code hallucination}: doing so would silently
 *   count that third state as a pass. This aggregator tallies both fields directly off
 *   {@link JudgeResult.NotFound}, never from one another.
 * [AC] eval-usage-dashboard:AC-6b
 * [AC] eval-usage-dashboard:AC-8
 */
public final class EvalReportAggregator {

    private EvalReportAggregator() {
    }

    /**
     * [Purpose] Builds one report row from all judged cases of a single mode x rerank combination.
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
     * [Caution] An empty {@code judgedCases} list yields an all-zero row (avgLatencyMs=0,
     *   totalCostUsd=ZERO, every count/denominator 0). {@link EvalReportRow#passed()} on such a
     *   row now evaluates to {@code false} (foundTotal=0 and notFoundTotal=0 both fail the fixed
     *   25-item eval-set check, 2026-08-28 review Medium-2) — do not rely on this method to
     *   validate eval-set completeness beyond that fail-closed guard.
     */
    public static EvalReportRow aggregate(List<JudgedCase> judgedCases) {
        int foundTotal = 0;
        int answerCorrect = 0;
        int sourceCorrect = 0;
        int notFoundTotal = 0;
        int notFoundCorrect = 0;
        int hallucinationCount = 0;
        long totalLatencyMs = 0;
        BigDecimal totalCostUsd = BigDecimal.ZERO;
        int missingCostCount = 0;

        for (JudgedCase judgedCase : judgedCases) {
            totalLatencyMs += judgedCase.response().latencyMs();

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

        return new EvalReportRow(
                answerCorrect, sourceCorrect, foundTotal,
                notFoundCorrect, notFoundTotal, hallucinationCount,
                avgLatencyMs, totalCostUsd, missingCostCount);
    }
}
