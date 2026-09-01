"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api/client";
import { fetchUsage, type ModelUsageResponse, type UsageResponse } from "@/lib/api/usage";
import styles from "./UsageDashboard.module.css";

type FetchState =
  | { status: "loading" }
  | { status: "ready"; data: UsageResponse }
  | { status: "error"; detail: string };

type PresetId = "7d" | "30d" | "mtd";

interface DateRange {
  from: string; // <input type="date"> value, "YYYY-MM-DD"
  to: string;
}

const PRESETS: { id: PresetId; label: string }[] = [
  { id: "7d", label: "최근 7일" },
  { id: "30d", label: "최근 30일" },
  { id: "mtd", label: "이번 달" },
];

/**
 * [Purpose] Card U3's admin usage dashboard -- a date-range filter (start/end date inputs + 3
 *   presets + explicit "조회" apply) plus a totals/model-breakdown display, mounted by
 *   `app/admin/usage/page.tsx` (Spec §5 Affected Surfaces, prototype blob `8ad404c9`). Mirrors
 *   `DocumentManager`'s role as the single composition root/state owner for its own thin
 *   `page.tsx` (document-ingestion T11's own precedent for this same `/admin` shell).
 * [Rationale] The prototype's scenario-preview chips (데이터/빈 상태/로딩/에러 전환) are its own
 *   demo tooling, not part of the shipped screen -- per this card's brief and the precedent
 *   `DocumentManager`'s own [Rationale] already set for the same prototype family, every state
 *   below is instead reached the ordinary way by branching on this component's own fetch state
 *   (`FetchState` above), never a manually-selected preview mode. The model table renders
 *   `UsageResponse.byModel` verbatim, one row per entry, with no synthetic extra row -- the
 *   prototype's trailing "(단가 미확인)" row has no backend counterpart (`missingCostCount` is a
 *   single period-wide total with no per-model breakdown on the wire, `lib/api/usage.ts`'s own
 *   [Caution]); that count is instead surfaced only via the warn-tone metric card and the
 *   conditional banner below it.
 * [Caution] The backend's period bound is half-open `[from, to)` (`UsageRepository`'s own
 *   [Caution]), so the "종료일" input's own day is sent as the *following* day's local midnight,
 *   not its own -- see `toRequestRange`. Otherwise every request made on the end date itself
 *   would be silently excluded from the result. Editing either date input by hand clears the
 *   active preset but does not refetch on its own -- only a preset click, "조회", or a retry
 *   applies a (possibly identical) range and triggers a fetch, each by handing the fetch effect
 *   a fresh `DateRange` object so its `[appliedRange]` dependency always sees a new reference,
 *   even when the from/to values themselves are unchanged (e.g. retrying after an error).
 * [AC] eval-usage-dashboard:AC-18
 */
