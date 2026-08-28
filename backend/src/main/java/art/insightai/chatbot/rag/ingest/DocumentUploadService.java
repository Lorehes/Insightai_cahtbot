package art.insightai.chatbot.rag.ingest;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentQuotaExceededException;
import art.insightai.chatbot.rag.domain.DocumentRepository;
import art.insightai.chatbot.rag.domain.DocumentTooLargeException;
import art.insightai.chatbot.rag.domain.DocumentUploadedEvent;
import art.insightai.chatbot.rag.domain.InvalidDocumentFilenameException;
import art.insightai.chatbot.rag.domain.UnsupportedDocumentTypeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * [Purpose] Business logic behind {@code POST /api/admin/documents} (document-ingestion Spec Tech
 *   Review T5): validates a multipart upload, creates the {@code documents} row in PENDING,
 *   writes the original bytes to disk, and publishes the event that hands off to T8's async
 *   ingestion pipeline.
 * [Rationale] Validation order follows AD-7 -- MIME whitelist, then size, then filename shape
 *   (review 2026-08-28 High-1), then the cumulative row-count quota, all before any {@code
 *   documents} row is inserted -- so a rejected upload never leaves a partial row
 *   (document-ingestion:AC-2, AC-3). The quota check mirrors {@code
 *   chat.service.ConversationService#createConversation}'s "count, then reject before insert"
 *   shape one Spec over. This class intentionally has no dependency on {@code DocumentParser}/
 *   {@code DocumentChunker}/{@code EmbeddingModel}/{@code VectorStore} -- AC-1 requires the upload
 *   response to precede any parsing or embedding, and the absence of those collaborators here is
 *   what makes that provable by a mock-based unit test rather than merely asserted.
 * [Caution] The row-count check and the eventual insert are not wrapped in one atomic
 *   check-and-insert (no {@code SELECT ... FOR UPDATE}, no unique constraint on the count) -- same
 *   accepted race as {@code ConversationService#createConversation}'s own [Side effects]: two
 *   concurrent uploads can both pass the count check and both insert, letting the row count exceed
 *   {@code app.ingest.max-document-count} by a small margin. Harmless at this project's demo scale
 *   (an unauthenticated admin endpoint with single-digit concurrent callers); revisit only if this
 *   deployment grows multi-instance. Separately, {@link #upload} holds one Hikari pooled
 *   connection for the whole {@code @Transactional} span, including the up-to-{@code
 *   app.ingest.max-file-size} (20MB) disk write -- an accepted trade-off of AD-7/AD-2/NR-3's fixed
 *   ordering (review 2026-08-28 performance Low-1), not something to restructure without revisiting
 *   those constraints; worth re-measuring if the connection pool (default size 10) is ever seen
 *   exhausted under concurrent uploads.
 */
@Service
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);

    /**
     * Mirrors {@code Document.filename}'s {@code varchar(255)} column (review 2026-08-28 High-1)
     * -- kept as a plain constant here, not a {@code @Size} annotation, because {@code
     * MultipartFile.getOriginalFilename()} is not a {@code @Valid}-checked request-body field (see
     * {@link InvalidDocumentFilenameException}'s [Rationale]). {@code String.length()} below
     * counts UTF-16 code units, while PostgreSQL's {@code varchar(255)} counts code points; code
     * point count is always &lt;= code unit count, so this bound is strictly more conservative
     * than the column -- a value that passes it can never overflow the column, even across
     * surrogate pairs (verified with {@code psql}, review 2026-08-28 Low-6). Do <strong>not</strong>
     * "relax" this to {@code originalFilename.codePointCount(0, originalFilename.length())} or a
     * UTF-8 byte-length check -- either would let through values this guard currently (safely,
     * if slightly over-eagerly) rejects, reopening the overflow path review 2026-08-28 High-1
     * closed.
     */
    private static final int MAX_ORIGINAL_FILENAME_LENGTH = 255;

    private final DocumentRepository documentRepository;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentUploadService(DocumentRepository documentRepository, AppProperties appProperties,
            ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.appProperties = appProperties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * [Purpose] Validates, persists, and stores the original bytes of one uploaded document, then
     *   publishes {@link DocumentUploadedEvent} to hand off to the async pipeline.
     * [Side effects] Inserts one {@code documents} row (status PENDING) and writes one file under
     *   {@code app.ingest.upload-dir}. Throws {@link UnsupportedDocumentTypeException} (400),
     *   {@link DocumentTooLargeException} (413), {@link InvalidDocumentFilenameException} (400),
     *   or {@link DocumentQuotaExceededException} (403) instead of doing either when a guard fails
     *   -- no row and no file are left behind in any of those four cases (document-ingestion:AC-2,
     *   AC-3). Beyond the four guards, {@code documentRepository.saveAndFlush} forces the {@code
     *   INSERT} to run -- and, if {@code filename} or any other column value is rejected by the
     *   database (e.g. a value that slips past this method's own guards), to fail -- before
     *   {@link #writeOriginalFile} ever executes, so a flush failure never reaches the file write
     *   at all. If the file write itself then fails after that successful flush, this method
     *   deletes any partially-written file and rethrows; because the flushed insert lives in this
     *   same still-open transaction, the rethrow's rollback undoes it too (AD-7). <strong>This
     *   covers only the flush-failure and file-write-failure windows</strong> (review 2026-08-28
     *   Low-1, narrowing an earlier version of this Javadoc that claimed unconditional coverage of
     *   "any later failure"): a failure between the successful file write and the eventual
     *   transaction <em>commit</em> (commit itself failing, or a listener on {@link
     *   DocumentUploadedEvent} throwing before commit -- see [Caution] below) still rolls back the
     *   row while the file stays on disk, and {@link #deleteQuietly}'s own delete is itself
     *   best-effort -- neither of those two windows has a compensating delete.
     * [Caution] This method publishes {@link DocumentUploadedEvent} via {@code
     *   ApplicationEventPublisher.publishEvent} from inside this {@code @Transactional} method on
     *   purpose (Tech Review AD-2/NR-3): T8's {@code @TransactionalEventListener(phase =
     *   AFTER_COMMIT)} listener is silently skipped -- no exception, no log -- if the publish call
     *   happens outside an active transaction. The symptom is "the upload succeeds (202) but the
     *   document stays PENDING forever." Do not move this call to a non-transactional method or
     *   call it after this method returns. {@code saveAndFlush} (rather than {@code save}) is what
     *   makes that AC-2/AC-3 "no row left behind" guarantee actually hold at the database level --
     *   see [Side effects] above; do not revert it to {@code save} without re-checking review
     *   2026-08-28 High-1's reproduction (a 256+ char filename previously committed a file to disk
     *   with zero {@code documents} rows ever inserted, silently defeating {@code
     *   app.ingest.max-document-count}). Separately (review 2026-08-28 Low-1): if T8's listener is
     *   ever wired as a plain {@code @EventListener} or any phase other than {@code AFTER_COMMIT}
     *   instead, and that listener throws, the exception fires <em>before</em> this transaction
     *   commits -- exactly the uncovered "failure before commit" window [Side effects] above now
     *   describes. The row rolls back but the file this method already wrote to {@code
     *   app.ingest.upload-dir} is not deleted by anything in this class. Keep the listener on
     *   {@code AFTER_COMMIT}; if that ever changes, a compensating delete needs to be added at the
     *   new call site, not assumed to already exist here.
     * [AC] document-ingestion:AC-1, document-ingestion:AC-2, document-ingestion:AC-3,
     *   document-ingestion:AC-4
     */
    @Transactional
    public Document upload(MultipartFile file) {
        AppProperties.Ingest ingest = appProperties.ingest();

        String mimeType = file.getContentType();
        if (mimeType == null || !ingest.allowedMimeTypes().contains(mimeType)) {
            throw new UnsupportedDocumentTypeException(mimeType);
        }

        long sizeBytes = file.getSize();
        if (sizeBytes > ingest.maxFileSize().toBytes()) {
            throw new DocumentTooLargeException(sizeBytes);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()
                || originalFilename.length() > MAX_ORIGINAL_FILENAME_LENGTH
                || containsControlCharacter(originalFilename)) {
            log.warn("업로드 파일명 검증 실패, 업로드 거부 (null={}, blank={}, length={}, controlChar={})",
                    originalFilename == null,
                    originalFilename != null && originalFilename.isBlank(),
                    originalFilename == null ? 0 : originalFilename.length(),
                    originalFilename != null && containsControlCharacter(originalFilename));
            throw new InvalidDocumentFilenameException();
        }

        long currentCount = documentRepository.count();
        if (currentCount >= ingest.maxDocumentCount()) {
            log.warn("문서 누적 상한 도달, 업로드 거부 (app.ingest.max-document-count={}, 현재 행 수={})",
                    ingest.maxDocumentCount(), currentCount);
            throw new DocumentQuotaExceededException();
        }

        Document document = documentRepository.saveAndFlush(
                new Document(originalFilename, mimeType, sizeBytes));

        writeOriginalFile(ingest.uploadDir(), document, file);

        eventPublisher.publishEvent(new DocumentUploadedEvent(document.getId()));
        return document;
    }

    /**
     * [Purpose] Writes {@code file}'s bytes to {@code {uploadDir}/{document.id}} -- the id-derived
     *   path DD-2 requires so that no caller-supplied filename ever reaches the filesystem path
     *   (document-ingestion:AC-4). {@code document.getId()} is a server-generated {@link
     *   java.util.UUID}, never derived from request input, so this path is structurally immune to
     *   {@code ../} traversal regardless of what {@code file.getOriginalFilename()} contains.
     * [Side effects] Ensures {@code uploadDir} exists ({@code Files.createDirectories}, review
     *   2026-08-28 High-3(a)) before writing -- a fresh environment's first upload no longer 500s
     *   with {@code NoSuchFileException} just because nobody has created {@code UPLOAD_DIR} on disk
     *   yet (document-ingestion:AC-1). Writes one file. On an {@link IOException} raised by {@code
     *   Files.copy} for a reason other than the target already existing, deletes any
     *   partially-written file before rethrowing as an unchecked exception, which triggers this
     *   transaction's rollback of the already-flushed {@code documents} row (AD-7, see {@link
     *   #upload}'s [Side effects]). A {@link FileAlreadyExistsException} is rethrown without that
     *   cleanup step (review 2026-08-28 security Low-4): the file at {@code target} was not created
     *   by this call in that case (an id collision, astronomically unlikely for a random {@link
     *   java.util.UUID}, or a pre-existing directory-creation conflict), so deleting it would remove
     *   a file this method never wrote.
     */
    private void writeOriginalFile(String uploadDir, Document document, MultipartFile file) {
        Path directory = Path.of(uploadDir);
        Path target = directory.resolve(document.getId().toString());
        try {
            Files.createDirectories(directory);
            Files.copy(file.getInputStream(), target);
        } catch (FileAlreadyExistsException e) {
            throw new UncheckedIOException(
                    "Failed to write uploaded file for document " + document.getId(), e);
        } catch (IOException e) {
            deleteQuietly(target);
            throw new UncheckedIOException(
                    "Failed to write uploaded file for document " + document.getId(), e);
        }
    }

    /**
     * [Purpose] Rejects any ISO control character (U+0000-U+001F, U+007F-U+009F) in {@code
     *   originalFilename} before it reaches {@link #upload}'s database insert.
     * [Rationale] PostgreSQL's {@code text}/{@code varchar} columns refuse U+0000 outright
     *   ({@code ERROR: null character not permitted}, verified with {@code psql}, review
     *   2026-08-28 security Low-4); without this guard such a filename would turn into a 500 from
     *   {@code documentRepository.saveAndFlush} instead of this method's own 400. Reachability
     *   from the real HTTP path is unconfirmed (whether an RFC 2231 encoded {@code filename*}
     *   parameter can smuggle a raw control character past Tomcat's header parsing was not
     *   checked) -- the guard closes the gap structurally regardless of that open question.
     */
    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    /**
     * [Purpose] Best-effort cleanup of a partially-written file after {@link #writeOriginalFile}
     *   fails.
     * [Side effects] Swallows any {@link IOException} from the delete itself -- the caller's
     *   original {@link UncheckedIOException} is what actually surfaces and triggers rollback.
     */
    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            log.warn("업로드 실패 후 부분 파일 삭제 실패, 수동 정리 필요할 수 있음 (path={})", target);
        }
    }
}
