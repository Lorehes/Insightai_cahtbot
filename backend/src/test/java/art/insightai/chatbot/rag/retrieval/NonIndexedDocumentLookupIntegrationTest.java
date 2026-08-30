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
 *   end-to-end through each axis, this class covers the query itself in isolation. Also covers
 *   {@link NonIndexedDocumentLookup#countExcludedByStatus()} against the same live table
 *   (hybrid-rag:AC-17) with a delta-based positive control, since {@code countExcludedByStatus}'s
 *   own unit test ({@link NonIndexedDocumentLookupTest}) mocks {@link JdbcTemplate} and cannot
 *   prove the SQL text actually counts real rows.
 * [Caution] This runs against a live, shared {@code documents} table other sessions may write to
 *   concurrently. {@link #countsExcludedDocumentsByStatusWithPositiveControl()} asserts on the
 *   delta between a before/after snapshot rather than on absolute counts, which makes it immune
 *   to rows that already existed before this test ran. It is NOT immune to a write landing in the
 *   window between the two snapshot reads (two independent, non-transactional queries under
 *   READ COMMITTED) -- a concurrent session inserting or transitioning a non-{@code INDEXED}
 *   document in that window can shift the delta and fail this test without a real regression
 *   (review round 1, hybrid-rag T14, correctness Medium-2). Wrapping both reads in one
 *   {@code @Transactional} test does NOT close that window (review round 2, hybrid-rag T14,
 *   correctness Low-2, correcting the round-1 write-up above) -- READ COMMITTED, this project's
 *   default isolation level, takes a fresh snapshot per *statement*, not per transaction, so a
 *   commit landing between the two reads is still visible to the second read even inside a single
 *   surrounding transaction. Closing the window would require REPEATABLE READ or stricter, and
 *   that conflicts with this class's per-test {@code @AfterEach} cleanup (the manual
 *   {@code DELETE}s need their own committed view of the row they are removing, which a
 *   REPEATABLE READ snapshot taken before seeding would not reliably give). So this class accepts
 *   the residual window instead and surfaces it via the {@code .as(...)} failure messages below --
 *   a failure here should be read as "check for a concurrent session" before it is read as "the
 *   query regressed".
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

    @Test
    @DisplayName("[hybrid-rag:AC-17] 비-INDEXED 문서를 시드하면 상태별 건수에 실제로 반영되고, INDEXED 문서는 집계에 잡히지 않는다 (양성 대조)")
    void countsExcludedDocumentsByStatusWithPositiveControl() {
        // given: baseline snapshot before seeding -- this is a live container other sessions may
        // be concurrently writing non-INDEXED documents into, so this test asserts on the delta
        // its own seeding causes rather than on absolute counts. That is immune to rows that
        // predate this test, but NOT to a write landing between this snapshot and the "after"
        // snapshot below -- see this class's Javadoc [Caution].
        List<ExcludedDocumentStatusCount> before = lookup.countExcludedByStatus();
        long failedBefore = countFor(before, "FAILED");
        long pendingBefore = countFor(before, "PENDING");
        long totalBefore = total(before);

        // when: seed two FAILED, one PENDING, and one INDEXED document
        insertDocument("FAILED");
        insertDocument("FAILED");
        insertDocument("PENDING");
        insertDocument("INDEXED");
        List<ExcludedDocumentStatusCount> after = lookup.countExcludedByStatus();

        // then: positive control -- the seeded non-INDEXED rows actually moved the per-status
        // counts by exactly the number this test inserted, proving the query counts real rows
        // rather than a query that silently sees nothing (the failure mode AC-17 calls out).
        // Failure messages carry the raw before/after snapshots so a concurrent-write false-fail
        // (class Javadoc [Caution]) is distinguishable from a real regression at a glance.
        assertThat(countFor(after, "FAILED") - failedBefore)
                .as("FAILED delta; before=%s after=%s", before, after)
                .isEqualTo(2L);
        assertThat(countFor(after, "PENDING") - pendingBefore)
                .as("PENDING delta; before=%s after=%s", before, after)
                .isEqualTo(1L);
        // then: the INDEXED document seeded above does not inflate any status's count -- the
        // total excluded delta equals exactly the 3 non-INDEXED rows seeded, not 4
        long totalAfter = total(after);
        assertThat(totalAfter - totalBefore)
                .as("total excluded delta; before=%s after=%s", before, after)
                .isEqualTo(3L);
    }

    private static long countFor(List<ExcludedDocumentStatusCount> counts, String status) {
        return counts.stream()
                .filter(c -> c.status().equals(status))
                .mapToLong(ExcludedDocumentStatusCount::count)
                .findFirst()
                .orElse(0L);
    }

    private static long total(List<ExcludedDocumentStatusCount> counts) {
        return counts.stream().mapToLong(ExcludedDocumentStatusCount::count).sum();
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
