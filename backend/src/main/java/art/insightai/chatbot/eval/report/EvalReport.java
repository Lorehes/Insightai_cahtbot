package art.insightai.chatbot.eval.report;

import java.util.List;

/**
 * [Purpose] The full set of per-combination rows produced by one sweep run (AC-7, built by E5)
 *   plus AC-9a's "at least one combination passes" verdict across all of them.
 * [Rationale] E4's scope stops at in-memory aggregation and per-row pass/fail (Tech Review card
 *   table, E5 owns the sweep execution and file output). This wrapper is the minimal piece AC-9a
 *   needs beyond a single row — "조합이 1개 이상이면 통과" requires looking across rows, which no
 *   single {@link EvalReportRow} can answer alone.
 * [AC] eval-usage-dashboard:AC-9a
 */
public record EvalReport(List<EvalReportRow> rows) {

    /**
     * [Purpose] Defends {@link #anyCombinationPassed()} against post-construction mutation of the
     *   backing list changing its verdict, and fails fast on a null list instead of deferring the
     *   NPE to first use.
     * [Rationale] L1 (2026-08-28 review) — {@code rows} previously escaped as whatever list the
     *   caller passed in; a caller holding a mutable {@code List} could add or remove rows after
     *   construction and silently flip {@link #anyCombinationPassed()}'s result.
     */
    public EvalReport {
        rows = List.copyOf(rows);
    }

    /**
     * [Purpose] AC-9a: the sweep as a whole passes if at least one combination's row passes.
     */
    public boolean anyCombinationPassed() {
        return rows.stream().anyMatch(EvalReportRow::passed);
    }
}
