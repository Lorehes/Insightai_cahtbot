package art.insightai.chatbot.eval.report;

/**
 * [Purpose] One row of the per-run snapshot of non-{@code INDEXED} document statuses whose chunks
 *   were excluded from search (AC-19a field 1) — how many excluded chunks belong to a document
 *   currently sitting at a given status.
 * [Rationale] Deliberately its own type rather than reusing {@code rag.retrieval}'s
 *   {@code ExcludedDocumentStatusCount}, even though the shape mirrors it: card E6 must not import
 *   from or depend on {@code rag/retrieval} (that package is under separate, concurrent rework —
 *   Tech Review card E6 boundary note), and E6's report logic must stay valid regardless of how
 *   {@code hybrid-rag} T14 shapes its own query result. Card E5 (not this card) is responsible for
 *   mapping the real {@code hybrid-rag} T14 query result into one of these per run before calling
 *   {@link EvalReportAggregator#aggregate(java.util.List, java.util.List)}. {@code status} is kept
 *   as a plain {@code String} — the same choice {@code rag.retrieval.ExcludedDocumentStatusCount}
 *   already made — rather than importing {@code rag.domain.DocumentStatus}, for the same
 *   decoupling reason.
 * [Caution] This count is a per-run snapshot (one query, taken once per sweep run), not a
 *   per-query value — see AC-19a/AC-19b's "(A) vs (B)" distinction. It is evidence that the
 *   exclusion filter is doing something, never a reason on its own to defer judgment (that is
 *   {@link EvalReportRow#retrievedNonIndexedChunkCount()}'s role).
 * [AC] eval-usage-dashboard:AC-19a
 */
public record EvalExcludedDocumentStatusCount(String status, long count) {

    /**
     * [Purpose] Rejects a negative {@code count} at construction time.
     * [Rationale] A negative count is never meaningful for a tally; rejecting it here matches the
     *   non-negative invariant already enforced on every other count field in this package (Low-6,
     *   2026-08-30 review).
     */
    public EvalExcludedDocumentStatusCount {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
    }
}
