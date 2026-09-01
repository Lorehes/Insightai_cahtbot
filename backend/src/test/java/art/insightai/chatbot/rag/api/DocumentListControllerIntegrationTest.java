package art.insightai.chatbot.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.support.AnonymousUserCleanup;
import art.insightai.chatbot.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [Purpose] End-to-end coverage of {@code GET /api/admin/documents} (document-ingestion Spec Tech
 *   Review T9, document-ingestion:AC-11) through the real servlet filter chain against a real
 *   database: every row from {@link DocumentListController.DocumentListItemResponse}'s field set
 *   ({@code id, filename, mimeType, sizeBytes, status, chunkCount, errorMessage, createdAt,
 *   indexedAt}) with correct values, including the two fields ({@code chunkCount}, {@code
 *   indexedAt}) that are {@code null} for a non-terminal document, and the {@code created_at}
 *   descending sort order.
 * [Rationale] {@code DocumentListServiceTest} (backend-engineer, unit level) already proves the
 *   service delegates to {@link art.insightai.chatbot.rag.domain.DocumentRepository
 *   #findAllByOrderByCreatedAtDesc()} against a mocked repository. This class adds what only a
 *   real {@code @SpringBootTest} can: the actual HTTP response shape through the real controller
 *   and Jackson serialization, and the actual sort order as PostgreSQL returns it -- project
 *   convention (통합 테스트는 실제 인스턴스, 모킹 금지). Rows are written directly via {@link JdbcTemplate}
 *   with explicit {@code created_at} values (mirroring {@code ConversationControllerIntegrationTest
 *   #listsOnlyOwnNonDeletedConversationsInUpdatedAtDescendingOrder}'s established pattern one Spec
 *   over), not through the upload endpoint or {@link art.insightai.chatbot.rag.domain.Document}'s
 *   entity setters (which do not expose {@code created_at}/{@code indexedAt} directly for every
 *   state) -- this keeps each row's shape fully under this test's control, including states no
 *   single upload flow produces on demand (e.g. a FAILED row with a populated {@code
 *   error_message} but null {@code chunk_count}/{@code indexed_at}).
 * [Caution] This is a shared, live database other sessions may concurrently write {@code
 *   documents} rows into (see this package's sibling {@code
 *   NonIndexedDocumentLookupIntegrationTest}'s own [Caution] for the same reasoning applied to a
 *   different query). Every assertion below therefore filters the full response down to this
 *   test's own inserted ids before checking field values or relative order, rather than asserting
 *   on the full list -- a concurrently-inserted foreign row could otherwise appear between two of
 *   this test's own rows without breaking any assertion that only checks "my rows are in the right
 *   relative order among themselves", since the list is globally sorted by {@code created_at desc}
 *   and filtering preserves relative order. The ordering test's insertion order is deliberately a
 *   non-monotonic permutation of {@code created_at} -- oldest, then newest, then middle -- not the
 *   ascending order itself; this makes the expected descending response order disagree with
 *   "returns rows in insertion order", "returns rows in reversed-insertion order", and "returns
 *   rows in {@code created_at} ascending order" simultaneously, not just the trivial two-element
 *   case that cannot distinguish those from one another (same reasoning {@code
 *   ConversationControllerIntegrationTest}'s own [Rationale] documents for why it uses three,
 *   non-monotonically-permuted rows rather than two).
 * [AC] document-ingestion:AC-11
 */
@AutoConfigureMockMvc
class DocumentListControllerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    /**
     * [Purpose] Tracks the {@code chatbot_sid} ids {@code AnonymousSessionFilter} minted for this
     *   class's own {@code MockMvc} requests -- see {@code AnonymousUserCleanup}'s own Javadoc
     *   (BL-034).
     */
    private AnonymousUserCleanup anonymousUserCleanup;

    @BeforeEach
    void createAnonymousUserCleanup() {
        anonymousUserCleanup = new AnonymousUserCleanup(jdbcTemplate);
    }

    @AfterEach
    void cleanUpInsertedRows() {
        for (UUID documentId : insertedDocumentIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
        }
        insertedDocumentIds.clear();
        anonymousUserCleanup.cleanUp();
    }

    @Test
    @DisplayName("[document-ingestion:AC-11] INDEXED 문서는 id, filename, mimeType, sizeBytes, status, "
            + "chunkCount, errorMessage(null), createdAt, indexedAt 9개 필드를 정확한 값으로 담아 반환한다")
    void returnsAllNineFieldsWithCorrectValuesForAnIndexedDocument() throws Exception {
        // given: a fully-populated INDEXED row -- every field AC-11 lists has a real, distinct
        // value so a field that is dropped, mismapped or swapped with a sibling field is caught
        UUID documentId = UUID.randomUUID();
        // truncated to microseconds -- PostgreSQL's timestamptz stores at most microsecond
        // precision, and some JVM clocks (observed on this Windows host) report sub-microsecond
        // nanoOfSecond values that would otherwise survive in this in-memory expectation but get
        // silently dropped on the DB round trip, making an untruncated isEqual() comparison flaky
        OffsetDateTime createdAt = OffsetDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime indexedAt = createdAt.plusMinutes(1);
        insertDocument(documentId, "field-mapping-test.txt", "text/plain", 123456789L,
                "INDEXED", null, 7, createdAt, indexedAt);

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/documents"))
                .andExpect(status().isOk())
                .andReturn();
        anonymousUserCleanup.track(result);

        // then: exactly one entry for this id, with every field matching what was inserted
        Map<String, Object> entry = findEntry(result, documentId);
        assertThat(entry.get("id")).isEqualTo(documentId.toString());
        assertThat(entry.get("filename")).isEqualTo("field-mapping-test.txt");
        assertThat(entry.get("mimeType")).isEqualTo("text/plain");
        assertThat(((Number) entry.get("sizeBytes")).longValue()).isEqualTo(123456789L);
        assertThat(entry.get("status")).isEqualTo("INDEXED");
        assertThat(((Number) entry.get("chunkCount")).intValue()).isEqualTo(7);
        assertThat(entry.get("errorMessage")).isNull();
        assertThat(OffsetDateTime.parse((String) entry.get("createdAt")).isEqual(createdAt)).isTrue();
        assertThat(OffsetDateTime.parse((String) entry.get("indexedAt")).isEqual(indexedAt)).isTrue();
    }

    @Test
    @DisplayName("[document-ingestion:AC-11] FAILED 문서는 errorMessage가 채워지고 chunkCount·indexedAt은 "
            + "null로 남는다 -- 대체값을 지어내지 않는다")
    void returnsNullChunkCountAndIndexedAtForAFailedDocument() throws Exception {
        // given: a FAILED row with a populated error_message but no chunk_count/indexed_at --
        // Document's own [Caution] says these are never fabricated a placeholder value, so the
        // DTO must not either
        UUID documentId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now().minusMinutes(3);
        insertDocument(documentId, "failed-test.txt", "text/plain", 42L,
                "FAILED", "parsing failed: unreadable content", null, createdAt, null);

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/documents"))
                .andExpect(status().isOk())
                .andReturn();
        anonymousUserCleanup.track(result);

        // then
        Map<String, Object> entry = findEntry(result, documentId);
        assertThat(entry.get("status")).isEqualTo("FAILED");
        assertThat(entry.get("errorMessage")).isEqualTo("parsing failed: unreadable content");
        assertThat(entry.get("chunkCount")).isNull();
        assertThat(entry.get("indexedAt")).isNull();
    }

    @Test
    @DisplayName("[document-ingestion:AC-11] 삽입 순서와 무관하게 created_at 내림차순으로 반환한다")
    void ordersResultsByCreatedAtDescendingRegardlessOfInsertionOrder() throws Exception {
        // given: three rows inserted in a non-monotonic created_at permutation -- oldest, then
        // newest, then middle -- not created_at-ascending order (insertion order does not track
        // created_at order here). With three distinct created_at values and this insertion order,
        // the expected [d2, d3, d1] (see created_at assignment below) disagrees with every
        // plausible wrong implementation simultaneously: insertion order would give [d1, d2, d3],
        // created_at ASC would give [d1, d3, d2], reversed-insertion would give [d3, d2, d1] --
        // none of those equal [d2, d3, d1] (mirrors ConversationControllerIntegrationTest
        // #listsOnlyOwnNonDeletedConversationsInUpdatedAtDescendingOrder's non-monotonic-permutation
        // reasoning, applied to this endpoint's single sort key)
        OffsetDateTime base = OffsetDateTime.now().minusHours(1);
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        insertDocument(d1, "order-d1.txt", "text/plain", 1L, "PENDING", null, null, base, null);
        insertDocument(d2, "order-d2.txt", "text/plain", 1L, "PENDING", null, null,
                base.plusSeconds(20), null);
        insertDocument(d3, "order-d3.txt", "text/plain", 1L, "PENDING", null, null,
                base.plusSeconds(10), null);

        // when
        MvcResult result = mockMvc.perform(get("/api/admin/documents"))
                .andExpect(status().isOk())
                .andReturn();
        anonymousUserCleanup.track(result);

        // then: filtered to this test's own three ids, in the order they appear in the response --
        // filtering preserves relative order under a globally-created_at-desc-sorted list, so a
        // concurrently-inserted foreign row (this is a shared, live database -- see class
        // [Caution]) cannot mask a wrong order among these three
        List<Map<String, Object>> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        List<String> ownIdsInResponseOrder = body.stream()
                .map(item -> (String) item.get("id"))
                .filter(id -> id.equals(d1.toString()) || id.equals(d2.toString()) || id.equals(d3.toString()))
                .toList();
        assertThat(ownIdsInResponseOrder)
                .containsExactly(d2.toString(), d3.toString(), d1.toString());
    }

    /**
     * [Purpose] Extracts the single response entry whose {@code id} matches {@code documentId}.
     * [Rationale] Filters rather than indexing the response list directly -- this is a shared,
     *   live database (class [Caution]), so a concurrently-inserted foreign row could otherwise
     *   shift this test's row to an unexpected index.
     */
    private Map<String, Object> findEntry(MvcResult result, UUID documentId) throws Exception {
        List<Map<String, Object>> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        return body.stream()
                .filter(item -> documentId.toString().equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No entry for document " + documentId + " in GET /api/admin/documents response"));
    }

    /**
     * [Purpose] Inserts one {@code documents} row with every column under this test's direct
     *   control, tracking the id for {@link #cleanUpInsertedRows}.
     * [Rationale] Raw {@link JdbcTemplate} insert rather than {@link
     *   art.insightai.chatbot.rag.domain.Document}'s constructor/setters, mirroring {@code
     *   ConversationControllerIntegrationTest}'s reasoning for why explicit-timestamp fixtures
     *   bypass the entity: this test needs states (e.g. FAILED with null {@code indexed_at}) and
     *   exact {@code created_at} values the entity's own API does not expose a way to set.
     */
    private void insertDocument(UUID id, String filename, String mimeType, Long sizeBytes,
            String status, String errorMessage, Integer chunkCount,
            OffsetDateTime createdAt, OffsetDateTime indexedAt) {
        insertedDocumentIds.add(id);
        jdbcTemplate.update(
                "INSERT INTO documents "
                        + "(id, filename, mime_type, size_bytes, status, error_message, chunk_count, "
                        + "created_at, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, filename, mimeType, sizeBytes, status, errorMessage, chunkCount, createdAt, indexedAt);
    }
}