export function UsageDashboard() {
  const [today] = useState(() => new Date());
  const [draftRange, setDraftRange] = useState<DateRange>(() => computePresetRange("mtd", today));
  const [activePresetId, setActivePresetId] = useState<PresetId | null>("mtd");
  const [appliedRange, setAppliedRange] = useState<DateRange>(draftRange);
  const [fetchState, setFetchState] = useState<FetchState>({ status: "loading" });

  // `setFetchState({status: "loading"})` is never called synchronously inside this effect body
  // (`react-hooks/set-state-in-effect` forbids that) -- the initial "loading" value instead
  // comes from `useState`'s own initializer above, and every *subsequent* run is preceded by
  // the triggering handler below (`handlePresetPick`/`handleApply`/`handleRetry`) already having
  // set it before calling `setAppliedRange`. This effect's own `setFetchState` calls only ever
  // happen after the `await` inside the async IIFE, the same "post-await, not synchronous" shape
  // `DocumentManager`'s own mount effect and `refresh` callback already use.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const { from, to } = toRequestRange(appliedRange);
      try {
        const data = await fetchUsage(from, to);
        if (!cancelled) setFetchState({ status: "ready", data });
      } catch (err: unknown) {
        if (!cancelled) setFetchState({ status: "error", detail: describeFailureDetail(err) });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [appliedRange]);

  const handlePresetPick = useCallback(
    (id: PresetId) => {
      const range = computePresetRange(id, today);
      setDraftRange(range);
      setActivePresetId(id);
      setFetchState({ status: "loading" });
      setAppliedRange(range);
    },
    [today],
  );

  const handleFromChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setDraftRange((prev) => ({ ...prev, from: e.target.value }));
    setActivePresetId(null);
  }, []);

  const handleToChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setDraftRange((prev) => ({ ...prev, to: e.target.value }));
    setActivePresetId(null);
  }, []);

  const handleApply = useCallback(() => {
    setFetchState({ status: "loading" });
    setAppliedRange({ ...draftRange }); // fresh object even if from/to match the last applied range
  }, [draftRange]);

  const handleRetry = useCallback(() => {
    setFetchState({ status: "loading" });
    setAppliedRange((prev) => ({ ...prev }));
  }, []);

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1>사용량</h1>
        <p className={styles.description}>
          기간 내 모델 호출의 토큰 사용량과 비용입니다. 비용은 호출 시점의 단가로 계산됩니다.
        </p>
      </header>

      <section className={styles.filterBar} aria-label="기간 필터">
        <label className={styles.dateField}>
          <span>시작일</span>
          <input type="date" value={draftRange.from} onChange={handleFromChange} />
        </label>
        <span className={styles.dateSeparator} aria-hidden="true">
          –
        </span>
        <label className={styles.dateField}>
          <span>종료일</span>
          <input type="date" value={draftRange.to} onChange={handleToChange} />
        </label>
        <div className={styles.presets}>
          {PRESETS.map((preset) => (
            <button
              key={preset.id}
              type="button"
              className={activePresetId === preset.id ? styles.presetButtonActive : styles.presetButton}
              onClick={() => handlePresetPick(preset.id)}
              aria-pressed={activePresetId === preset.id}
            >
              {preset.label}
            </button>
          ))}
        </div>
        <button type="button" className={styles.applyButton} onClick={handleApply}>
          조회
        </button>
      </section>

      {fetchState.status === "loading" && <LoadingState />}
      {fetchState.status === "error" && <ErrorState detail={fetchState.detail} onRetry={handleRetry} />}
      {fetchState.status === "ready" && fetchState.data.totalRequests === 0 && <EmptyState />}
      {fetchState.status === "ready" && fetchState.data.totalRequests > 0 && (
        <UsageContent data={fetchState.data} range={appliedRange} />
      )}
    </div>
  );
}

/** [Purpose] "최초 조회 중" state -- 4 shimmering skeleton cards mirroring the prototype's own
 *  loading layout, with an `sr-only` status text so the wait is still announced non-visually
 *  (same `role="status"` + `sr-only` shape `DocumentManager`'s own `LoadingState` uses). */
function LoadingState() {
  return (
    <div role="status" className={styles.loadingState}>
      <span className={styles.srOnly}>사용량을 불러오는 중입니다</span>
      <div className={styles.skeletonGrid} aria-hidden="true">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className={styles.skeletonCard} />
        ))}
      </div>
    </div>
  );
}

/** [Purpose] `GET /api/admin/usage` failure state -- prototype's fixed "사용량을 불러오지
 *  못했습니다" heading, with `detail` (this call's actual status) substituted for the
 *  prototype's hardcoded "504 Gateway Timeout" example, the same "실제 상태코드 반영" pattern
 *  `DocumentManager`'s own `ErrorState` follows for its own endpoint. */
function ErrorState({ detail, onRetry }: { detail: string; onRetry: () => void }) {
  return (
    <div role="alert" className={styles.errorState}>
      <h2>사용량을 불러오지 못했습니다</h2>
      <p>GET /api/admin/usage 요청이 실패했습니다 ({detail}).</p>
      <button type="button" className={styles.retryButton} onClick={onRetry}>
        다시 시도
      </button>
    </div>
  );
}

/** [Purpose] Zero-call-in-period state -- distinct from the error state above; a successful
 *  fetch whose period simply had no assistant-role messages (`totalRequests === 0`). */
function EmptyState() {
  return (
    <div className={styles.emptyState}>
      <h2>이 기간에는 사용 이력이 없습니다</h2>
      <p>선택한 기간에 기록된 모델 호출이 없습니다. 기간을 넓혀서 다시 조회해 보세요.</p>
    </div>
  );
}

