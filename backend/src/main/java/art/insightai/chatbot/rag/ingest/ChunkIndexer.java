package art.insightai.chatbot.rag.ingest;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/**
 * [Purpose] Indexes a document's already-chunked, already-metadata-stamped {@link Document}s
 *   into {@code vector_store_bgem3} via Spring AI's {@link VectorStore} bean, so they become
 *   retrievable by both search axes this Spec's evidence ledger checks: pgvector cosine
 *   similarity (document-ingestion:AC-9) and ParadeDB BM25 (document-ingestion:AC-10).
 * [Rationale] Tech Review AD-1: chunk INSERT goes through {@link VectorStore#add(List)}
 *   unmodified -- unlike {@link ChunkRepository}'s delete/count, whose predicate had to be pinned
 *   to exactly {@code metadata->>'document_id'} (RK-6, 1.x/2.0 filter-expression signature
 *   uncertainty), plain {@code add(List<Document>)} carries no such uncertainty: confirmed via
 *   {@code javap} against the local {@code spring-ai-vector-store:2.0.1} jar (project rule P3,
 *   not copied from a 1.x example) --
 *   {@code public abstract void add(java.util.List<org.springframework.ai.document.Document>);}
 *   with no checked exception and no overload this card needs. {@code add()} batches the given
 *   chunks through whichever {@code EmbeddingModel} bean the context wires (the real
 *   {@code OpenAiEmbeddingModel} in production, {@code StubEmbeddingModel} in this Spec's
 *   integration tests per §6 "공통 전제") and writes {@code content}/{@code metadata}/
 *   {@code embedding} in one call -- there is nothing left for this class to do beyond that one
 *   delegation.
 * [Caution] Whether the resulting rows are actually retrievable by {@code <=>} similarity search
 *   (AC-9) or ParadeDB {@code |||} BM25 search (AC-10) is verified by this Spec's Integration test
 *   suite (§6 Test Cases table places both under "Integration", never "Unit") against a live
 *   ParadeDB container -- not by a mocked-{@link VectorStore} unit test, which cannot exercise
 *   either search path. This class also does not touch the {@code documents} table or its
 *   {@code status}/{@code chunk_count}/{@code indexed_at} columns, and does not call
 *   {@link ChunkRepository#countByDocumentId} to double-check the insert: that verification step
 *   -- "confirm indexing actually produced rows before marking a document INDEXED" -- is called
 *   out in {@link ChunkRepository}'s own Javadoc as T8's responsibility (AC-7's state-machine
 *   card), not T7's; duplicating it here would preempt that card's scope (project rule G2).
 *   Callers must pass chunks that already carry AC-8's five metadata keys ({@code document_id},
 *   {@code filename}, {@code chunk_index}, {@code dept}, {@code acl}, {@code page}) -- this class
 *   does not stamp or validate metadata itself; that is {@link ChunkMetadataBuilder}'s job (T6),
 *   which must run before this class is called.
 */
@Component
public class ChunkIndexer {

    private final VectorStore vectorStore;

    public ChunkIndexer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * [Purpose] Indexes the given chunks by delegating straight to {@link VectorStore#add(List)}
     *   (AD-1) -- no transformation, filtering, or batching of its own.
     * [Side effects] Inserts one row per element of {@code chunks} into {@code
     *   vector_store_bgem3}, embedding each chunk's text via whichever {@code EmbeddingModel}
     *   bean the calling context wires, as part of {@code add()}'s own batching.
     * [AC] document-ingestion:AC-9, document-ingestion:AC-10
     */
    public void index(List<Document> chunks) {
        vectorStore.add(chunks);
    }
}
