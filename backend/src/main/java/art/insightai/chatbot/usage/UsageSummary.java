package art.insightai.chatbot.usage;

import java.math.BigDecimal;
import java.util.List;

/**
 * [Purpose] Full answer to a "usage over a period" query — the aggregate totals plus the
 *   per-model breakdown, assembled by {@link UsageService#summarize}.
 * [Rationale] Field names match the response shape eval-usage-dashboard Spec §4/§5 describes for
 *   {@code GET /api/admin/usage} (AC-13, AC-16, AC-17) exactly, so the U2 controller card can
 *   serialize this record with no renaming step.
 * [Caution] {@code byModel} rows and the top-level totals are computed by two separate SQL
 *   queries ({@link UsageRepository#fetchTotals} and {@link UsageRepository#fetchByModel})
 *   against the same {@code role = 'assistant'} + period predicate — {@link UsageService}
 *   passes the same {@code from}/{@code to} to both so they stay consistent with each other.
 */
public record UsageSummary(
        long totalRequests,
        long promptTokens,
        long completionTokens,
        BigDecimal totalCostUsd,
        long missingCostCount,
        List<ModelUsage> byModel) {
}