/**
 * [Purpose] The data state -- 4 metric cards (totalCostUsd, promptTokens, completionTokens,
 *   missingCostCount), a conditional missing-cost warning banner, the per-model table, and the
 *   applied range's footer label (eval-usage-dashboard:AC-18).
 * [Rationale] The warning banner only renders when `missingCostCount > 0` (this card's brief) --
 *   the metric card itself always renders, even at 0, so a healthy period still visibly confirms
 *   "0 건 미집계" rather than omitting the card entirely.
 * [AC] eval-usage-dashboard:AC-18
 */
function UsageContent({ data, range }: { data: UsageResponse; range: DateRange }) {
  return (
    <div>
      <div className={styles.metricsGrid}>
        <MetricCard
          label="총 비용"
          value={formatCurrencyUsd(data.totalCostUsd)}
          unit="USD"
          hint="기간 내 모델 호출 비용 합계"
        />
        <MetricCard
          label="총 프롬프트 토큰"
          value={formatCount(data.promptTokens)}
          unit="tok"
          hint="검색된 문서 청크 포함"
        />
        <MetricCard
          label="총 완료 토큰"
          value={formatCount(data.completionTokens)}
          unit="tok"
          hint="모델이 생성한 응답 토큰"
        />
        <MetricCard
          tone="warn"
          label="비용 측정 실패"
          value={formatCount(data.missingCostCount)}
          unit="건"
          hint="단가를 확인할 수 없어 비용에 반영되지 않은 호출입니다. 0원이 아니라 미집계입니다."
        />
      </div>

      {data.missingCostCount > 0 && (
        <div role="alert" className={styles.warningBanner}>
          <WarningGlyph size={17} className={styles.warningBannerIcon} />
          <p>
            <strong>{formatCount(data.missingCostCount)}건의 호출은 비용을 계산하지 못했습니다.</strong>{" "}
            응답에 단가 정보가 없어 위의 총 비용에서 빠져 있습니다. 실제 청구액은 표시된 금액보다 클 수
            있습니다.
          </p>
        </div>
      )}

      <h2 className={styles.tableHeading}>모델별 사용량</h2>
      <ModelTable rows={data.byModel} />

      <p className={styles.rangeLabel}>
        {range.from} – {range.to} · 총 {formatCount(data.totalRequests)}건의 호출
      </p>
    </div>
  );
}

/** [Purpose] One metric card -- label, value + unit, and a muted explanatory hint. `tone="warn"`
 *  marks the missing-cost card with both a text label prefix and its own CSS class (never color
 *  alone), the same "두 가지 채널" rule `AdminLayout`'s own active-tab marking follows. */
function MetricCard({
  label,
  value,
  unit,
  hint,
  tone,
}: {
  label: string;
  value: string;
  unit: string;
  hint: string;
  tone?: "warn";
}) {
  return (
    <div className={tone === "warn" ? styles.metricCardWarn : styles.metricCard}>
      <div className={styles.metricLabel}>
        {tone === "warn" && <WarningGlyph size={15} className={styles.metricLabelIcon} />}
        {label}
      </div>
      <div className={styles.metricValue}>
        {value} <span className={styles.metricUnit}>{unit}</span>
      </div>
      <p className={styles.metricHint}>{hint}</p>
    </div>
  );
}

/** Prototype's warning triangle (AdminUsage blob P:104-108 / MetricCard blob P:21-25) --
 *  decorative and text-free (`aria-hidden`), so the surrounding element's own text is unchanged;
 *  the "warn" meaning is still carried by the label/banner text, never by this glyph alone. */
function WarningGlyph({ size, className }: { size: number; className: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" aria-hidden="true" className={className}>
      <path d="M6 1.4 L11.2 10.4 H0.8 Z" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M6 4.9 V7.1" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="6" cy="8.8" r="0.72" fill="currentColor" />
    </svg>
  );
}

/**
 * [Purpose] The per-model usage table -- one row per `UsageResponse.byModel` entry, in the
 *   backend's own order (`UsageRepository.BY_MODEL_SQL_SUFFIX`: `ORDER BY model`), columns in
 *   the prototype's specified order (모델/요청 수/프롬프트 토큰/완료 토큰/비용(USD)).
 * [Caution] Renders `rows` verbatim -- does not append a synthetic row for
 *   `missingCostCount` (see this file's own top-level [Rationale] for why the prototype's
 *   "(단가 미확인)" row is not ported).
 * [AC] eval-usage-dashboard:AC-18
 */
