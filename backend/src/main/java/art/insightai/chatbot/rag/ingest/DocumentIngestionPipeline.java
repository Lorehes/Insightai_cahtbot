package art.insightai.chatbot.rag.ingest;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentRepository;
import art.insightai.chatbot.rag.domain.DocumentStatus;

/**
 * [Purpose] Runs one uploaded document all the way through ingestion -- parse, chunk, stamp
 *   metadata, index, verify, finalize -- driving the {@code documents.status} state machine as it
 *   goes and compensating (deleting any chunks already written) when a step fails.
 * [Rationale] Tech Review card T8. The whole method is deliberately <b>not</b> transactional
 *   (AD-3): document-ingestion:AC-5 requires PENDING -&gt; PARSING -&gt; EMBEDDING -&gt; INDEXED
 *   to be recorded in that order and observable from outside, which only holds if each transition
 *   commits on its own -- that is {@link DocumentStatusTransitioner}'s job
 *   ({@code REQUIRES_NEW}). Each collaborator here already exists and is reused unchanged
 *   ({@link DocumentParser} T6, {@link DocumentChunker} T6, {@link ChunkMetadataBuilder} T6,
 *   {@link ChunkIndexer} T7, {@link ChunkRepository} T3); this class adds only the ordering,
 *   the failure compensation and the terminal bookkeeping.
 *   <p>
 *   <b>{@code chunk_count} is read back from the database, not taken from {@code chunks.size()}.</b>
 *   document-ingestion:AC-7 defines the expected value as "{@code metadata->>'document_id'} 로 센
 *   실제 행 수", so {@link ChunkRepository#countByDocumentId} -- which runs exactly that predicate
 *   -- is the only source that can make the recorded count true by construction rather than by
 *   assumption. It also doubles as the post-condition check that indexing really wrote rows.
 *   <p>
 *   <b>Where a zero-chunk document goes: FAILED, never INDEXED.</b> {@code Document#markIndexed}
 *   throws {@link IllegalArgumentException} for {@code chunkCount < 1}, so "INDEXED with 0 chunks"
 *   is not a reachable state, and AC-7 independently requires an INDEXED document to have at least
 *   one chunk. The only remaining question is which terminal state an empty document lands in, and
 *   FAILED is the only option that keeps every AC true at once: leaving it in a non-terminal state
 *   would strand it forever (Spec §7 OQ-8 offers no retry), and AC-6 already prescribes
 *   FAILED + non-blank {@code error_message} + zero chunks as the shape of "this document did not
 *   get indexed". This is a real path, not a theoretical one -- an empty file, a whitespace-only
 *   text file, or a file whose declared MIME type Tika cannot extract text from (AD-5 accepts
 *   {@code Content-Type} at face value, NR-5) all produce zero chunks. Two separate guards below
 *   cover it: an early one right after chunking (cheap -- it avoids paying for embeddings on a
 *   document that has nothing to embed) and the post-index count check (which also catches an
 *   indexer that silently wrote nothing). Both report through the same FAILED path, with distinct
 *   {@code error_message} values so an admin can tell "nothing to index" from "indexing produced
 *   nothing".
 * [Side effects] Commits several {@code documents} rows updates (via
 *   {@link DocumentStatusTransitioner}), reads the uploaded original file from
 *   {@code app.ingest.upload-dir}, inserts rows into {@code vector_store_bgem3}, and on failure
 *   deletes that document's rows from it. Calls the embedding model (a real, billed OpenRouter
 *   round trip in production) once per indexing attempt.
 * [Caution] This class is invoked from {@link DocumentUploadedEventListener} on the
 *   {@code ingestExecutor} pool; it must keep holding at most one pooled JDBC connection at a
 *   time or it breaks the arithmetic documented in {@link IngestAsyncConfig}'s [Caution]. That is
 *   why nothing here opens an enclosing transaction and why {@link ChunkRepository} /
 *   {@link ChunkIndexer} calls are sequential. It is also safe to call directly (synchronously)
 *   from a test, which is the intended way to verify the pipeline's behaviour without racing the
 *   executor.
 * [AC] document-ingestion:AC-5, document-ingestion:AC-6, document-ingestion:AC-7
 */
