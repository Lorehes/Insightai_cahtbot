package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.support.ChunkSeeder;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.support.StubEmbeddingModel;

/**
 * [Purpose] End-to-end verification of {@link HybridDocumentRetriever} against the live
 *   ParadeDB/pgvector indexes (Spec §6 I1, I2) -- the only place AC-13 can actually be judged
 *   true or false, per Tech Review "AC-13 소유권 정정": {@code
 *   ParadeDbBm25SearchAdapterIntegrationTest}'s own AC-13-tagged test is BM25-axis-only partial
 *   evidence, not the full hybrid pipeline this class exercises with real {@link
 *   VectorStoreSearchAdapter}, {@link ParadeDbBm25SearchAdapter}, {@link ReciprocalRankFusion},
 *   and {@link OpenRouterRerankAdapter} beans wired together by the real Spring context (mode
 *   overridden to {@code hybrid} via {@link TestPropertySource}; {@code app.rerank.enabled}
 *   stays at its {@code false} default, so {@link OpenRouterRerankAdapter} never makes a network
 *   call).
 * [Rationale] Every seeded chunk carries a real, discriminative embedding from a fresh {@link
 *   StubEmbeddingModel} instance (never the all-zero {@link ChunkSeeder#dummyEmbeddingLiteral()})
 *   because this suite's assertions depend on vector-axis ranking, not merely on a row existing
 *   (see {@link ChunkSeeder#insertChunk(UUID, String, float[])}'s own Javadoc for why the
 *   all-zero literal cannot be used here). {@link StubEmbeddingModel#vectorFor} is a pure
 *   function of the input text (CLAUDE.md fact — deterministic hashing, see that class's own
 *   Javadoc), so a locally constructed instance produces byte-identical vectors to the one the
 *   Spring-managed {@link StubEmbeddingModel} bean uses for the query text at request time; no
 *   autowiring of the bean itself is needed here.
 * [Caution] R-F (Spec §8, confirmed live during T4 rework): a short chunk that shares an entire
 *   product code with the query (e.g. {@code "ES-2024-B는 별도 코드입니다"}) can outscore a longer
 *   exact-match chunk on the BM25 axis alone. {@link #rankExactMatchFirstForProductCodeQuery}
 *   seeds exactly that competitor per the T7 card brief's explicit instruction, so this test has
 *   real discriminating power instead of trivially passing with only one matching row. Per the
 *   card brief: if this test fails, that failure is reported as-is (not forced to pass by hand-
 *   tuning weights/rrfK, which is a D7/coordinator decision, not this card's) -- see the dev-log
 *   entry and final report for this card's actual observed result.
 * [Caution] {@link #rankExactMatchFirstForProductCodeQuery} was {@code @Disabled} from the
 *   dev-qa-tester QA pass on this card (2026-08-28) until eval-usage-dashboard:T1 (2026-09-01):
 *   at the original weights (rrfK=60, weight 0.5/0.5) both chunks landed on the exact same RRF
 *   score (0.5/61 + 0.5/62) and the tie-break rule (BM25-accumulated-first) handed rank 1 to the
 *   competitor chunk -- §8 R-F materializing, not a bug in this class or in {@link
 *   ReciprocalRankFusion}. T1 fixed weight-bm25/weight-vector at 0.4/0.6 in {@code
 *   application.yaml} from the D7 sweep and re-enabled the test; see that method's own Javadoc
 *   and hybrid-rag Spec Tech Review "AC-13 소유권 재정정 (2026-08-28)". The weights are read from
 *   the real {@code application.yaml} (not overridden here), so a future retune that breaks the
 *   exact-match ordering fails this test rather than passing silently.
 */
@TestPropertySource(properties = "app.search.mode=hybrid")
class HybridDocumentRetrieverIntegrationTest extends IntegrationTestBase {

