package art.insightai.chatbot.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import art.insightai.chatbot.rag.ingest.DocumentUploadService;
import art.insightai.chatbot.support.AnonymousUserCleanup;
import art.insightai.chatbot.support.DocumentRowCounter;
import art.insightai.chatbot.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * [Purpose] Verifies the *container-level* half of document-ingestion:AC-3's 2-tier size guard
 *   (Tech Review AD-6): a request whose body exceeds {@code
 *   spring.servlet.multipart.max-file-size}/{@code max-request-size} (25MB, application.yaml)
 *   never reaches {@code DocumentUploadService#upload}'s own 20MB application-level check at
 *   all -- {@code DispatcherServlet} throws {@code MaxUploadSizeExceededException} while
 *   resolving the multipart request itself, which {@code GlobalExceptionHandler} maps to the
 *   same 413 through a separate handler. This is now proven directly rather than inferred from
 *   status code alone (review 2026-08-28 Medium-2): {@link #documentUploadService} is a {@link
 *   MockitoSpyBean} wrapping the real bean, and the test asserts {@code upload(...)} is never
 *   invoked at all -- the request must be rejected before the container even hands it to {@code
 *   DispatcherServlet}'s mapped controller method, not merely rejected by some other 413 path
 *   that happens to share the same status code.
 * [Rationale] {@code MockMvc}'s {@code .multipart(...)} builder (used by this card's sibling,
 *   {@link DocumentUploadControllerIntegrationTest}) constructs a {@code
 *   MockMultipartHttpServletRequest} that already implements {@code MultipartHttpServletRequest}
 *   directly from in-memory parts -- {@code DispatcherServlet#checkMultipart} sees an
 *   already-resolved multipart request (via {@code WebUtils.getNativeRequest(request,
 *   MultipartHttpServletRequest.class)}) and never invokes {@code
 *   StandardServletMultipartResolver#resolveMultipart}, so the real container-level {@code
 *   MultipartConfigElement} size check that raises {@code MaxUploadSizeExceededException} never
 *   runs under {@code MockMvc} at all (confirmed against {@code spring-test:7.0.9} sources during
 *   this card -- see {@code MockMultipartHttpServletRequestBuilder}). This class instead starts a
 *   real embedded Tomcat ({@code webEnvironment = RANDOM_PORT}) and drives it with a real {@link
 *   RestClient} over an actual socket -- the only way in this project's current dependency set to
 *   exercise genuine Servlet-level multipart parsing. {@code app.ingest.upload-dir} is overridden
 *   to a JUnit-managed {@link #uploadDir} via {@link DynamicPropertySource} (review 2026-08-28
 *   High-2) instead of the real {@code infra/.env} {@code UPLOAD_DIR}: before this change, {@link
 *   #cleanUpUploadDir()} deleted every file under whatever the real configured upload directory
 *   was, with no filter -- against a developer's real {@code UPLOAD_DIR}, that is a live-data
 *   deletion, not a test artifact cleanup (confirmed via directory mtime that a concurrent full
 *   suite run had already emptied it once during this card's own review). Pointing this test at
 *   its own temp directory removes that risk entirely and, as a side benefit, makes {@link
 *   #uploadDirEmpty()}'s assertion actually mean what it claims -- before this change the
 *   assertion was really "the shared directory happens to be empty right now", which depended on
 *   whatever other test classes had run earlier in the same JVM.
 * [Caution] No new test dependency was added: {@link RestClient} is already on this module's
 *   classpath via {@code spring-boot-starter-restclient} (see {@code
 *   art.insightai.chatbot.rag.retrieval.OpenRouterRerankAdapter}). {@code TestRestTemplate} is
 *   NOT available in this project's Boot 4.1.1 modular test starters (confirmed absent from the
 *   resolved Maven classpath during this card -- {@code spring-boot-starter-webmvc-test} does not
 *   pull it in), which is why the multipart body below is built by hand with {@link
 *   MultipartBodyBuilder} instead of {@code TestRestTemplate}'s usual multipart helpers. The
 *   26MB payload is deliberately only ~1MB over the 25MB container ceiling, staying under
 *   Tomcat's default {@code maxSwallowSize} (2MB): far past that, the connector would abandon
 *   swallowing the unread request body and reset the connection before a clean 413 response
 *   could be written, turning this test flaky. {@code org.springframework.test.context.bean.
 *   override.mockito.MockitoSpyBean} (confirmed present on {@code spring-test:7.0.9}'s classpath,
 *   Tech Review AD-4) forces its own {@code ApplicationContext}, distinct from other test classes'
 *   caches, so this class already pays for its own context boot regardless (performance Low-3).
 *   {@link #cleanUpUploadDir()} and {@link #uploadDirEmpty()} now resolve the directory to sweep
 *   straight from {@link #uploadDir} instead of {@code appProperties.ingest().uploadDir()} (review
 *   2026-08-28 round 2 security L-5): the only thing that made the previous property-derived form
 *   safe was this class's own {@code @DynamicPropertySource} override five lines above it -- delete
 *   that override by accident and {@code appProperties.ingest().uploadDir()} silently resolves back
 *   to the real {@code infra/.env} {@code UPLOAD_DIR}, reviving INC-010's blanket-delete-of-live-
 *   data risk with no compile-time or structural signal. Reading {@link #uploadDir} directly makes
 *   that impossible regardless of whether the override still exists, the same structural-guard shape
 *   as {@code support.DatabaseGuardExtension} on the DB side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentUploadContainerSizeLimitIntegrationTest extends IntegrationTestBase {

    /**
     * Mirrors {@code GlobalExceptionHandler#DOCUMENT_TOO_LARGE_CLIENT_MESSAGE} (private constant,
     * shared by {@code handleDocumentTooLarge}/{@code handleMaxUploadSizeExceeded}) -- review
     * 2026-08-28 Medium-2 asks this test to assert the response body carries that fixed string
     * rather than only checking the status code. Deliberately duplicated as a literal rather than
     * referencing the production constant: it is {@code private}, and this test's real
     * discriminator between the two 413 paths is {@link #documentUploadService} never being
     * invoked, not this string (both handlers intentionally return the exact same text, AD-6).
     */
    private static final String DOCUMENT_TOO_LARGE_CLIENT_MESSAGE =
            "uploaded file exceeds the maximum allowed size";

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void overrideUploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.ingest.upload-dir", () -> uploadDir.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private DocumentUploadService documentUploadService;

    /**
     * [Purpose] Tracks the {@code chatbot_sid} ids {@code AnonymousSessionFilter} minted for this
     *   class's own requests -- see {@code AnonymousUserCleanup}'s own Javadoc (BL-034). This class
     *   drives the endpoint over a real socket via {@link RestClient} rather than {@code MockMvc},
     *   so both call sites use {@link AnonymousUserCleanup#trackFromSetCookieHeaders} (raw
     *   {@code Set-Cookie} response header values) instead of the {@code MvcResult}-based {@link
     *   AnonymousUserCleanup#track} overload.
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

    @AfterEach
    void cleanUpUploadDir() throws IOException {
        // Defensive only -- the request under test is expected to leave no file behind; this
        // guards against a failing/defective run leaking a file into this test class's own
        // JUnit-managed temp directory (review 2026-08-28 High-2 -- no longer the shared
        // infra/.env UPLOAD_DIR, see class [Rationale]). Sweeps {@link #uploadDir} directly, not
        // a path re-derived from {@code appProperties.ingest().uploadDir()} (review 2026-08-28
        // round 2 security L-5) -- see class [Caution] for why that distinction matters.
        if (Files.isDirectory(uploadDir)) {
            try (var stream = Files.list(uploadDir)) {
                stream.forEach(this::deleteQuietly);
            }
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-3] 컨테이너 한도(25MB) 초과 요청은 MaxUploadSizeExceededException 경로로 413을 반환하고 "
            + "documents 행도 업로드 파일도 남기지 않으며 DocumentUploadService#upload는 호출되지 않는다")
    void rejectsRequestExceedingContainerMultipartLimit() {
        // given: 26MB, over both spring.servlet.multipart.max-file-size and max-request-size
        // (25MB, application.yaml) -- see class [Caution] for why 26MB specifically.
        byte[] oversizedContent = new byte[26 * 1024 * 1024];
        long documentsBefore = DocumentRowCounter.countDocuments(jdbcTemplate);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        ByteArrayResource resource = new ByteArrayResource(oversizedContent) {
            @Override
            public String getFilename() {
                return "container-oversized.txt";
            }
        };
        bodyBuilder.part("file", resource, MediaType.TEXT_PLAIN);
        RestClient restClient = RestClient.create();

        // when
        HttpClientErrorException thrown = catchHttpClientErrorException(restClient, bodyBuilder);

        // then: rejected with 413, and the fixed body this handler is supposed to return
        assertThat(thrown).as("expected the container multipart limit to reject this request with a 4xx")
                .isNotNull();
        // AnonymousSessionFilter runs ahead of DispatcherServlet's multipart resolution, so it
        // still minted a users row for this request even though the container rejected it (BL-034)
        anonymousUserCleanup.trackFromSetCookieHeaders(
                thrown.getResponseHeaders() == null ? null : thrown.getResponseHeaders().get("Set-Cookie"));
        assertThat(thrown.getStatusCode().value()).isEqualTo(413);
        String detail = JsonPath.read(thrown.getResponseBodyAsString(), "$.detail");
        assertThat(detail).isEqualTo(DOCUMENT_TOO_LARGE_CLIENT_MESSAGE);

        // then: no DB/filesystem trace
        assertThat(DocumentRowCounter.countDocuments(jdbcTemplate)).isEqualTo(documentsBefore);
        assertThat(uploadDirEmpty()).isTrue();

        // then: the request never reached DocumentUploadService#upload at all -- this is the
        // direct evidence that it was the *container* guard that rejected it, not the
        // application-level DocumentTooLargeException path, which happens to raise the same
        // 413/detail (review 2026-08-28 Medium-2; AD-6's 2-tier structure would otherwise be able
        // to silently collapse into one tier without this test noticing).
        verify(documentUploadService, never()).upload(any());
    }

    @Test
    @DisplayName("[document-ingestion §5] 정상 크기의 파일 업로드는 202를 반환하고 실제 요청 경로에서 "
            + "DocumentUploadService#upload가 호출된다")
    void acceptsNormalUploadAndInvokesServiceUpload() {
        // given: small allowed-MIME file, far under both size ceilings -- the positive control
        // this class was missing (review 2026-08-28 round 2 correctness L-7). The test above's
        // `verify(documentUploadService, never()).upload(any())` passes even if this spy bean were
        // never wired into the real request path at all (an AD-6 regression that made the
        // container guard always fall through, silently collapsing the 2-tier size guard into one
        // tier, would still leave that `never()` verification green) -- this case is the other half
        // that would actually fail if the spy stopped intercepting real requests.
        byte[] smallContent = "small upload for L-7 positive control".getBytes(StandardCharsets.UTF_8);
        long documentsBefore = DocumentRowCounter.countDocuments(jdbcTemplate);
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        ByteArrayResource resource = new ByteArrayResource(smallContent) {
            @Override
            public String getFilename() {
                return "small.txt";
            }
        };
        bodyBuilder.part("file", resource, MediaType.TEXT_PLAIN);
        RestClient restClient = RestClient.create();
        UUID documentId = null;

        try {
            // when
            ResponseEntity<String> response = restClient.post()
                    .uri("http://localhost:" + port + "/api/admin/documents")
                    .body(bodyBuilder.build())
                    .retrieve()
                    .toEntity(String.class);

            // then: accepted, and the spy actually observed the real request
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            anonymousUserCleanup.trackFromSetCookieHeaders(response.getHeaders().get("Set-Cookie"));
            documentId = UUID.fromString(JsonPath.read(response.getBody(), "$.id"));
            assertThat(DocumentRowCounter.countDocuments(jdbcTemplate)).isEqualTo(documentsBefore + 1);
            verify(documentUploadService).upload(any());
        } finally {
            // this class has no per-id row tracking (unlike DocumentUploadControllerIntegrationTest)
            // because every other test here expects zero rows created; clean up this one manually
            // so it does not leak into another test's `documentsBefore` baseline.
            if (documentId != null) {
                jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            }
        }
    }

    private HttpClientErrorException catchHttpClientErrorException(
            RestClient restClient, MultipartBodyBuilder bodyBuilder) {
        try {
            restClient.post()
                    .uri("http://localhost:" + port + "/api/admin/documents")
                    .body(bodyBuilder.build())
                    .retrieve()
                    .toBodilessEntity();
            return null;
        } catch (HttpClientErrorException e) {
            return e;
        }
    }

    private boolean uploadDirEmpty() {
        // Reads {@link #uploadDir} directly, not appProperties.ingest().uploadDir() (review
        // 2026-08-28 round 2 security L-5) -- see class [Caution].
        if (!Files.isDirectory(uploadDir)) {
            return true;
        }
        try (var stream = Files.list(uploadDir)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort test cleanup
        }
    }
}
