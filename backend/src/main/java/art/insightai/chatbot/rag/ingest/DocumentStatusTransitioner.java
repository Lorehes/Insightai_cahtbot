package art.insightai.chatbot.rag.ingest;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentRepository;
import art.insightai.chatbot.rag.domain.DocumentStatus;

/**
 * [Purpose] Applies one {@code documents.status} change per call and commits it immediately, so
 *   the intermediate states of a running ingestion (PENDING -&gt; PARSING -&gt; EMBEDDING -&gt;
 *   INDEXED) are observable from outside the pipeline while it is still running.
 * [Rationale] Tech Review AD-3: the pipeline is deliberately <i>not</i> wrapped in a single
 *   transaction. document-ingestion:AC-5 requires the transitions to be "기록된다" in order, and a
 *   single enclosing transaction would make every intermediate state invisible until the very end
 *   -- the AC would then be unverifiable by any external observer. {@code REQUIRES_NEW} also keeps
 *   a pooled JDBC connection from being held across the (slow, network-bound) embedding round
 *   trip; see {@link IngestAsyncConfig}'s [Caution] for why that budget matters here.
 *   <p>
 *   This class holds <b>no transition table of its own</b>. Whether a move is legal is decided by
 *   {@link Document#transitionTo} which in turn calls {@link DocumentStatus#canTransitionTo} --
 *   the single source of truth that {@code DocumentStatus}'s own Javadoc [Caution] explicitly
 *   forbids duplicating in this class.
 * [Side effects] Each public method opens and commits its own transaction against the
 *   {@code documents} table. A caller already inside a transaction does not roll these back.
 * [Caution] Because each call commits independently, a pipeline that dies mid-run leaves the last
 *   committed intermediate state in place (Spec §7 OQ-8 accepts that: delete and re-upload). The
 *   {@code REQUIRES_NEW} propagation only takes effect through the Spring proxy -- another method
 *   of this same class must never call these directly (self-invocation bypasses the proxy), and
 *   {@link DocumentIngestionPipeline} therefore injects this as a separate bean.
 * [AC] document-ingestion:AC-5, document-ingestion:AC-6, document-ingestion:AC-7
 */
@Service
public class DocumentStatusTransitioner {

    private static final Logger log = LoggerFactory.getLogger(DocumentStatusTransitioner.class);

    private final DocumentRepository documentRepository;

    public DocumentStatusTransitioner(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * [Purpose] Moves a document to {@code target} and commits, failing loudly on an illegal move.
     * [Rationale] Used for the two non-terminal steps of the happy path (PARSING, EMBEDDING).
     *   An illegal transition here is a programming error in the pipeline's step order, not a
     *   document-level failure, so it propagates rather than being swallowed -- the pipeline's own
     *   catch block converts it into a FAILED document (document-ingestion:AC-6).
     * [AC] document-ingestion:AC-5
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionTo(UUID documentId, DocumentStatus target) {
        Document document = require(documentId);
        document.transitionTo(target);
        documentRepository.save(document);
        log.debug("문서 상태 전이 (documentId={}, target={})", documentId, target);
    }

    /**
     * [Purpose] Terminal success transition: records INDEXED together with {@code chunk_count}
     *   and {@code indexed_at} in one committed step.
     * [Rationale] {@code indexedAt} is generated here rather than passed in, mirroring how
     *   {@link Document}'s constructor generates {@code createdAt}; {@link OffsetDateTime} keeps
     *   the offset, as this project's conventions require for time values.
     * [Caution] {@link Document#markIndexed} rejects {@code chunkCount < 1} with
     *   {@link IllegalArgumentException} because document-ingestion:AC-7 requires an INDEXED
     *   document to have at least one chunk. Callers must therefore never route a zero-chunk
     *   document here -- {@link DocumentIngestionPipeline} sends those to {@link #markFailed}
     *   instead; see that class's Javadoc for the reasoning.
     * [AC] document-ingestion:AC-5, document-ingestion:AC-7
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexed(UUID documentId, int chunkCount) {
        Document document = require(documentId);
        document.markIndexed(chunkCount, OffsetDateTime.now());
        documentRepository.save(document);
        log.debug("문서 색인 완료 (documentId={}, chunkCount={})", documentId, chunkCount);
    }

    /**
     * [Purpose] Terminal failure transition: records FAILED with a non-blank {@code error_message}.
     * [Rationale] Unlike {@link #transitionTo}, this method never throws for a state reason. It is
     *   only ever called from a {@code catch} block, and throwing there would replace the original
     *   failure with a secondary one and leave the document stuck in a non-terminal state. A
     *   document that is already terminal (INDEXED/FAILED) or that has since been deleted is
     *   therefore logged and skipped. The "is this move legal" question is still answered by
     *   {@link DocumentStatus#canTransitionTo} rather than by a hardcoded terminal-state list here.
     * [Side effects] Commits {@code status = FAILED} and {@code error_message}.
     * [Caution] Chunk cleanup is NOT done here -- {@link DocumentIngestionPipeline} deletes the
     *   document's chunks <i>before</i> calling this, so that any observer who sees FAILED already
     *   sees zero chunks (document-ingestion:AC-6). Moving the cleanup into this method would
     *   invert that ordering.
     * [AC] document-ingestion:AC-6
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID documentId, String errorMessage) {
        Optional<Document> found = documentRepository.findById(documentId);
        if (found.isEmpty()) {
            log.warn("실패 처리 대상 문서가 없어 건너뜀 (documentId={})", documentId);
            return;
        }
        Document document = found.get();
        if (!document.getStatus().canTransitionTo(DocumentStatus.FAILED)) {
            log.warn("이미 종결 상태라 실패 처리 건너뜀 (documentId={}, status={})",
                    documentId, document.getStatus());
            return;
        }
        document.markFailed(errorMessage);
        documentRepository.save(document);
        log.warn("문서 색인 실패 기록 (documentId={}, errorMessage={})", documentId, errorMessage);
    }

    /**
     * [Purpose] Loads the document or fails fast when it no longer exists.
     * [Rationale] A missing row on the success path means the document was deleted (T10) while the
     *   pipeline was running; continuing would silently lose the transition. Returning the managed
     *   instance lets the caller mutate it and rely on the explicit {@code save()} below, which
     *   takes the {@code merge()} path safely because {@code Document#isNew()} is already false
     *   after a load (see {@link Document}'s class-level [Caution]).
     */
    private Document require(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Document " + documentId + " no longer exists"));
    }
}
