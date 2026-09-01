package art.insightai.chatbot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import art.insightai.chatbot.eval.evalset.EvalCase;
import art.insightai.chatbot.eval.evalset.EvalSet;
import art.insightai.chatbot.eval.evalset.EvalSetLoader;
import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.rag.retrieval.Bm25SearchPort;
import art.insightai.chatbot.rag.retrieval.VectorSearchPort;
import art.insightai.chatbot.support.DatabaseGuardExtension;

/**
 * [Purpose] D7 sweep, stage 1 (T1): measures the axis-native score distribution -- BM25 {@code
 *   pdb.score} and pgvector cosine similarity -- that the two {@code app.search.*-raw-score-
 *   threshold} filters (BL-025) actually see, for every question of the 25-item eval set, against
 *   the real indexed corpus and the real bge-m3 query embeddings. Writes the per-question top-N
 *   table and a found/not-found summary to {@code {DOC_ROOT}/docs/eval/raw-score-distribution-
 *   <date>.md} so the threshold values T1 fixes in {@code application.yaml} are chosen from
 *   measured numbers, not guessed (hybrid-rag Spec §8-D2's explicit warning).
 * [Rationale] BL-025 established that the fused RRF score is a function of rank alone and cannot
 *   separate "has an answer" from "has no answer" (9 of 10 not-found questions got the same top-1
 *   fused score as found questions). Only the axis-native scores carry relevance, so the threshold
 *   sweep has to start from their distribution -- and that distribution can be measured with 25
 *   embedding calls and 50 SQL queries, with no chat completion and no judge at all. The expensive
 *   stage (real answers + judge, {@code EvalThresholdSweepIntegrationTest}) then only verifies the
 *   candidate values this stage produces, instead of searching blindly with paid calls.
 *   Both raw thresholds are pinned to {@code 0.0} here so the full top-20 of each axis is observed
 *   regardless of what {@code application.yaml} ends up holding after T1.
 * [Caution] Real cost: 25 real embedding calls (bge-m3, fractions of a cent) -- {@code
 *   @Tag("eval")}, excluded from the default build (chat:AC-35), run with {@code -Peval}. The
 *   "relevant" flag on a found-question chunk is a heuristic (chunk text contains every {@code
 *   expect_contains} string) used only to read the table; it is not a judge verdict. Requires the
 *   {@code chatbot-db} container, {@code infra/.env}, and the {@code Z:} drive.
 * [AC] eval-usage-dashboard:AC-10 (measurement half -- the assertion half is in
 *   {@code EvalThresholdSweepIntegrationTest})
 */
@Tag("eval")
@SpringBootTest
@ExtendWith(DatabaseGuardExtension.class)
class EvalRawScoreDistributionTest {

    private static final Logger log = LoggerFactory.getLogger(EvalRawScoreDistributionTest.class);

    private static final Path EVAL_SET_PATH = Path.of("Z:/Work/02.insight-chat/docs/eval/eval-set.yaml");
    private static final Path REPORT_DIR = Path.of("Z:/Work/02.insight-chat/docs/eval");
    private static final int AXIS_TOP_K = 20;
    private static final int ROWS_PER_AXIS_IN_REPORT = 6;

    @DynamicPropertySource
    static void realEmbeddingAndUnfilteredAxes(DynamicPropertyRegistry registry) {
        OpenRouterEnv.openEmbedding(registry);
        registry.add("app.search.bm25-raw-score-threshold", () -> "0.0");
        registry.add("app.search.vector-raw-score-threshold", () -> "0.0");
    }

    @Autowired
    private Bm25SearchPort bm25SearchPort;
    @Autowired
    private VectorSearchPort vectorSearchPort;

    private record AxisRow(int rank, double score, boolean relevant, Object page, String preview) {
    }

    private record QuestionResult(EvalCase evalCase, List<AxisRow> bm25, List<AxisRow> vector) {
        boolean found() {
            return evalCase instanceof EvalCase.Found;
        }

        double max(List<AxisRow> rows) {
            return rows.isEmpty() ? 0.0 : rows.get(0).score();
        }

