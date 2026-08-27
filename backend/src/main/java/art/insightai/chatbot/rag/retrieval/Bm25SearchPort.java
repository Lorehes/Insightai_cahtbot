package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] The BM25 full-text-search axis of the hybrid pipeline: given a query string,
 *   returns the top {@code topK} chunks by ParadeDB BM25 rank.
 * [Rationale] Interface-only in this card (Tech Review D-T4): the implementation is T4's scope
 *   (ParadeDB adapter, one query covering rank/content/metadata per Tech Review D-T5, plus one
 *   {@code EXPLAIN} check). Declaring the contract now lets T7's orchestrator be built and
 *   tested against a mock before T4 lands.
 * [Caution] Same threshold rule as {@link VectorSearchPort}: no filtering inside the
 *   implementation, fusion output is filtered exactly once (Tech Review D-T3).
 */
public interface Bm25SearchPort {

    /**
     * [Purpose] Runs a BM25 full-text search and returns the raw (unfiltered, unfused) results
     *   for this axis.
     * [Caution] The query string must reach ParadeDB as a bound parameter, never string-
     *   concatenated into SQL (Tech Review D-T5).
     */
    List<RetrievedChunk> search(String query, int topK);
}
