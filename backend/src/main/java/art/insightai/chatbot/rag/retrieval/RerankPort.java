package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] The optional reranking stage: given the fused candidate list, returns up to
 *   {@code topK} of them reordered by a cross-encoder relevance score.
 * [Rationale] Interface-only in this card (Tech Review D-T4); the implementation (OpenRouter
 *   {@code cohere/rerank-v3.5} call) is T6's scope. Declaring the contract now lets T7's
 *   orchestrator implement the enabled/disabled branch (AC-6/AC-7) and the failure fallback
 *   (AC-8) against a mock, independent of T6.
 * [Caution] {@link RetrievedChunk#score} on the returned list must stay the RRF fusion score,
 *   not the rerank score (Tech Review D-T6) — the rerank call may only decide ordering and
 *   truncation, not overwrite {@code score}. A caller whose implementation reorders correctly
 *   but rewrites {@code score} would silently violate AC-11/D-T6 without any test in this card
 *   catching it, since this card ships no implementation.
 */
public interface RerankPort {

    /**
     * [Purpose] Reranks {@code candidates} against {@code query} and returns the top
     *   {@code topK} in the API's response order.
     * [Caution] Must throw on API error/timeout rather than swallow it — the orchestrator (T7),
     *   not this port, is responsible for catching that and falling back to the fused order
     *   with a WARN log (AC-8).
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
