package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] The vector-search axis of the hybrid pipeline: given a query string, returns the
 *   top {@code topK} chunks by embedding similarity.
 * [Rationale] Declared as an interface (Tech Review D-T4) so T7's orchestrator
 *   ({@code HybridDocumentRetriever}) can be unit-tested against a mock — no DB, no network —
 *   which is what makes AC-1 ("BM25 SQL never runs in vector mode") provable without an
 *   integration test. {@link VectorStoreSearchAdapter} is the only production implementation
 *   this card provides.
 * [Caution] Implementations must NOT apply a similarity threshold here (Tech Review D-T3):
 *   score-threshold filtering happens exactly once, in the orchestrator, right after RRF
 *   fusion. An adapter that pre-filters would make AC-4's threshold behavior depend on which
 *   axis a chunk came from.
 */
public interface VectorSearchPort {

    /**
     * [Purpose] Runs a vector similarity search and returns the raw (unfiltered, unfused)
     *   results for this axis.
     * [Caution] {@code topK} here is the per-axis fetch size (e.g. {@code app.search.vector-top-k}),
     *   not the final response size ({@code app.search.top-k}) — those are cut only after fusion
     *   and (optionally) reranking (Tech Review D-T3).
     */
    List<RetrievedChunk> search(String query, int topK);
}
