package art.insightai.chatbot.rag.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

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
 *   this Spec's explicit SQL-injection guard for the one adapter in this project that runs a
 *   raw hand-written SQL string against user-influenced input) — never string-concatenate it
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
     * plan output).
     */
    private static final String BM25_SEARCH_SQL =
            "SELECT id, content, metadata, pdb.score(id) AS score "
                    + "FROM vector_store_bgem3 "
                    + "WHERE content ||| ? "
                    + "ORDER BY score DESC "
                    + "LIMIT ?";

    private final JdbcTemplate jdbcTemplate;
    private final JsonParser jsonParser;

    public ParadeDbBm25SearchAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonParser = JsonParserFactory.getJsonParser();
    }

    /**
     * [Purpose] Runs the BM25 axis query and maps each row straight to a {@link RetrievedChunk}.
     * [Caution] {@code query} and {@code topK} are passed as JDBC bind parameters (the
     *   {@code ?} placeholders below), never concatenated into the SQL text — see this class's
     *   [Caution] for why that is a hard requirement here.
     * [AC] hybrid-rag:AC-13
     */
    @Override
    public List<RetrievedChunk> search(String query, int topK) {
        return jdbcTemplate.query(BM25_SEARCH_SQL, this::mapRow, query, topK);
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