@Service
public class DocumentIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionPipeline.class);

    /**
     * [Purpose] Client-visible {@code error_message} for a failure in the parse/chunk stage.
     * [Rationale] Fixed English strings, not {@code exception.getMessage()}: {@code error_message}
     *   is surfaced verbatim by {@code GET /api/admin/documents} (document-ingestion:AC-11) on an
     *   unauthenticated endpoint (Spec §7 OQ-7), and a raw exception message can embed absolute
     *   filesystem paths or driver internals. This mirrors {@code GlobalExceptionHandler}'s
     *   established "fixed client string, operator detail goes to the log" shape (INC-007). The
     *   full stack trace is logged at ERROR at the catch site.
     */
    static final String PARSE_FAILURE_MESSAGE = "failed to parse the uploaded document";

    /** [Purpose] Client-visible {@code error_message} for a failure in the embed/index stage. */
    static final String INDEX_FAILURE_MESSAGE = "failed to embed and index the document";

    /** [Purpose] Client-visible {@code error_message} when parsing/chunking yielded no chunk at all. */
    static final String EMPTY_CONTENT_FAILURE_MESSAGE =
            "no indexable text could be extracted from the uploaded document";

    /** [Purpose] Client-visible {@code error_message} when indexing completed but wrote no row. */
    static final String EMPTY_INDEX_FAILURE_MESSAGE =
            "indexing completed without producing any searchable chunk";

    /**
     * [Purpose] Appended to the {@code error_message} when the compensating chunk delete itself
     *   failed, so the admin list shows that this document may still hold orphaned chunks.
     * [Rationale] Silently recording a plain FAILED in that case would assert
     *   document-ingestion:AC-6's "청크 0건" without it being true.
     */
    static final String CLEANUP_FAILED_SUFFIX = " (chunk cleanup also failed; manual cleanup required)";

    private final DocumentRepository documentRepository;
    private final DocumentStatusTransitioner statusTransitioner;
    private final DocumentParser documentParser;
    private final DocumentChunker documentChunker;
    private final ChunkMetadataBuilder chunkMetadataBuilder;
    private final ChunkIndexer chunkIndexer;
    private final ChunkRepository chunkRepository;
    private final AppProperties appProperties;

    public DocumentIngestionPipeline(DocumentRepository documentRepository,
                                     DocumentStatusTransitioner statusTransitioner,
                                     DocumentParser documentParser,
                                     DocumentChunker documentChunker,
                                     ChunkMetadataBuilder chunkMetadataBuilder,
                                     ChunkIndexer chunkIndexer,
                                     ChunkRepository chunkRepository,
                                     AppProperties appProperties) {
        this.documentRepository = documentRepository;
        this.statusTransitioner = statusTransitioner;
        this.documentParser = documentParser;
        this.documentChunker = documentChunker;
        this.chunkMetadataBuilder = chunkMetadataBuilder;
        this.chunkIndexer = chunkIndexer;
        this.chunkRepository = chunkRepository;
        this.appProperties = appProperties;
    }

    /**
     * [Purpose] Ingests the document with the given id, end to end.
     * [Rationale] Takes an id rather than an entity because it runs on a different thread and a
     *   later transaction than the upload that created the row ({@code DocumentUploadedEvent}
     *   carries only the id, AD-2) -- passing a detached entity across that boundary would risk
     *   acting on stale state (e.g. a document the admin deleted in the meantime).
     * [Side effects] See class-level [Side effects]. Never throws for a document-level failure:
     *   any {@link RuntimeException} from a step is converted into a FAILED document. A failure of
     *   the FAILED-recording step itself is still allowed to propagate, so it is not silently lost;
     *   since the T8 rework it is caught and logged one frame up, by
     *   {@code DocumentUploadedEventListener}'s task wrapper, rather than by Spring's async
     *   uncaught-exception handler (that handler no longer sits on this path -- the listener
     *   submits to {@code ingestExecutor} explicitly instead of using {@code @Async}).
     * [AC] document-ingestion:AC-5, document-ingestion:AC-6, document-ingestion:AC-7
     */
    public void ingest(UUID documentId) {
        Optional<Document> found = documentRepository.findById(documentId);
        if (found.isEmpty()) {
            log.warn("인제스천 대상 문서가 없어 건너뜀 (documentId={})", documentId);
            return;
        }
        Document document = found.get();

        String clientErrorMessage = PARSE_FAILURE_MESSAGE;
        try {
            statusTransitioner.transitionTo(documentId, DocumentStatus.PARSING);

            List<org.springframework.ai.document.Document> parsed =
                    documentParser.parse(originalFileResource(document));
            List<org.springframework.ai.document.Document> chunks = documentChunker.chunk(parsed);
            if (chunks.isEmpty()) {
                clientErrorMessage = EMPTY_CONTENT_FAILURE_MESSAGE;
                throw new IllegalStateException(
                        "Document " + documentId + " produced no chunk after parsing");
            }
            chunkMetadataBuilder.build(chunks, document);

            clientErrorMessage = INDEX_FAILURE_MESSAGE;
            statusTransitioner.transitionTo(documentId, DocumentStatus.EMBEDDING);
            chunkIndexer.index(chunks);

            int indexedChunkCount = chunkRepository.countByDocumentId(documentId);
            if (indexedChunkCount < 1) {
                clientErrorMessage = EMPTY_INDEX_FAILURE_MESSAGE;
                throw new IllegalStateException(
                        "Document " + documentId + " has no indexed chunk after indexing");
            }
            statusTransitioner.markIndexed(documentId, indexedChunkCount);
        } catch (RuntimeException e) {
            log.error("문서 인제스천 실패 (documentId={}, errorMessage={})",
                    documentId, clientErrorMessage, e);
            compensateAndFail(documentId, clientErrorMessage);
        }
    }

    /**
     * [Purpose] Removes any chunk this run already wrote, then records the document as FAILED.
     * [Rationale] The delete runs <b>before</b> the FAILED transition on purpose: document-ingestion
     *   :AC-6 says a FAILED document must have zero chunks, so an observer must never be able to
     *   see FAILED while orphaned chunks still exist. {@link ChunkRepository#deleteByDocumentId} is
     *   the shared T3 predicate ({@code metadata->>'document_id'}, AD-1) -- the same one AC-6 and
     *   AC-12 are judged by -- rather than a second hand-written copy of that SQL.
     * [Side effects] Deletes rows from {@code vector_store_bgem3} and commits a FAILED
     *   {@code documents} row.
     * [Caution] A failure of the delete itself must not prevent the FAILED transition, or the
     *   document would be stranded in PARSING/EMBEDDING with no way back (no retry path exists,
     *   Spec §7 OQ-3/OQ-8). It is caught, logged, and reflected in the recorded
     *   {@code error_message} via {@link #CLEANUP_FAILED_SUFFIX} instead of being hidden.
     * [AC] document-ingestion:AC-6
     */
    private void compensateAndFail(UUID documentId, String clientErrorMessage) {
        String errorMessage = clientErrorMessage;
        try {
            int deleted = chunkRepository.deleteByDocumentId(documentId);
            if (deleted > 0) {
                log.warn("실패한 문서의 부분 색인 청크 보상 삭제 (documentId={}, deleted={})",
                        documentId, deleted);
            }
        } catch (RuntimeException e) {
            log.error("실패한 문서의 청크 보상 삭제 실패, 수동 정리 필요 (documentId={})", documentId, e);
            errorMessage = clientErrorMessage + CLEANUP_FAILED_SUFFIX;
        }
        statusTransitioner.markFailed(documentId, errorMessage);
    }

    /**
     * [Purpose] Resolves the on-disk original saved at upload time, {@code {upload-dir}/{id}}
     *   (DD-2 / document-ingestion:AC-4).
     * [Rationale] The upload directory is read through {@link AppProperties} rather than from the
     *   environment directly, and the filename is derived from the server-generated id, never from
     *   caller input.
     *   <p>
     *   The path must be a <b>regular file that is not a symbolic link</b> (review 2026-08-28,
     *   T8 L-1). {@code Files.isRegularFile} follows links by default, so without
     *   {@link LinkOption#NOFOLLOW_LINKS} anyone able to replace {@code {upload-dir}/{id}} with a
     *   link would get the link target's contents parsed, indexed, and afterwards readable through
     *   the unauthenticated RAG endpoint -- the upload MIME allow-list never sees that file. Local
     *   write access to the upload directory is already required, so this is a defence in depth,
     *   not a remotely reachable hole. The rejection deliberately produces a plain
     *   {@link IllegalStateException} inside the caller's {@code try}, which lands on the existing
     *   {@link #PARSE_FAILURE_MESSAGE} path: no new status, no new client-visible string, and the
     *   admin list cannot tell a link from an unparsable file (it should not -- that distinction
     *   is operator information and stays in the log).
     *   <p>
     *   The same check subsumes the missing-file case, which used to be left to
     *   {@code TikaDocumentReader} to discover. The outcome is unchanged (FAILED with
     *   {@link #PARSE_FAILURE_MESSAGE}); it is now decided one frame earlier.
     * [Caution] The resource's filename is the bare UUID, with no extension. That does not weaken
     *   format detection: {@code TikaDocumentReader} constructs a fresh empty {@code Metadata} and
     *   never passes a resource-name hint to {@code AutoDetectParser} (verified in
     *   {@code spring-ai-tika-document-reader:2.0.1} sources), so detection is content-based
     *   regardless of the name. The name only ends up in the parsed document's {@code source}
     *   metadata key, which {@link ChunkMetadataBuilder} does not read -- it stamps
     *   {@code filename} from the {@link Document} entity instead.
     */
    private Resource originalFileResource(Document document) {
        Path path = Path.of(appProperties.ingest().uploadDir())
                .resolve(document.getId().toString());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Original file of document " + document.getId()
                    + " is missing or is not a regular file");
        }
        return new FileSystemResource(path);
    }
}
