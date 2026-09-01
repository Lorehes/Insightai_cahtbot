package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Verifies {@link ReciprocalRankFusion}'s score formula (AC-3) and score-threshold
 *   filter (AC-4) as pure logic — no Spring context, no DB, no network.
 * [Caution] {@code appProperties(...)} overrides only {@code search} on {@link
 *   TestAppPropertiesFactory}'s valid baseline (T5 review 2026-08-27, Medium-4) -- the other five
 *   components, including {@code AppProperties.Models} (chat T5's 6th record component, unrelated
 *   to fusion logic), stay at the factory's valid defaults purely to satisfy the aggregate's
 *   compact constructors; {@link ReciprocalRankFusion} never reads {@code AppProperties.models()}.
 */
class ReciprocalRankFusionTest {

    private static AppProperties appProperties(int rrfK, double weightBm25, double weightVector, double scoreThreshold) {
        return TestAppPropertiesFactory.builder()
                .search(new AppProperties.Search(
                        AppProperties.Search.Mode.HYBRID, 5, rrfK, weightBm25, weightVector, 20, 20, scoreThreshold, 0.0, 0.0))
                .build();
    }

    private static RetrievedChunk chunk(String id) {
        return new RetrievedChunk(id, "content-" + id, Map.of("document_id", UUID.randomUUID().toString()), 0.0);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-3] 두 축 모두에 등장한 청크는 두 항이 합산되어 한 축에만 등장한 청크보다 앞선다")
    void sumsBothAxisTermsForChunkPresentInBothAxes() {
        // given: rrfK=60, equal weights. "both" is rank 1 on both axes; "bm25-only" is rank 2
        //   on BM25 (behind "both"). Expected fused scores: both = 0.5/61 + 0.5/61 = 1/61
        //   ≈ 0.016393, bm25-only = 0.5/62 ≈ 0.008065 — "both" outranks "bm25-only" even though
        //   a rank-1-only chunk (0.5/61 ≈ 0.008197) would also have outranked it, which is the
        //   point AC-3 makes: the two-axis sum beats a single-axis term at the same rank.
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(appProperties(60, 0.5, 0.5, 0.0));
        RetrievedChunk both = chunk("both");
        RetrievedChunk bm25Only = chunk("bm25-only");
        List<RetrievedChunk> bm25Results = List.of(both, bm25Only);
        List<RetrievedChunk> vectorResults = List.of(both);

        // when
        List<RetrievedChunk> fused = rrf.fuse(bm25Results, vectorResults);

        // then: "both" ranks ahead of "bm25-only"
        assertThat(fused).extracting(RetrievedChunk::id).containsExactly("both", "bm25-only");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(fused.get(1).score()).isCloseTo(0.5 / 62, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("[hybrid-rag:AC-3] rank가 클수록(순위가 낮을수록) 융합 점수가 작아진다")
    void scoreDecreasesAsRankIncreases() {
        // given: three chunks on the BM25 axis only, ranks 1..3, nothing on the vector axis
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(appProperties(60, 1.0, 1.0, 0.0));
        RetrievedChunk rank1 = chunk("rank1");
        RetrievedChunk rank2 = chunk("rank2");
        RetrievedChunk rank3 = chunk("rank3");

        // when
        List<RetrievedChunk> fused = rrf.fuse(List.of(rank1, rank2, rank3), List.of());

        // then
        assertThat(fused).extracting(RetrievedChunk::id).containsExactly("rank1", "rank2", "rank3");
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        assertThat(fused.get(1).score()).isGreaterThan(fused.get(2).score());
    }

    @Test
    @DisplayName("[hybrid-rag:AC-4] 융합 점수가 임계값 미만인 청크는 결과에서 제외된다")
    void excludesChunksBelowScoreThreshold() {
        // given: rrfK=60, weight=1.0 on a single axis. rank1 score = 1/61 ≈ 0.01639,
        //   rank2 score = 1/62 ≈ 0.01613. Threshold set strictly between the two.
        double threshold = 0.0163;
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(appProperties(60, 1.0, 1.0, threshold));
        RetrievedChunk rank1 = chunk("rank1");
        RetrievedChunk rank2 = chunk("rank2");

        // when
        List<RetrievedChunk> fused = rrf.fuse(List.of(rank1, rank2), List.of());

        // then
        assertThat(fused).extracting(RetrievedChunk::id).containsExactly("rank1");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-4] 융합 점수가 임계값과 정확히 같은 청크는 통과한다 (경계값)")
    void includesChunkWhenScoreExactlyEqualsThreshold() {
        // given: rrfK=60, weight=1.0, single-axis rank-1 chunk scores exactly 1/61
        double exactScore = 1.0 / 61;
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(appProperties(60, 1.0, 1.0, exactScore));
        RetrievedChunk rank1 = chunk("rank1");

        // when
        List<RetrievedChunk> fused = rrf.fuse(List.of(rank1), List.of());

        // then
        assertThat(fused).extracting(RetrievedChunk::id).containsExactly("rank1");
    }

    @Test
    @DisplayName("양쪽 축이 모두 비어 있으면 빈 목록을 반환한다")
    void returnsEmptyListWhenBothAxesEmpty() {
        // given
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(appProperties(60, 0.5, 0.5, 0.0));

        // when
        List<RetrievedChunk> fused = rrf.fuse(List.of(), List.of());

        // then
        assertThat(fused).isEmpty();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-3] 축별 가중치(weight_axis)가 서로 바뀌지 않고 정확한 축에 적용된다")
    void appliesEachAxisWeightToItsOwnAxisNotSwapped() {
        // given: asymmetric weights (bm25=0.9, vector=0.1). If the implementation ever swapped
        //   which weight applies to which axis, this would flip the outcome — a test using equal
        //   weights (like the other AC-3 cases above) could never catch that class of bug because
        //   both axes would produce identical contributions regardless of which weight lands
        //   where. "bm25-rank1" appears only on the BM25 axis at rank 1; "vector-rank1" appears
        //   only on the vector axis at rank 1.
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(appProperties(60, 0.9, 0.1, 0.0));
        RetrievedChunk bm25Rank1 = chunk("bm25-rank1");
        RetrievedChunk vectorRank1 = chunk("vector-rank1");

        // when
        List<RetrievedChunk> fused = rrf.fuse(List.of(bm25Rank1), List.of(vectorRank1));

        // then: bm25-rank1 (weight 0.9) clearly outranks vector-rank1 (weight 0.1) despite both
        //   being rank 1 on their respective axis, and each fused score equals exactly its own
        //   axis's weight over (rrfK + rank) — not the other axis's weight.
        assertThat(fused).extracting(RetrievedChunk::id).containsExactly("bm25-rank1", "vector-rank1");
        assertThat(fused.get(0).score()).isCloseTo(0.9 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(fused.get(1).score()).isCloseTo(0.1 / 61, org.assertj.core.data.Offset.offset(1e-9));
    }
}
