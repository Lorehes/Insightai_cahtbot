package art.insightai.chatbot.eval.report;

import java.util.Locale;

/**
 * [Purpose] One row of the report's {@code lower(finish_reason)} distribution (AC-20a) — how many
 *   judged cases in this combination ended with a given finish reason.
 * [Rationale] {@code finishReason} may be {@code null}: a case whose finish reason was never
 *   recorded is counted as its own bucket (Spec AC-20a — "null을 별도 값으로 세며 행에서 빠뜨리지
 *   않는다") rather than being dropped or folded into another bucket. Lowercasing used to be solely
 *   {@link EvalReportAggregator}'s responsibility before grouping; the compact constructor below
 *   now enforces it here too, so {@link EvalReportRow#judgmentDeferred()}'s case-sensitive
 *   {@code equals} comparison against {@code "length"} cannot silently fail-open on a caller (e.g.
 *   a test, or a future call site) that constructs a row with an un-lowercased finish reason
 *   (Medium-1, 2026-08-30 review). {@link EvalReportAggregator} still lowercases before calling
 *   this constructor; that call is now redundant but harmless, since normalization is idempotent.
 * [AC] eval-usage-dashboard:AC-20a
 */
public record FinishReasonCount(String finishReason, long count) {

    /**
     * [Purpose] Normalizes {@code finishReason} to lower case and rejects a negative {@code count}
     *   at construction time.
     * [Rationale] A negative count is never meaningful for a tally; rejecting it here matches the
     *   non-negative invariant already enforced on every other count field in this package (Low-6,
     *   2026-08-30 review).
     */
    public FinishReasonCount {
        finishReason = finishReason != null ? finishReason.toLowerCase(Locale.ROOT) : null;
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
    }
}
