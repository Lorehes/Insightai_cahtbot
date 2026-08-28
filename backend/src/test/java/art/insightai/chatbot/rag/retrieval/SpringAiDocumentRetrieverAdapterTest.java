package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Verifies {@link SpringAiDocumentRetrieverAdapter}'s translation between T7's {@link
 *   RetrievedChunk} and Spring AI's {@link Document} in isolation -- {@link
 *   HybridDocumentRetriever} is mocked, so no DB, no network, no Spring context. Not tagged to any
 *   AC: this class is a contract-mapping seam T9's card brief assigns AC-5 to, not this adapter
 *   itself (the AC-5 short-circuit lives in {@link RagEmptyContextGuardAdvisorTest}).
 * [Rationale] The null-metadata-value drop ({@link #dropsNullValuedMetadataEntriesBeforeBuildingDocument()})
 *   is the one behavior in this class that is not a straight field copy -- everything else is a
 *   direct assertion that {@link Document#getId()}/{@code getText()}/{@code getMetadata()}/{@code
 *   getScore()} mirror {@link RetrievedChunk#id()}/{@code content()}/{@code metadata()}/{@code
 *   score()} unchanged.
 */
@ExtendWith(MockitoExtension.class)
class SpringAiDocumentRetrieverAdapterTest {

    private static final String QUERY_TEXT = "질의문";

    @Mock
    private HybridDocumentRetriever hybridDocumentRetriever;

    private SpringAiDocumentRetrieverAdapter adapter() {
        return new SpringAiDocumentRetrieverAdapter(hybridDocumentRetriever);
    }

    @Test
    @DisplayName("query.text() 를 HybridDocumentRetriever.retrieve() 에 그대로 전달하고 "
            + "결과 청크를 순서를 보존한 Document 목록으로 반환한다")
    void delegatesQueryTextAndPreservesOrder() {
        // given
        RetrievedChunk first = new RetrievedChunk("id-1", "content-1", Map.of("document_id", "doc-1"), 0.7);
        RetrievedChunk second = new RetrievedChunk("id-2", "content-2", Map.of("document_id", "doc-2"), 0.3);
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of(first, second));

        // when
        List<Document> result = adapter().retrieve(Query.builder().text(QUERY_TEXT).build());

        // then
        verify(hybridDocumentRetriever).retrieve(eq(QUERY_TEXT));
        assertThat(result).extracting(Document::getId).containsExactly("id-1", "id-2");
        assertThat(result).extracting(Document::getText).containsExactly("content-1", "content-2");
    }

    @Test
    @DisplayName("RetrievedChunk.score() 가 RRF 융합 점수 그대로 Document.getScore() 로 복사된다 (Tech Review D-T6)")
    void copiesScoreVerbatim() {
        // given
        RetrievedChunk chunk = new RetrievedChunk("id-1", "content-1", Map.of("document_id", "doc-1"), 0.123456);
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of(chunk));

        // when
        List<Document> result = adapter().retrieve(Query.builder().text(QUERY_TEXT).build());

        // then
        assertThat(result).singleElement().extracting(Document::getScore).isEqualTo(0.123456);
    }

    @Test
    @DisplayName("메타데이터 값이 null 인 키(예: page)는 Document 로 옮기지 않고 제거한다 "
            + "(org.springframework.ai.document.Document 는 null 값을 허용하지 않음)")
    void dropsNullValuedMetadataEntriesBeforeBuildingDocument() {
        // given
        Map<String, Object> metadataWithNullPage = new HashMap<>();
        metadataWithNullPage.put("document_id", "doc-1");
        metadataWithNullPage.put("filename", "회사소개.pdf");
        metadataWithNullPage.put("page", null);
        RetrievedChunk chunk = new RetrievedChunk("id-1", "content-1", metadataWithNullPage, 0.5);
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of(chunk));

        // when -- must not throw IllegalArgumentException (Document forbids null metadata values)
        List<Document> result = adapter().retrieve(Query.builder().text(QUERY_TEXT).build());

        // then
        Map<String, Object> mappedMetadata = result.get(0).getMetadata();
        assertThat(mappedMetadata).containsEntry("document_id", "doc-1");
        assertThat(mappedMetadata).containsEntry("filename", "회사소개.pdf");
        assertThat(mappedMetadata).doesNotContainKey("page");
    }

    @Test
    @DisplayName("빈 결과를 그대로 빈 목록으로 반환한다")
    void returnsEmptyListWhenRetrieverReturnsNothing() {
        // given
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of());

        // when
        List<Document> result = adapter().retrieve(Query.builder().text(QUERY_TEXT).build());

        // then
        assertThat(result).isEmpty();
    }
}
