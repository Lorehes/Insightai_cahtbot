import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { UsageDashboard } from "./UsageDashboard";
import { ApiError } from "@/lib/api/client";
import { fetchUsage, type UsageResponse } from "@/lib/api/usage";

/**
 * Only the network boundary (`lib/api/usage`) is mocked, mirroring
 * `DocumentManager.test.tsx`'s own convention for the same `/admin` shell (document-ingestion
 * T11). The prototype's own scenario-preview chips are demo tooling this card deliberately does
 * not port (`UsageDashboard`'s own [Rationale]) -- every state below is instead reached the
 * ordinary way, by controlling what the mocked `fetchUsage` resolves or rejects with.
 * [Caution] Not every `it()` below carries the `[eval-usage-dashboard:AC-18]` tag. AC-18 only
 *   requires the screen to display `totalCostUsd`/token totals/the model table/`missingCostCount`
 *   -- the retry-capable error banner, the date-range presets, and the manual "조회" flow are
 *   real screen behavior worth locking, but not what AC-18 itself asserts, so they stay untagged
 *   (review 2026-08-30 Medium-3, which found 3 of the file's original 5 AC-18 tags over-scoped
 *   this way; Spec §4's AC-18 evidence cell carries the corrected count).
 * [AC] eval-usage-dashboard:AC-18
 */
jest.mock("@/lib/api/usage");

const mockedFetchUsage = fetchUsage as jest.MockedFunction<typeof fetchUsage>;

function usage(overrides: Partial<UsageResponse> = {}): UsageResponse {
  return {
    totalRequests: 2191,
    promptTokens: 1_842_930,
    completionTokens: 386_214,
    totalCostUsd: 12.84,
    missingCostCount: 0,
    byModel: [
      {
        model: "claude-sonnet-4-6",
        requests: 1208,
        promptTokens: 1_514_602,
        completionTokens: 301_470,
        costUsd: 10.92,
      },
      {
        model: "claude-haiku-4-5",
        requests: 946,
        promptTokens: 328_328,
        completionTokens: 84_744,
        costUsd: 1.92,
      },
    ],
    ...overrides,
  };
}