function ModelTable({ rows }: { rows: ModelUsageResponse[] }) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th scope="col">모델</th>
            <th scope="col" className={styles.alignRight}>
              요청 수
            </th>
            <th scope="col" className={styles.alignRight}>
              프롬프트 토큰
            </th>
            <th scope="col" className={styles.alignRight}>
              완료 토큰
            </th>
            <th scope="col" className={styles.alignRight}>
              비용 (USD)
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.model}>
              <td className={styles.modelCell}>{row.model}</td>
              <td className={styles.alignRight}>{formatCount(row.requests)}</td>
              <td className={styles.alignRight}>{formatCount(row.promptTokens)}</td>
              <td className={styles.alignRight}>{formatCount(row.completionTokens)}</td>
              <td className={styles.alignRight}>{formatCurrencyUsd(row.costUsd)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const COUNT_FORMATTER = new Intl.NumberFormat("en-US");

/** Formats an integer with thousands separators (prototype's own "1,842,930" style) --
 *  `Intl.NumberFormat("en-US")` is pinned explicitly rather than the bare
 *  `Number#toLocaleString()` default, so the grouping does not depend on the runtime's own
 *  default ICU locale. */
function formatCount(n: number): string {
  return COUNT_FORMATTER.format(n);
}

/**
 * [Purpose] Formats a USD amount (`UsageResponse.totalCostUsd` / `ModelUsageResponse.costUsd`,
 *   a JSON number per those interfaces' own [Caution], never `null`) as e.g. "$12.84"
 *   (prototype's own example).
 * [Rationale] A fixed `toFixed(2)` rounds this project's actual per-call costs (e.g. `0.0025`,
 *   the `2.25E-6`-per-token scale `application.yaml`'s own comment documents) down to "$0.00",
 *   silently hiding a real nonzero cost (review 2026-08-30 High-1). {@link decimalDigitsFor}
 *   widens the fraction digits only for amounts under one cent, so the ordinary $1+ case still
 *   renders the prototype's plain 2-decimal style.
 * [Caution] Never round-trip this string back through `Number()` for arithmetic -- it is a
 *   display-only format, matching `ModelUsageResponse.costUsd`'s own [Caution].
 */
function formatCurrencyUsd(amount: number): string {
  return `$${amount.toFixed(decimalDigitsFor(amount))}`;
}

/**
 * [Purpose] Picks how many fraction digits {@link formatCurrencyUsd} shows for one amount.
 * [Rationale] Amounts of $0.01 or more keep the prototype's plain 2-decimal style. Smaller
 *   nonzero amounts get enough extra digits to reach their first significant decimal place plus
 *   one, so e.g. `0.0025` renders as "$0.0025", never rounds down to "$0.00" -- but the digit
 *   count is capped at 6 so a microscopic float artifact near zero can't produce an absurdly
 *   long string. `Math.log10`'s own floating-point imprecision on an exact power of ten (e.g.
 *   `0.001`) is nudged with a tiny epsilon before flooring, so that case doesn't fall one digit
 *   short.
 */
function decimalDigitsFor(amount: number): number {
  const abs = Math.abs(amount);
  if (abs === 0 || abs >= 0.01) return 2;
  const firstSignificantPlace = Math.floor(-Math.log10(abs) + 1e-9) + 1;
  return Math.min(6, Math.max(2, firstSignificantPlace + 1));
}

/** Formats a Date's local-timezone offset as "+09:00"/"-05:00" (never "Z") -- pairs with
 *  {@link toOffsetDateTimeString} below to satisfy the backend's `OffsetDateTime` binding,
 *  which rejects an offset-less value (backend CLAUDE.md fact #5, `UsageController`'s own
 *  [Rationale]). */
function formatOffset(date: Date): string {
  const totalMinutes = -date.getTimezoneOffset(); // Date#getTimezoneOffset is UTC-minus-local
  const sign = totalMinutes >= 0 ? "+" : "-";
  const abs = Math.abs(totalMinutes);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

/** Renders a Date (read via its local field getters) as an ISO-8601 string carrying an explicit
 *  offset (e.g. "2026-08-01T00:00:00+09:00"). `Date#toISOString()` always emits "Z" (UTC), never
 *  the browser's own local offset, so this hand-rolls the string instead. */
function toOffsetDateTimeString(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    formatOffset(date)
  );
}

/** Parses a `<input type="date">` value ("YYYY-MM-DD") as local midnight. `new Date(str)` would
 *  instead parse the date-only ECMAScript form as UTC midnight, silently shifting the moment by
 *  the browser's own UTC offset -- this constructs from the individual numeric fields instead,
 *  which the `Date` constructor always interprets as local time. Caller must check
 *  {@link isValidDateInput} first (see that function's own [Purpose]). */
function parseDateInput(value: string): Date {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day, 0, 0, 0);
}

