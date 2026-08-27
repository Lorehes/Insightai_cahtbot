package art.insightai.chatbot.rag.ingest;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * [Purpose] Direct SQL access to {@code vector_store_bgem3} rows keyed by
 *   {@code metadata->>'document_id'} — counting and deleting the chunks that belong to a given
 *   {@link art.insightai.chatbot.rag.domain.Document}.
 * [Rationale] Tech Review AD-1 (document-ingestion Spec): chunk insertion goes through
 *   {@code VectorStore.add()}, but delete/count intentionally bypass {@code PgVectorStore}'s
 *   filter API (RK-6 — its 1.x/2.0 filter-expression signature was unconfirmed at design time)
 *   in favor of {@link JdbcTemplate} against the exact predicate the Spec's AC-7/AC-12/AC-13
 *   already use to define correctness: {@code metadata->>'document_id'}. This class is the
 *   single shared place both T8 (failure compensation delete) and T10 (document delete
 *   endpoint) call into, so that predicate is written exactly once.
 * [Caution] {@code vector_store_bgem3.metadata} is a plain {@code json} column (not
 *   {@code jsonb}, per {@code V1__init.sql} — {@code PgVectorStore}'s own default schema), and
 *   {@code document_id} is stored as the string form of a UUID inside it. Callers must not pass
 *   the raw {@link UUID}; both methods below bind {@code documentId.toString()} so the
 *   comparison is against text, matching how {@code ->>'document_id'} reads back. There is no
 *   supporting index on this expression in the Flyway-owned schema as of this card — RK-4
 *   confirmed a plain B-tree expression index CAN be added (ParadeDB's "one index per table"
 *   limit applies only to its own {@code USING paradedb} index method, not to ordinary B-tree),
 *   but adding it for real is a schema change owned by the chat Spec's migration, not this card.
 */
@Repository
public class ChunkRepository {

    private static final String COUNT_BY_DOCUMENT_ID_SQL =
            "SELECT COUNT(*) FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?";

    private static final String DELETE_BY_DOCUMENT_ID_SQL =
            "DELETE FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?";

    private final JdbcTemplate jdbcTemplate;

    public ChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * [Purpose] Counts the chunk rows currently indexed for a given document.
     * [Rationale] Used by later cards (T8) to verify indexing actually produced rows before
     *   marking a document INDEXED, and by T10 to report how many chunks were removed.
     */
    public int countByDocumentId(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
                COUNT_BY_DOCUMENT_ID_SQL, Integer.class, documentId.toString());
        return count != null ? count : 0;
    }

    /**
     * [Purpose] Deletes all chunk rows for a given document.
     * [Rationale] Shared by T8's failure-path compensation delete and T10's document delete
     *   endpoint (AD-1) — both need exactly this predicate and neither should re-implement it.
     * [Side effects] Permanently removes rows from {@code vector_store_bgem3}; callers own the
     *   transaction boundary.
     */
    public int deleteByDocumentId(UUID documentId) {
        return jdbcTemplate.update(DELETE_BY_DOCUMENT_ID_SQL, documentId.toString());
    }
}
