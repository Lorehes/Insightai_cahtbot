package art.insightai.chatbot.rag.retrieval;

/**
 * [Purpose] One row of {@link NonIndexedDocumentLookup#countExcludedByStatus()}'s breakdown: how
 *   many {@code documents} rows currently sit at a given non-{@code INDEXED} {@code status}.
 * [Rationale] Mirrors {@code usage.ModelUsage} -- a named record per group-by row, returned as a
 *   {@code List} from a {@link org.springframework.jdbc.core.JdbcTemplate}-backed repository/lookup
 *   method, rather than a raw {@code Map<String, Long>} (review round 1, hybrid-rag T14
 *   maintainability Medium-1: this project already established the record-per-row convention via
 *   {@code usage.UsageRepository#fetchByModel()}, so this class follows it instead of introducing
 *   a second return shape for the same kind of query).
 */
public record ExcludedDocumentStatusCount(String status, long count) {
}
