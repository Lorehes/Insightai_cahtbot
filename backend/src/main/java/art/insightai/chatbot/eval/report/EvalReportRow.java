package art.insightai.chatbot.eval.report;

import java.math.BigDecimal;

/**
 * [Purpose] One aggregated report row for a single mode x rerank combination (AC-8) — the six
 *   required metrics plus the denominators needed to render them as "13/15", and a missing-cost
 *   counter (D-6) that keeps a null cost_usd from being silently treated as zero.
 * [Rationale] Denominators ({@code foundTotal}, {@code notFoundTotal}) are carried alongside the
 *   numerators instead of hardcoded 15/10 constants, per the E4 card instruction to derive them
 *   from the actual judged set so the "13/15" display never desyncs from the judged count. AC-9a's
 *   pass/fail *gate*, however, no longer trusts these derived denominators alone — see
 *   {@link #passed()}.
 * [AC] eval-usage-dashboard:AC-6b
 * [AC] eval-usage-dashboard:AC-8
 * [AC] eval-usage-dashboard:AC-9a
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
        int missingCostCount) {

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
     *   the E5 card, not this constructor.
     */
    public EvalReportRow {
        if (answerCorrect < 0 || sourceCorrect < 0 || foundTotal < 0
                || notFoundCorrect < 0 || notFoundTotal < 0 || hallucinationCount < 0
                || avgLatencyMs < 0 || missingCostCount < 0) {
            throw new IllegalArgumentException("EvalReportRow counts and latency must not be negative");
        }
        if (totalCostUsd == null) {
            throw new IllegalArgumentException("totalCostUsd must not be null");
        }
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
}
