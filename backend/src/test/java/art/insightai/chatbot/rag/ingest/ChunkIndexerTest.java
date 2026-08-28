package art.insightai.chatbot.rag.ingest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * [Purpose] Unit tests for {@link ChunkIndexer}'s single responsibility -- delegating to {@link
 *   VectorStore#add(List)} unchanged (AD-1). Whether the resulting rows are actually retrievable
 *   by vector similarity (document-ingestion:AC-9) or ParadeDB BM25 (document-ingestion:AC-10) is
 *   the Spec's Integration test suite's job (§6), which needs a live ParadeDB container and
 *   real/stub embeddings that a mocked {@link VectorStore} here cannot provide -- so neither test
 *   below carries an AC-9/AC-10 tag; see {@link ChunkIndexer}'s Javadoc for why.
 * [Rationale] {@link ChunkIndexer} has exactly one collaborator and no branching, so mock-verify
 *   is sufficient to fully characterize its behavior without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ChunkIndexerTest {

    @Mock
    private VectorStore vectorStore;

    @Test
    @DisplayName("[document-ingestion §5] index()는 받은 청크 리스트를 그대로 VectorStore.add()에 전달한다")
    void delegatesGivenChunksToVectorStoreAddUnchanged() {
        // given
        ChunkIndexer indexer = new ChunkIndexer(vectorStore);
        List<Document> chunks = List.of(
                Document.builder()
                        .text("첫 번째 청크 본문")
                        .metadata("document_id", UUID.randomUUID().toString())
                        .metadata("chunk_index", 0)
                        .build(),
                Document.builder()
                        .text("두 번째 청크 본문")
                        .metadata("document_id", UUID.randomUUID().toString())
                        .metadata("chunk_index", 1)
                        .build());

        // when
        indexer.index(chunks);

        // then: the exact same list instance/contents reach VectorStore.add(), and nothing else
        // is called on it (no delete/getName/etc.)
        verify(vectorStore).add(chunks);
        verifyNoMoreInteractions(vectorStore);
    }

    @Test
    @DisplayName("[document-ingestion §5] index()는 빈 청크 리스트도 그대로 VectorStore.add()에 전달한다 (별도 분기 없음)")
    void passesThroughEmptyChunkListWithoutSpecialCasing() {
        // given
        ChunkIndexer indexer = new ChunkIndexer(vectorStore);
        List<Document> emptyChunks = List.of();

        // when
        indexer.index(emptyChunks);

        // then: no guard/early-return -- an empty list still reaches add() as-is (interpreting
        // "0 chunks" as a failure condition is T8's state-machine scope, not this class's)
        verify(vectorStore).add(emptyChunks);
        verifyNoMoreInteractions(vectorStore);
    }
}
