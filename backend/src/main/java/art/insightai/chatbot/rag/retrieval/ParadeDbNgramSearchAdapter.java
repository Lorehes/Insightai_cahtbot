package art.insightai.chatbot.rag.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] {@link NgramSearchPort} implementation: BM25 over the character 2-3-gram view
 *   ({@code alias=content_ngram}, {@code V4__add_ngram_view_to_bm25_index.sql}) of the same
 *   {@code vector_store_bgem3} rows the lindera axis reads.
 * [Rationale] Mirrors {@link ParadeDbBm25SearchAdapter} deliberately -- same table, same row shape,
 *   same orphan exclusion (hybrid-rag:AC-15), same bound-parameter rule -- and differs in exactly
 *   two places: the left-hand side is {@code content::pdb.alias('content_ngram')} (ParadeDB's
 *   documented way to target an aliased field, {@code docs/documentation/tokenizers/
 *   multiple-per-field.mdx}, verified on the live container 2026-09-03), and there is no glossary
 *   phrase predicate. The whole query goes to {@code |||} (match any gram): rare grams such as
 *   {@code 트에}/{@code 에이아} carry high IDF and dominate the score, which is what lets an
 *   unknown proper noun or a typo surface here when the dictionary-bound lindera axis misses it
 *   (probe report §4). {@code &&&} (all grams) was rejected for full-sentence queries because it
 *   requires every cross-word gram of the sentence to appear verbatim -- it returned 0 rows for
 *   every natural-language question tried. The glossary phrase predicate stays on the lindera axis
 *   only: it exists to fix that tokenizer's particle split, which n-grams do not suffer from.
 * [Caution] {@code pdb.score} on this view is on a different (roughly 3x larger) scale than the
 *   lindera view's; {@code app.ngram.raw-score-threshold} is the only threshold that may be
 *   applied to these results. The query text and every excluded id MUST stay bound parameters
 *   (see {@link ParadeDbBm25SearchAdapter}'s class-level [Caution] -- {@code |||} treats a bound
 *   value as a literal, {@code @@@} would parse it as query DSL). Requires the V4 index: without
 *   the {@code content_ngram} alias PostgreSQL raises an error on every call, so
 *   {@code app.ngram.enabled} must be false whenever V4 is rolled back.
 * [AC] hybrid-rag:AC-13
 * [AC] hybrid-rag:AC-15
 */
@Component
public class ParadeDbNgramSearchAdapter implements NgramSearchPort {

    private static final String NGRAM_SEARCH_SQL =
            "SELECT id, content, metadata, pdb.score(id) AS score "
                    + "FROM vector_store_bgem3 "
                    + "WHERE content::pdb.alias('content_ngram') ||| ? "
                    + "ORDER BY score DESC "
                    + "LIMIT ?";

    private static final String EXCLUSION_PREDICATE_TEMPLATE = "metadata->>'document_id' NOT IN (%s)";

    private final JdbcTemplate jdbcTemplate;
    private final NonIndexedDocumentLookup nonIndexedDocumentLookup;
    private final ParadeDbChunkRowMapper rowMapper = new ParadeDbChunkRowMapper();

    public ParadeDbNgramSearchAdapter(JdbcTemplate jdbcTemplate, NonIndexedDocumentLookup nonIndexedDocumentLookup) {
        this.jdbcTemplate = jdbcTemplate;
        this.nonIndexedDocumentLookup = nonIndexedDocumentLookup;
    }

    /**
     * [Purpose] Runs the n-gram axis query, excluding chunks of non-{@code INDEXED} documents.
     * [Caution] Parameter order must match predicate order: query, excluded ids, limit. One
     *   single-table query, one {@code PreparedStatement}, plus the exclusion lookup's own
     *   parameterless statement (same shape as the lindera adapter).
     */
    @Override
    public List<RetrievedChunk> search(String query, int topK) {
        List<String> excludedDocumentIds =
                nonIndexedDocumentLookup.findAll().stream().map(NonIndexedDocument::id).toList();
        List<Object> params = new ArrayList<>(excludedDocumentIds.size() + 2);
        params.add(query);
        String sql = NGRAM_SEARCH_SQL;
        if (!excludedDocumentIds.isEmpty()) {
            String placeholders =
                    excludedDocumentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
            sql = sql.replace("ORDER BY",
                    "AND " + String.format(EXCLUSION_PREDICATE_TEMPLATE, placeholders) + " ORDER BY");
            params.addAll(excludedDocumentIds);
        }
        params.add(topK);
        return jdbcTemplate.query(sql, rowMapper, params.toArray());
    }
}
