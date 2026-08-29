package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import art.insightai.chatbot.support.IntegrationTestBase;

/**
 * [Purpose] Verifies {@link NonIndexedDocumentLookup} against the live {@code documents} table:
 *   rows whose {@code status} is not {@code INDEXED} are returned with both {@code id} and
 *   {@code status} preserved, and {@code INDEXED} rows are excluded. This is the shared
 *   collaborator both {@link ParadeDbBm25SearchAdapter} and {@link VectorStoreSearchAdapter}
 *   depend on for hybrid-rag:AC-15/AC-16 -- their own integration tests cover the exclusion
 *   end-to-end through each axis, this class covers the query itself in isolation.
 */
class NonIndexedDocumentLookupIntegrationTest extends IntegrationTestBase {

    @Autowired
    private NonIndexedDocumentLookup lookup;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : insertedDocumentIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", id);
        }
        insertedDocumentIds.clear();
    }

    @Test
    @DisplayName("status가 INDEXED가 아닌 문서만 id·status 쌍으로 반환하고, INDEXED 문서는 포함하지 않는다")
    void returnsOnlyNonIndexedDocumentsWithIdAndStatusPreserved() {
        // given: one of each non-terminal/terminal-failed status, plus one INDEXED document that
        // must NOT show up in the result (positive/negative control in one test, mirroring
        // ParadeDbBm25SearchAdapterIntegrationTest/VectorStoreSearchAdapterIntegrationTest's
        // pattern at the axis level)
        UUID pendingId = insertDocument("PENDING");
        UUID failedId = insertDocument("FAILED");
        UUID indexedId = insertDocument("INDEXED");

        // when
        List<NonIndexedDocument> result = lookup.findAll();

        // then: both non-INDEXED documents are present, the INDEXED one is absent -- narrowed to
        // this test's own ids since this is a live, shared infra container other sessions may
        // also be writing non-INDEXED documents into concurrently
        assertThat(result)
                .extracting(NonIndexedDocument::id)
                .contains(pendingId.toString(), failedId.toString())
                .doesNotContain(indexedId.toString());
        // then: status is preserved verbatim per row, not just id (coordinator note, 2026-08-28 --
        // see this class's own Javadoc / NonIndexedDocumentLookup's Javadoc for why status is kept)
        assertThat(result)
                .filteredOn(doc -> doc.id().equals(pendingId.toString()))
                .extracting(NonIndexedDocument::status)
                .containsExactly("PENDING");
        assertThat(result)
                .filteredOn(doc -> doc.id().equals(failedId.toString()))
                .extracting(NonIndexedDocument::status)
                .containsExactly("FAILED");
    }

    private UUID insertDocument(String status) {
        UUID id = UUID.randomUUID();
        insertedDocumentIds.add(id);
        jdbcTemplate.update(
                "INSERT INTO documents (id, filename, status) VALUES (?, ?, ?)",
                id, "lookup-test-" + id + ".txt", status);
        return id;
    }
}
