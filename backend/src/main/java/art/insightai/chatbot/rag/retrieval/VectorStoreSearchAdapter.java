package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] {@link VectorSearchPort} implementation that delegates to Spring AI's
 *   {@link VectorStore} bean, which application.yaml wires to the {@code vector_store_bgem3}
 *   pgvector table (Tech Review D-T1/D-T4).
 * [Rationale] {@link VectorStore#similaritySearch(SearchRequest)} (declared on
 *   {@code VectorStoreRetriever}, which {@code VectorStore} extends in Spring AI 2.0.1 —
 *   verified via {@code javap} against the local jar, since D-T1 flagged this API as
 *   unconfirmed) already handles the pgvector HNSW query; this adapter's only job is
 *   query/topK -> {@link SearchRequest} translation in, and Spring AI {@link Document} ->
 *   {@link RetrievedChunk} translation out.
 * [Caution] {@code similarityThresholdAll()} is intentional, not an oversight: Tech Review
 *   D-T3 requires the vector axis to apply no threshold of its own so that AC-4's
 *   {@code score-threshold} filter runs exactly once, in the orchestrator, after RRF fusion.
 *   Do not add {@code .similarityThreshold(...)} here.
 */
@Component
public class VectorStoreSearchAdapter implements VectorSearchPort {

    private final VectorStore vectorStore;

    public VectorStoreSearchAdapter(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<RetrievedChunk> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThresholdAll()
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(this::toRetrievedChunk)
                .toList();
    }

    /**
     * [Purpose] Maps one Spring AI {@link Document} search hit to this project's
     *   axis-agnostic {@link RetrievedChunk}.
     * [Caution] {@code getScore()} is {@link Double} and can be {@code null} per the Spring AI
     *   contract; defaulting to {@code 0.0} here keeps {@link RetrievedChunk#score} a primitive
     *   {@code double} rather than pushing a null-check onto every downstream consumer (RRF
     *   fusion, T7).
     */
    private RetrievedChunk toRetrievedChunk(Document document) {
        Double score = document.getScore();
        return new RetrievedChunk(
                document.getId(),
                document.getText(),
                document.getMetadata(),
                score != null ? score : 0.0
        );
    }
}