        double maxRelevant(List<AxisRow> rows) {
            return rows.stream().filter(AxisRow::relevant).mapToDouble(AxisRow::score).max().orElse(0.0);
        }
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-10] 25문항의 축별 원점수(BM25 / 코사인) 분포를 실제 코퍼스·실제 임베딩으로 측정해 파일로 남긴다")
    void measuresRawScoreDistribution() throws IOException {
        EvalSet evalSet = EvalSetLoader.load(EVAL_SET_PATH);
        List<QuestionResult> results = new ArrayList<>();

        for (EvalCase evalCase : evalSet.cases()) {
            List<RetrievedChunk> bm25 = bm25SearchPort.search(evalCase.question(), AXIS_TOP_K);
            List<RetrievedChunk> vector = vectorSearchPort.search(evalCase.question(), AXIS_TOP_K);
            results.add(new QuestionResult(evalCase, toRows(evalCase, bm25), toRows(evalCase, vector)));
        }

        assertThat(results).hasSize(25);
        // every found question must retrieve something on the vector axis at threshold 0, or the
        // measurement itself is broken (no corpus / poisoned embedding key) rather than informative
        assertThat(results.stream().filter(QuestionResult::found).allMatch(r -> !r.vector().isEmpty()))
                .as("every found question retrieved at least one chunk on the vector axis")
                .isTrue();

        Path file = REPORT_DIR.resolve("raw-score-distribution-" + LocalDate.now() + ".md");
        Files.createDirectories(REPORT_DIR);
        Files.writeString(file, render(results), StandardCharsets.UTF_8);
        log.info("[eval raw-score] distribution written to {}", file);
    }

    /**
     * [Purpose] AC-10's assertion half, at the confirmed values: the raw thresholds sit strictly
     *   between the D1-spike noise score (1.18, plan §7.1 spike 5-c / hybrid-rag R6) and the lowest
     *   relevant chunk of any found question, so every relevant chunk survives both axis filters
     *   while the below-threshold tail (the 1.18-class noise) is excluded.
     * [Rationale] Measured against the same live corpus/embeddings as {@link
     *   #measuresRawScoreDistribution}; no chat completion involved, so this is deterministic for a
     *   fixed corpus and cheap (25 embedding calls).
     * [AC] eval-usage-dashboard:AC-10
     */
    @Test
    @DisplayName("[eval-usage-dashboard:AC-10] 확정 원점수 임계값(BM25 1.3 / 코사인 0.35)은 D1 스파이크급 노이즈 청크"
            + "(BM25 1.18 이하)를 제외하면서, 있는 질문 15건의 정답 청크는 두 축 모두에서 전부 통과시킨다")
    void confirmedRawThresholdsExcludeNoiseButKeepEveryRelevantChunk() {
        EvalSet evalSet = EvalSetLoader.load(EVAL_SET_PATH);
        double bm25Threshold = SweepConfirmedDefaults.BM25_RAW_SCORE_THRESHOLD;
        double vectorThreshold = SweepConfirmedDefaults.VECTOR_RAW_SCORE_THRESHOLD;

        assertThat(bm25Threshold).as("threshold must exclude the D1-spike 1.18 noise row").isGreaterThan(1.18);

        int excludedBm25 = 0;
        int excludedVector = 0;
        for (EvalCase evalCase : evalSet.cases()) {
            List<AxisRow> bm25 = toRows(evalCase, bm25SearchPort.search(evalCase.question(), AXIS_TOP_K));
            List<AxisRow> vector = toRows(evalCase, vectorSearchPort.search(evalCase.question(), AXIS_TOP_K));
            excludedBm25 += (int) bm25.stream().filter(r -> r.score() < bm25Threshold).count();
            excludedVector += (int) vector.stream().filter(r -> r.score() < vectorThreshold).count();
            if (evalCase instanceof EvalCase.Found) {
                assertThat(bm25.stream().anyMatch(r -> r.relevant() && r.score() >= bm25Threshold))
                        .as("found question \"%s\" keeps a relevant chunk on the BM25 axis at threshold %s",
                                evalCase.question(), bm25Threshold)
                        .isTrue();
                assertThat(vector.stream().anyMatch(r -> r.relevant() && r.score() >= vectorThreshold))
                        .as("found question \"%s\" keeps a relevant chunk on the cosine axis at threshold %s",
                                evalCase.question(), vectorThreshold)
                        .isTrue();
            }
        }
        assertThat(excludedBm25).as("chunks the BM25 threshold actually excludes across 25 questions").isPositive();
        assertThat(excludedVector).as("chunks the cosine threshold actually excludes across 25 questions").isPositive();
        log.info("[eval raw-score] AC-10 at bm25>={} cosine>={}: excluded bm25={} cosine={} (of 25x{} per axis)",
                bm25Threshold, vectorThreshold, excludedBm25, excludedVector, AXIS_TOP_K);
    }

