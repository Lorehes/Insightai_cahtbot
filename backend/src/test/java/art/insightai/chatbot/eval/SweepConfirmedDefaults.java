package art.insightai.chatbot.eval;

import art.insightai.chatbot.common.config.AppProperties;

/**
 * [Purpose] The {@code app.search.*} / {@code app.rerank.enabled} values the D7 sweep confirmed
 *   (eval-usage-dashboard T1, 2026-09-01), in one place, so three things are pinned to the same
 *   numbers: (1) {@code application.yaml}'s defaults ({@link SearchDefaultsReflectSweepTest} parses
 *   the file and compares), (2) the real-call sweep that produced the verdict ({@link
 *   EvalThresholdSweepIntegrationTest} runs its "confirmed" combination at exactly these values
 *   and asserts the live {@link AppProperties} bound them), and (3) the stage-1 threshold
 *   assertion ({@link EvalRawScoreDistributionTest}).
 * [Rationale] Test contexts pin both raw thresholds to 0.0 ({@code config/application.yaml} --
 *   stub embedding scale), so an {@code @SpringBootTest} cannot read the production defaults back
 *   through {@link AppProperties} to prove AC-11/AC-12; comparing the YAML file itself against
 *   these constants, and running the sweep at these same constants, closes that chain instead.
 *   Evidence files: {@code {DOC_ROOT}/docs/eval/raw-score-distribution-2026-09-01.md} (stage 1) and
 *   {@code {DOC_ROOT}/docs/eval/threshold-sweep-2026-09-01.md} (stage 2).
 * [Caution] Changing a value here without re-running the sweep turns AC-11/AC-12 into a claim
 *   with no measurement behind it -- change the YAML, re-run {@code -Peval}, then update here.
 */
final class SweepConfirmedDefaults {

    private SweepConfirmedDefaults() {
    }

    static final AppProperties.Search.Mode MODE = AppProperties.Search.Mode.HYBRID;
    static final boolean RERANK_ENABLED = false;
    static final int RRF_K = 60;
    static final double WEIGHT_BM25 = 0.4;
    static final double WEIGHT_VECTOR = 0.6;
    /** Fused-score filter (AC-4) stays disabled -- the fused score carries no relevance (BL-025). */
    static final double SCORE_THRESHOLD = 0.0;
    /** ParadeDB {@code pdb.score} scale. Stage 1: lowest relevant chunk 1.536, D1-spike noise 1.18. */
    static final double BM25_RAW_SCORE_THRESHOLD = 1.3;
    /** bge-m3 cosine scale. Stage 1: lowest relevant chunk 0.3874. */
    static final double VECTOR_RAW_SCORE_THRESHOLD = 0.35;
}
