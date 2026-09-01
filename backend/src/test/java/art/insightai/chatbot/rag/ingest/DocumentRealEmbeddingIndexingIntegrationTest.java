package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import art.insightai.chatbot.support.DatabaseGuardExtension;
import art.insightai.chatbot.support.StubEmbeddingModel;

/**
 * [Purpose] Proves, with a real (non-stub) OpenRouter embedding call, that
 *   {@code document-ingestion:AC-19} holds: uploading one document ends with {@code
 *   documents.status=INDEXED}, {@code chunk_count >= 1}, and that many rows actually present in
 *   {@code vector_store_bgem3}.
 * [Rationale] BL-023 (2026-09-01) found that {@code ChunkMetadataBuilder}'s deliberate {@code
 *   page: null} metadata entry (required by AC-8) crashes {@code
 *   OpenAiEmbeddingModel.getEmbeddingContent} with a {@code NullPointerException} the moment a
 *   real embedding call is made — and every existing integration test extends {@link
 *   art.insightai.chatbot.support.IntegrationTestBase}, which replaces the embedding model with
 *   {@link art.insightai.chatbot.support.StubEmbeddingModel} and therefore never exercises that
 *   code path. No amount of additional stub-based testing can catch this class of defect (the
 *   BL-023 diagnosis, independently confirmed by insight-chat-7f). This class deliberately does
 *   <b>not</b> extend {@code IntegrationTestBase} for that reason — see that class's own
 *   [Caution]: "a subclass that needs the real chat or embedding autoconfiguration ... must not
 *   extend this class." Instead it overrides only the {@code spring.ai.openai.embedding.*} keys
 *   {@code src/test/resources/config/application.yaml} poisons — the common-level {@code
 *   spring.ai.openai.{base-url,api-key}} keys and {@code chat.*} are both left poisoned (security
 *   review, 2026-09-01: the common-level keys are read directly by {@code
 *   OpenRouterRerankAdapter}, a plain unconditional {@code @Component} with no profile guard, so
 *   un-poisoning them would build a real-keyed rerank {@code RestClient} bean in this context for
 *   no reason this test needs — only {@code app.rerank.enabled=false}, set explicitly below,
 *   currently keeps that client unused) via {@link
 *   #realOpenRouterCredentials}, reading the real values from {@code infra/.env} at test-JVM
 *   runtime exactly like the production app does ({@code spring.config.import} in {@code
 *   application.yaml}) — never printed, logged, or otherwise surfaced by this class.
 *   {@code @DynamicPropertySource} outranks both {@code config/application.yaml} and pom.xml's
 *   surefire {@code systemPropertyVariables} (Spring's documented highest-precedence test property
 *   source), so this is the one test class in the suite that reaches the real embedding endpoint.
 * [Caution] Tagged {@code @Tag("eval-infra")} (BL-024, distinct from the existing {@code
 *   @Tag("eval")} used by the 25-question judge sweep) and requires {@code -Peval-infra}
 *   (pom.xml) — the default {@code ./mvnw test} build excludes it, same as {@code @Tag("eval")}
 *   (chat:AC-35 — zero real OpenRouter calls during ordinary builds). Running this test performs
 *   one real embedding call and incurs real (small) OpenRouter cost. Requires {@code infra/.env}
 *   to hold a valid {@code OPENROUTER_API_KEY} and the {@code chatbot-db} container to be running.
 * [AC] document-ingestion:AC-19
 */
@Tag("eval-infra")
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(DatabaseGuardExtension.class)
class DocumentRealEmbeddingIndexingIntegrationTest {

    private static final byte[] CONTENT =
            "인사이트에이아이 사내 문서 샘플. document-ingestion:AC-19 실제 임베딩 색인 검증용 본문입니다."
                    .getBytes(StandardCharsets.UTF_8);

    private static final long TERMINAL_WAIT_MILLIS = 60_000L;

