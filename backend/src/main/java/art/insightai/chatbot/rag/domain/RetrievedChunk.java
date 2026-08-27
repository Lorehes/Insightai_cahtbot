package art.insightai.chatbot.rag.domain;

import java.util.Map;

/**
 * [Purpose] A single chunk returned by one retrieval axis (vector or BM25) before fusion,
 *   filtering, or reranking are applied.
 * [Rationale] Kept axis-agnostic on purpose: {@code id}/{@code content}/{@code metadata}/
 *   {@code score} are the only fields both {@link art.insightai.chatbot.rag.retrieval.VectorSearchPort}
 *   and {@link art.insightai.chatbot.rag.retrieval.Bm25SearchPort} can produce without knowing
 *   about each other (Tech Review D-T2 — fusion happens in Java, not SQL, so both axes must
 *   speak the same intermediate type). {@code score} is the axis's own similarity/rank score,
 *   not the RRF-fused score — {@link ReciprocalRankFusion} (T7) computes that separately and
 *   {@link SourceRef#score} is the one exposed to callers (Tech Review D-T6).
 * [Caution] {@code id} is the {@code vector_store_bgem3.id} row id (a chunk id), not
 *   {@code documents.id}. The source document id lives inside {@code metadata} under the
 *   {@code document_id} key (see {@link DocumentRepository}); mapping it out to
 *   {@link SourceRef#documentId} is T8's job, not this record's.
 */
public record RetrievedChunk(
        String id,
        String content,
        Map<String, Object> metadata,
        double score
) {
}
