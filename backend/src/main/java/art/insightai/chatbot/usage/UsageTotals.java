package art.insightai.chatbot.usage;

import java.math.BigDecimal;

/**
 * [Purpose] Aggregate usage counters for {@code messages} rows with {@code role = 'assistant'}
 *   over a (possibly unbounded) period, without the per-model breakdown.
 * [Rationale] Mirrors the eval-usage-dashboard Spec's Tech Review D-8 aggregation contract:
 *   {@code totalCostUsd} sums only rows where {@code cost_usd IS NOT NULL}, and
 *   {@code missingCostCount} carries the count of rows excluded from that sum. The two fields are
 *   kept separate on purpose (eval-usage-dashboard:AC-17) — collapsing a null cost to {@code 0}
 *   before summing would silently hide the OpenRouter SDK dropping {@code usage.cost} (R12) behind
 *   a plausible-looking total.
 * [Caution] {@code totalCostUsd} is never {@code null} here (the repository's SQL always
 *   {@code COALESCE}s the sum to {@code 0}), even when every row in the period is missing a cost —
 *   callers must read {@code missingCostCount} to tell "genuinely free" apart from "cost unknown".
 */
public record UsageTotals(
        long totalRequests,
        long promptTokens,
        long completionTokens,
        BigDecimal totalCostUsd,
        long missingCostCount) {
}