    private static final long POLL_INTERVAL_MILLIS = 200L;

    @TempDir
    static Path uploadDir;

    /**
     * [Purpose] Un-poisons only the {@code spring.ai.openai.embedding.*} keys {@code
     *   config/application.yaml} forces to an unroutable address for every other {@code
     *   @SpringBootTest}, using the same {@code infra/.env} the production app reads (never this
     *   class's own literal values).
     * [Caution] {@code chat.*} and the common-level {@code spring.ai.openai.{base-url,api-key}}
     *   are both deliberately left poisoned (security review, 2026-09-01) — this test only calls
     *   the embedding model, and un-poisoning the common level would additionally build a
     *   real-keyed {@code RestClient} bean inside {@code OpenRouterRerankAdapter} (an unconditional
     *   {@code @Component}, not gated on {@code app.rerank.enabled}) for no reason this test needs.
     *   {@code app.rerank.enabled=false} is set explicitly below as defense in depth, so that bean
     *   stays unused regardless of what the active {@code application.yaml} default is.
     */
    @DynamicPropertySource
    static void realOpenRouterCredentials(DynamicPropertyRegistry registry) {
        Properties env = loadEnvProperties();
        String apiKey = env.getProperty("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "infra/.env must set OPENROUTER_API_KEY to run @Tag(\"eval-infra\") tests");
        }
        String baseUrl = env.getProperty("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");
        registry.add("spring.ai.openai.embedding.base-url", () -> baseUrl);
        registry.add("spring.ai.openai.embedding.api-key", () -> apiKey);
        registry.add("app.rerank.enabled", () -> "false");
        registry.add("app.ingest.upload-dir", () -> uploadDir.toString());
    }

