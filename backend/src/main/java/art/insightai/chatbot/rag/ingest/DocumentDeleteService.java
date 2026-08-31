package art.insightai.chatbot.rag.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentDeleteLockTimeoutException;
import art.insightai.chatbot.rag.domain.DocumentNotFoundException;
import art.insightai.chatbot.rag.domain.DocumentRepository;

/**
 * [Purpose] Business logic behind {@code DELETE /api/admin/documents/{id}} (document-ingestion
 *   Spec §5 Affected Surfaces, Tech Review T10): removes all three traces of a document -- the
 *   {@code documents} row, its chunks in {@code vector_store_bgem3}, and the original file under
 *   {@code app.ingest.upload-dir} -- or reports 404 if the id does not exist.
 * [Rationale] Reuses two collaborators the Tech Review already assigned this exact job to rather
 *   than re-implementing either: {@link ChunkRepository#deleteByDocumentId} (T3, AD-1) for the
 *   {@code metadata->>'document_id'}-scoped chunk delete -- the same predicate {@link
 *   DocumentIngestionPipeline#compensateAndFail} already uses for its own failure-path cleanup, so
 *   this class is the second and only other caller of that method -- and {@code {upload-dir}/{id}}
 *   (DD-2) for the original file's path, the same derivation {@code DocumentUploadService
 *   #writeOriginalFile} and {@code DocumentIngestionPipeline#originalFileResource} already use.
 *   <p>
 *   <b>Actual order of {@link #delete} (review 2026-08-30 T10 round 3, Medium-2 -- this paragraph
 *   previously described the reverse order and contradicted this class's own [Caution] below):
 *   existence check (cheap, pre-lock) &rarr; lock acquisition &rarr; transaction begin &rarr;
 *   existence re-check (authoritative) &rarr; original file delete &rarr; chunk delete &rarr; row
 *   delete &rarr; commit &rarr; lock release.</b> Two existence checks appear on purpose, not by
 *   duplication:
 *   <ul>
 *     <li>{@link #delete}'s first line calls {@link DocumentRepository#existsById} <i>before</i>
 *     acquiring {@link DocumentIngestLockRegistry}'s stripe. This id-that-does-not-exist is not
 *     unable to reach this method -- {@code DELETE /api/admin/documents/{id}} is unauthenticated
 *     (§7 OQ-7) -- so this check exists purely to fail cheaply for that case rather than to
 *     bound anything: {@link DocumentIngestLockRegistry} is now a fixed-size stripe array
 *     (review 2026-08-30 T10 round 3, High-1; see that class's own [Rationale]), so skipping this
 *     check would not let the lock registry grow -- it would only make an unknown id needlessly
 *     contend for a stripe another, real document's operation may be holding. This check is not
 *     authoritative: the row it inspects can still be deleted by a concurrent caller between this
 *     check and the lock acquisition below, which is exactly why the transaction re-checks
 *     existence rather than trusting this result.</li>
 *     <li>Inside the transaction, {@code documentRepository.findById(documentId).orElseThrow(...)}
 *     is the authoritative check -- the one whose result the rest of the method (file delete,
 *     chunk delete, row delete) actually acts on, and the one document-ingestion:AC-13's 404
 *     contract depends on. Everything from here through the row delete runs inside one
 *     {@link TransactionTemplate}-managed transaction (see [Caution] below for why a {@link
 *     TransactionTemplate} field rather than a {@code @Transactional} method annotation), so the
 *     existence check, the file delete, the chunk delete, and the row delete succeed or fail
 *     together -- there is no state where the row was confirmed to exist but the file, the
 *     chunks, or the row itself survive the delete, or vice versa. Note that the original file
 *     delete ({@link #deleteOriginalFileIfPresent}) is <i>inside</i> this transaction, immediately
 *     after the authoritative existence check, not before it and not outside it.</li>
 *   </ul>
 *   {@code Files.deleteIfExists} inside {@link #deleteOriginalFileIfPresent} is itself idempotent
 *   (a second call against an already-missing path is a successful no-op, not an error), so a
 *   caller who retries the same {@code DELETE} call after a failure that occurred before the
 *   transaction committed repeats the exact same operation against unchanged state (the failed
 *   transaction rolled back, so the row and chunks are still there and the file delete step is a
 *   no-op the second time if it already ran once): nothing needs to be undone by hand for either
 *   failure mode, a plain retry is always sufficient. The opposite order (database first, file
 *   last, outside the transaction) was rejected because it does not have this property: a
 *   file-delete failure after a committed database delete would leave an orphaned file with no
 *   {@code documents} row to retry against -- {@code DELETE} on that id would then 404
 *   (document-ingestion:AC-13's own contract), so the API offers no way to finish the cleanup.
 * [Side effects] Deletes at most one file under {@code app.ingest.upload-dir}, deletes zero or
 *   more rows from {@code vector_store_bgem3}, and deletes exactly one {@code documents} row.
 *   Nothing is soft-deleted -- unlike {@code chat.service.ConversationService#deleteConversation}
 *   (chat Spec A-3, physical delete out of scope there), this Spec's AC-12 requires the row itself
 *   to stop existing.
 * [Caution] <b>Lock boundary corrected (review 2026-08-30 T10 round 2 High-1).</b> The round-1 fix
 *   (see {@link DocumentIngestLockRegistry}'s own Javadoc) made {@link #delete} hold the AC-17
 *   mutex across its whole critical section, but round 1 still put that critical section inside a
 *   {@code @Transactional}-annotated {@link #delete} method. That is wrong for this specific case:
 *   Spring's {@code @Transactional} is implemented by a proxy that commits <i>after</i> the
 *   annotated method returns, so a {@code finally { unlock() }} written inside the method body runs
 *   <b>before</b> the proxy's commit -- there is a window, between this method returning and the
 *   proxy actually issuing {@code COMMIT}, during which the lock is already free but the delete is
 *   not yet durable. If {@link DocumentIngestionPipeline}'s chunk-commit step acquired the lock in
 *   that window, its {@code findById(documentId).isEmpty()} guard would see the still-uncommitted
 *   row as present and commit chunks for a document about to disappear -- the exact orphan AC-17
 *   exists to prevent, reopened one layer down.
 *   <p>
 *   The fix is to stop relying on the method-annotation proxy for this method and instead drive the
 *   transaction explicitly with a {@link TransactionTemplate}: {@link
 *   TransactionTemplate#execute} runs its callback <i>and</i> commits (or rolls back) before it
 *   returns control to the caller, so by the time {@link #delete}'s own {@code finally {
 *   unlock() }} runs, the commit (or rollback) has already happened on the same call stack -- there
 *   is no proxy boundary left for the unlock to race ahead of. This also removes the round-1
 *   self-invocation hazard {@code @Transactional} carries for free (a same-class call to a
 *   {@code @Transactional} method bypasses the proxy and silently runs non-transactionally): a
 *   {@link TransactionTemplate} field has no proxy to bypass in the first place. The template's
 *   propagation is pinned to {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} explicitly
 *   (review 2026-08-30 T10 round 3, Low-1) rather than left at the default {@code REQUIRED}, so
 *   that if a future caller ever invokes {@link #delete} from inside another transaction, this
 *   method still gets its own transaction that commits before {@link #delete} returns -- relying
 *   on the default would silently reopen the exact round-1 bug this [Caution] describes the moment
 *   such a caller appears, since a participated-in outer transaction commits on the outer
 *   caller's schedule, not this method's.
 *   <p>
 *   <b>Lock acquisition is now bounded (review 2026-08-30 T10 round 3, High-1 and Medium-1).</b>
 *   {@link #delete} calls {@link DocumentIngestLockRegistry#tryLock} with {@code
 *   AppProperties.Ingest#deleteLockTimeout} instead of the unbounded {@link
 *   DocumentIngestLockRegistry#lock} round 1/round 2 used, and throws {@link
 *   DocumentDeleteLockTimeoutException} (mapped to 503) on expiry -- see that exception class's
 *   own [Rationale] and {@code AppProperties.Ingest#deleteLockTimeout}'s [Rationale] for why an
 *   unauthenticated caller must not be able to park this request thread indefinitely behind a
 *   same-stripe ingestion.
 * [AC] document-ingestion:AC-12, document-ingestion:AC-13, document-ingestion:AC-17
 */
