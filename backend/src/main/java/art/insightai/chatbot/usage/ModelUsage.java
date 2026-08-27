package art.insightai.chatbot.usage;

import java.math.BigDecimal;

/**
 * [Purpose] One row of the per-model usage breakdown (eval-usage-dashboard:AC-16) — usage counters
 *   for a single {@code messages.model} value within the requested period.
 * [Rationale] {@code model} is {@code messages.model}, not {@code conversations.model} (Tech
 *   Review D-8) — a conversation's model can change mid-conversation, so grouping by the
 *   message-level column is the only way per-model cost/token totals stay accurate. {@code
 *   costUsd} excludes rows with a {@code null} cost, the same FILTER policy {@link UsageTotals}
 *   applies at the aggregate level, so summing {@code costUsd} across every row of {@code
 *   byModel} matches {@link UsageTotals#totalCostUsd()} exactly for the same period.
 */
public record ModelUsage(
        String model,
        long requests,
        long promptTokens,
        long completionTokens,
        BigDecimal costUsd) {
}
