package art.insightai.chatbot.usage;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * [Purpose] Direct SQL access to {@code messages} for usage aggregation — totals and per-model
 *   breakdown over an optional {@code [from, to)} period (eval-usage-dashboard Spec, card U1).
 * [Rationale] Tech Review D-8 requires a {@code FILTER (WHERE cost_usd IS NOT NULL)} /
 *   {@code FILTER (WHERE cost_usd IS NULL)} pair to separate the cost sum from the missing-cost
 *   count — plain {@code SUM(cost_usd)} would collapse a {@code null} row into "contributes 0",
 *   which is indistinguishable from a genuinely free request and defeats AC-17's purpose. JPQL
 *   has no {@code FILTER} clause, so this repository is plain {@link JdbcTemplate} SQL rather
 *   than a Spring Data JPA repository, following the precedent already set by
 *   {@code rag.ingest.ChunkRepository}/{@code rag.retrieval.ParadeDbBm25SearchAdapter} for
 *   hand-written SQL that Spring Data cannot express. {@code messages} itself stays the JPA
 *   entity {@code chat.domain.Message} owns for writes (Tech Review A-4) — this class only reads.
 *   The period filter is built by string concatenation of static, hard-coded SQL fragments (never
 *   caller input) with bind parameters for the actual values, so no SQL-injection surface is
 *   introduced (contrast with {@code ParadeDbBm25SearchAdapter}'s [Caution], which guards a
 *   different, user-influenced-value risk that does not apply here).
 * [Caution] What a {@code null cost_usd} means changed on 2026-09-02 (user decision "A안"): a turn
 *   the {@code RagEmptyContextGuardAdvisor} short-circuited (no OpenRouter call at all) is now
 *   persisted as {@code 0/0/0}, not {@code null}, so {@code missing_cost_count} counts only turns
 *   where a request was actually sent but its cost never arrived — a stream that ended in {@code
 *   cancelled}/{@code error} before the terminal usage chunk, or a provider response without the
 *   {@code cost} extension. Rows written before that date were backfilled by hand on the demo DB
 *   (6 rows, ids 2–12); other environments carrying older data would need the same one-off
 *   {@code UPDATE} on {@code role='assistant' AND cost_usd IS NULL AND prompt_tokens IS NULL AND
 *   finish_reason='stop' AND content='제공된 문서에서 찾을 수 없습니다.'}.
 * [Caution] Every query here is scoped to {@code role = 'assistant'} per Tech Review D-8 — the
 *   'user' row of a turn never carries {@code model}/token/cost data (see
 *   {@code chat.domain.Message}'s class-level Caution), so including it would only add zero-valued
 *   noise, not new signal. The period bound is half-open: {@code from <= created_at < to}. Passing
 *   {@code null} for {@code from} and/or {@code to} omits that side of the bound entirely (Tech
 *   Review D-8 "기본값: 둘 다 없으면 전체 기간") rather than being treated as "match nothing".
 */
@Repository
public class UsageRepository {

    private static final String TOTALS_SQL =
            "SELECT COUNT(*) AS total_requests, "
                    + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
                    + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
                    + "COALESCE(SUM(cost_usd) FILTER (WHERE cost_usd IS NOT NULL), 0) AS total_cost_usd, "
                    + "COUNT(*) FILTER (WHERE cost_usd IS NULL) AS missing_cost_count "
                    + "FROM messages WHERE role = 'assistant'";

    private static final String BY_MODEL_SQL_PREFIX =
            "SELECT model, COUNT(*) AS requests, "
                    + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
                    + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
                    + "COALESCE(SUM(cost_usd) FILTER (WHERE cost_usd IS NOT NULL), 0) AS cost_usd "
                    + "FROM messages WHERE role = 'assistant'";

    private static final String BY_MODEL_SQL_SUFFIX = " GROUP BY model ORDER BY model";

    private final JdbcTemplate jdbcTemplate;

    public UsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * [Purpose] Returns aggregate request/token/cost counters for the given period.
     * [AC] eval-usage-dashboard:AC-14, eval-usage-dashboard:AC-17
     */
    public UsageTotals fetchTotals(OffsetDateTime from, OffsetDateTime to) {
        List<Object> params = new ArrayList<>();
        String sql = TOTALS_SQL + buildPeriodFilter(from, to, params);
        return jdbcTemplate.queryForObject(sql, this::mapTotals, params.toArray());
    }

    /**
     * [Purpose] Returns one row per distinct {@code messages.model} value for the given period,
     *   ordered by model name for a stable response.
     * [AC] eval-usage-dashboard:AC-16
     */
    public List<ModelUsage> fetchByModel(OffsetDateTime from, OffsetDateTime to) {
        List<Object> params = new ArrayList<>();
        String sql = BY_MODEL_SQL_PREFIX + buildPeriodFilter(from, to, params) + BY_MODEL_SQL_SUFFIX;
        return jdbcTemplate.query(sql, this::mapModelUsage, params.toArray());
    }

    /**
     * [Purpose] Builds the optional {@code AND created_at ...} clauses for the half-open period
     *   filter and appends the corresponding bind values to {@code params} in the same order the
     *   clauses are written, so the caller's {@code params.toArray()} lines up with the {@code ?}
     *   placeholders.
     * [Rationale] Private, following this repository's existing mapping-helper convention
     *   ({@code rag.retrieval.ParadeDbBm25SearchAdapter}, {@code rag.retrieval.ReciprocalRankFusion},
     *   {@code rag.retrieval.SourceRefMapper}): mapping/filter helpers stay private and are
     *   verified through {@code UsageIntegrationTest} against a live database rather than through
     *   mock-based unit tests (review 2026-08-27 Medium-4).
     */
    private static String buildPeriodFilter(OffsetDateTime from, OffsetDateTime to, List<Object> params) {
        StringBuilder filter = new StringBuilder();
        if (from != null) {
            filter.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            filter.append(" AND created_at < ?");
            params.add(to);
        }
        return filter.toString();
    }

    /**
     * [Purpose] Maps one {@link #TOTALS_SQL} result row to {@link UsageTotals}.
     * [Caution] {@code total_cost_usd} and {@code missing_cost_count} are read from two distinct
     *   columns on purpose (eval-usage-dashboard:AC-17) — never derive one from the other.
     */
    private UsageTotals mapTotals(ResultSet rs, int rowNum) throws SQLException {
        return new UsageTotals(
                rs.getLong("total_requests"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getBigDecimal("total_cost_usd"),
                rs.getLong("missing_cost_count"));
    }

    /**
     * [Purpose] Maps one {@link #BY_MODEL_SQL_PREFIX} result row to {@link ModelUsage}.
     */
    private ModelUsage mapModelUsage(ResultSet rs, int rowNum) throws SQLException {
        return new ModelUsage(
                rs.getString("model"),
                rs.getLong("requests"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getBigDecimal("cost_usd"));
    }
}