describe("UsageDashboard", () => {
  beforeEach(() => {
    mockedFetchUsage.mockReset();
  });

  describe("로딩 상태", () => {
    it("shows the skeleton grid with an accessible status text before the first fetch resolves", async () => {
      let resolveUsage: (data: UsageResponse) => void = () => {};
      mockedFetchUsage.mockReturnValue(
        new Promise((resolve) => {
          resolveUsage = resolve;
        }),
      );

      render(<UsageDashboard />);

      const status = screen.getByRole("status");
      expect(within(status).getByText("사용량을 불러오는 중입니다")).toBeInTheDocument();

      resolveUsage(usage());

      await waitFor(() => expect(screen.queryByText("사용량을 불러오는 중입니다")).not.toBeInTheDocument());
    });
  });

  describe("에러 상태", () => {
    it("shows the retry-capable error banner reflecting the real status code, and retries on click", async () => {
      mockedFetchUsage.mockRejectedValueOnce(new ApiError(504, "504 Gateway Timeout: upstream busy"));
      mockedFetchUsage.mockResolvedValueOnce(usage());

      render(<UsageDashboard />);

      expect(await screen.findByRole("alert")).toHaveTextContent("사용량을 불러오지 못했습니다");
      expect(
        screen.getByText("GET /api/admin/usage 요청이 실패했습니다 (504 Gateway Timeout)."),
      ).toBeInTheDocument();

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText("$12.84")).toBeInTheDocument();
      expect(mockedFetchUsage).toHaveBeenCalledTimes(2);
    });
  });

  describe("빈 상태", () => {
    it("shows the empty-period message when the fetch succeeds with zero calls", async () => {
      mockedFetchUsage.mockResolvedValueOnce(usage({ totalRequests: 0, byModel: [] }));

      render(<UsageDashboard />);

      expect(await screen.findByText("이 기간에는 사용 이력이 없습니다")).toBeInTheDocument();
      expect(screen.getByText(/기간을 넓혀서 다시 조회해 보세요/)).toBeInTheDocument();
    });
  });

  describe("데이터 상태", () => {
    it("renders a sub-cent totalCostUsd without rounding it down to \"$0.00\" (review 2026-08-30 High-1 regression guard)", async () => {
      // This project's real per-call costs can be sub-cent (application.yaml's own comment
      // documents a ~2.25E-6-per-token scale) -- a fixed `toFixed(2)` would round `0.0025` down
      // to "$0.00", silently hiding a real nonzero cost. Asserts the exact rendered string, not
      // just "not $0.00" -- `formatCurrencyUsd`/`decimalDigitsFor` reverting to a flat
      // `toFixed(2)` fails this on the positive assertion alone (renders "$0.00" instead of
      // "$0.0025"), so this test does not depend on the negative assertion for its
      // discriminating power (kept anyway, for a reader's clarity).
      mockedFetchUsage.mockResolvedValueOnce(usage({ totalCostUsd: 0.0025 }));

      render(<UsageDashboard />);

      expect(await screen.findByText("$0.0025")).toBeInTheDocument();
      expect(screen.queryByText("$0.00")).not.toBeInTheDocument();
    });

    it("[eval-usage-dashboard:AC-18] displays totalCostUsd, the token totals, the per-model table, and missingCostCount", async () => {
      mockedFetchUsage.mockResolvedValueOnce(usage({ missingCostCount: 37 }));

      render(<UsageDashboard />);

      await screen.findByText("$12.84"); // totalCostUsd
      expect(screen.getByText("1,842,930")).toBeInTheDocument(); // promptTokens
      expect(screen.getByText("386,214")).toBeInTheDocument(); // completionTokens
      // Exact, scoped assertions rather than a bare partial regex across the whole document
      // (review 2026-08-30 Low-1 -- `/37/` would also match "137", "372", etc. and could not
      // tell the metric card's own count apart from the banner's). Each container is scoped to
      // its own subtree via `within` first, then matched with the exact rendered string.
      const missingCostCard = screen.getByText("비용 측정 실패").parentElement!;
      expect(within(missingCostCard).getByText("37")).toBeInTheDocument();
      const banner = screen.getByRole("alert");
      expect(within(banner).getByText("37건의 호출은 비용을 계산하지 못했습니다.")).toBeInTheDocument();

      const table = screen.getByRole("table");
      const rows = within(table).getAllByRole("row");
      expect(rows).toHaveLength(3); // header + 2 models -- no synthetic "미집계" row
      const [, row1, row2] = rows;
      expect(within(row1).getByText("claude-sonnet-4-6")).toBeInTheDocument();
      expect(within(row1).getByText("1,208")).toBeInTheDocument();
      expect(within(row1).getByText("1,514,602")).toBeInTheDocument();
      expect(within(row1).getByText("301,470")).toBeInTheDocument();
      expect(within(row1).getByText("$10.92")).toBeInTheDocument();
      expect(within(row2).getByText("claude-haiku-4-5")).toBeInTheDocument();
      expect(within(row2).getByText("$1.92")).toBeInTheDocument();

      expect(screen.getByText(/· 총 2,191건의 호출/)).toBeInTheDocument();
    });

    it("[eval-usage-dashboard:AC-18] shows the missing-cost warning banner only when missingCostCount > 0", async () => {
      mockedFetchUsage.mockResolvedValueOnce(usage({ missingCostCount: 0 }));

      render(<UsageDashboard />);

      await screen.findByText("$12.84");
      // The metric card itself still renders at 0 -- only the banner is conditional.
      expect(screen.getByText("비용 측정 실패")).toBeInTheDocument();
      expect(screen.queryByText(/건의 호출은 비용을 계산하지 못했습니다/)).not.toBeInTheDocument();
    });

    it("renders the missing-cost banner's own content (not just inferred from the metric card) when missingCostCount > 0", async () => {
      // The previous coverage of this positive case was indirect (review 2026-08-30 Low-2) --
      // the banner's presence was only ever inferred from a count that also happened to appear
      // in the metric card, never asserted on the banner element itself. This queries the
      // `role="alert"` banner directly and checks its own text.
      mockedFetchUsage.mockResolvedValueOnce(usage({ missingCostCount: 5 }));

      render(<UsageDashboard />);

      await screen.findByText("$12.84");
      const banner = screen.getByRole("alert");
      expect(within(banner).getByText("5건의 호출은 비용을 계산하지 못했습니다.")).toBeInTheDocument();
    });

    it("never renders the prototype's synthetic '(단가 미확인)' row (regression guard)", async () => {
      mockedFetchUsage.mockResolvedValueOnce(usage({ missingCostCount: 37 }));

      render(<UsageDashboard />);

      await screen.findByRole("table");
      expect(screen.queryByText("(단가 미확인)")).not.toBeInTheDocument();
      expect(screen.queryByText("측정 실패")).not.toBeInTheDocument();
    });
  });

  describe("기간 필터", () => {
    it("refetches with an exact 30-day span, as an offset-bearing ISO datetime, when '최근 30일' is picked", async () => {
      mockedFetchUsage.mockResolvedValue(usage());

      const user = userEvent.setup();
      render(<UsageDashboard />);
      await screen.findByText("$12.84");
      expect(mockedFetchUsage).toHaveBeenCalledTimes(1);

      await user.click(screen.getByRole("button", { name: "최근 30일" }));

      await waitFor(() => expect(mockedFetchUsage).toHaveBeenCalledTimes(2));
      const [thirtyDayFrom, thirtyDayTo] = mockedFetchUsage.mock.calls[1];

      // An offset-bearing ISO datetime (backend CLAUDE.md fact #5 -- an offset-less value is
      // rejected with 400), never a bare date or a "Z"-suffixed UTC one.
      const OFFSET_DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/;
      expect(thirtyDayFrom).toMatch(OFFSET_DATETIME_RE);
      expect(thirtyDayTo).toMatch(OFFSET_DATETIME_RE);

      // "최근 30일" is exactly a 30-day inclusive window (to is the day after the 30th day, per
      // the backend's half-open [from, to) period -- UsageRepository's own [Caution]). Asserted
      // as an exact span rather than "wider than 이번 달" -- that comparison is not reliably
      // true (e.g. on the 30th of a 30-day month, 이번 달 spans exactly 30 days too, which this
      // test caught during authoring).
      const thirtyDaySpanMs =
        new Date(thirtyDayTo as string).getTime() - new Date(thirtyDayFrom as string).getTime();
      expect(thirtyDaySpanMs).toBe(30 * 24 * 60 * 60 * 1000);
    });

    it("'조회' applies manually edited start/end dates without requiring a preset", async () => {
      mockedFetchUsage.mockResolvedValue(usage());

      const user = userEvent.setup();
      render(<UsageDashboard />);
      await screen.findByText("$12.84");
      expect(mockedFetchUsage).toHaveBeenCalledTimes(1);

      // `fireEvent.change` with the full value, not `userEvent.type` character-by-character --
      // jsdom's `<input type="date">` rejects any intermediate partial-date string a
      // keystroke-by-keystroke type would produce, silently resetting `.value` to "" mid-typing.
      fireEvent.change(screen.getByLabelText("시작일"), { target: { value: "2026-08-10" } });
      fireEvent.change(screen.getByLabelText("종료일"), { target: { value: "2026-08-12" } });
      await user.click(screen.getByRole("button", { name: "조회" }));

      await waitFor(() => expect(mockedFetchUsage).toHaveBeenCalledTimes(2));
      const [from, to] = mockedFetchUsage.mock.calls[1];
      // 2026-08-10 through 2026-08-12 inclusive is a 3-day span -- "to" is sent as the day
      // *after* 2026-08-12 (half-open [from, to)), so the span is exactly 3 days, not 2.
      const spanMs = new Date(to as string).getTime() - new Date(from as string).getTime();
      expect(spanMs).toBe(3 * 24 * 60 * 60 * 1000);
    });

    it("retries the same applied range (fresh request) when '다시 시도' is clicked without changing the filter", async () => {
      mockedFetchUsage.mockRejectedValueOnce(new ApiError(500, "500 Internal Server Error: boom"));
      mockedFetchUsage.mockResolvedValueOnce(usage());

      const user = userEvent.setup();
      render(<UsageDashboard />);
      await screen.findByRole("alert");

      await user.click(screen.getByRole("button", { name: "다시 시도" }));

      await waitFor(() => expect(mockedFetchUsage).toHaveBeenCalledTimes(2));
      const [firstFrom, firstTo] = mockedFetchUsage.mock.calls[0];
      const [secondFrom, secondTo] = mockedFetchUsage.mock.calls[1];
      expect(secondFrom).toBe(firstFrom);
      expect(secondTo).toBe(firstTo);
      expect(await screen.findByText("$12.84")).toBeInTheDocument();
    });
  });
});
