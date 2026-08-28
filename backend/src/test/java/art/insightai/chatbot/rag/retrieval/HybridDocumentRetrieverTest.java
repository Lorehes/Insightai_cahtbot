package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Verifies {@link HybridDocumentRetriever}'s own orchestration logic in isolation --
 *   mode branching (AC-1, AC-2), the pipeline order that keeps topK truncation strictly after
 *   reranking (Tech Review D-T3), and both failure fallbacks it owns (AC-8, AC-14) -- with every
 *   collaborator ({@link VectorSearchPort}, {@link Bm25SearchPort}, {@link ReciprocalRankFusion},
 *   {@link RerankPort}) mocked. No DB, no network, no Spring context. AC-13 (final "ES-2024-A"
 *   ranks first) is deliberately NOT covered here -- it can only be demonstrated end to end
 *   against the live ParadeDB/pgvector indexes, which is {@code
 *   HybridDocumentRetrieverIntegrationTest}'s job (Spec §6 I2, Tech Review "AC-13 소유권 정정"). {@link
 *   #throwsNullPointerExceptionImmediatelyWhenQueryIsNull()} additionally locks the {@code
 *   retrieve(null)} entry-guard contract added during T7's second rework round -- untagged, since
 *   it covers a caller-misuse regression rather than any Spec AC.
 * [Rationale] {@link ReciprocalRankFusion} is mocked (not the real instance) so these tests only
 *   assert what this class calls and with which arguments -- the fusion formula itself is {@code
 *   ReciprocalRankFusionTest}'s own AC-3/AC-4 evidence, and re-deriving it here would duplicate
 *   that coverage while coupling this file to fusion's internal math.
 * [Caution] A {@code ch.qos.logback.core.read.ListAppender} is attached directly to {@link
 *   HybridDocumentRetriever}'s logger (rather than only asserting on returned values) because
 *   AC-8/AC-14's own text names the log level ("WARN 로그"/"ERROR 로그") as part of the
 *   requirement, not just the fallback list/empty-list behavior. This project has no shared
 *   log-assertion helper yet (verified: no existing test file attaches a Logback appender) -- the
 *   appender here is scoped to a single logger and detached in {@link #detachLogAppender()} so it
 *   cannot leak into any other test class's log output.
 */
@ExtendWith(MockitoExtension.class)
class HybridDocumentRetrieverTest {

    private static final String QUERY = "질의문";

    @Mock
    private VectorSearchPort vectorSearchPort;

    @Mock
    private Bm25SearchPort bm25SearchPort;

    @Mock
    private ReciprocalRankFusion reciprocalRankFusion;

    @Mock
    private RerankPort rerankPort;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        retrieverLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        retrieverLogger().detachAppender(logAppender);
    }

    private static Logger retrieverLogger() {
        return (Logger) LoggerFactory.getLogger(HybridDocumentRetriever.class);
    }

    private HybridDocumentRetriever retriever(AppProperties.Search search) {
        AppProperties appProperties = TestAppPropertiesFactory.builder().search(search).build();
        return new HybridDocumentRetriever(
                vectorSearchPort, bm25SearchPort, reciprocalRankFusion, rerankPort, appProperties);
    }

    private static RetrievedChunk chunk(String id, double score) {
        return new RetrievedChunk(id, "content-" + id, Map.of(), score);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-1] mode=vector 이면 Bm25SearchPort 가 한 번도 호출되지 않고 "
            + "벡터 검색 결과만 융합에 전달된다")
    void neverCallsBm25PortInVectorMode() {
        // given
        AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.VECTOR, 5, 60, 0.5, 0.5, 20, 7, 0.0);
        List<RetrievedChunk> vectorRaw = List.of(chunk("v1", 0.9));
        List<RetrievedChunk> fused = List.of(chunk("v1", 0.5));
        when(vectorSearchPort.search(QUERY, 7)).thenReturn(vectorRaw);
        when(reciprocalRankFusion.fuse(List.of(), vectorRaw)).thenReturn(fused);
        when(rerankPort.rerank(QUERY, fused, 5)).thenReturn(fused);

        // when
        List<RetrievedChunk> result = retriever(search).retrieve(QUERY);

        // then
        verify(bm25SearchPort, never()).search(anyString(), anyInt());
        verify(vectorSearchPort).search(QUERY, 7);
        assertThat(result).isEqualTo(fused);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-2] mode=hybrid 이면 BM25·벡터 축이 각각 bm25TopK/vectorTopK 로 "
            + "조회되고 하나의 목록으로 융합되어 반환된다")
    void queriesBothAxesWithConfiguredTopKAndFusesInHybridMode() {
        // given
        AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.HYBRID, 4, 60, 0.5, 0.5, 11, 9, 0.0);
        List<RetrievedChunk> bm25Raw = List.of(chunk("b1", 3.0));
        List<RetrievedChunk> vectorRaw = List.of(chunk("v1", 0.8));
        List<RetrievedChunk> fused = List.of(chunk("b1", 0.5), chunk("v1", 0.3));
        when(bm25SearchPort.search(QUERY, 11)).thenReturn(bm25Raw);
        when(vectorSearchPort.search(QUERY, 9)).thenReturn(vectorRaw);
        when(reciprocalRankFusion.fuse(bm25Raw, vectorRaw)).thenReturn(fused);
        when(rerankPort.rerank(QUERY, fused, 4)).thenReturn(fused);

        // when
        List<RetrievedChunk> result = retriever(search).retrieve(QUERY);

        // then: explicit verify() on fuse()'s arguments -- not left to STRICT_STUBS' implicit
        // "unmatched stub -> null -> downstream NPE/assertion failure" behavior (review
        // 2026-08-28_hybrid-rag-T7.md Low-2: that indirect dependence was real but understated in
        // the Spec §4 AC-2 evidence cell, which previously described this as if the assertion were
        // explicit)
        verify(bm25SearchPort).search(QUERY, 11);
        verify(vectorSearchPort).search(QUERY, 9);
        verify(reciprocalRankFusion).fuse(bm25Raw, vectorRaw);
        assertThat(result).isEqualTo(fused);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-8] 리랭크 포트가 예외를 던지면 전파되지 않고 융합 결과 상위 topK 건으로 "
            + "폴백하며 WARN 로그를 남긴다")
    void fallsBackToFusedTopKWithoutPropagatingWhenRerankThrows() {
        // given
        AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.VECTOR, 2, 60, 0.5, 0.5, 20, 20, 0.0);
        List<RetrievedChunk> vectorRaw = List.of(chunk("v1", 0.9));
        List<RetrievedChunk> fused =
                List.of(chunk("f1", 0.9), chunk("f2", 0.8), chunk("f3", 0.7));
        when(vectorSearchPort.search(QUERY, 20)).thenReturn(vectorRaw);
        when(reciprocalRankFusion.fuse(List.of(), vectorRaw)).thenReturn(fused);
        when(rerankPort.rerank(QUERY, fused, 2)).thenThrow(new RuntimeException("rerank API timeout"));

        // when: the call must complete normally, not throw
        List<RetrievedChunk> result = retriever(search).retrieve(QUERY);

        // then: fallback is the fused list's own top-2 (fused is already sorted best-first, per
        // ReciprocalRankFusion's own contract), and a WARN was logged
        assertThat(result).containsExactly(fused.get(0), fused.get(1));
        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("[hybrid-rag:AC-14] 검색 포트가 예외를 던지면 전파되지 않고 빈 목록을 반환하며 "
            + "ERROR 로그를 남긴다 (벡터축 실패)")
    void returnsEmptyListWithoutPropagatingWhenVectorAxisThrows() {
        // given
        AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.VECTOR, 5, 60, 0.5, 0.5, 20, 20, 0.0);
        when(vectorSearchPort.search(QUERY, 20)).thenThrow(new RuntimeException("DB connection lost"));

        // when
        List<RetrievedChunk> result = retriever(search).retrieve(QUERY);

        // then: no fusion/rerank attempted once the axis search itself failed
        assertThat(result).isEmpty();
        verify(reciprocalRankFusion, never()).fuse(any(), any());
        verify(rerankPort, never()).rerank(anyString(), any(), anyInt());
        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("[hybrid-rag:AC-14] mode=hybrid 에서 BM25 축만 예외를 던져도 벡터축 결과로 부분 진행하지 "
            + "않고 빈 목록을 반환한다")
    void returnsEmptyListWhenOnlyBm25AxisThrowsInHybridMode() {
        // given: this pins the coordinator decision documented in HybridDocumentRetriever's own
        // [Caution] -- "either axis fails" collapses to empty, not a partial-axis result
        AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.HYBRID, 5, 60, 0.5, 0.5, 20, 20, 0.0);
        when(bm25SearchPort.search(QUERY, 20)).thenThrow(new RuntimeException("BM25 query failed"));

        // when
        List<RetrievedChunk> result = retriever(search).retrieve(QUERY);

        // then
        assertThat(result).isEmpty();
        verify(reciprocalRankFusion, never()).fuse(any(), any());
        verify(rerankPort, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("retrieve(null) 은 진입부에서 즉시 NullPointerException 을 던지고 어떤 검색/융합/리랭크 포트도 "
            + "호출되지 않는다 -- 재작업 1회차가 만든 NPE 회귀(2026-08-28 리뷰 2라운드 Medium-1: null 질의가 "
            + "requireNonNull 가드 없이 searchAndFuse 까지 내려가 RuntimeException 으로 오분류된 뒤, catch 블록의 "
            + "query.length() 에서 별도의 미포착 NPE 로 죽어 \"never throws\" 계약이 조용히 깨졌던 문제)를 잠그는 "
            + "계약 회귀 가드다. 이 테스트는 어떤 AC 도 커버하지 않는다 -- retrieve(null) 은 Spec 어느 AC 도 다루지 "
            + "않는 호출자 오용(caller misuse) 시나리오이지 검색/리랭크 축의 정상 실패 폴백(AC-8/AC-14)이 아니다")
    void throwsNullPointerExceptionImmediatelyWhenQueryIsNull() {
        // given
        AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.HYBRID, 5, 60, 0.5, 0.5, 20, 20, 0.0);
        HybridDocumentRetriever retriever = retriever(search);

        // when / then: NPE surfaces before either try block, not caught as a search-axis failure
        assertThatThrownBy(() -> retriever.retrieve(null)).isInstanceOf(NullPointerException.class);

        // then: proves the NPE fired at entry, not inside searchAndFuse/rerank -- distinguishing
        // this from an exception thrown mid-pipeline that happened to also be an NPE
        verify(vectorSearchPort, never()).search(anyString(), anyInt());
        verify(bm25SearchPort, never()).search(anyString(), anyInt());
        verify(reciprocalRankFusion, never()).fuse(any(), any());
        verify(rerankPort, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("topK 절단은 리랭크 호출 뒤에 일어난다 -- 융합 결과 전체가 잘리지 않은 채 rerank 로 전달된다")
    void passesFullFusedListToRerankBeforeAnyTopKCut() {
        // given: fused has 5 entries, well above topK=2 -- if this class truncated before calling
        // rerankPort, the captured argument below would already be size 2
        AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.VECTOR, 2, 60, 0.5, 0.5, 20, 20, 0.0);
        List<RetrievedChunk> vectorRaw = List.of(chunk("v1", 0.9));
        List<RetrievedChunk> fused = List.of(
                chunk("f1", 0.9), chunk("f2", 0.8), chunk("f3", 0.7), chunk("f4", 0.6), chunk("f5", 0.5));
        when(vectorSearchPort.search(QUERY, 20)).thenReturn(vectorRaw);
        when(reciprocalRankFusion.fuse(List.of(), vectorRaw)).thenReturn(fused);
        ArgumentCaptor<List<RetrievedChunk>> candidatesCaptor = ArgumentCaptor.captor();
        List<RetrievedChunk> rerankedTop2 = List.of(fused.get(2), fused.get(0));
        when(rerankPort.rerank(eq(QUERY), candidatesCaptor.capture(), eq(2))).thenReturn(rerankedTop2);

        // when
        List<RetrievedChunk> result = retriever(search).retrieve(QUERY);

        // then: rerankPort received all 5 fused candidates (not pre-truncated to topK=2), and the
        // final result is exactly whatever rerankPort itself decided to return
        assertThat(candidatesCaptor.getValue()).hasSize(5);
        assertThat(result).isEqualTo(rerankedTop2);
    }
}
