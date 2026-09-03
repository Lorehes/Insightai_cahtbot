package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] The character n-gram keyword axis -- the second lexical view of {@code content}
 *   ({@code alias=content_ngram}, V4 migration), queried separately from {@link Bm25SearchPort}.
 * [Rationale] A separate port rather than a flag on {@link Bm25SearchPort} because the two views
 *   have different score scales and different thresholds ({@code app.ngram.raw-score-threshold}
 *   vs {@code app.search.bm25-raw-score-threshold}) and are fused as distinct axes; {@link
 *   HybridDocumentRetriever} must be able to skip this one entirely when {@code app.ngram.enabled}
 *   is false. See {@link art.insightai.chatbot.common.config.AppProperties.Ngram}.
 */
public interface NgramSearchPort {

    /**
     * [Purpose] Returns up to {@code topK} chunks ranked by n-gram BM25 score, highest first, with
     *   {@link RetrievedChunk#score()} on the n-gram view's own {@code pdb.score} scale.
     */
    List<RetrievedChunk> search(String query, int topK);
}
