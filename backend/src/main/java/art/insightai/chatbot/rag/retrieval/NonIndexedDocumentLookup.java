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
 * [Caution] Returns id+status pairs, not just id, even though every current caller of {@link
 *   #findAll()} only consumes id for the {@code NOT IN}/{@code nin} exclusion list. {@code
 *   status} is deliberately retained rather than discarded at the query site: hybrid-rag T14 adds
 *   {@link #countExcludedByStatus()}, a separate per-run diagnostic query for {@code
 *   eval-usage-dashboard} to read the status distribution of excluded/orphaned {@code documents}
 *   rows (see that method's Javadoc) -- this class already had {@code status} in hand at {@link
 *   #findAll()}'s query time, which is why the row shape carried it before this second query
 *   existed (coordinator note, 2026-08-28).
 */
@Component
public class NonIndexedDocumentLookup {

    private static final String SQL = "SELECT id, status FROM documents WHERE status <> 'INDEXED'";

    private static final String COUNT_BY_STATUS_SQL =
            "SELECT status, COUNT(*) AS count FROM documents WHERE status <> 'INDEXED' GROUP BY status";

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

    /**
     * [Purpose] Returns, as of the moment this method runs, how many {@code documents} rows (not
     *   chunks -- see [Caution]) are excluded from both search axes ({@link #findAll()}'s
     *   population), grouped by their current {@code status} (e.g. {@code FAILED} -> 3, {@code
     *   PENDING} -> 1).
     * [Rationale] Separate query and separate call site from {@link #findAll()}: this method's
     *   caller is a per-run diagnostic (Tech Review D-T14 -- an eval sweep calls this once at
     *   sweep start, not once per search request the way {@link ParadeDbBm25SearchAdapter} and
     *   {@link VectorStoreSearchAdapter} each call {@link #findAll()}). Grouping by status rather
     *   than returning a single total preserves which failure mode is responsible for the
     *   exclusion, which {@code eval-usage-dashboard} needs to route to {@code document-ingestion}
     *   for further triage -- a single count would conflate unrelated states (e.g. rows still
     *   {@code PENDING} mid-pipeline vs. rows genuinely {@code FAILED}). {@code GROUP BY} runs in
     *   the database rather than aggregating {@link #findAll()}'s rows in Java so a status with
     *   zero excluded rows never needs special-casing (it is simply absent from the list) and no
     *   memory is spent materializing per-row objects only to immediately collapse them. Returns
     *   {@code List<ExcludedDocumentStatusCount>} rather than {@code Map<String, Long>} (review
     *   round 1, hybrid-rag T14 maintainability Medium-1) to follow the record-per-row convention
     *   {@code usage.UsageRepository#fetchByModel()}/{@code usage.ModelUsage} already established
     *   for this exact shape of query in this codebase.
     * [Caution] Counts {@code documents} table rows -- one per document -- not chunks. A {@code
     *   FAILED} document whose compensating chunk delete already ran (document-ingestion T8 review
     *   correctness Medium-2) contributes 1 to this count while owning 0 rows in {@code
     *   vector_store_bgem3}, so "documents excluded" and "chunks excluded" are different numbers
     *   for the same status. AC-17's literal text says "청크"(chunks)'s status counts "검색 실행
     *   시점"(at search-execution time); the Tech-Review-approved design (Spec §Tech Review,
     *   2026-08-28 revision, D-T10) counts {@code documents} rows as a per-run snapshot taken at
     *   sweep start instead -- see review round 1 (hybrid-rag T14) for the full rationale. This is
     *   also a point-in-time snapshot, not a live subscription -- {@code status} values for
     *   individual documents can change between this call and whenever the caller reads the
     *   returned list. A status with 0 excluded rows is absent from the list, not present with
     *   count 0 -- callers must not assume every {@code DocumentStatus} constant appears.
     * [AC] hybrid-rag:AC-17
     */
    public List<ExcludedDocumentStatusCount> countExcludedByStatus() {
        return jdbcTemplate.query(COUNT_BY_STATUS_SQL, this::mapStatusCount);
    }

    private ExcludedDocumentStatusCount mapStatusCount(ResultSet rs, int rowNum) throws SQLException {
        return new ExcludedDocumentStatusCount(rs.getString("status"), rs.getLong("count"));
    }
}