/**
 * [Purpose] True when `value` is a complete, well-formed `<input type="date">` value
 *   ("YYYY-MM-DD", all three fields numeric) -- guards {@link parseDateInput}/
 *   {@link toRequestRange} against an emptied-out date input.
 * [Rationale] A user can clear a `<input type="date">` by hand, which reports `value: ""`.
 *   Without this guard, {@link parseDateInput}'s `Number("")` fields become `NaN`, and the
 *   resulting `Date` silently propagates into `toOffsetDateTimeString` as the literal string
 *   `"NaN-NaN-NaNTNaN:NaN:NaN-NaN:NaN"`, which the backend rejects with an opaque 400 (review
 *   2026-08-30 Medium-4). Does not validate calendar correctness beyond what the regex/`Number`
 *   parse already reject (e.g. "2026-02-30" still passes, matching what the native date picker
 *   itself already lets through) -- this only needs to keep `NaN` off the wire, not fully
 *   validate the calendar.
 */
function isValidDateInput(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  return Number.isFinite(year) && Number.isFinite(month) && Number.isFinite(day);
}

function formatDateInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * [Purpose] Converts a UI `DateRange` (inclusive start date, inclusive end date, both as
 *   `<input type="date">` strings) into the `{from, to}` offset-datetime strings
 *   `fetchUsage` sends on the wire.
 * [Rationale] The backend's period bound is half-open `[from, to)` (`UsageRepository`'s own
 *   [Caution]: `created_at >= from AND created_at < to`), but "종료일" in this UI is meant to be
 *   inclusive of the whole day the user picked -- so `to` is sent as the *following* day's local
 *   midnight, not the picked day's own midnight. Without this shift, every request made on the
 *   end date itself would fall on the excluded side of `< to` and silently disappear from the
 *   result. Either bound is omitted (returned `undefined`, dropped by `fetchUsage`'s own
 *   `URLSearchParams` building) when its input is empty/invalid per {@link isValidDateInput},
 *   which leaves that side of the period open -- the same "may be omitted" contract
 *   `fetchUsage`'s own [Rationale] already documents -- rather than ever sending a `NaN`-laden
 *   string (review 2026-08-30 Medium-4).
 * [AC] eval-usage-dashboard:AC-18
 */
function toRequestRange(range: DateRange): { from?: string; to?: string } {
  const from = isValidDateInput(range.from) ? toOffsetDateTimeString(parseDateInput(range.from)) : undefined;
  let to: string | undefined;
  if (isValidDateInput(range.to)) {
    const toExclusive = parseDateInput(range.to);
    toExclusive.setDate(toExclusive.getDate() + 1);
    to = toOffsetDateTimeString(toExclusive);
  }
  return { from, to };
}

/** Computes a preset's `{from, to}` `<input type="date">` values relative to `today` (captured
 *  once at mount, `UsageDashboard`'s own `today` state) -- "최근 N일" is an N-day inclusive
 *  window ending today (`N - 1` days back), "이번 달" is the 1st of `today`'s month through
 *  today, matching the prototype's own 3 presets. */
function computePresetRange(id: PresetId, today: Date): DateRange {
  const to = formatDateInput(today);
  if (id === "mtd") {
    const from = new Date(today.getFullYear(), today.getMonth(), 1);
    return { from: formatDateInput(from), to };
  }
  const days = id === "7d" ? 7 : 30;
  const from = new Date(today);
  from.setDate(from.getDate() - (days - 1));
  return { from: formatDateInput(from), to };
}

/** Extracts just the "{status} {statusText}" lead-in from an {@link ApiError}'s message
 *  (`client.ts`'s `describeFailure` always builds it as `"{status} {statusText}"`, optionally
 *  followed by `": {body}"`) -- same helper shape as `DocumentManager.tsx`'s own
 *  `describeListFailureDetail`. */
function describeFailureDetail(err: unknown): string {
  if (err instanceof ApiError) {
    return err.message.split(":")[0];
  }
  return err instanceof Error ? err.message : String(err);
}
