package art.insightai.chatbot.eval.report;

import java.util.List;

/**
 * [Purpose] The full language-scoped view of one sweep run's judged cases (AD-10) — one
 *   {@link EvalLanguageReportRow} per language (AC-10) plus every judged case's individual
 *   language-match record (AC-11).
 * [Rationale] Deliberately carries no pass/fail verdict of its own (AD-11) — {@link
 *   EvalReportRow#passed()} and {@link EvalReport#anyCombinationPassed()} stay untouched and
 *   remain the only AC-9a gate, scoped by the caller (T9) to the {@code Language.KO} subset. This
 *   type is measurement-and-aggregation only, per Spec §2.2-2 — it exists to be read/displayed,
 *   not to gate a build.
 * [Caution] {@code rows} and {@code caseMatches} are defensively copied to immutable lists
 *   (mirrors {@link EvalReport}'s L1 fix) so a caller's mutable list cannot mutate this report
 *   after construction and silently change what a reader already observed.
 * [AC] multilingual-support:AC-10
 * [AC] multilingual-support:AC-11
 */
public record EvalLanguageReport(List<EvalLanguageReportRow> rows, List<CaseLanguageMatch> caseMatches) {

    /**
     * [Purpose] Fails fast on a null list and defends {@link #rows}/{@link #caseMatches} against
     *   post-construction mutation, mirroring {@link EvalReport}'s L1 fix.
     */
    public EvalLanguageReport {
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        if (caseMatches == null) {
            throw new IllegalArgumentException("caseMatches must not be null");
        }
        rows = List.copyOf(rows);
        caseMatches = List.copyOf(caseMatches);
    }
}