    private static List<AxisRow> toRows(EvalCase evalCase, List<RetrievedChunk> chunks) {
        List<AxisRow> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            boolean relevant = evalCase instanceof EvalCase.Found found
                    && found.expectContains().stream().allMatch(chunk.content()::contains);
            String preview = chunk.content().replace('\n', ' ').replace('|', '/');
            preview = preview.substring(0, Math.min(40, preview.length()));
            rows.add(new AxisRow(i + 1, chunk.score(), relevant, chunk.metadata().get("page"), preview));
        }
        return rows;
    }

    private static String render(List<QuestionResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# D7 sweep stage 1 -- raw score distribution (BL-025 filters' input)\n\n");
        sb.append("Generated: ").append(OffsetDateTime.now()).append('\n');
        sb.append("Corpus: real indexed documents in chatbot-db; query embeddings: real bge-m3; ")
                .append("axis top-k: ").append(AXIS_TOP_K).append("; both raw thresholds pinned to 0.0.\n\n");
        sb.append("`relevant` = chunk text contains every expect_contains string (heuristic, found questions only).\n\n");

        sb.append("## Summary\n\n");
        sb.append("| # | type | question | bm25 max | bm25 max relevant | cosine max | cosine max relevant |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int n = 0;
        for (QuestionResult r : results) {
            n++;
            sb.append("| ").append(n)
                    .append(" | ").append(r.found() ? "found" : "not_found")
                    .append(" | ").append(r.evalCase().question().replace('|', '/'))
                    .append(" | ").append(fmt(r.max(r.bm25())))
                    .append(" | ").append(r.found() ? fmt(r.maxRelevant(r.bm25())) : "-")
                    .append(" | ").append(fmt(r.max(r.vector())))
                    .append(" | ").append(r.found() ? fmt(r.maxRelevant(r.vector())) : "-")
                    .append(" |\n");
        }

        sb.append("\n### Separation\n\n");
        appendSeparation(sb, "BM25 (pdb.score)", results, true);
        appendSeparation(sb, "cosine similarity", results, false);

        sb.append("\n## Per question (top ").append(ROWS_PER_AXIS_IN_REPORT).append(" per axis)\n\n");
        n = 0;
        for (QuestionResult r : results) {
            n++;
            sb.append("### ").append(n).append(". [").append(r.found() ? "found" : "not_found").append("] ")
                    .append(r.evalCase().question()).append("\n\n");
            sb.append("| axis | rank | score | relevant | page | preview |\n|---|---|---|---|---|---|\n");
            appendAxis(sb, "bm25", r.bm25());
            appendAxis(sb, "cosine", r.vector());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendSeparation(StringBuilder sb, String label, List<QuestionResult> results, boolean bm25) {
        double foundMinRelevant = results.stream().filter(QuestionResult::found)
                .mapToDouble(r -> r.maxRelevant(bm25 ? r.bm25() : r.vector())).min().orElse(0.0);
        double foundMinTop = results.stream().filter(QuestionResult::found)
                .mapToDouble(r -> r.max(bm25 ? r.bm25() : r.vector())).min().orElse(0.0);
        double notFoundMaxTop = results.stream().filter(r -> !r.found())
                .mapToDouble(r -> r.max(bm25 ? r.bm25() : r.vector())).max().orElse(0.0);
        sb.append("- **").append(label).append("** -- found: min over questions of top-1 = ")
                .append(fmt(foundMinTop)).append(", min over questions of best *relevant* chunk = ")
                .append(fmt(foundMinRelevant)).append(" / not_found: max over questions of top-1 = ")
                .append(fmt(notFoundMaxTop)).append(". A threshold strictly between ")
                .append(fmt(notFoundMaxTop)).append(" and ").append(fmt(foundMinRelevant))
                .append(notFoundMaxTop < foundMinRelevant ? " separates the two sets on this axis." : " does NOT exist -- the sets overlap on this axis.")
                .append('\n');
    }

    private static void appendAxis(StringBuilder sb, String axis, List<AxisRow> rows) {
        for (AxisRow row : rows.stream().limit(ROWS_PER_AXIS_IN_REPORT).toList()) {
            sb.append("| ").append(axis).append(" | ").append(row.rank()).append(" | ").append(fmt(row.score()))
                    .append(" | ").append(row.relevant() ? "yes" : "")
                    .append(" | ").append(row.page() == null ? "" : row.page())
                    .append(" | ").append(row.preview()).append(" |\n");
        }
        if (rows.isEmpty()) {
            sb.append("| ").append(axis).append(" | - | (no rows) | | | |\n");
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
