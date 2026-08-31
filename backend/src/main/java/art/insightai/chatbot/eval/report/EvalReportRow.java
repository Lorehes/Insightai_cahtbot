package art.insightai.chatbot.eval.report;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * [Purpose] One aggregated report row for a single mode x rerank combination (AC-8) — the six
 *   required metrics plus the denominators needed to render them as "13/15", a missing-cost
 *   counter (D-6) that keeps a null cost_usd from being silently treated as zero, and two
 *   contamination indicators (AC-19a, AC-20a) that mark a row as judgment-deferred rather than
 *   trusting its pass/fail verdict at face value.
 * [Rationale] Denominators ({@code foundTotal}, {@code notFoundTotal}) are carried alongside the
 *   numerators instead of hardcoded 15/10 constants, per the E4 card instruction to derive them
 *   from the actual judged set so the "13/15" display never desyncs from the judged count. AC-9a's
 *   pass/fail *gate*, however, no longer trusts these derived denominators alone — see
 *   {@link #passed()}. {@code excludedByStatus} (AC-19a field 1) and {@code
 *   retrievedNonIndexedChunkCount}/{@code finishReasonCounts} (AC-19a field 2 / AC-20a) are a
 *   separate, independent axis from the pass/fail gate: a row can pass AC-9a and still be
 *   judgment-deferred (see {@link #judgmentDeferred()}), because contamination in the retrieval or
 *   generation pipeline can make {@link #passed()} true or false for the wrong reason.
 * [AC] eval-usage-dashboard:AC-6b
 * [AC] eval-usage-dashboard:AC-8
 * [AC] eval-usage-dashboard:AC-9a
 * [AC] eval-usage-dashboard:AC-19a
 * [AC] eval-usage-dashboard:AC-20a
 */
public record EvalReportRow(
        int answerCorrect,
        int sourceCorrect,
        int foundTotal,
        int notFoundCorrect,
        int notFoundTotal,
        int hallucinationCount,
        long avgLatencyMs,
        BigDecimal totalCostUsd,
        int missingCostCount,
        List<EvalExcludedDocumentStatusCount> excludedByStatus,
        int retrievedNonIndexedChunkCount,
        List<FinishReasonCount> finishReasonCounts) {

    /**
     * [Purpose] Finish reason bucket value that, when present with a count of 1 or more, marks a
     *   row as judgment-deferred (AC-20a). Values in {@link #finishReasonCounts} are expected to
     *   already be lowercased by {@link EvalReportAggregator} (or by a test constructing a row
     *   directly), so this constant is compared with {@code equals}, not {@code equalsIgnoreCase}.
     */
    private static final String LENGTH_FINISH_REASON = "length";

    /**
     * [Purpose] Legacy 9-arg shape kept for every pre-existing call site
     *   ({@code EvalReportRowTest}, {@code EvalReportTest}, {@code EvalReportAggregatorTest} via
     *   {@link EvalReportAggregator#aggregate(List)}) that has no opinion on the three new
     *   contamination fields.
     * [Rationale] Defaults to an empty {@code excludedByStatus}, {@code
     *   retrievedNonIndexedChunkCount=0} and an empty {@code finishReasonCounts} — "no
     *   contamination observed" — so those tests keep verifying AC-6b/AC-8/AC-9a exactly as before
     *   without being touched by this card (E6 card note: "순수 추가만").
     */
    public EvalReportRow(
            int answerCorrect,
            int sourceCorrect,
            int foundTotal,
            int notFoundCorrect,
            int notFoundTotal,
            int hallucinationCount,
            long avgLatencyMs,
            BigDecimal totalCostUsd,
            int missingCostCount) {
        this(answerCorrect, sourceCorrect, foundTotal, notFoundCorrect, notFoundTotal,
                hallucinationCount, avgLatencyMs, totalCostUsd, missingCostCount,
                List.of(), 0, List.of());
    }

    /**
     * [Purpose] AC-9a's fixed eval-set shape: 15 found cases + 10 not-found cases = the 25-item
     *   set AC-1 defines. {@link #passed()} treats any row whose denominators do not match these
     *   exactly as an automatic fail rather than trusting a derived, possibly-short count.
     */
    private static final int AC9_FOUND_TOTAL = 15;

    /** [Purpose] See {@link #AC9_FOUND_TOTAL} — the not-found half of AC-1's 25-item eval set. */
    private static final int AC9_NOT_FOUND_TOTAL = 10;

    /**
     * [Purpose] AC-9a's minimum passing count on the found axis — "at least 13 of 15 found cases
     *   must be answer-correct". Kept as an absolute count, not a ratio (see {@link #passed()}).
     */
    private static final int AC9_ANSWER_MIN = 13;

    /**
     * [Purpose] Rejects impossible or inconsistent rows at construction time instead of letting
     *   {@link #passed()} silently misjudge them later.
     * [Rationale] Every numerator/denominator here is a non-negative count, {@code totalCostUsd}
     *   must not be null, and a numerator can never exceed its own denominator — these are the
     *   invariants actually enforced below.
     * [Caution] This does NOT catch a swapped constructor argument between fields that share the
     *   same denominator: {@code answerCorrect} <-> {@code sourceCorrect} are both bounded by
     *   {@code foundTotal}, and {@code notFoundCorrect} <-> {@code hallucinationCount} are both
     *   bounded by {@code notFoundTotal}. Swapping either pair still satisfies every check below,
     *   yet {@link #passed()} reads {@code answerCorrect} alone, so such a swap silently changes
     *   the pass/fail result (maintainability Medium-2, 2026-08-28 round-2 review). E5 will
     *   construct rows directly from four sweep combinations; closing this gap with a named
     *   factory (e.g. {@code ofFound(...)}/{@code ofNotFound(...)}) or a builder is a decision for
     *   the E5 card, not this constructor. {@code excludedByStatus}/{@code finishReasonCounts} are
     *   defensively copied to immutable lists (mirrors {@link EvalReport}'s L1 fix) so that a
     *   caller's mutable list cannot flip {@link #judgmentDeferred()}'s result after construction.
     *   Each list's elements are checked for {@code null} before that copy and rejected with the
     *   same {@code IllegalArgumentException} used by every other check in this constructor —
     *   without this check, {@code List.copyOf} on a list containing {@code null} throws {@code
     *   NullPointerException} instead, an inconsistent exception type for what is otherwise the
     *   same "invalid input" case as a null list argument itself (Low-3, 2026-08-30 review).
     */
    public EvalReportRow {
        if (answerCorrect < 0 || sourceCorrect < 0 || foundTotal < 0
                || notFoundCorrect < 0 || notFoundTotal < 0 || hallucinationCount < 0
                || avgLatencyMs < 0 || missingCostCount < 0 || retrievedNonIndexedChunkCount < 0) {
            throw new IllegalArgumentException("EvalReportRow counts and latency must not be negative");
        }
        if (totalCostUsd == null) {
            throw new IllegalArgumentException("totalCostUsd must not be null");
        }
        if (excludedByStatus == null) {
            throw new IllegalArgumentException("excludedByStatus must not be null");
        }
        if (finishReasonCounts == null) {
            throw new IllegalArgumentException("finishReasonCounts must not be null");
        }
        if (excludedByStatus.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("excludedByStatus must not contain a null element");
        }
        if (finishReasonCounts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("finishReasonCounts must not contain a null element");
        }
        excludedByStatus = List.copyOf(excludedByStatus);
        finishReasonCounts = List.copyOf(finishReasonCounts);
        if (answerCorrect > foundTotal) {
            throw new IllegalArgumentException(
                    "answerCorrect (%d) cannot exceed foundTotal (%d)".formatted(answerCorrect, foundTotal));
        }
        if (sourceCorrect > foundTotal) {
            throw new IllegalArgumentException(
                    "sourceCorrect (%d) cannot exceed foundTotal (%d)".formatted(sourceCorrect, foundTotal));
        }
        if (notFoundCorrect > notFoundTotal) {
            throw new IllegalArgumentException(
                    "notFoundCorrect (%d) cannot exceed notFoundTotal (%d)".formatted(notFoundCorrect, notFoundTotal));
        }
        if (hallucinationCount > notFoundTotal) {
            throw new IllegalArgumentException(
                    "hallucinationCount (%d) cannot exceed notFoundTotal (%d)".formatted(hallucinationCount, notFoundTotal));
        }
    }

    /**
     * [Purpose] AC-9a's pass rule for this single combination: every not-found case must be
     *   correct (100%, no ratio allowed) AND at least 13/15 of found cases must be correct.
     *   AC-9a looks only at the answer axis — do not substitute {@link
     *   art.insightai.chatbot.eval.judge.JudgeResult#pass()} here, since {@code Found.pass()} is
     *   {@code answerCorrect && sourceCorrect} and would silently fold the source-citation axis
     *   into a rule AC-9a defines over the answer axis alone (L6, 2026-08-28 review).
     * [Rationale] The user confirmed on 2026-08-26 that 100% on the not-found side is the
     *   strictest hallucination standard on purpose (no ratio relaxation). Deriving
     *   {@code foundTotal}/{@code notFoundTotal} from the judged set (see class-level Rationale)
     *   turned AC-9a's *absolute* thresholds into a *relative* ratio: a short judged run (e.g. 9 of
     *   15 found cases judged) would pass at 8/9 correct, which is well below the intended 13/15
     *   bar (2026-08-28 review Medium-2). This method now fails closed instead: a row whose
     *   denominators do not exactly match the fixed eval-set size ({@link #AC9_FOUND_TOTAL}/{@link
     *   #AC9_NOT_FOUND_TOTAL}) is treated as not passed, regardless of its numerators — an
     *   eval-set-size mismatch is a data problem, not something AC-9a should paper over with a
     *   relaxed threshold.
     * [Caution] 13.0/15 = 0.8667; writing the found-axis check as {@code answerCorrect >= 0.87 *
     *   foundTotal} would reject exactly 13/15 (0.87 * 15 = 13.050000000000001), which is the
     *   boundary AC-9a requires to pass. Once {@code foundTotal} is pinned to exactly {@link
     *   #AC9_FOUND_TOTAL} by the guard above, the ratio and the absolute count coincide, so the
     *   comparison below is the plain {@code answerCorrect >= AC9_ANSWER_MIN} — no cross-
     *   multiplication and no floating-point literal needed or wanted.
     */
    public boolean passed() {
        if (foundTotal != AC9_FOUND_TOTAL || notFoundTotal != AC9_NOT_FOUND_TOTAL) {
            return false;
        }
        return notFoundCorrect == notFoundTotal && answerCorrect >= AC9_ANSWER_MIN;
    }

    /**
     * [Purpose] Whether this row's verdict should be treated as unreliable and held back from a
     *   pass/fail claim, independent of {@link #passed()} — AC-19a field 2 ("회수된 청크 중
     *   INDEXED 외 상태의 건수" &gt;= 1) and AC-20a ("length" finish reason count &gt;= 1) are the
     *   two triggers.
     * [Rationale] {@code excludedByStatus} (AC-19a field 1) is deliberately excluded from this
     *   check — the Spec is explicit that it is evidence the exclusion filter is working, not a
     *   reason to defer judgment (AC-19a: "이는 판정 유보 사유가 아니다"). Only contamination that
     *   actually reached the judged answer (a non-INDEXED chunk was retrieved, or the LLM response
     *   was truncated) can have corrupted the pass/fail verdict, which is what the other two
     *   signals capture.
     */
    public boolean judgmentDeferred() {
        if (retrievedNonIndexedChunkCount >= 1) {
            return true;
        }
        return finishReasonCounts.stream()
                .anyMatch(entry -> LENGTH_FINISH_REASON.equals(entry.finishReason()) && entry.count() >= 1);
    }
}
