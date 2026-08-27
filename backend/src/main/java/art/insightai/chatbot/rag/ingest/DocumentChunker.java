package art.insightai.chatbot.rag.ingest;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.common.config.AppProperties;

/**
 * [Purpose] Splits parsed {@link Document}s into token-bounded chunks using Spring AI's {@link
 *   TokenTextSplitter}, sized from {@code app.ingest.chunk-size} / {@code
 *   app.ingest.min-chunk-size-chars}.
 * [Rationale] Tech Review AD-10: {@code TokenTextSplitter(chunkSize=2000, minChunkSizeChars=500)},
 *   no custom splitter. {@link TokenTextSplitter} has no overlap parameter (project CLAUDE.md
 *   fact #11) -- the Spec's OQ-1 answer widens the chunk instead of simulating overlap.
 * [Caution] Every other {@link TokenTextSplitter.Builder} option (encoding type,
 *   minChunkLengthToEmbed, maxNumChunks, keepSeparator, punctuationMarks) is left at the
 *   library's own default on purpose -- AD-10 rules out inventing additional tuning here.
 */
@Component
public class DocumentChunker {

    private final TokenTextSplitter splitter;

    public DocumentChunker(AppProperties appProperties) {
        AppProperties.Ingest ingest = appProperties.ingest();
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(ingest.chunkSize())
                .withMinChunkSizeChars(ingest.minChunkSizeChars())
                .build();
    }

    /**
     * [Purpose] Splits the given parsed {@link Document}(s) into token-bounded chunks.
     * [Caution] The returned list's order determines the {@code chunk_index} {@link
     *   ChunkMetadataBuilder} assigns downstream -- callers must not reorder it in between.
     */
    public List<Document> chunk(List<Document> documents) {
        return splitter.split(documents);
    }
}
