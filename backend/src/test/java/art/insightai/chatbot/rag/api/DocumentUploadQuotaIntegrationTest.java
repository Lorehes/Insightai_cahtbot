package art.insightai.chatbot.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.support.AnonymousUserCleanup;
import art.insightai.chatbot.support.DocumentRowCounter;
import art.insightai.chatbot.support.IntegrationTestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * [Purpose] Locks the {@code app.ingest.max-document-count} ceiling's HTTP contract
 *   (document-ingestion:AC-15): once the ceiling is reached, an otherwise-valid upload is
 *   rejected with 403 and leaves no new {@code documents} row and no new file under {@code
 *   app.ingest.upload-dir} (review 2026-08-28 Medium-3).
 * [Rationale] No test anywhere in this repository exercised {@code
 *   DocumentQuotaExceededException}'s mapping to 403 through {@code GlobalExceptionHandler} before
 *   this class -- {@code DocumentUploadServiceTest}'s unit-level coverage only asserts the
 *   exception type, not the HTTP status it maps to, so deleting or renaming the handler would have
 *   passed the whole suite silently. The ceiling is pinned to {@code 0} via a class-level {@link
 *   TestPropertySource} (its own Spring context, separate from {@code
 *   DocumentUploadControllerIntegrationTest}'s default 100) so "any upload is over the limit"
 *   holds deterministically without first padding {@code documents} up to a real threshold --
 *   mirrors {@code chat.api.SessionQuotaIntegrationTest}'s same {@code =0} pinning technique for
 *   an analogous ceiling. {@code app.ingest.upload-dir} is overridden to a JUnit-managed {@link
 *   #uploadDir} via {@link DynamicPropertySource}, same reasoning as {@code
 *   DocumentUploadControllerIntegrationTest}/{@code DocumentUploadContainerSizeLimitIntegrationTest}
 *   (review 2026-08-28 High-2) -- this class asserts the upload directory gained no new file.
 * [Caution] Spec §5 (`:133`) originally assigned the actual ceiling check to T5 without an AC;
 *   correctness review flagged that gap (review 2026-08-28 Medium-3) and this class's {@code
 *   @DisplayName} deliberately carried no {@code [document-ingestion:AC-N]} prefix until an AC
 *   was approved. The user approved document-ingestion:AC-15 for this exact contract on
 *   2026-08-28 (review 2026-08-28 round 3, "승인된 후속 조치" #1) and this class's test is now
 *   tagged accordingly -- this passing test is what moves that Spec §4 row's evidence ledger to
 *   ✅. {@link #countUploadDirEntries()} now reads {@link #uploadDir} directly instead of {@code
 *   appProperties.ingest().uploadDir()} (review 2026-08-28 round 2 security L-5) -- see {@code
 *   DocumentUploadContainerSizeLimitIntegrationTest}'s class [Caution] for why.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.ingest.max-document-count=0")
class DocumentUploadQuotaIntegrationTest extends IntegrationTestBase {

    private static final byte[] ALLOWED_CONTENT =
            "sample content for document-ingestion T5 quota coverage".getBytes(StandardCharsets.UTF_8);

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

    /**
     * [Purpose] Tracks the {@code chatbot_sid} id {@code AnonymousSessionFilter} minted for this
     *   class's own {@code MockMvc} request -- see {@code AnonymousUserCleanup}'s own Javadoc
     *   (BL-034).
     */
    private AnonymousUserCleanup anonymousUserCleanup;

    @BeforeEach
    void createAnonymousUserCleanup() {
        anonymousUserCleanup = new AnonymousUserCleanup(jdbcTemplate);
    }

    @AfterEach
    void cleanUpAnonymousUsers() {
        anonymousUserCleanup.cleanUp();
    }

    @Test
    @DisplayName("[document-ingestion:AC-15] max-document-count 상한(0으로 고정) 도달 상태에서 허용 MIME 파일을 업로드하면 "
            + "403을 반환하고 documents 행도 upload-dir 파일도 늘지 않는다")
    void rejectsUploadOnceDocumentCountCeilingIsReached() throws Exception {
        // given: ceiling pinned to 0 (class-level @TestPropertySource), so documentRepository
        // .count() >= maxDocumentCount holds regardless of how many rows already exist from other
        // sessions sharing this real database -- no seeding required, mirrors
        // SessionQuotaIntegrationTest's =0 technique.
        long documentsBefore = DocumentRowCounter.countDocuments(jdbcTemplate);
        long uploadDirEntriesBefore = countUploadDirEntries();
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", ALLOWED_CONTENT);

        // when
        MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                .andExpect(status().isForbidden())
                .andReturn();
        anonymousUserCleanup.track(result);

        // then: no row, no file
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
}
