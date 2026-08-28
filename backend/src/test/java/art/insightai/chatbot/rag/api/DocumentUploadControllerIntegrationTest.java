package art.insightai.chatbot.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.rag.ingest.ChunkRepository;
import art.insightai.chatbot.support.DocumentRowCounter;
import art.insightai.chatbot.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [Purpose] End-to-end coverage of {@code POST /api/admin/documents} (document-ingestion Spec
 *   Tech Review T5) through the real servlet filter chain against a real database and a real
 *   filesystem write: the success response shape and its immediate DB/vector-store state
 *   (document-ingestion:AC-1), the id-derived storage path (document-ingestion:AC-4), and both
 *   rejection branches leaving zero trace in either {@code documents} or the upload directory
 *   (document-ingestion:AC-2 MIME, document-ingestion:AC-3 application-level size). The
 *   container-level half of AC-3 ({@code MaxUploadSizeExceededException}) is deliberately NOT
 *   exercised here -- see {@link DocumentUploadContainerSizeLimitIntegrationTest}'s class Javadoc
 *   for why {@code MockMvc} structurally cannot reach that path.
 * [Rationale] {@code DocumentUploadServiceTest} (backend-engineer, unit level) already proves
 *   AC-1's "no parsing/embedding collaborator is even referenced" claim structurally (the service
 *   has no such dependency to mock) and AC-2/AC-3/AC-4's guard logic against a mocked repository
 *   and a throwaway temp directory. This class adds what only a real {@code @SpringBootTest} can:
 *   the actual HTTP status code through {@code GlobalExceptionHandler}, the actual persisted row
 *   shape, and the actual bytes on disk under a real filesystem write -- project convention
 *   (통합 테스트는 실제 인스턴스, 모킹 금지).
 * [Caution] {@code app.ingest.upload-dir} is overridden to a JUnit-managed {@link #uploadDir} via
 *   {@link DynamicPropertySource} (review 2026-08-28 High-2), not the real {@code infra/.env}
 *   {@code UPLOAD_DIR}: this class's assertions read/list that directory directly (AC-4's "exactly
 *   one file" check), and doing that against a developer's real upload directory in a shared repo
 *   risks a false failure from leftover files belonging to an unrelated session -- the sibling
 *   {@code DocumentUploadContainerSizeLimitIntegrationTest}'s class Javadoc documents that a
 *   concurrent full-suite run had already emptied the real directory once during this card, via
 *   its own {@code @AfterEach} blanket sweep. A previous version of this Javadoc also noted that
 *   {@code app.ingest.upload-dir} did not exist on disk before this card and that this class's own
 *   test run had to create it by hand outside the test (review 2026-08-28 High-3) -- both are no
 *   longer true: {@code DocumentUploadService#writeOriginalFile} now calls {@code
 *   Files.createDirectories} itself (High-3(a)), and {@link #uploadDir} is created by JUnit's
 *   {@code @TempDir} regardless, so no manual step precedes a green run of this class (High-3(b)).
 *   {@link #countUploadDirEntries()}/{@link #uploadedFilePath(UUID)} now read {@link #uploadDir}
 *   directly instead of {@code appProperties.ingest().uploadDir()} (review 2026-08-28 round 2
 *   security L-5) -- see {@code DocumentUploadContainerSizeLimitIntegrationTest}'s class [Caution]
 *   for why a property-derived path is structurally weaker than reading the {@code @TempDir} field.
 *   Also adds {@link #rejectsOverlongFilenameLeavingNoRowOrFile()} (round 2 Medium-1: no test
 *   anywhere previously exercised {@code InvalidDocumentFilenameException}'s mapping to 400) and
 *   extends the AC-2 case plus {@link #truncatesOverlongMimeTypeInResponseBody()} to assert the
 *   400 body contract, not just the status code (round 2 Medium-2b). Round 3 tightens both
 *   further: {@link #rejectsOverlongFilenameLeavingNoRowOrFile()} now also asserts the fixed
 *   {@code detail} string (round 3 Low-2), and {@link #truncatesOverlongMimeTypeInResponseBody()}
 *   now asserts the truncated value is a genuine prefix of the original, not just short enough
 *   (round 3 Low-3).
 */
@AutoConfigureMockMvc
class DocumentUploadControllerIntegrationTest extends IntegrationTestBase {

    private static final byte[] ALLOWED_CONTENT =
            "sample content for document-ingestion T5 integration coverage".getBytes(StandardCharsets.UTF_8);

    /**
     * Mirrors {@code GlobalExceptionHandler#UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE} (private
     * constant) -- review 2026-08-28 round 2 Medium-2b asks the AC-2 case to assert the response
     * body's fixed {@code detail}, not only its status code. Deliberately duplicated as a literal
     * rather than referencing the production constant, same reasoning as {@code
     * DocumentUploadContainerSizeLimitIntegrationTest#DOCUMENT_TOO_LARGE_CLIENT_MESSAGE}: it is
     * {@code private}.
     */
    private static final String UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE = "unsupported document MIME type";

    /**
     * Mirrors {@code GlobalExceptionHandler#INVALID_DOCUMENT_FILENAME_CLIENT_MESSAGE} (private
     * constant) -- review 2026-08-28 round 3 Low-2: {@link #rejectsOverlongFilenameLeavingNoRowOrFile()}
     * previously asserted only the status code and DB/filesystem side effects, so a handler that
     * echoed {@code e.getMessage()} back verbatim (the exact pattern security Low-1 closed for the
     * sibling MIME-type 400 above) would have passed unnoticed. Deliberately duplicated as a
     * literal rather than referencing the production constant -- it is {@code private}.
     */
    private static final String INVALID_DOCUMENT_FILENAME_CLIENT_MESSAGE =
            "uploaded file must have a name no longer than the server allows";

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void overrideUploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.ingest.upload-dir", () -> uploadDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkRepository chunkRepository;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedRows() {
        for (UUID documentId : insertedDocumentIds) {
            chunkRepository.deleteByDocumentId(documentId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            deleteOrThrow(uploadedFilePath(documentId));
        }
        insertedDocumentIds.clear();
    }

    @Test
    @DisplayName("[document-ingestion:AC-1][document-ingestion:AC-4] 허용 MIME 파일 업로드는 202+PENDING을 반환하고 "
            + "응답 시점까지 색인 흔적이 없으며, 원본은 사용자 파일명이 아니라 document.id 경로에만 저장된다")
    void uploadsAllowedFileReturns202PendingWithNoIndexingTraceAndIdDerivedPath() throws Exception {
        // given: an original filename crafted to escape upload-dir if it were ever used verbatim
        // for the storage path (DD-2/AC-4) -- this is the same shape DocumentUploadServiceTest's
        // unit-level AC-4 case uses, replayed here through the real filesystem write.
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../../../etc/passwd", "text/plain", ALLOWED_CONTENT);

        // when
        MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                .andExpect(status().isAccepted())
                .andReturn();

        // then: response shape (AC-1)
        String responseBody = result.getResponse().getContentAsString();
        String status = JsonPath.read(responseBody, "$.status");
        String filename = JsonPath.read(responseBody, "$.filename");
        String idString = JsonPath.read(responseBody, "$.id");
        assertThat(status).isEqualTo("PENDING");
        assertThat(filename).isEqualTo("../../../../etc/passwd");
        UUID documentId = UUID.fromString(idString);
        insertedDocumentIds.add(documentId);

        // then: the persisted row is PENDING with no completion fields set yet -- this and the
        // vector-store check below are what this test can actually observe about AC-1's response-
        // time state claim. What this does NOT prove, and what the evidence ledger's AC-1 cell no
        // longer overstates (review 2026-08-28 Medium-1): a mock-verified "the parsing collaborator
        // was never invoked" check as Spec §6's Unit table calls for -- there is currently no
        // DocumentParser dependency on DocumentUploadService to mock at all, so that specific claim
        // rests on the class's structure (see DocumentUploadServiceTest's own [Rationale]), not on
        // an interaction verification here. Nor is there yet anything to race against: no listener
        // for DocumentUploadedEvent exists anywhere in the codebase as of this card (T8's async
        // pipeline is not implemented yet) -- this exact assertion must be re-run once T8 wires up
        // its @TransactionalEventListener before it can be trusted as evidence against *that* race
        // (see DocumentUploadedEvent's own [Caution]/NR-3).
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, indexed_at, chunk_count FROM documents WHERE id = ?", documentId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("indexed_at")).isNull();
        assertThat(row.get("chunk_count")).isNull();
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();

        // then: the original bytes landed under upload-dir/{document.id}, never under a path
        // derived from the malicious original filename (AC-4)
        Path expectedPath = uploadedFilePath(documentId);
        assertThat(expectedPath).exists().hasBinaryContent(ALLOWED_CONTENT);
        try (var stream = Files.list(uploadDir)) {
            assertThat(stream.toList()).containsExactly(expectedPath);
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-2] 허용 목록에 없는 MIME 파일은 400을 반환하고 documents 행도 upload-dir 파일도 남기지 않으며 "
            + "응답 본문은 고정 detail과 원문 그대로의 mimeType 프로퍼티를 담는다")
    void rejectsDisallowedMimeTypeLeavingNoRowOrFile() throws Exception {
        // given
        long documentsBefore = DocumentRowCounter.countDocuments(jdbcTemplate);
        long uploadDirEntriesBefore = countUploadDirEntries();
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.hwp", "application/x-hwp", ALLOWED_CONTENT);

        // when
        MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                .andExpect(status().isBadRequest())
                .andReturn();

        // then: no row, no file
        assertThat(DocumentRowCounter.countDocuments(jdbcTemplate)).isEqualTo(documentsBefore);
        assertThat(countUploadDirEntries()).isEqualTo(uploadDirEntriesBefore);

        // then: 400 body contract (review 2026-08-28 round 2 Medium-2b) -- a fixed client-facing
        // detail (GlobalExceptionHandler#UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE, no longer
        // e.getMessage() verbatim, security Low-1) plus the rejected Content-Type echoed back
        // through a separate mimeType property. This Content-Type is 17 chars, far under the
        // handler's 128-char echo cap, so it must come back byte-for-byte unmodified -- this is
        // the "normal case, untouched" half of the truncation contract; the "capped when over the
        // limit" half is asserted separately by truncatesOverlongMimeTypeInResponseBody below,
        // deliberately without pinning the exact truncated value (backend-engineer is concurrently
        // changing truncate()'s algorithm from substring to a codePoint-safe cut, security L-2).
        String responseBody = result.getResponse().getContentAsString();
        String detail = JsonPath.read(responseBody, "$.detail");
        String mimeType = JsonPath.read(responseBody, "$.mimeType");
        assertThat(detail).isEqualTo(UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE);
        assertThat(mimeType).isEqualTo("application/x-hwp");
    }

    @Test
    @DisplayName("[document-ingestion §5] 허용 목록에 없는 MIME 타입 문자열이 128자를 넘으면 응답의 mimeType 프로퍼티는 "
            + "원문의 접두이며 상한(128자) 이하로 잘려서 내려온다")
    void truncatesOverlongMimeTypeInResponseBody() throws Exception {
        // given: a Content-Type far past GlobalExceptionHandler#ECHOED_MIME_TYPE_MAX_LENGTH (128).
        // Asserts the contract (capped length, and the truncated value is a genuine prefix of the
        // original -- review 2026-08-28 round 3 Low-3, closing the gap where an empty string or a
        // fixed placeholder would previously have passed), not the exact truncation algorithm --
        // see rejectsDisallowedMimeTypeLeavingNoRowOrFile's [then] comment above for why the exact
        // cut position (which can shift by one for a surrogate pair straddling the boundary, see
        // GlobalExceptionHandler#truncate's own [Rationale]) is deliberately not pinned. "x".repeat
        // has no surrogate pairs, so a plain startsWith is enough here without needing to special-
        // case that boundary.
        String overlongMimeType = "x".repeat(200);
        MockMultipartFile file = new MockMultipartFile("file", "notes.bin", overlongMimeType, ALLOWED_CONTENT);

        // when
        MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                .andExpect(status().isBadRequest())
                .andReturn();

        // then: fixed detail, mimeType present, capped at 128, and a real prefix of the original
        // -- no assertion on the exact truncated content or cut position beyond that
        String responseBody = result.getResponse().getContentAsString();
        String detail = JsonPath.read(responseBody, "$.detail");
        String mimeType = JsonPath.read(responseBody, "$.mimeType");
        assertThat(detail).isEqualTo(UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE);
        assertThat(mimeType).hasSizeLessThanOrEqualTo(128);
        assertThat(overlongMimeType).startsWith(mimeType);
    }

    @Test
    @DisplayName("[document-ingestion §5] 파일명이 255자를 초과하면 400을 반환하고 documents 행도 upload-dir 파일도 남기지 않으며 "
            + "응답 본문은 고정 detail을 담는다")
    void rejectsOverlongFilenameLeavingNoRowOrFile() throws Exception {
        // given: 256 chars, one past Document.filename's varchar(255) column width -- the same
        // shape as DocumentUploadServiceTest's unit-level AC-1 High-1 reproduction, replayed here
        // through the real HTTP/DB/GlobalExceptionHandler path (review 2026-08-28 round 2
        // Medium-1). Before this test existed, nothing in the repository exercised
        // InvalidDocumentFilenameException's mapping to 400 end-to-end: deleting
        // GlobalExceptionHandler#handleInvalidDocumentFilename or changing its status code passed
        // the whole suite, and the real behavior was an unmapped 500 -- the exact "one long Korean
        // filename 500s a normal user" symptom review round 1 High-1 flagged.
        long documentsBefore = DocumentRowCounter.countDocuments(jdbcTemplate);
        long uploadDirEntriesBefore = countUploadDirEntries();
        String overlongFilename = "a".repeat(256);
        MockMultipartFile file = new MockMultipartFile(
                "file", overlongFilename, "text/plain", ALLOWED_CONTENT);

        // when
        MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                .andExpect(status().isBadRequest())
                .andReturn();

        // then
        assertThat(DocumentRowCounter.countDocuments(jdbcTemplate)).isEqualTo(documentsBefore);
        assertThat(countUploadDirEntries()).isEqualTo(uploadDirEntriesBefore);

        // then: 400 body contract (review 2026-08-28 round 3 Low-2) -- a fixed client-facing
        // detail, not e.getMessage() reflected back verbatim (mirrors the AC-2 case's fixed-string
        // assertion above)
        String responseBody = result.getResponse().getContentAsString();
        String detail = JsonPath.read(responseBody, "$.detail");
        assertThat(detail).isEqualTo(INVALID_DOCUMENT_FILENAME_CLIENT_MESSAGE);
    }

    @Test
    @DisplayName("[document-ingestion:AC-3] app.ingest.max-file-size(20MB) 초과·컨테이너 한도(25MB) 이하 파일은 413을 반환하고 "
            + "documents 행도 upload-dir 파일도 남기지 않는다")
    void rejectsFileOverApplicationLimitButUnderContainerLimit() throws Exception {
        // given: 21MB -- strictly between the 20MB application-level ceiling
        // (app.ingest.max-file-size) and the 25MB container-level ceiling
        // (spring.servlet.multipart.max-file-size, Tech Review AD-6) -- isolates
        // DocumentUploadService's own guard from the container's. MockMvc's .multipart(...)
        // builds parts directly in memory (see DocumentUploadContainerSizeLimitIntegrationTest's
        // class Javadoc), so this request never reaches the container-level check at all -- this
        // test exercises only the application-level branch.
        byte[] oversizedContent = new byte[21 * 1024 * 1024];
        long documentsBefore = DocumentRowCounter.countDocuments(jdbcTemplate);
        long uploadDirEntriesBefore = countUploadDirEntries();
        MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", oversizedContent);

        // when
        mockMvc.perform(multipart("/api/admin/documents").file(file))
                .andExpect(status().isPayloadTooLarge());

        // then
        assertThat(DocumentRowCounter.countDocuments(jdbcTemplate)).isEqualTo(documentsBefore);
        assertThat(countUploadDirEntries()).isEqualTo(uploadDirEntriesBefore);
    }

    private long countUploadDirEntries() throws IOException {
        // Reads uploadDir directly, not appProperties.ingest().uploadDir() (review 2026-08-28
        // round 2 security L-5) -- see DocumentUploadContainerSizeLimitIntegrationTest's class
        // [Caution] for why that distinction matters.
        if (!Files.isDirectory(uploadDir)) {
            return 0L;
        }
        try (var stream = Files.list(uploadDir)) {
            return stream.count();
        }
    }

    private Path uploadedFilePath(UUID documentId) {
        return uploadDir.resolve(documentId.toString());
    }

    /**
     * [Purpose] Deletes one file this test uploaded, failing the test loudly instead of
     *   swallowing the error, if that delete itself fails.
     * [Rationale] Named to match its actual behavior (review 2026-08-28 maintainability Low-2):
     *   unlike the sibling {@code deleteQuietly} helpers in {@code DocumentUploadService}
     *   (production, best-effort cleanup after a failed write) and {@code
     *   DocumentUploadContainerSizeLimitIntegrationTest} (defensive sweep of an already-expected-
     *   empty directory), this method's caller ({@link #cleanUpInsertedRows}) is cleaning up a file
     *   this test itself is certain it wrote -- a failure to delete it here is itself a test-
     *   environment problem worth surfacing, not one to hide.
     */
    private void deleteOrThrow(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean up test-uploaded file " + path, e);
        }
    }
}
