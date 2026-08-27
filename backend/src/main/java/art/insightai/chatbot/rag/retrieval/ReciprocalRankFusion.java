package art.insightai.chatbot.rag.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Fuses the BM25 and vector search axes into a single ranked list with Reciprocal
 *   Rank Fusion, then drops entries below {@code app.search.score-threshold} (hybrid-rag Spec
 *   AC-3, AC-4; Tech Review D-T2, D-T3).
 * [Rationale] The fused score per chunk id is {@code Σ (weight_axis / (rrfK + rank_axis))}
 *   (AC-3's exact formula, Spec §7.3 SQL translated into Java per D-T2). {@code rank_axis} is
 *   1-based and derived purely from list position: callers must pass each axis's results
 *   already sorted best-first, since this class trusts input order rather than re-ranking by
 *   {@link RetrievedChunk#score()}. A chunk id present in both axis lists accumulates both
 *   terms, which is what lets it outrank a chunk only one axis surfaced at the same rank
 *   (AC-3). The threshold filter (AC-4) runs here, immediately after fusion, so the pipeline
 *   order Tech Review D-T3 requires ("threshold applies exactly once, right after RRF fusion")
 *   cannot be skipped or duplicated by whichever orchestrator (T7's
 *   {@code HybridDocumentRetriever}) calls this class.
 * [Caution] The threshold comparison is {@code >=}, not {@code >} (Spec Test Case U2: a score
 *   exactly equal to the threshold must pass). Returned {@link RetrievedChunk} instances carry
 *   the fused RRF score in {@code score()} — a new value, distinct from whatever axis-native
 *   score the same id had on input (Tech Review D-T6: {@code SourceRef#score} is always this
 *   fused score, never an axis or rerank score). When an id appears in both axes, {@code
 *   content}/{@code metadata} are kept from whichever axis is scanned first (BM25, then
 *   vector) — both axes are expected to describe the same underlying chunk row identically, so
 *   this choice has no observable effect. This class does not call a database or network; it
 *   only operates on the lists it is given (Tech Review D-T4).
 * [AC] hybrid-rag:AC-3
 * [AC] hybrid-rag:AC-4
 */
@Component
public class ReciprocalRankFusion {

    private final int rrfK;
    private final double weightBm25;
    private final double weightVector;
    private final double scoreThreshold;

    public ReciprocalRankFusion(AppProperties appProperties) {
        AppProperties.Search search = appProperties.search();
        this.rrfK = search.rrfK();
        this.weightBm25 = search.weightBm25();
        this.weightVector = search.weightVector();
        this.scoreThreshold = search.scoreThreshold();
    }

    /**
     * [Purpose] Fuses both axes and applies the score-threshold filter in one pass.
     * [Caution] Either list may be empty (e.g. {@code app.search.mode=vector} never runs the
     *   BM25 axis at all) — pass {@code List.of()}, not {@code null}, for the axis that did not
     *   run. The result is sorted descending by fused score; ties keep the order chunks were
     *   first encountered in (BM25 axis before vector axis), which is an implementation detail,
     *   not an AC guarantee.
     */
    public List<RetrievedChunk> fuse(List<RetrievedChunk> bm25Results, List<RetrievedChunk> vectorResults) {
        Map<String, RetrievedChunk> chunksById = new LinkedHashMap<>();
        Map<String, Double> scoresById = new LinkedHashMap<>();

        accumulate(bm25Results, weightBm25, chunksById, scoresById);
        accumulate(vectorResults, weightVector, chunksById, scoresById);

        return scoresById.entrySet().stream()
                .filter(entry -> entry.getValue() >= scoreThreshold)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> withFusedScore(chunksById.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    /**
     * [Purpose] Adds one axis's contribution — {@code weight / (rrfK + rank)} per chunk, where
     *   rank is the 1-based position of the chunk in {@code axisResults} — to the running
     *   fused-score map, summing contributions for ids already seen on a previous axis.
     */
    private void accumulate(
            List<RetrievedChunk> axisResults,
            double weight,
            Map<String, RetrievedChunk> chunksById,
            Map<String, Double> scoresById
    ) {
        for (int i = 0; i < axisResults.size(); i++) {
            RetrievedChunk chunk = axisResults.get(i);
            int rank = i + 1;
            double contribution = weight / (rrfK + rank);
            chunksById.putIfAbsent(chunk.id(), chunk);
            scoresById.merge(chunk.id(), contribution, Double::sum);
        }
    }

    /**
     * [Purpose] Rebuilds a {@link RetrievedChunk} with its axis-native score replaced by the
     *   fused RRF score (Tech Review D-T6).
     */
    private RetrievedChunk withFusedScore(RetrievedChunk chunk, double fusedScore) {
        return new RetrievedChunk(chunk.id(), chunk.content(), chunk.metadata(), fusedScore);
    }
}
