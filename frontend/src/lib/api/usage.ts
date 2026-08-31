import { apiFetch } from "./client";

/**
 * [Purpose] One entry of `GET /api/admin/usage`'s `byModel` array (eval-usage-dashboard Spec
 *   §5, card U3, confirmed against `UsageController.ModelUsageResponse`) -- the exact field
 *   set/order that record declares.
 * [Caution] `costUsd` is never `null` -- the backend's SQL sums with `COALESCE(... , 0)`
 *   (`UsageRepository.BY_MODEL_SQL_PREFIX`), so a model with every request missing a price still
 *   reports `0`, not `null`. There is also no per-model missing-cost count on the wire; only
 *   the top-level `UsageResponse.missingCostCount` exists (see that interface's own [Caution]).
 *   `costUsd` is a JSON number (Jackson's default `BigDecimal` serialization -- project
 *   CLAUDE.md's `write-bigdecimal-as-plain` only changes fixed-point vs. exponent notation, it
 *   does not stringify), never a string, matching `lib/api/messages.ts`'s `ChatUsage.cost` for
 *   the same `BigDecimal` source (review 2026-08-30 Medium-1). Do not do currency arithmetic on
 *   it in JS (float rounding) -- format it for display only, via `UsageDashboard`'s
 *   `formatCurrencyUsd`.
 * [AC] eval-usage-dashboard:AC-18
 */
export interface ModelUsageResponse {
  model: string;
  requests: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
}

/**
 * [Purpose] Response body for `GET /api/admin/usage` (eval-usage-dashboard Spec §5, card U3,
 *   confirmed against `UsageController.UsageResponse`) -- totals plus a per-model breakdown for
 *   an optional `[from, to)` period.
 * [Caution] `missingCostCount` is a single period-wide total, not broken down per model --
 *   `byModel[].costUsd` already folds any missing-price request in as `0` via `COALESCE`
 *   (`UsageRepository`'s own [Rationale]), so there is no way to attribute a missing-cost count
 *   back to a specific row of `byModel`. A UI must show this count separately (a metric card /
 *   banner), never as a synthetic extra row appended to the model table. `totalCostUsd` is a
 *   JSON number, not a string -- same `BigDecimal` wire shape and same "display only, no JS
 *   arithmetic" caution as {@link ModelUsageResponse.costUsd} above.
 * [AC] eval-usage-dashboard:AC-18
 */
export interface UsageResponse {
  totalRequests: number;
  promptTokens: number;
  completionTokens: number;
  totalCostUsd: number;
  missingCostCount: number;
  byModel: ModelUsageResponse[];
}

/**
 * [Purpose] Calls `GET /api/admin/usage?from=&to=` for the admin usage dashboard
 *   (eval-usage-dashboard:AC-18).
 * [Rationale] `from`/`to` are taken as caller-supplied strings, not `Date`/`OffsetDateTime`
 *   values -- the caller (`UsageDashboard`) owns turning a `<input type="date">` value into the
 *   offset-bearing ISO-8601 string the backend's `@DateTimeFormat(iso = ISO.DATE_TIME)` binding
 *   requires (backend CLAUDE.md fact #5: an offset-less value is rejected with 400), since that
 *   conversion depends on UI concerns (day-boundary inclusivity, the browser's own local
 *   timezone) this module has no business deciding. Either may be omitted to leave that side of
 *   the period open, matching `UsageController#usage`'s own two optional `@RequestParam`s.
 * [AC] eval-usage-dashboard:AC-18
 */
export function fetchUsage(from?: string, to?: string): Promise<UsageResponse> {
  const params = new URLSearchParams();
  if (from !== undefined) params.set("from", from);
  if (to !== undefined) params.set("to", to);
  const query = params.toString();
  return apiFetch<UsageResponse>(`/api/admin/usage${query.length > 0 ? `?${query}` : ""}`);
}