@Service
public class DocumentDeleteService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeleteService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final AppProperties appProperties;
    private final DocumentIngestLockRegistry ingestLockRegistry;
    private final TransactionTemplate transactionTemplate;
    private final Duration deleteLockTimeout;

    public DocumentDeleteService(DocumentRepository documentRepository, ChunkRepository chunkRepository,
            AppProperties appProperties, DocumentIngestLockRegistry ingestLockRegistry,
            PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.appProperties = appProperties;
        this.ingestLockRegistry = ingestLockRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.deleteLockTimeout = appProperties.ingest().deleteLockTimeout();
    }

    /**
     * [Purpose] Entry point for {@code DELETE /api/admin/documents/{id}}. Fails cheaply for an id
     *   that does not exist, then -- inside one lock-and-transaction critical section -- looks the
     *   document up again authoritatively (throwing 404 if it is already gone), deletes the
     *   original file, deletes its chunks, and deletes its row, in the order this class's Javadoc
     *   [Rationale] justifies.
     * [Side effects] See class-level [Side effects].
     * [Rationale] Deliberately <b>not</b> {@code @Transactional} -- see class-level [Caution] for
     *   why a method-annotation proxy's commit-after-return timing is exactly the bug round 2 of
     *   this card's review found, and why {@link TransactionTemplate#execute} (which commits before
     *   it returns) closes it. The lock is acquired (via {@link DocumentIngestLockRegistry#tryLock},
     *   review round 3 Medium-1) before {@link TransactionTemplate#execute} is called and released
     *   only in this method's own {@code finally}, after {@code execute} has already returned -- so
     *   {@link DocumentIngestLockRegistry#unlock} for this id is always called strictly after this
     *   id's delete transaction has committed or rolled back, never concurrently with it and never
     *   before it (document-ingestion:AC-17). The leading {@link DocumentRepository#existsById}
     *   call runs before the lock is even requested, purely as a cheap fast-fail for an id that
     *   cannot possibly need the lock -- see class-level [Rationale] for why this no longer serves
     *   any lock-registry-size purpose (round 3, High-1) now that {@link DocumentIngestLockRegistry}
     *   is a fixed-size stripe array.
     * [Caution] See class-level [Caution] on the lock/transaction boundary, the {@link
     *   DocumentIngestLockRegistry} stripe scheme, and its single-JVM-instance scope. <b>Test hook
     *   for the ordering claim above</b> (handed off to dev-qa-tester, 2R rework): this method
     *   offers no explicit seam for observing "unlock happened after commit" directly, but the
     *   ordering is verifiable without one, in either of two ways -- (1) spy {@link
     *   DocumentIngestLockRegistry} and, inside the stubbed {@code unlock()}, query {@code
     *   documents}/{@code vector_store_bgem3} through a connection independent of this call's
     *   persistence context (e.g. a raw {@code JdbcTemplate} query, not the same {@code
     *   EntityManager}) to confirm the row and its chunks are already gone at that exact moment --
     *   since {@link TransactionTemplate#execute} commits synchronously before returning, a fresh
     *   read-committed query only sees the row absent if the commit has truly already happened; or
     *   (2) register a {@link org.springframework.transaction.support.TransactionSynchronization}
     *   inside a test-only subclass or wrapping collaborator whose {@code afterCommit()} records a
     *   timestamp/flag, and assert that flag is already set by the time a spied {@code unlock()}
     *   runs. Route (1) needs no production seam and is the cheaper of the two.
     * [AC] document-ingestion:AC-12, document-ingestion:AC-13, document-ingestion:AC-17
     */
    public void delete(UUID documentId) {
        if (!documentRepository.existsById(documentId)) {
            throw new DocumentNotFoundException(documentId);
        }

        boolean acquired = ingestLockRegistry.tryLock(documentId, deleteLockTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new DocumentDeleteLockTimeoutException(documentId);
        }
        try {
            transactionTemplate.execute(status -> {
                Document document = documentRepository.findById(documentId)
                        .orElseThrow(() -> new DocumentNotFoundException(documentId));

                deleteOriginalFileIfPresent(documentId);

                int deletedChunks = chunkRepository.deleteByDocumentId(documentId);
                documentRepository.delete(document);
                log.info("문서 삭제 완료 (documentId={}, deletedChunks={})", documentId, deletedChunks);
                return null;
            });
        } finally {
            ingestLockRegistry.unlock(documentId);
        }
    }

    /**
     * [Purpose] Deletes the original file at {@code {upload-dir}/{id}} (DD-2), the same path
     *   {@code DocumentUploadService#writeOriginalFile} wrote it to.
     * [Rationale] {@code Files.deleteIfExists} rather than {@code Files.delete}: a missing file is
     *   not an error here -- it is the expected steady state on a retry after a previous call's
     *   file-delete step already succeeded (see class-level [Rationale]), and it is also reachable
     *   the first time for a document whose upload never got as far as the file write (a row that
     *   somehow survived without one is not a state this pipeline produces per {@code
     *   DocumentUploadService#upload}'s own [Side effects], but this method does not assume that
     *   invariant holds forever).
     * [Side effects] Deletes at most one file. Any {@link IOException} other than the file already
     *   being absent is wrapped in an {@link UncheckedIOException} and propagates, aborting {@link
     *   #delete} before either database delete runs.
     */
    private void deleteOriginalFileIfPresent(UUID documentId) {
        Path target = Path.of(appProperties.ingest().uploadDir()).resolve(documentId.toString());
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (!deleted) {
                log.warn("삭제 대상 원본 파일이 이미 없음 (documentId={}, path={})", documentId, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete original file for document " + documentId, e);
        }
    }
}
