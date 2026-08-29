package art.insightai.chatbot.rag.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * [Purpose] Queries {@code documents} for every row whose {@code status} is not {@code INDEXED}
 *   -- the exclusion population Tech Review D-T10 requires both search axes ({@link
 *   ParadeDbBm25SearchAdapter}, {@link VectorStoreSearchAdapter}) to apply symmetrically so an
 *   orphaned chunk (one left in {@code vector_store_bgem3} by a document row that never reached
 *   {@code INDEXED} -- e.g. document-ingestion T8 review correctness Medium-2's "compensating
 *   delete itself fails" path) is never returned by either axis (hybrid-rag:AC-15, AC-16).
 * [Rationale] Lives in {@code rag/retrieval} and queries {@code documents} directly via {@link
 *   JdbcTemplate} rather than going through {@code DocumentRepository}/{@code ChunkRepository}
 *   (T13 card constraint: {@code rag/ingest} is claimed by a concurrent session and must not be
 *   touched). {@code WHERE status <> 'INDEXED'} is used instead of enumerating {@code
 *   DocumentStatus}'s non-terminal values (PENDING/PARSING/EMBEDDING/FAILED) so this class does
 *   not need to change if that enum ever gains a new non-terminal state. Each caller (both search
 *   adapters) invokes {@link #findAll()} once per its own {@code search()} call -- not once per
 *   user-facing {@code retrieve()} request shared across axes -- because the two ports'
 *   signatures ({@code Bm25SearchPort}, {@code VectorSearchPort}) are locked at query/topK only
 *   (coordinator direction, 2026-08-28): there is no shared place upstream ({@code
 *   HybridDocumentRetriever}) this card may thread a precomputed set through. Both adapters run
 *   the exact same query here, so the exclusion RULE applied by each axis is identical (D-T10's
 *   "두 축이 대칭이어야 한다") -- but running the same query twice does NOT guarantee the two calls see
 *   the same exclusion POPULATION: they are two separate reads in two separate transactions, each
 *   its own point-in-time snapshot. If a document's {@code status} transitions between the two
 *   calls (e.g. {@code INDEXED -> FAILED} mid-request), one axis excludes it and the other does
 *   not, and the two axes' RRF inputs draw from asymmetric populations for that request (T13
 *   review correctness Medium-2; Spec D-T10's 2026-08-28 revision records this as a property this
 *   design accepts, not a bug: the earlier "single fetch before search" design would have removed
 *   this specific window, but not the separate fetch-vs-search TOCTOU window every design here
 *   still has, so the tradeoff was judged not worth reintroducing a shared-fetch dependency
 *   between the two ports).
 * [Caution] Returns id+status pairs, not just id, even though every current caller in this card
 *   only consumes id for the {@code NOT IN}/{@code nin} exclusion list. {@code status} is
 *   deliberately retained rather than discarded at the query site: {@code eval-usage-dashboard}
 *   Spec is adding a report AC over the status distribution of excluded/orphaned chunks, and this
 *   class already has {@code status} in hand at query time -- dropping it here would force that
 *   Spec to add a second query later for a value already read once (coordinator note,
 *   2026-08-28). Whether/how that distribution is ever surfaced is undecided and out of this
 *   card's scope -- this class exposes no aggregate, count, or other public surface beyond the
 *   raw per-row list.
 */
@Component
public class NonIndexedDocumentLookup {

    private static final String SQL = "SELECT id, status FROM documents WHERE status <> 'INDEXED'";

    private final JdbcTemplate jdbcTemplate;

    public NonIndexedDocumentLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * [Purpose] Returns every {@code documents} row whose {@code status} is not {@code INDEXED},
     *   as of the moment this method runs.
     * [AC] hybrid-rag:AC-15
     * [AC] hybrid-rag:AC-16
     */
    public List<NonIndexedDocument> findAll() {
        return jdbcTemplate.query(SQL, this::mapRow);
    }

    private NonIndexedDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String status = rs.getString("status");
        return new NonIndexedDocument(id.toString(), status);
    }
}
