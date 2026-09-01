package art.insightai.chatbot.rag.ingest;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentExtractionForbiddenException;
import art.insightai.chatbot.rag.domain.DocumentQuotaExceededException;
import art.insightai.chatbot.rag.domain.DocumentRepository;
import art.insightai.chatbot.rag.domain.DocumentTooLargeException;
import art.insightai.chatbot.rag.domain.DocumentUploadedEvent;
import art.insightai.chatbot.rag.domain.InvalidDocumentFilenameException;
import art.insightai.chatbot.rag.domain.UnsupportedDocumentTypeException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link DocumentUploadService#upload}'s guards (MIME whitelist —
 *   document-ingestion:AC-2, size — document-ingestion:AC-3, cumulative quota —
 *   document-ingestion:AC-15), successful-path response shape (document-ingestion:AC-1), and the
 *   id-derived storage path that structurally blocks path traversal (document-ingestion:AC-4),
 *   with {@link DocumentRepository}/{@link ApplicationEventPublisher} mocked and a real temp
 *   directory standing in for {@code app.ingest.upload-dir}. Integration-level coverage against a
 *   real database/filesystem is dev-qa-tester's scope.
 * [Rationale] {@link TestAppPropertiesFactory#builder()} supplies every {@link AppProperties}
 *   component this service does not read, so each test only overrides {@code ingest}. {@code
 *   documentRepository.saveAndFlush(...)} (review 2026-08-28 High-1(b) moved the production code
 *   from {@code save} to {@code saveAndFlush}) is stubbed to return its argument unchanged (mirrors
 *   {@code ConversationServiceTest}'s stub style for a save-then-return repository call) — this
 *   class assigns its own id in the constructor (see {@link Document}'s own [Rationale]), so the
 *   stub does not need to invent one.
 */
@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    private static final String ALLOWED_MIME_TYPE = "text/plain";
    private static final byte[] CONTENT = "sample content".getBytes();

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private Path uploadDir;
    private DocumentUploadService service;

    @BeforeEach
    void setUp() throws IOException {
        uploadDir = Files.createTempDirectory("document-upload-service-test");
        service = new DocumentUploadService(documentRepository, appProperties(defaultIngest()), eventPublisher);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(uploadDir)) {
            try (var stream = Files.walk(uploadDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best-effort test cleanup
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-1] 허용 MIME 파일 업로드 시 PENDING 상태의 Document 를 반환하고 이벤트를 발행한다")
    void uploadsAllowedFileAndPublishesEvent() {
        // given
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", ALLOWED_MIME_TYPE, CONTENT);

        // when
        Document result = service.upload(file);

        // then
        assertThat(result.getStatus().name()).isEqualTo("PENDING");
        assertThat(result.getFilename()).isEqualTo("notes.txt");
        assertThat(result.getMimeType()).isEqualTo(ALLOWED_MIME_TYPE);
        ArgumentCaptor<DocumentUploadedEvent> eventCaptor = ArgumentCaptor.forClass(DocumentUploadedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().documentId()).isEqualTo(result.getId());
    }

    // 아래 3건은 배치 리뷰 2026-09-01 security Low-1 / 사용자 결정 2026-09-02(추출 금지 PDF 거부)의
    // 증거다 -- document-ingestion:AC-20 (Done 이후 신설, 사용자 승인 2026-09-02).
    // PDF 는 테스트 안에서 PDFBox 로 생성한다(바이너리 픽스처 없음). MIME 은 허용값을 그대로 쓴다 --
    // 가드는 선언 Content-Type 이 아니라 %PDF- 매직바이트로 판별하므로(스푸핑 대비) 이 조합이
    // 오히려 판별 방식 자체를 증명한다. 뒤 2건(사용자 암호·일반 PDF 통과)은 AC-20 뒷문장
    // 「무엇을 거부하지 않는가」의 경계 증거다.
    @Test
    @DisplayName("[document-ingestion:AC-20] 추출 금지(owner-password) PDF 는 행·파일·이벤트 없이 DocumentExtractionForbiddenException 으로 거부된다")
    void rejectsExtractionForbiddenPdfWithoutRowFileOrEvent() throws IOException {
        // given: opens with the empty user password, but canExtractContent=false
        AccessPermission noExtract = new AccessPermission();
        noExtract.setCanExtractContent(false);
        byte[] pdf = pdfBytes(new StandardProtectionPolicy("owner-secret", "", noExtract));
        MockMultipartFile file = new MockMultipartFile("file", "protected.pdf", ALLOWED_MIME_TYPE, pdf);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(DocumentExtractionForbiddenException.class);
        verify(documentRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(listFiles(uploadDir)).isEmpty();
    }

    @Test
    @DisplayName("[document-ingestion:AC-20] 사용자 암호가 걸린(열리지 않는) PDF 는 업로드 가드의 대상이 아니다 -- 기존대로 업로드는 통과하고 파싱 단계에서 실패한다")
    void letsUserPasswordProtectedPdfThroughTheUploadGuard() throws IOException {
        // given: a real user password -- Loader.loadPDF("") cannot open it (InvalidPasswordException)
        byte[] pdf = pdfBytes(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "locked.pdf", ALLOWED_MIME_TYPE, pdf);

        // when
        Document result = service.upload(file);

        // then: the guard stays silent -- the existing PARSE_FAILURE path owns this case
        assertThat(result.getStatus().name()).isEqualTo("PENDING");
        verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));
    }

    @Test
    @DisplayName("[document-ingestion:AC-20] 암호가 없는 일반 PDF 는 추출 금지 가드를 그대로 통과한다")
    void letsPlainPdfThroughTheUploadGuard() throws IOException {
        // given
        byte[] pdf = pdfBytes(null);
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "plain.pdf", ALLOWED_MIME_TYPE, pdf);

        // when
        Document result = service.upload(file);

        // then
        assertThat(result.getStatus().name()).isEqualTo("PENDING");
        verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));
    }

    /** One-page PDF built in-memory; {@code policy} null = unencrypted. */
    private static byte[] pdfBytes(StandardProtectionPolicy policy) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            if (policy != null) {
                doc.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Non-recursive listing of {@code dir} -- upload-dir emptiness check for the reject cases. */
    private static List<Path> listFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.toList();
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-2] 허용 목록에 없는 MIME 타입은 UnsupportedDocumentTypeException 을 던지고 행을 만들지 않는다")
    void rejectsDisallowedMimeTypeWithoutCreatingRow() {
        // given
        MockMultipartFile file = new MockMultipartFile("file", "notes.hwp", "application/x-hwp", CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
        verify(documentRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-2] Content-Type 이 없는 업로드도 UnsupportedDocumentTypeException 으로 거부한다")
    void rejectsMissingContentTypeWithoutCreatingRow() {
        // given
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", null, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-3] app.ingest.max-file-size 초과 파일은 DocumentTooLargeException 을 던지고 행·파일을 남기지 않는다")
    void rejectsOversizedFileWithoutCreatingRowOrFile() {
        // given
        service = new DocumentUploadService(
                documentRepository,
                appProperties(ingestWithMaxFileSize(DataSize.ofBytes(4))),
                eventPublisher);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(DocumentTooLargeException.class);
        verify(documentRepository, never()).saveAndFlush(any());
        try (var stream = Files.list(uploadDir)) {
            assertThat(stream.toList()).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-15] 누적 문서 수가 max-document-count 이상이면 DocumentQuotaExceededException 을 던지고 행을 만들지 않는다")
    void rejectsUploadWhenDocumentCountReachesCeiling() {
        // given
        service = new DocumentUploadService(
                documentRepository,
                appProperties(ingestWithMaxDocumentCount(1)),
                eventPublisher);
        when(documentRepository.count()).thenReturn(1L);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(DocumentQuotaExceededException.class);
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-4] 원본 파일은 사용자 파일명이 아니라 document.id 로 upload-dir 바로 아래에 저장된다")
    void storesOriginalFileUnderIdDerivedPathIgnoringCallerFilename() {
        // given
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../../../etc/passwd", ALLOWED_MIME_TYPE, CONTENT);

        // when
        Document result = service.upload(file);

        // then
        Path expected = uploadDir.resolve(result.getId().toString());
        assertThat(expected).exists().hasBinaryContent(CONTENT);
        try (var stream = Files.list(uploadDir)) {
            List<Path> entries = stream.toList();
            assertThat(entries).containsExactly(expected);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-1] upload-dir 가 아직 없으면 최초 업로드가 스스로 디렉터리를 만들고 성공한다")
    void createsUploadDirOnFirstWriteWhenMissing() throws IOException {
        // given: upload-dir does not exist yet on disk -- the same shape as a fresh checkout's
        // UPLOAD_DIR before anyone has provisioned it (review 2026-08-28 High-3(a)).
        Path freshUploadDir = uploadDir.resolve("not-yet-created");
        assertThat(freshUploadDir).doesNotExist();
        service = new DocumentUploadService(
                documentRepository,
                appProperties(ingestWithUploadDir(freshUploadDir.toString())),
                eventPublisher);
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", ALLOWED_MIME_TYPE, CONTENT);

        // when
        Document result = service.upload(file);

        // then
        assertThat(freshUploadDir.resolve(result.getId().toString())).exists().hasBinaryContent(CONTENT);
    }

    @Test
    @DisplayName("[document-ingestion §5] 쓰기 대상 경로에 이미 파일이 있으면(id 충돌) 그 파일을 지우거나 "
            + "덮어쓰지 않고 예외를 그대로 전파한다")
    void propagatesWithoutFileCleanupWhenTargetAlreadyExists() throws IOException {
        // given: reproduces a genuine target-path collision -- writeOriginalFile's
        // FileAlreadyExistsException branch exists to protect a file that already sits at
        // {uploadDir}/{document.id} (an id collision, per that branch's own [Side effects]), not
        // to protect upload-dir itself from being a non-directory (review 2026-08-28 Medium-1/
        // Low-5: the previous version of this test pointed the whole upload-dir override at a
        // plain file, which only exercised Files.createDirectories's own FileAlreadyExistsException
        // -- a different failure mode -- and its lone assertion checked that unrelated path, giving
        // the test no power to detect a regression in the branch it claimed to cover). The
        // document's id is a server-generated java.util.UUID assigned inside Document's own
        // constructor (see Document's [Rationale]), so it cannot be known ahead of time to
        // pre-place a colliding file before calling upload() -- instead, the saveAndFlush stub
        // creates the colliding file itself once the real id is known, immediately before
        // writeOriginalFile would write to that same path.
        byte[] preExistingContent =
                "pre-existing file at the target path, must survive the collision".getBytes(StandardCharsets.UTF_8);
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            Files.write(uploadDir.resolve(document.getId().toString()), preExistingContent);
            return document;
        });
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file)).isInstanceOf(UncheckedIOException.class);
        verify(eventPublisher, never()).publishEvent(any());
        // then: the file that was already sitting at the target path is untouched -- not deleted
        // (writeOriginalFile's FileAlreadyExistsException branch does not call deleteQuietly) and
        // not overwritten with this call's own CONTENT bytes (Files.copy has no REPLACE_EXISTING
        // option) -- this discriminates the branch under test from the generic-IOException branch
        // below, which does delete on the way out.
        try (var stream = Files.list(uploadDir)) {
            List<Path> entries = stream.toList();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0)).hasBinaryContent(preExistingContent);
        }
    }

    @Test
    @DisplayName("[document-ingestion §5] 쓰기 도중 일반 IOException이 발생하면 부분 파일을 삭제하고 예외를 전파한다")
    void deletesPartialFileAndPropagatesOnGenericWriteFailure() throws IOException {
        // given: a MultipartFile whose input stream fails on the very first read -- Files.copy
        // creates (and opens) the target file before it starts reading from the source, so by the
        // time this read failure surfaces, a real 0-byte partial file exists at {uploadDir}/
        // {document.id}. This reproduces writeOriginalFile's *other* catch branch (a generic
        // IOException, not FileAlreadyExistsException), which calls deleteQuietly before
        // rethrowing (review 2026-08-28 round 2 "부수 관찰": this branch lost its only test case
        // during this card's High-1/High-3 rework, when the old
        // propagatesAndCleansUpWhenFileWriteFails scenario -- a merely-missing directory -- stopped
        // reproducing a failure at all now that writeOriginalFile self-heals missing directories).
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MultipartFile file = new FailingInputStreamMultipartFile(
                new MockMultipartFile("file", "notes.txt", ALLOWED_MIME_TYPE, CONTENT));

        // when / then
        assertThatThrownBy(() -> service.upload(file)).isInstanceOf(UncheckedIOException.class);
        verify(eventPublisher, never()).publishEvent(any());
        // then: the partial file writeOriginalFile created before the read failure is gone
        try (var stream = Files.list(uploadDir)) {
            assertThat(stream.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("[document-ingestion §5] saveAndFlush 실패 시 파일을 쓰지 않고 예외를 그대로 전파한다")
    void doesNotWriteFileWhenSaveAndFlushFails() throws IOException {
        // given: simulates any database-level rejection of the row (e.g. a constraint violation
        // that slips past this method's own guards) -- review 2026-08-28 High-1(b)'s reproduction
        // showed that with plain save() this left an orphaned file on disk with zero documents
        // rows ever inserted (bypassing app.ingest.max-document-count). saveAndFlush moves the
        // INSERT ahead of the file write so that guarantee no longer depends on a compensating
        // delete.
        when(documentRepository.saveAndFlush(any(Document.class)))
                .thenThrow(new DataIntegrityViolationException("simulated insert failure"));
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(eventPublisher, never()).publishEvent(any());
        try (var stream = Files.list(uploadDir)) {
            assertThat(stream.toList()).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("[document-ingestion §5] 파일명이 없으면 InvalidDocumentFilenameException 을 던지고 행을 만들지 않는다")
    void rejectsNullFilenameWithoutCreatingRow() {
        // given
        MockMultipartFile file = new MockMultipartFile("file", null, ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(InvalidDocumentFilenameException.class);
        verify(documentRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("[document-ingestion §5] 파일명이 공백이면 InvalidDocumentFilenameException 을 던지고 행을 만들지 않는다")
    void rejectsBlankFilenameWithoutCreatingRow() {
        // given
        MockMultipartFile file = new MockMultipartFile("file", "   ", ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(InvalidDocumentFilenameException.class);
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[document-ingestion §5] 파일명이 255자를 초과하면 InvalidDocumentFilenameException 을 던지고 "
            + "행도 파일도 만들지 않는다")
    void rejectsOverlongFilenameWithoutCreatingRowOrFile() {
        // given: 256 chars, one past Document.filename's varchar(255) column width (review
        // 2026-08-28 High-1 reproduction: previously this reached Files.copy and DataIntegrity-
        // ViolationException at flush, leaving an orphaned file with no documents row).
        String overlongFilename = "a".repeat(256);
        MockMultipartFile file = new MockMultipartFile("file", overlongFilename, ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(InvalidDocumentFilenameException.class);
        verify(documentRepository, never()).saveAndFlush(any());
        try (var stream = Files.list(uploadDir)) {
            assertThat(stream.toList()).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("[document-ingestion §5] 파일명에 제어문자(U+0000)가 있으면 InvalidDocumentFilenameException 을 던지고 "
            + "행을 만들지 않는다")
    void rejectsFilenameContainingControlCharacterWithoutCreatingRow() {
        // given: U+0000 trips containsControlCharacter's Character.isISOControl guard (review
        // 2026-08-28 Low-1) -- before this test, no test anywhere in this repository exercised
        // that guard at all, so deleting its condition from upload() would have passed the whole
        // suite silently (the filename would instead have reached documentRepository.saveAndFlush
        // and failed with PostgreSQL's own null-character rejection, a 500 instead of this
        // method's own 400 -- see containsControlCharacter's [Rationale]).
        MockMultipartFile file = new MockMultipartFile("file", "notes .txt", ALLOWED_MIME_TYPE, CONTENT);

        // when / then
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(InvalidDocumentFilenameException.class);
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[document-ingestion §5] 파일명이 정확히 255자면 허용된다")
    void acceptsFilenameAtExactly255Chars() {
        // given: the boundary itself must still succeed -- only length > 255 is rejected.
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String filenameAt255 = "a".repeat(255);
        MockMultipartFile file = new MockMultipartFile("file", filenameAt255, ALLOWED_MIME_TYPE, CONTENT);

        // when
        Document result = service.upload(file);

        // then
        assertThat(result.getFilename()).isEqualTo(filenameAt255);
    }

    private AppProperties.Ingest defaultIngest() {
        return new AppProperties.Ingest(
                uploadDir.toAbsolutePath().toString(),
                DataSize.ofMegabytes(20),
                List.of(ALLOWED_MIME_TYPE, "application/pdf"),
                2000,
                500,
                100,
                Duration.ofSeconds(30));
    }

    private AppProperties appProperties(AppProperties.Ingest ingest) {
        return TestAppPropertiesFactory.builder().ingest(ingest).build();
    }

    /**
     * [Purpose] Test-local helpers so each case only restates the one field it varies, instead of
     *   repeating {@link AppProperties.Ingest}'s full 7-argument constructor inline.
     */
    private AppProperties.Ingest ingestWithMaxFileSize(DataSize maxFileSize) {
        AppProperties.Ingest base = defaultIngest();
        return new AppProperties.Ingest(base.uploadDir(), maxFileSize, base.allowedMimeTypes(),
                base.chunkSize(), base.minChunkSizeChars(), base.maxDocumentCount(), base.deleteLockTimeout());
    }

    private AppProperties.Ingest ingestWithMaxDocumentCount(int maxDocumentCount) {
        AppProperties.Ingest base = defaultIngest();
        return new AppProperties.Ingest(base.uploadDir(), base.maxFileSize(), base.allowedMimeTypes(),
                base.chunkSize(), base.minChunkSizeChars(), maxDocumentCount, base.deleteLockTimeout());
    }

    private AppProperties.Ingest ingestWithUploadDir(String uploadDirOverride) {
        AppProperties.Ingest base = defaultIngest();
        return new AppProperties.Ingest(uploadDirOverride, base.maxFileSize(), base.allowedMimeTypes(),
                base.chunkSize(), base.minChunkSizeChars(), base.maxDocumentCount(), base.deleteLockTimeout());
    }

    /**
     * [Purpose] Wraps a real {@link MultipartFile} but returns an {@link InputStream} that fails
     *   on its very first {@code read()}, for {@link
     *   #deletesPartialFileAndPropagatesOnGenericWriteFailure} -- reproduces a generic
     *   {@link IOException} mid-{@code Files.copy} without relying on filesystem permission tricks
     *   (unreliable across this project's Windows/POSIX dev environments).
     * [Rationale] {@code Files.copy(InputStream, Path, CopyOption...)} opens/creates the target
     *   file before it reads any bytes from the source, so failing on the first read still leaves
     *   a real partial (0-byte) file on disk for {@code writeOriginalFile}'s {@code deleteQuietly}
     *   branch to clean up -- unlike a failure inside {@link MultipartFile#getInputStream()}
     *   itself, which would fail before {@code Files.copy} ever creates that file.
     */
    private static final class FailingInputStreamMultipartFile implements MultipartFile {

        private final MultipartFile delegate;

        private FailingInputStreamMultipartFile(MultipartFile delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getOriginalFilename() {
            return delegate.getOriginalFilename();
        }

        @Override
        public String getContentType() {
            return delegate.getContentType();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public long getSize() {
            return delegate.getSize();
        }

        @Override
        public byte[] getBytes() throws IOException {
            return delegate.getBytes();
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("simulated mid-copy read failure");
                }
            };
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            delegate.transferTo(dest);
        }
    }
}