    /**
     * [Purpose] Reads {@code infra/.env} the same way {@code application.yaml}'s {@code
     *   spring.config.import} does, trying the same two relative locations (terminal cwd=backend/
     *   vs. IntelliJ cwd=repo root) that comment documents.
     */
    private static Properties loadEnvProperties() {
        for (String candidate : new String[] {"../infra/.env", "./infra/.env"}) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                Properties props = new Properties();
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    props.load(reader);
                    return props;
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + path, e);
                }
            }
        }
        throw new IllegalStateException("infra/.env not found at ../infra/.env or ./infra/.env");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private EmbeddingModel embeddingModel;

    private UUID insertedDocumentId;

    /**
     * [Purpose] Removes the row(s)/file this test created, tolerating a still-running ingestion.
     * [Caution] Wrapped in {@code try/finally} (correctness review, 2026-09-01) so a failure
     *   deleting one artifact does not skip the others. If {@link #awaitTerminalStatus} timed out
     *   (ingestion still running), this waits briefly for a terminal state before deleting --
     *   deleting the {@code documents} row out from under a still-running ingestion would leave its
     *   chunks orphaned in {@code vector_store_bgem3} with no owning row (the exact state {@code
     *   document-ingestion:AC-17}/{@code AC-18} exist to detect, not something this cleanup should
     *   itself create).
     */
    @AfterEach
    void cleanUp() {
        if (insertedDocumentId == null) {
            return;
        }
        try {
            awaitTerminalStatus(insertedDocumentId);
        } finally {
            try {
                chunkRepository.deleteByDocumentId(insertedDocumentId);
            } finally {
                try {
                    jdbcTemplate.update("DELETE FROM documents WHERE id = ?", insertedDocumentId);
                } finally {
                    try {
                        Files.deleteIfExists(uploadDir.resolve(insertedDocumentId.toString()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(
                                "Failed to delete test upload " + insertedDocumentId, e);
                    } finally {
                        insertedDocumentId = null;
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-19] 실제(비-스텁) 임베딩 호출로 문서를 업로드하면 INDEXED로 종료되고 "
            + "chunk_count가 1 이상이며 그 청크 전부가 vector_store_bgem3에 실제 행으로 존재한다")
    void indexesARealDocumentThroughTheRealEmbeddingModel() throws Exception {
        // given: the injected EmbeddingModel is really the production bean, not the stub
        // IntegrationTestBase would have wired in -- without this, a future change that made the
        // stub reachable here would let this test pass while verifying nothing (correctness review)
        assertThat(embeddingModel)
                .as("this test only proves AC-19 if the real embedding model is wired in")
                .isNotInstanceOf(StubEmbeddingModel.class);

        // given/when: a real multipart upload through the real production endpoint
        MockMultipartFile file =
                new MockMultipartFile("file", "ac19-real-embedding.txt", "text/plain", CONTENT);

        MvcResult result = mockMvc.perform(multipart("/api/admin/documents").file(file))
                .andExpect(status().isAccepted())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        insertedDocumentId = UUID.fromString(JsonPath.read(responseBody, "$.id"));

        // then: ingestion (running on ingestExecutor, real embedding call included) reaches a
        // terminal state -- FAILED here would mean BL-023's fix regressed, not an environment flake
        String terminalStatus = awaitTerminalStatus(insertedDocumentId);
        assertThat(terminalStatus)
                .as("terminal status of the real-embedding ingestion")
                .isEqualTo("INDEXED");

        var row = jdbcTemplate.queryForMap(
                "SELECT chunk_count, indexed_at FROM documents WHERE id = ?", insertedDocumentId);
        int chunkCount = ((Number) row.get("chunk_count")).intValue();
        assertThat(chunkCount).as("chunk_count").isGreaterThanOrEqualTo(1);
        assertThat(row.get("indexed_at")).isNotNull();

        int actualVectorStoreRows = chunkRepository.countByDocumentId(insertedDocumentId);
        assertThat(actualVectorStoreRows)
                .as("rows actually present in vector_store_bgem3 for this document")
                .isEqualTo(chunkCount)
                .isGreaterThanOrEqualTo(1);

        // then: page is present as a literal JSON null (not absent, not a string) in storage --
        // PgVectorStore.toJson never consults the embed/inference-only exclusion
        // ChunkMetadataBuilder sets (AC-8), independent of whichever chunk_count this row happens
        // to report (correctness review Low-3/Low-5: closes the near-tautology in the chunk-count
        // comparison above with an independent observation). json_typeof(metadata->'page')
        // distinguishes "key present with JSON null" ('null') from "key absent" (SQL NULL) --
        // metadata->>'page' alone cannot make that distinction, since both cases read back as null.
        // vector_store_bgem3.metadata is `json`, not `jsonb` (PgVectorStore's own column type), so
        // this uses json_typeof, not jsonb_typeof.
        List<String> pageJsonTypes = jdbcTemplate.queryForList(
                "SELECT json_typeof(metadata->'page') AS page_type FROM vector_store_bgem3 "
                        + "WHERE metadata->>'document_id' = ?",
                String.class, insertedDocumentId.toString());
        assertThat(pageJsonTypes)
                .as("json_typeof(metadata->'page') for every chunk of this document")
                .hasSize(chunkCount)
                .containsOnly("null");
    }

    /**
     * [Purpose] Polls {@code documents.status} until it reaches a terminal value, returning it.
     * [Rationale] Matches the identically-named/purposed helper in {@code
     *   DocumentUploadControllerIntegrationTest}/{@code DocumentUploadAsyncHandoffIntegrationTest}
     *   (maintainability review, 2026-09-01) -- the card forbids introducing a new waiting
     *   dependency, so this is the {@link JdbcTemplate} polling those classes already established.
     */
    private String awaitTerminalStatus(UUID documentId) {
        long deadline = System.currentTimeMillis() + TERMINAL_WAIT_MILLIS;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbcTemplate.queryForObject(
                    "SELECT status FROM documents WHERE id = ?", String.class, documentId);
            if ("INDEXED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status;
    }
}
