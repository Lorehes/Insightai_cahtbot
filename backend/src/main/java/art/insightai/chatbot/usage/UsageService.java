package art.insightai.chatbot.usage;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Purpose] Assembles {@link UsageSummary} for a given period by combining
 *   {@link UsageRepository}'s totals and per-model breakdown queries (eval-usage-dashboard Spec,
 *   card U1).
 * [Rationale] Kept as a thin orchestration layer over two read-only queries — there is no
 *   business rule here beyond "run both queries against the same period and combine the results"
 *   (Tech Review D-8). Validating {@code from > to} is explicitly the U2 controller's
 *   responsibility (Tech Review D-8 "검증 위치: from > to → 400은 Controller"), so this class
 *   accepts whatever {@code from}/{@code to} it is given and does not reject or reorder them.
 * [Caution] {@code from}/{@code to} are passed unchanged to both {@link
 *   UsageRepository#fetchTotals} and {@link UsageRepository#fetchByModel} — passing them
 *   separately (e.g. resolving "now" independently for each call) would let the two queries drift
 *   apart and make {@code byModel} sums stop matching the top-level totals. {@link #summarize} is
 *   explicitly {@code REPEATABLE_READ} rather than relying on the default {@code READ COMMITTED}
 *   isolation (PostgreSQL): under {@code READ COMMITTED}, each statement inside the same
 *   transaction can still see a newer snapshot, so an INSERT landing between the two queries would
 *   make {@code byModel}'s sum diverge from {@code totals} (review 2026-08-27 Medium-2) — the
 *   {@code ModelUsage} invariant that byModel always sums to totals (Spec §6 I3) depends on both
 *   queries reading the same snapshot.
 */
@Service
public class UsageService {

    private final UsageRepository usageRepository;

    public UsageService(UsageRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    /**
     * [Purpose] Returns the full usage summary (totals + per-model breakdown) for messages sent
     *   in {@code [from, to)}. Either bound may be {@code null} to leave that side of the period
     *   open (Tech Review D-8 default: both {@code null} means the entire history).
     * [AC] eval-usage-dashboard:AC-14, eval-usage-dashboard:AC-16, eval-usage-dashboard:AC-17
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsageSummary summarize(OffsetDateTime from, OffsetDateTime to) {
        UsageTotals totals = usageRepository.fetchTotals(from, to);
        List<ModelUsage> byModel = usageRepository.fetchByModel(from, to);
        return new UsageSummary(
                totals.totalRequests(),
                totals.promptTokens(),
                totals.completionTokens(),
                totals.totalCostUsd(),
                totals.missingCostCount(),
                byModel);
    }
}
