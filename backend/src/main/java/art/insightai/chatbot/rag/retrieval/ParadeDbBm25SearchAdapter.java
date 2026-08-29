package art.insightai.chatbot.rag.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] {@link Bm25SearchPort} implementation that runs a ParadeDB BM25 full-text query
 *   directly against {@code vector_store_bgem3} via {@link JdbcTemplate}.
 * [Rationale] Tech Review D-T5 required the real syntax to be verified against the live
 *   {@code infra} ParadeDB container before being written here rather than copied from the
 *   plan's §7.3 "design example". Verified 2026-08-26 against {@code paradedb/paradedb:0.25.4-pg18}
 *   (container {@code chatbot-db}): {@code content ||| ?} (the ParadeDB match operator) plus
 *   {@code pdb.score(id)} works unchanged with bound parameters (both the query text and the
 *   {@code LIMIT} — confirmed via {@code PREPARE ... EXECUTE}, which exercises the same
 *   extended-query-protocol bind path the PostgreSQL JDBC driver uses for
 *   {@link JdbcTemplate#query(String, RowMapper, Object...)}). Only one query is issued and it
 *   already carries {@code content}/{@code metadata}/the BM25 score, satisfying D-T5's "no N+1"
 *   requirement without a separate row-fetch step. The plan's §7.3 SQL additionally joined
 *   {@code bm25}/{@code vec} CTEs and grouped for RRF fusion; that fusion now happens in Java
 *   (Tech Review D-T2), so this adapter only needs the BM25-only half of that example — there is
 *   no join or aggregate here at all.
 * [Caution] The query string MUST reach the database as a bound parameter (Tech Review D-T5,
 *   this Spec's explicit SQL-injection guard for hand-written SQL run against user-influenced
 *   input — since T13/D-T11, {@link VectorStoreSearchAdapter} runs the same kind of raw SQL for
 *   the vector axis and documents the identical bound-parameter requirement there; this is no
 *   longer the only adapter in this project doing so) — never string-concatenate it
 *   into {@code BM25_SEARCH_SQL}. This query MUST keep using the {@code |||} (match disjunction)
 *   operator, never {@code @@@}: {@code @@@} parses its right-hand side as a ParadeDB query-DSL
 *   expression even when it arrives as a bound parameter, so an adversarial value still reaches
 *   the DSL parser and can break or widen the query (verified against the live {@code chatbot-db}
 *   container: {@code content @@@ '(((\"unclosed'} raises a parse error, while
 *   {@code content ||| '(((\"unclosed'} is treated as a plain literal and safely returns 0 rows).
 *   {@code vector_store_bgem3.metadata} is a plain {@code json}
 *   column (not {@code jsonb}, {@code V1__init.sql}), so it comes back from the driver as a
 *   JSON text string and is parsed here with {@link JsonParser}, not deserialized natively by
 *   the driver. {@code JsonParser} (from {@code org.springframework.boot.json}, already on the
 *   classpath via the {@code spring-boot} core jar every Spring Boot app carries) is used instead
 *   of calling a concrete JSON library's API (e.g. Jackson's {@code ObjectMapper}) directly, so
 *   this class stays decoupled from whichever implementation {@link JsonParserFactory} selects.
 *   Re-verified 2026-08-26: {@code mvn dependency:tree} shows this project is NOT Jackson-free —
 *   {@code spring-boot-starter-jackson} transitively pulls in Jackson 3
 *   ({@code tools.jackson.core:jackson-databind:3.1.5}) via the
 *   {@code spring-boot-starter-webmvc}/{@code spring-ai-*} dependencies, and
 *   {@code javap -c JsonParserFactory} confirms {@link JsonParserFactory#getJsonParser()} checks
 *   for {@code tools.jackson.databind.ObjectMapper} before Gson/{@code BasicJsonParser} — so the
 *   parser actually constructed here at runtime is {@code JacksonJsonParser}, not
 *   {@code BasicJsonParser} as an earlier version of this comment incorrectly claimed. This class
 *   still adds no new dependency (project rule #7): Jackson already arrives transitively
 *   regardless of this adapter, and this class depends only on the pre-existing {@code JsonParser}
 *   abstraction, never on Jackson's API directly.
 * [Caution] T13/Tech Review D-T10 added a second, unconditional round trip: every {@link #search}
 *   call starts by running {@link NonIndexedDocumentLookup#findAll()} to fetch the current
 *   non-{@code INDEXED} document id population (hybrid-rag:AC-15 — orphaned chunks left behind by
 *   a document that never reached {@code INDEXED} must never surface here). That query carries no
 *   bind parameters, so {@link JdbcTemplate} issues it via a plain {@code java.sql.Statement}, not
 *   a {@code PreparedStatement} — {@code
 *   ParadeDbBm25SearchAdapterIntegrationTest#populatesContentMetadataAndScoreFromASingleQueryWithoutNPlusOne}'s
 *   {@code PreparedStatement}-count assertion (still exactly 1, unchanged by this card) therefore
 *   does not observe it; that test's own comment explains why. Which of the two SQL strings below
 *   actually runs is conditional on that population's size: when it is empty, {@link
 *   #BM25_SEARCH_SQL} runs unchanged (the original "no N+1" case); when it is non-empty, {@link
 *   #BM25_SEARCH_SQL_WITH_EXCLUSION_TEMPLATE} runs instead, adding exactly one more
 *   bound-parameter predicate ({@code AND metadata->>'document_id' NOT IN (...)}) — still no join,
 *   and still a fixed number of round trips (2) regardless of how many documents are excluded or
 *   how many rows the main query returns. The excluded ids themselves are always bound JDBC
 *   parameters, never string-concatenated, for the same injection reason as {@code query} above.
 * [Caution] {@code metadata->>'document_id'} returns SQL {@code NULL} for a row whose {@code
 *   metadata} has no {@code document_id} key; {@code NULL NOT IN (...)} evaluates to {@code NULL}
 *   (not {@code true}), so such a row is silently excluded from every result whenever the
 *   exclusion list is non-empty (T13 review Low L-1) — the same {@code NOT IN} sharp edge {@link
 *   VectorStoreSearchAdapter} documents on its own exclusion predicate.
 */
@Component
public class ParadeDbBm25SearchAdapter implements Bm25SearchPort {

    /**
     * One query, no join/aggregate: the plan's §7.3 example additionally joined a {@code vec}
     * CTE for RRF fusion, which is why it triggered ParadeDB's "DataFusion aggregate scan not
     * used" warning (Risk R-B). Since D-T2 moved fusion to Java, this BM25-only query never had
     * that join to begin with — confirmed by {@code EXPLAIN (ANALYZE)} against the live
     * container showing a plain {@code Custom Scan (ParadeDB Base Scan)} /
     * {@code TopKScanExecState} plan with no DataFusion warning at all (dev-log has the full
     * plan output). Used only when {@link NonIndexedDocumentLookup#findAll()} returns empty
     * (T13/D-T10 — "집합이 비면 필터 자체를 생략한다").
     */
    private static final String BM25_SEARCH_SQL =
            "SELECT id, content, metadata, pdb.score(id) AS score "
                    + "FROM vector_store_bgem3 "
                    + "WHERE content ||| ? "
                    + "ORDER BY score DESC "
                    + "LIMIT ?";

    /**
     * [Purpose] Same query as {@link #BM25_SEARCH_SQL} plus an exclusion predicate for orphaned
     *   chunks (T13/D-T10, hybrid-rag:AC-15) — used only when {@link #search} finds a non-empty
     *   exclusion population.
     * [Caution] {@code %s} is substituted with a placeholder list ({@code "?, ?, ..."}) sized to
     *   that population — never with an id value itself — before this string reaches {@link
     *   JdbcTemplate}; every actual excluded id still arrives as its own bound parameter. This is
     *   still a single-table, no-join predicate (a plain {@code NOT IN} list, not a subquery or a
     *   join against {@code documents}), so it does not reintroduce Risk R-B.
     */
    private static final String BM25_SEARCH_SQL_WITH_EXCLUSION_TEMPLATE =
            "SELECT id, content, metadata, pdb.score(id) AS score "
                    + "FROM vector_store_bgem3 "
                    + "WHERE content ||| ? "
                    + "AND metadata->>'document_id' NOT IN (%s) "
                    + "ORDER BY score DESC "
                    + "LIMIT ?";

    private final JdbcTemplate jdbcTemplate;
    private final JsonParser jsonParser;
    private final NonIndexedDocumentLookup nonIndexedDocumentLookup;

    public ParadeDbBm25SearchAdapter(
            JdbcTemplate jdbcTemplate, NonIndexedDocumentLookup nonIndexedDocumentLookup) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonParser = JsonParserFactory.getJsonParser();
        this.nonIndexedDocumentLookup = nonIndexedDocumentLookup;
    }

    /**
     * [Purpose] Runs the BM25 axis query and maps each row straight to a {@link RetrievedChunk},
     *   excluding chunks that belong to a non-{@code INDEXED} document (T13/D-T10).
     * [Caution] {@code query}, {@code topK}, and every excluded document id are passed as JDBC
     *   bind parameters, never concatenated into the SQL text — see this class's [Caution] for
     *   why that is a hard requirement here.
     * [AC] hybrid-rag:AC-13
     * [AC] hybrid-rag:AC-15
     */
    @Override
    public List<RetrievedChunk> search(String query, int topK) {
        List<String> excludedDocumentIds = excludedDocumentIds();
        if (excludedDocumentIds.isEmpty()) {
            return jdbcTemplate.query(BM25_SEARCH_SQL, this::mapRow, query, topK);
        }
        String placeholders =
                excludedDocumentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = String.format(BM25_SEARCH_SQL_WITH_EXCLUSION_TEMPLATE, placeholders);
        List<Object> params = new ArrayList<>(excludedDocumentIds.size() + 2);
        params.add(query);
        params.addAll(excludedDocumentIds);
        params.add(topK);
        return jdbcTemplate.query(sql, this::mapRow, params.toArray());
    }

    /**
     * [Purpose] Fetches this axis's exclusion population (T13/D-T10) and narrows it to the id
     *   values {@link #search} needs.
     * [Caution] {@code status} is intentionally not read here — see {@link
     *   NonIndexedDocumentLookup}'s Javadoc for why it is retained at the query site instead of
     *   being dropped.
     */
    private List<String> excludedDocumentIds() {
        return nonIndexedDocumentLookup.findAll().stream().map(NonIndexedDocument::id).toList();
    }

    /**
     * [Purpose] Maps one result row to this project's axis-agnostic {@link RetrievedChunk}.
     * [Caution] {@code metadata} arrives as a JSON text string (the column type is {@code json},
     *   not {@code jsonb}); a null column value maps to an empty map rather than a null
     *   {@link RetrievedChunk#metadata()} so downstream consumers (T7, T8) never need a null
     *   check that {@link VectorStoreSearchAdapter}'s callers don't need either.
     */
    private RetrievedChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedChunk(
                rs.getString("id"),
                rs.getString("content"),
                parseMetadata(rs.getString("metadata")),
                rs.getDouble("score"));
    }

    /**
     * [Purpose] Parses the {@code metadata} column's raw JSON text into a {@code Map}.
     * [Caution] A malformed JSON value here would mean {@code vector_store_bgem3} rows were
     *   written by something other than {@code PgVectorStore}/this project's own ingestion path
     *   (document-ingestion Spec) — surfaced as an unchecked exception rather than silently
     *   dropped metadata, since callers (T8's {@code SourceRef} mapping) rely on
     *   {@code document_id}/{@code filename} being present.
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null) {
            return Map.of();
        }
        try {
            return jsonParser.parseMap(metadataJson);
        } catch (JsonParseException e) {
            throw new IllegalStateException(
                    "Malformed metadata JSON in vector_store_bgem3 row", e);
        }
    }
}
