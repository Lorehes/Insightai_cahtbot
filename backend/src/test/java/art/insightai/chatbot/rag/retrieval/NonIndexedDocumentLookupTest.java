package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * [Purpose] Unit-level coverage of {@link NonIndexedDocumentLookup#countExcludedByStatus()}'s SQL
 *   text and row-to-{@link ExcludedDocumentStatusCount} mapping, mirroring {@code
 *   VectorStoreSearchAdapterTest}'s pattern of mocking {@link JdbcTemplate} and invoking the
 *   captured {@link RowMapper} directly against a mocked {@link ResultSet} -- no DB is involved.
 *   The positive-control proof required by hybrid-rag:AC-17 (seeding real non-INDEXED documents
 *   and confirming they are actually counted against a live database) is out of scope here and
 *   left to dev-qa-tester's integration test, per this card's instructions.
 */
@ExtendWith(MockitoExtension.class)
class NonIndexedDocumentLookupTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("[hybrid-rag:AC-17] GROUP BY status로 INDEXED를 제외한 상태별 건수를 조회한다")
    void queriesGroupedCountsExcludingIndexedStatus() {
        // given
        NonIndexedDocumentLookup lookup = new NonIndexedDocumentLookup(jdbcTemplate);

        // when
        lookup.countExcludedByStatus();

        // then: the SQL text excludes INDEXED and groups by status -- no per-row cap or ordering
        // assumption is baked in since callers only need the list, not row order
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class));
        assertThat(sqlCaptor.getValue())
                .contains("status <> 'INDEXED'")
                .contains("GROUP BY status");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-17] 결과 행을 ExcludedDocumentStatusCount(status, count)로 매핑한다")
    void mapsResultRowsToExcludedDocumentStatusCounts() throws SQLException {
        // given
        NonIndexedDocumentLookup lookup = new NonIndexedDocumentLookup(jdbcTemplate);
        lookup.countExcludedByStatus();
        ArgumentCaptor<RowMapper<ExcludedDocumentStatusCount>> rowMapperCaptor =
                ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(any(String.class), rowMapperCaptor.capture());

        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(resultSet.getString("status")).thenReturn("FAILED");
        when(resultSet.getLong("count")).thenReturn(3L);

        // when
        ExcludedDocumentStatusCount count = rowMapperCaptor.getValue().mapRow(resultSet, 0);

        // then
        assertThat(count).isEqualTo(new ExcludedDocumentStatusCount("FAILED", 3L));
    }

    // 이 테스트는 jdbcTemplate.query(...)를 List.of()를 반환하도록 스텁하고 그 스텁이 그대로
    // 돌아오는지만 본다 -- 프로덕션 SQL·행 매핑·INDEXED 필터 중 어느 것도 실행되거나 판정되지
    // 않는다(review round 2, hybrid-rag T14, correctness Low-3). AC-17을 검증하는 것은 위 두
    // 테스트(SQL 텍스트, 행 매핑)와 통합 테스트의 양성 대조이므로 이 테스트는 어떤 AC도 태그하지
    // 않는다.
    @Test
    @DisplayName("결과 행이 없으면 빈 List를 반환한다 (Mockito 스텁 왕복 확인용 회귀 가드, AC 아님)")
    void returnsEmptyListWhenNoRowsMatch() {
        // given
        NonIndexedDocumentLookup lookup = new NonIndexedDocumentLookup(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

        // when
        List<ExcludedDocumentStatusCount> counts = lookup.countExcludedByStatus();

        // then
        assertThat(counts).isEmpty();
    }
}
