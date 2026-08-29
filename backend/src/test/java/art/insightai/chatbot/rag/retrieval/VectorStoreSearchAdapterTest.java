package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Verifies {@link VectorStoreSearchAdapter}'s SQL/parameter construction (T13/D-T11
 *   raw-SQL rework: {@link JdbcTemplate} and {@link EmbeddingModel} replace the old {@link
 *   org.springframework.ai.vectorstore.VectorStore} mock this class used before the rework) and
 *   its {@link RowMapper}'s row-to-{@link RetrievedChunk} mapping. No DB or network is involved
 *   -- {@link JdbcTemplate} and {@link EmbeddingModel} are both mocked, so {@code jdbcTemplate
 *   .query(...)} never actually runs a query here; the {@link RowMapper} lambda this class passes
 *   is instead captured and invoked directly against a mocked {@link ResultSet} to test its
 *   mapping logic in isolation (only a live-DB round trip, {@code
 *   VectorStoreSearchAdapterIntegrationTest}, can prove the SQL itself is valid and the {@code
 *   NOT IN} exclusion actually excludes the right rows against pgvector).
 */
@ExtendWith(MockitoExtension.class)
class VectorStoreSearchAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private NonIndexedDocumentLookup nonIndexedDocumentLookup;

    @Test
    @DisplayName("제외 대상 문서 id가 없으면 NOT IN 절 없이 query 임베딩·topK를 바인딩 파라미터로 위임한다")
    void issuesUnfilteredSqlWithBoundParametersWhenNoExcludedDocuments() {
        // given: no non-INDEXED documents (D-T10's "집합이 비면 필터 자체를 생략한다")
        when(embeddingModel.embed("직원 수는 몇 명인가요")).thenReturn(new float[] {0.1f, 0.2f});
        when(nonIndexedDocumentLookup.findAll()).thenReturn(List.of());
        VectorStoreSearchAdapter adapter =
                new VectorStoreSearchAdapter(jdbcTemplate, embeddingModel, nonIndexedDocumentLookup);

        // when
        adapter.search("직원 수는 몇 명인가요", 20);

        // then: the query embedding is computed exactly once and reused for both the ORDER BY
        // and WHERE bind slots, the distance cutoff is 1.0 (D-T3's similarityThresholdAll), and
        // the SQL text carries no exclusion predicate
        verify(embeddingModel, times(1)).embed("직원 수는 몇 명인가요");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowMapper.class),
                eq("[0.1,0.2]"), eq("[0.1,0.2]"), eq(1.0), eq(20));
        assertThat(sqlCaptor.getValue()).doesNotContain("NOT IN");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-16] 제외 대상 문서 id가 있으면 NOT IN 절과 각 id를 바인딩 파라미터로 SQL에 싣는다")
    void issuesNotInSqlWithBoundExclusionParametersWhenExcludedDocumentsExist() {
        // given: two non-INDEXED documents (T13/D-T10) -- status itself is not consumed by this
        // adapter, only id (see NonIndexedDocumentLookup's own Javadoc for why status is still
        // carried on the record)
        when(embeddingModel.embed("질의")).thenReturn(new float[] {0.5f});
        when(nonIndexedDocumentLookup.findAll())
                .thenReturn(List.of(
                        new NonIndexedDocument("doc-1", "FAILED"),
                        new NonIndexedDocument("doc-2", "PENDING")));
        VectorStoreSearchAdapter adapter =
                new VectorStoreSearchAdapter(jdbcTemplate, embeddingModel, nonIndexedDocumentLookup);

        // when
        adapter.search("질의", 5);

        // then: the SQL text carries exactly one "?" placeholder per excluded id, and every id
        // (never string-concatenated) arrives as its own bound parameter after the
        // vector/threshold slots, followed by topK -- mirrors ParadeDbBm25SearchAdapter's own
        // NOT IN parameter ordering
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowMapper.class),
                eq("[0.5]"), eq("[0.5]"), eq(1.0), eq("doc-1"), eq("doc-2"), eq(5));
        assertThat(sqlCaptor.getValue()).contains("NOT IN (?, ?)");
    }

    @Test
    @DisplayName("결과 행의 distance를 1-distance 점수로, JSON 텍스트 metadata를 Map으로 매핑한다")
    void mapsRowsToRetrievedChunksWithScoreAndParsedMetadata() throws SQLException {
        // given
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {1f});
        when(nonIndexedDocumentLookup.findAll()).thenReturn(List.of());
        VectorStoreSearchAdapter adapter =
                new VectorStoreSearchAdapter(jdbcTemplate, embeddingModel, nonIndexedDocumentLookup);
        adapter.search("질의", 5);
        ArgumentCaptor<RowMapper<RetrievedChunk>> rowMapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), rowMapperCaptor.capture(), any(), any(), any(), any());

        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(resultSet.getDouble("distance")).thenReturn(0.35);
        when(resultSet.getString("id")).thenReturn("chunk-1");
        when(resultSet.getString("content")).thenReturn("회사소개 본문 일부");
        when(resultSet.getString("metadata")).thenReturn("{\"document_id\":\"11111111-1111-1111-1111-111111111111\"}");

        // when
        RetrievedChunk chunk = rowMapperCaptor.getValue().mapRow(resultSet, 0);

        // then: score is 1 - distance (cosine similarity), matching PgVectorStore's own formula
        assertThat(chunk.id()).isEqualTo("chunk-1");
        assertThat(chunk.content()).isEqualTo("회사소개 본문 일부");
        assertThat(chunk.metadata()).containsEntry("document_id", "11111111-1111-1111-1111-111111111111");
        assertThat(chunk.score()).isEqualTo(0.65);
    }

    @Test
    @DisplayName("metadata 컬럼이 NULL이면 빈 Map으로 매핑한다")
    void mapsNullMetadataColumnToEmptyMap() throws SQLException {
        // given
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {1f});
        when(nonIndexedDocumentLookup.findAll()).thenReturn(List.of());
        VectorStoreSearchAdapter adapter =
                new VectorStoreSearchAdapter(jdbcTemplate, embeddingModel, nonIndexedDocumentLookup);
        adapter.search("질의", 5);
        ArgumentCaptor<RowMapper<RetrievedChunk>> rowMapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), rowMapperCaptor.capture(), any(), any(), any(), any());

        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(resultSet.getDouble("distance")).thenReturn(0.5);
        when(resultSet.getString("id")).thenReturn("chunk-2");
        when(resultSet.getString("content")).thenReturn("본문");
        when(resultSet.getString("metadata")).thenReturn(null);

        // when
        RetrievedChunk chunk = rowMapperCaptor.getValue().mapRow(resultSet, 0);

        // then
        assertThat(chunk.metadata()).isEmpty();
    }
}
