package art.insightai.chatbot.rag.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentDeleteLockTimeoutException;
import art.insightai.chatbot.rag.domain.DocumentNotFoundException;
import art.insightai.chatbot.rag.domain.DocumentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link DocumentDeleteService#delete}'s cases: the successful delete of
 *   all three traces (document-ingestion:AC-12), the 404 for an unknown id with no side effects
 *   and no lock attempt (document-ingestion:AC-13, review 2026-08-30 T10 round 3 High-1), the
 *   idempotent tolerance of an already-missing original file, and the 503 raised when the
 *   per-document lock cannot be acquired within the configured timeout (review round 3
 *   Medium-1) -- with {@link DocumentRepository}/{@link ChunkRepository} mocked and a real temp
 *   directory standing in for {@code app.ingest.upload-dir}. Integration-level coverage against a
 *   real database (BM25/vector rows actually gone) is dev-qa-tester's scope (Spec §6 "DELETE 후
 *   3종 부재 검증", "DELETE 404 + 타 문서 청크 보존").
 * [Rationale] Mirrors {@code DocumentUploadServiceTest}'s mock-plus-real-temp-dir style one class
 *   over. {@link TestAppPropertiesFactory#builder()} supplies every {@link AppProperties} component
 *   this service does not read, so each test only overrides {@code ingest.uploadDir} (and, for the
 *   lock-timeout case, {@code ingest.deleteLockTimeout}). {@link #transactionManager} is a plain
 *   Mockito mock rather than a real {@code PlatformTransactionManager}: {@link
 *   org.springframework.transaction.support.TransactionTemplate#execute} only delegates
 *   begin/commit/rollback to it and always invokes the callback directly on the calling thread
 *   regardless of what {@code getTransaction}/{@code commit} do, so an unstubbed mock (no-op
 *   {@code commit}, {@code null} {@code TransactionStatus}) is sufficient to exercise {@link
 *   DocumentDeleteService#delete}'s business logic against the other mocks below without a real
 *   database. {@link #doesNotAttemptLockForUnknownId} and {@link
 *   #throwsLockTimeoutWhenAnotherThreadHoldsTheSameStripe} use a real {@link
 *   DocumentIngestLockRegistry} (or a Mockito mock of it, for the former) instead of the shared
 *   {@link #service()} helper, because both exercise the lock/stripe machinery itself rather than
 *   the transaction body.
 */
@ExtendWith(MockitoExtension.class)
class DocumentDeleteServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ChunkRepository chunkRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @TempDir
    private Path uploadDir;

    private DocumentDeleteService service() {
        return new DocumentDeleteService(documentRepository, chunkRepository, appProperties(),
                new DocumentIngestLockRegistry(), transactionManager);
    }

    private AppProperties appProperties() {
        return appProperties(Duration.ofSeconds(30));
    }

    private AppProperties appProperties(Duration deleteLockTimeout) {
        AppProperties.Ingest defaults = TestAppPropertiesFactory.valid().ingest();
        AppProperties.Ingest ingest = new AppProperties.Ingest(uploadDir.toString(), defaults.maxFileSize(),
                defaults.allowedMimeTypes(), defaults.chunkSize(), defaults.minChunkSizeChars(),
                defaults.maxDocumentCount(), deleteLockTimeout);
        return TestAppPropertiesFactory.builder().ingest(ingest).build();
    }

    @Test
    @DisplayName("[document-ingestion:AC-12] 존재하는 문서를 삭제하면 원본 파일·청크·행이 모두 사라진다")
    void deletesFileChunksAndRowForExistingDocument() throws IOException {
        // given
        Document document = new Document("report.txt", "text/plain", 42L);
        Path originalFile = uploadDir.resolve(document.getId().toString());
        Files.writeString(originalFile, "sample content");
        when(documentRepository.existsById(document.getId())).thenReturn(true);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(chunkRepository.deleteByDocumentId(document.getId())).thenReturn(3);

        // when
        service().delete(document.getId());

        // then
        assertThat(originalFile).doesNotExist();
        verify(chunkRepository).deleteByDocumentId(document.getId());
        verify(documentRepository).delete(document);
    }

    @Test
    @DisplayName("[document-ingestion:AC-13] 존재하지 않는 id 는 DocumentNotFoundException 을 던지고 "
            + "청크·행 삭제를 시도하지 않는다")
    void throwsNotFoundAndSkipsAnyDeleteForUnknownId() {
        // given
        UUID unknownId = UUID.randomUUID();
        when(documentRepository.existsById(unknownId)).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service().delete(unknownId))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(unknownId.toString());
        // never touching ChunkRepository.deleteByDocumentId for any id is exactly what makes an
        // unrelated document's chunks structurally unreachable from this call (AC-13's second
        // clause) -- the pre-lock existsById guard rejects the call before it ever learns which
        // id to scope a delete to.
        verify(chunkRepository, never()).deleteByDocumentId(any());
        verify(documentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-13] 존재하지 않는 id 는 락을 시도조차 하지 않는다 "
            + "(review 2026-08-30 T10 round 3 High-1 — 무인증 요청의 불필요한 락 경합 방지)")
    void doesNotAttemptLockForUnknownId() {
        // given
        UUID unknownId = UUID.randomUUID();
        when(documentRepository.existsById(unknownId)).thenReturn(false);
        DocumentIngestLockRegistry mockRegistry = mock(DocumentIngestLockRegistry.class);
        DocumentDeleteService service = new DocumentDeleteService(documentRepository, chunkRepository,
                appProperties(), mockRegistry, transactionManager);

        // when
        assertThatThrownBy(() -> service.delete(unknownId)).isInstanceOf(DocumentNotFoundException.class);

        // then: the pre-lock existsById guard rejects before the lock registry is ever touched --
        // this is the behavior that makes an unbounded stream of unknown ids harmless, now that
        // DocumentIngestLockRegistry itself is also a fixed-size stripe array rather than a
        // never-evicted per-id map (round 3 High-1's two independent layers of defense).
        verifyNoInteractions(mockRegistry);
        verify(documentRepository, never()).findById(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-17] 같은 문서 id 의 락을 다른 스레드가 쥐고 있어 타임아웃 안에 "
            + "풀리지 않으면 DocumentDeleteLockTimeoutException 을 던진다 (review round 3 Medium-1)")
    void throwsLockTimeoutWhenAnotherThreadHoldsTheSameStripe() throws InterruptedException {
        // given: a real DocumentIngestLockRegistry, with a second thread holding this exact
        // document's stripe (ReentrantLock is reentrant for the same thread, so the contention
        // must come from a different thread to actually block).
        Document document = new Document("busy.txt", "text/plain", 1L);
        when(documentRepository.existsById(document.getId())).thenReturn(true);
        DocumentIngestLockRegistry realRegistry = new DocumentIngestLockRegistry();
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            realRegistry.lock(document.getId());
            holderAcquired.countDown();
            try {
                releaseHolder.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                realRegistry.unlock(document.getId());
            }
        });
        holder.start();
        holderAcquired.await();

        try {
            DocumentDeleteService service = new DocumentDeleteService(documentRepository, chunkRepository,
                    appProperties(Duration.ofMillis(50)), realRegistry, transactionManager);

            // when / then
            assertThatThrownBy(() -> service.delete(document.getId()))
                    .isInstanceOf(DocumentDeleteLockTimeoutException.class)
                    .hasMessageContaining(document.getId().toString());
            // the transaction body (which reads the document authoritatively) never ran, because
            // the lock was never acquired -- confirms the 503 is raised before any DB side effect.
            verify(documentRepository, never()).findById(any());
            verify(chunkRepository, never()).deleteByDocumentId(any());
        } finally {
            releaseHolder.countDown();
            holder.join();
        }
    }

    @Test
    @DisplayName("[document-ingestion §5] 원본 파일이 이미 없어도(재시도 상황) 청크·행 삭제는 정상적으로 진행된다")
    void deletesChunksAndRowEvenWhenOriginalFileAlreadyMissing() {
        // given: no file written at uploadDir/{id} at all -- reproduces a retry after a previous
        // call's file-delete step already succeeded (see DocumentDeleteService's own [Rationale]).
        Document document = new Document("already-cleaned.txt", "text/plain", 7L);
        Path originalFile = uploadDir.resolve(document.getId().toString());
        assertThat(originalFile).doesNotExist();
        when(documentRepository.existsById(document.getId())).thenReturn(true);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(chunkRepository.deleteByDocumentId(document.getId())).thenReturn(0);

        // when
        service().delete(document.getId());

        // then
        verify(chunkRepository).deleteByDocumentId(document.getId());
        verify(documentRepository).delete(document);
    }

    // Note: DocumentDeleteService#deleteOriginalFileIfPresent's IOException-propagation branch
    // (a real I/O failure, as opposed to the file simply being absent) is intentionally not
    // exercised here. Forcing a genuine IOException out of Files.deleteIfExists portably across
    // this project's Windows/POSIX dev environments would require either a permission-bit trick or
    // a non-directory-parent trick, both of which DocumentUploadServiceTest's own
    // FailingInputStreamMultipartFile comment already documents as unreliable for this project --
    // the on-Windows behavior of deleteIfExists against a missing/blocked parent segment often
    // resolves to "not found" (a tolerated no-op) rather than a thrown exception, which would make
    // such a test assert a Windows-specific false negative rather than prove the branch.
}