    @Autowired
    private HybridDocumentRetriever retriever;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    private ChunkSeeder chunkSeeder;
    private final StubEmbeddingModel embeddingModel = new StubEmbeddingModel();

    @BeforeEach
    void setUpSeeder() {
        chunkSeeder = new ChunkSeeder(jdbcTemplate);
    }

    @AfterEach
    void cleanUpSeededRows() {
        chunkSeeder.cleanUp();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-2] mode=hybrid 인 실제 Spring 컨텍스트에서 BM25·벡터 두 축을 거쳐 "
            + "질의와 일치하는 청크가 결과에 반환된다")
    void returnsMatchingChunkThroughRealHybridPipeline() {
        // given: a pre-seed diagnostic, not an assertion -- if a residual row containing "사내"
        // is left over from another session/test sharing this live chatbot-db container (T7
        // rework round 2, Low-3), matchContent would rank 2 instead of 1 on the BM25 axis and the
        // fused-score assertion below would fail for a reason unrelated to this class. Counting it
        // up front and surfacing it in the assertion's own failure message (see .as(...) below)
        // turns that failure mode from "silent misdiagnosis" into "visible in the first line of
        // the failure"
        Integer residualMatchRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store_bgem3 WHERE content ||| '사내'", Integer.class);

        // given: one chunk containing a standalone query token ("사내", no attached Korean
        // particle -- see [Caution] below for why that distinction matters here), plus one
        // unrelated chunk that must not outrank it
        UUID matchDocumentId = UUID.randomUUID();
        String matchContent = "인사이트에이아이는 사내 업무용 챗봇을 만드는 회사입니다.";
        chunkSeeder.insertChunk(matchDocumentId, matchContent, embeddingModel.embed(matchContent));

        UUID noiseDocumentId = UUID.randomUUID();
        String noiseContent = "연차휴가는 입사일을 기준으로 매년 갱신되며 최대 15일까지 사용할 수 있습니다.";
        chunkSeeder.insertChunk(noiseDocumentId, noiseContent, embeddingModel.embed(noiseContent));

        // when: real VectorSearchPort/Bm25SearchPort/ReciprocalRankFusion/RerankPort beans, no
        // mocks -- this is the wiring unit tests cannot prove
        List<RetrievedChunk> result = retriever.retrieve("사내");

        // then: content/non-empty alone cannot tell a two-axis fusion apart from a single-axis
        // result, so this also pins the exact fused RRF score (review 2026-08-28_hybrid-rag-T7.md
        // Medium-3). matchContent is confirmed at rank 1 on BOTH axes for this exact query (only
        // two chunks seeded, noiseContent shares no token with "사내" on either axis) -- verified
        // by direct measurement against the live container, not assumed: BM25 axis alone
        // (`content ||| '사내'`) returns exactly matchContent, score 0.719; vector axis alone
        // (StubEmbeddingModel cosine) scores matchContent 0.408 and noiseContent 0.0.
        //
        // The expected score is DERIVED from the injected AppProperties.Search (weightBm25,
        // weightVector, rrfK) rather than a hand-picked literal (T7 rework round 2, Low-2) --
        // eval-usage-dashboard:T1's own job is to retune those three application.yaml values, and
        // a literal here would silently decouple from that retuning and stop testing anything the
        // moment T1 lands. Per
        // ReciprocalRankFusion's formula (Σ weight_axis / (rrfK + rank_axis)), rank 1 on both axes
        // gives weightBm25/(rrfK+1) + weightVector/(rrfK+1). If either axis stopped contributing,
        // the observed score would instead be exactly half of this derived value (one term
        // missing), not a rounding-distance away -- so this assertion still fails the moment
        // either axis drops out regardless of what T1 sets the three values to (mirrors
        // ReciprocalRankFusionTest's own exact-score assertion style for the same formula).
        AppProperties.Search search = appProperties.search();
        double expectedScore =
                search.weightBm25() / (search.rrfK() + 1) + search.weightVector() / (search.rrfK() + 1);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).content()).isEqualTo(matchContent);
        assertThat(result.get(0).score())
                .as("fused score assumes matchContent ranks #1 on BOTH axes -- weightBm25=%s "
                                + "weightVector=%s rrfK=%s -> expected %s; if this fails while "
                                + "residualMatchRows(pre-seed count of rows already matching "
                                + "'사내')=%s is > 0, a leftover row from another session/test "
                                + "likely pushed matchContent to rank 2 on the BM25 axis instead "
                                + "(observed score %s)",
                        search.weightBm25(), search.weightVector(), search.rrfK(), expectedScore,
                        residualMatchRows, result.get(0).score())
                .isCloseTo(expectedScore, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * [Purpose] AC-13's final evidence (eval-usage-dashboard:T1, 2026-09-01). The {@code @Disabled}
     *   T7 left here was removed once T1 fixed {@code weight-bm25=0.4}/{@code weight-vector=0.6}
     *   in {@code application.yaml} from the D7 sweep: with the BM25 axis ranking the competitor
     *   first (R-F) and the vector axis ranking the exact match first, the fused scores are now
     *   {@code 0.4/62 + 0.6/61 = 0.016288} (exact) vs {@code 0.4/61 + 0.6/62 = 0.016235}
     *   (competitor) instead of the 0.5/0.5 dead heat the tie-break rule resolved the wrong way.
     * [Caution] The vector axis here is {@code StubEmbeddingModel}; the two raw thresholds are
     *   pinned to 0.0 for every test context ({@code config/application.yaml}) because the stub's
     *   cosine scale is unrelated to the production bge-m3 scale. What this test proves is the
     *   weight decision (rank arithmetic), which is scale-free; the production-side confirmation of
     *   the same weights is the D7 stage-2 sweep ({@code docs/eval/threshold-sweep-2026-09-01.md}).
     */
    @Test
    @DisplayName("[hybrid-rag:AC-13] mode=hybrid 로 \"ES-2024-A\" 질의 시 해당 청크가 결과 1위로 반환된다 "
            + "(R-F 경쟁 청크 포함, T1 확정 가중치 0.4/0.6 에서 최종 검증)")
    void rankExactMatchFirstForProductCodeQuery() {
        // given: Spec §6 시드 데이터(D1 스파이크 문장) plus R-F's own required competitor
        // ("ES-2024-B는 별도 코드입니다" -- a short chunk sharing the entire product code, which
        // Tech Review R-F measured live as OUTSCORING the exact match on the BM25 axis alone).
        // Seeded last-first order does not matter here (unlike ParadeDbBm25SearchAdapterIntegrationTest's
        // own regression guard) since this suite asserts on content/ranking, not insertion order.
        String exactMatchContent = "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.";
        String competitorContent = "ES-2024-B는 별도 코드입니다";
        String[] fillerContents = {
                "2024년 매출 자료",
                "ES는 유럽연합의 약자로도 쓰입니다",
                "연차휴가는 입사일을 기준으로 매년 갱신되며 최대 15일까지 사용할 수 있습니다.",
                "인사이트에이아이는 사내 업무용 챗봇을 만드는 회사입니다."
        };
        seedChunk(exactMatchContent);
        seedChunk(competitorContent);
        for (String filler : fillerContents) {
            seedChunk(filler);
        }

        // when
        List<RetrievedChunk> result = retriever.retrieve("ES-2024-A");

        // then: per the card brief, this assertion is written to the AC's actual requirement and
        // is NOT hand-tuned to pass -- see this class's [Caution] and the final report for what
        // was actually observed running this against the live container
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).content()).isEqualTo(exactMatchContent);
    }

    private void seedChunk(String content) {
        UUID documentId = UUID.randomUUID();
        chunkSeeder.insertChunk(documentId, content, embeddingModel.embed(content));
    }
}
