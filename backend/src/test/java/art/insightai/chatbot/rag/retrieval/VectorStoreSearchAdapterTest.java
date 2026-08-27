package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Verifies {@link VectorStoreSearchAdapter}'s translation both ways: query/topK into
 *   a {@link SearchRequest}, and Spring AI {@link Document} hits back into
 *   {@link RetrievedChunk}. No DB or network is involved — {@link VectorStore} is mocked.
 */
@ExtendWith(MockitoExtension.class)
class VectorStoreSearchAdapterTest {

    @Mock
    private VectorStore vectorStore;

    @Test
    @DisplayName("query·topK로 SearchRequest를 만들고 임계값 없이(similarityThresholdAll) 위임한다")
    void buildsSearchRequestWithoutSimilarityThreshold() {
        // given: the mocked VectorStore returns no hits — this test only inspects the request
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        VectorStoreSearchAdapter adapter = new VectorStoreSearchAdapter(vectorStore);

        // when
        adapter.search("직원 수는 몇 명인가요", 20);

        // then: the request captured by the mock carries the query/topK verbatim and applies
        //   no similarity threshold (Tech Review D-T3 — axis search must not pre-filter)
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest captured = captor.getValue();
        assertThat(captured.getQuery()).isEqualTo("직원 수는 몇 명인가요");
        assertThat(captured.getTopK()).isEqualTo(20);
        assertThat(captured.getSimilarityThreshold()).isEqualTo(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL);
    }

    @Test
    @DisplayName("VectorStore가 반환한 Document를 id·content·metadata·score가 보존된 RetrievedChunk로 매핑한다")
    void mapsVectorStoreHitsToRetrievedChunks() {
        // given: a single hit with a non-null score and metadata
        Document hit = Document.builder()
                .id("chunk-1")
                .text("회사소개 본문 일부")
                .metadata(Map.of("filename", "회사소개.pdf", "document_id", "11111111-1111-1111-1111-111111111111"))
                .score(0.87)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));
        VectorStoreSearchAdapter adapter = new VectorStoreSearchAdapter(vectorStore);

        // when
        List<RetrievedChunk> result = adapter.search("회사 소개", 5);

        // then
        assertThat(result).hasSize(1);
        RetrievedChunk chunk = result.get(0);
        assertThat(chunk.id()).isEqualTo("chunk-1");
        assertThat(chunk.content()).isEqualTo("회사소개 본문 일부");
        assertThat(chunk.metadata()).containsEntry("filename", "회사소개.pdf");
        assertThat(chunk.score()).isEqualTo(0.87);
    }

    @Test
    @DisplayName("VectorStore가 score를 null로 반환하면 0.0으로 기본값 처리한다")
    void defaultsNullScoreToZero() {
        // given: a hit built without a score (Spring AI Document#getScore() may be null)
        Document hitWithoutScore = Document.builder()
                .id("chunk-2")
                .text("본문")
                .metadata(Map.of())
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hitWithoutScore));
        VectorStoreSearchAdapter adapter = new VectorStoreSearchAdapter(vectorStore);

        // when
        List<RetrievedChunk> result = adapter.search("질의", 5);

        // then
        assertThat(result.get(0).score()).isEqualTo(0.0);
    }
}
