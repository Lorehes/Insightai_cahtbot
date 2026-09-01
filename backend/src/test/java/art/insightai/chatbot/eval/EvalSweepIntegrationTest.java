package art.insightai.chatbot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.service.ChatService;
import art.insightai.chatbot.chat.service.ConversationService;
import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.eval.evalset.EvalCase;
import art.insightai.chatbot.eval.evalset.EvalSet;
import art.insightai.chatbot.eval.evalset.EvalSetLoader;
import art.insightai.chatbot.eval.judge.EvalJudge;
import art.insightai.chatbot.eval.judge.EvalResponse;
import art.insightai.chatbot.eval.judge.JudgeResult;
import art.insightai.chatbot.eval.report.EvalExcludedDocumentStatusCount;
import art.insightai.chatbot.eval.report.EvalReport;
import art.insightai.chatbot.eval.report.EvalReportAggregator;
import art.insightai.chatbot.eval.report.EvalReportRow;
import art.insightai.chatbot.eval.report.JudgedCase;
import art.insightai.chatbot.rag.retrieval.NonIndexedDocumentLookup;
import art.insightai.chatbot.support.DatabaseGuardExtension;
import art.insightai.chatbot.user.AnonymousUserService;

/**
 * [Purpose] E5 — runs the real 25-question eval set (`{DOC_ROOT}/docs/eval/eval-set.yaml`)
 *   against all 4 {@code app.search.mode} x {@code app.rerank.enabled} combinations through the
 *   real production pipeline ({@link ChatService#streamReply}, real embeddings, real chat
 *   completions, real rerank calls on the two rerank-on combinations), and asserts {@code
 *   eval-usage-dashboard:AC-7} (four result rows exist, for four actually-different configurations)
 *   plus {@code AC-9b}/{@code AC-19b} (review round 1, correctness High-1/High-2 — see below).
 * [Rationale] {@code app.search.mode}/{@code app.rerank.enabled} are read once at Spring context
 *   startup by singleton beans ({@link
 *   art.insightai.chatbot.rag.retrieval.HybridDocumentRetriever} and friends) — there is no
 *   per-request override. Comparing all 4 combinations therefore requires 4 separate {@code
 *   ApplicationContext}s, not 4 calls against one running app. Each combination lives in its own
 *   {@code @Nested @SpringBootTest} class so Spring's test-context caching gives each one its own
 *   context; {@link #realOpenRouterCredentials} is declared once here and applies to every nested
 *   context too (Spring 5.3+: {@code @DynamicPropertySource} methods in an enclosing class apply
 *   to {@code @Nested} test classes) — it un-poisons only {@code chat.*}/{@code embedding.*} (4
 *   keys); the two rerank-on nested classes additionally un-poison the common-level keys
 *   themselves (review round 1, security Medium-1 — {@code OpenRouterRerankAdapter} is an
 *   unconditional {@code @Component} that reads only the common-level keys, so opening them in the
 *   3 rerank-off contexts would build a real-keyed rerank client those contexts never use).
 *   {@code @ExtendWith(DatabaseGuardExtension.class)} on this enclosing class applies to every
 *   {@code @Nested @SpringBootTest} below the same way (declarative extension registration is
 *   inherited from the enclosing class) — review round 1, security High-1: without it, a
 *   misconfigured {@code infra/.env} {@code DB_URL} would let Flyway/this sweep run against
 *   whatever database that points at, with no pre-refresh abort.
 * [Caution] Real cost: ~100 real chat completions + ~100 embeddings (query + BM25/vector
 *   retrieval) + ~50 rerank calls (the two rerank-on combinations). Report the actual {@code
 *   usage.cost} sum after running — do not estimate beforehand (eval-usage-dashboard Spec
 *   guidance). {@code @Tag("eval")} (not {@code eval-infra}) — this is the judge-calling sweep
 *   {@code -Peval} was built for, distinct from BL-024's {@code eval-infra} tag.
 *   <p>
 *   {@link PositiveControls} (AC-20b) runs one deliberate low-{@code maxTokens} call, separate
 *   from all 4 combinations and excluded from every {@link EvalReportRow} — mixing it into a
 *   combination's row would contaminate the 4-way comparison (insight-chat-7f, 2026-09-01).
 *   <p>
 *   <b>Cleanup (review round 1, security/performance Medium):</b> {@link #deleteSweepData} removes
 *   every anonymous user/conversation/message this class creates, at the end of each combination
 *   and of {@link PositiveControls} — {@code AnonymousUserService#resolveOrCreate(null)} always
 *   inserts a new row, and {@code app.session.max-anonymous-users} (1000) has no TTL/reclaim, so
 *   repeated runs without cleanup would permanently erode that production safety limit.
 * [AC] eval-usage-dashboard:AC-7
 * [AC] eval-usage-dashboard:AC-9b
 * [AC] eval-usage-dashboard:AC-19b
 */
@Tag("eval")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@ExtendWith(DatabaseGuardExtension.class)
class EvalSweepIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EvalSweepIntegrationTest.class);

    private static final Path EVAL_SET_PATH = Path.of("Z:/Work/02.insight-chat/docs/eval/eval-set.yaml");

    private static final Path REPORT_DIR = Path.of("Z:/Work/02.insight-chat/docs/eval");

    /**
     * [Purpose] Shared across all 4 nested contexts -- each appends its own row/label, in class
     *   order.
     * [Rationale] Plain {@code ArrayList}, not a concurrent collection (review round 1,
     *   maintainability Low-1) -- {@code @TestClassOrder} runs the 4 {@code @Nested} classes
     *   sequentially and this module has no {@code junit-platform.properties} enabling parallel
     *   execution (confirmed absent), so there is exactly one writer at a time.
     */
    private static final List<EvalReportRow> ROWS = new ArrayList<>();

    private static final List<String> LABELS = new ArrayList<>();

    /**
     * [Purpose] Un-poisons only the {@code chat.*}/{@code embedding.*} keys {@code
     *   config/application.yaml} forces to an unroutable address for every other {@code
     *   @SpringBootTest}, using the real {@code infra/.env} values (never printed here). The
     *   common-level {@code spring.ai.openai.{base-url,api-key}} keys are deliberately left
     *   poisoned here -- see class [Rationale] and {@link VectorRerank#combination}/{@link
     *   HybridRerank#combination}, which are the only two nested classes that additionally open
     *   them.
     */
    @DynamicPropertySource
    static void realOpenRouterCredentials(DynamicPropertyRegistry registry) {
        Properties env = loadEnvProperties();
        String apiKey = requireApiKey(env);
        String baseUrl = env.getProperty("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");
        registry.add("spring.ai.openai.chat.base-url", () -> baseUrl);
        registry.add("spring.ai.openai.chat.api-key", () -> apiKey);
        registry.add("spring.ai.openai.embedding.base-url", () -> baseUrl);
        registry.add("spring.ai.openai.embedding.api-key", () -> apiKey);
    }

    private static String requireApiKey(Properties env) {
        String apiKey = env.getProperty("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "infra/.env must set OPENROUTER_API_KEY to run @Tag(\"eval\") tests");
        }
        return apiKey;
    }

    /**
     * [Purpose] Reads {@code infra/.env} the same way {@code application.yaml}'s {@code
     *   spring.config.import} does, trying the same two relative locations (terminal cwd=backend/
     *   vs. IntelliJ cwd=repo root).
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

    /**
     * [Purpose] Fails fast (before spending any money on this combination) if the singleton
     *   {@code app.search.mode}/{@code app.rerank.enabled} beans this context actually bound do
     *   not match what this {@code @Nested} class's {@code @DynamicPropertySource} asked for.
     * [Rationale] Review round 1, correctness High-4 -- the sweep's only structural assertion
     *   before this fix was "4 rows exist", which cannot distinguish "4 different configurations
     *   ran" from "the same configuration ran 4 times" (e.g. if a future edit silently dropped a
     *   {@code combination()} method). Asserting the live {@link AppProperties} values closes that
     *   gap at the cheapest possible point -- before the 25-question loop even starts.
     */
    private static void assertActiveConfiguration(AppProperties appProperties,
            AppProperties.Search.Mode expectedMode, boolean expectedRerankEnabled) {
        assertThat(appProperties.search().mode())
                .as("app.search.mode actually bound in this context")
                .isEqualTo(expectedMode);
        assertThat(appProperties.rerank().enabled())
                .as("app.rerank.enabled actually bound in this context")
                .isEqualTo(expectedRerankEnabled);
    }

    /**
     * [Purpose] Runs all 25 eval-set cases through the real pipeline for one combination, appends
     *   the resulting row to the shared {@link #ROWS}/{@link #LABELS}, and returns it so the
     *   caller can assert on it.
     * [Rationale] Shared by all 4 {@code @Nested} classes so the per-case call/judge/aggregate
     *   logic exists exactly once. Each case gets its own fresh {@code Conversation} (RAG
     *   enabled) under a fresh anonymous user per combination, so no case's chat-memory window
     *   leaks into another case (they are independent single-turn questions, not a multi-turn
     *   scenario).
     * [Side effects] Deletes the user/conversations/messages it created (via {@link
     *   #deleteSweepData}) before returning -- see class [Caution].
     */
    private static EvalReportRow runCombinationSweep(String label, ChatService chatService,
            ConversationService conversationService, AnonymousUserService anonymousUserService,
            NonIndexedDocumentLookup nonIndexedDocumentLookup, JdbcTemplate jdbcTemplate) {
        EvalSet evalSet = EvalSetLoader.load(EVAL_SET_PATH);
        UUID userId = anonymousUserService.resolveOrCreate(null);
        List<JudgedCase> judgedCases = new ArrayList<>();

        try {
            for (EvalCase evalCase : evalSet.cases()) {
                Conversation conversation = conversationService.createConversation(userId, null, true);
                long start = System.currentTimeMillis();
                List<ChatChunk> chunks = chatService.streamReply(conversation, null, evalCase.question())
                        .collectList()
                        .block(Duration.ofSeconds(90));
                long latencyMs = System.currentTimeMillis() - start;

                String answer = concatenateDeltas(chunks);
                ChatChunk terminal = findTerminalChunk(chunks);
                // review round 1, correctness Medium-1: an infra-level stream failure (ChatChunk.error,
                // finishReason="error") must not be silently scored as a wrong/hallucinated answer --
                // fail this case loudly instead so the failure is visible as itself, not as a quality
                // data point. The "unknown" arm is defensive rather than reachable today (round 2,
                // correctness Low-3): ChatChunk.toChunk never sets it on a wire chunk -- only
                // onStreamTerminated's persist-time fallback does -- so a genuinely unmapped finish
                // reason would surface as findTerminalChunk's IllegalStateException first, not here.
                if ("error".equals(terminal.finishReason()) || "unknown".equals(terminal.finishReason())) {
                    throw new AssertionError("combination=" + label + " question=\"" + evalCase.question()
                            + "\" ended with infra-level finishReason=" + terminal.finishReason()
                            + " -- this is a stream failure, not a model answer; investigate before re-running");
                }
                List<String> sourceFilenames = terminal.sources() == null
                        ? List.of()
                        : terminal.sources().stream().map(ChatChunk.Source::filename).toList();
                // usage does not always land on the same chunk as finishReason -- ChatService's own
                // Javadoc: "the real token/cost summary lands only on the stream's last ChatResponse"
                // (T11), which onStreamTerminated reads from a separate holder than finishReason's.
                // Scan every chunk for the last non-null usage rather than assuming it is on `terminal`.
                ChatChunk.Usage usage = findLastUsage(chunks);
                BigDecimal costUsd = usage == null ? null : usage.cost();

                EvalResponse response = new EvalResponse(answer, sourceFilenames, latencyMs, costUsd);
                JudgeResult result = EvalJudge.judge(evalCase, response);

                int retrievedNonIndexedChunkCount = terminal.sources() == null
                        ? 0
                        : (int) terminal.sources().stream()
                                .filter(source -> !isIndexed(jdbcTemplate, source.documentId()))
                                .count();

                judgedCases.add(new JudgedCase(response, result, retrievedNonIndexedChunkCount,
                        terminal.finishReason()));

                log.info("[eval sweep] combination={} question=\"{}\" latencyMs={} cost={} finishReason={}",
                        label, evalCase.question(), latencyMs, costUsd, terminal.finishReason());
            }
        } finally {
            deleteSweepData(jdbcTemplate, userId);
        }

        List<EvalExcludedDocumentStatusCount> excludedByStatus = nonIndexedDocumentLookup.countExcludedByStatus()
                .stream()
                .map(c -> new EvalExcludedDocumentStatusCount(c.status(), c.count()))
                .toList();
        EvalReportRow row = EvalReportAggregator.aggregate(judgedCases, excludedByStatus);
        ROWS.add(row);
        LABELS.add(label);
        log.info("[eval sweep] combination={} row={}", label, row);
        return row;
    }

    /**
     * [Purpose] Asserts the invariants every combination row must satisfy regardless of judged
     *   quality (review round 1): {@code AC-19b}'s positive control actually counted (a
     *   chunk-less {@code FAILED} document from BL-023 is expected to remain seeded throughout
     *   the sweep -- do not delete it), the filter-survival field stays 0, and a usage-extraction
     *   regression (the exact bug this class's smoke test caught, correctness Medium-2) cannot
     *   pass silently as an all-null-cost row.
     * [AC] eval-usage-dashboard:AC-19b
     */
    private static void assertRowInvariants(EvalReportRow row, String label) {
        assertThat(row.excludedByStatus())
                .as("AC-19b positive control (BL-023's FAILED document) for %s", label)
                .anyMatch(c -> "FAILED".equals(c.status()) && c.count() >= 1);
        assertThat(row.retrievedNonIndexedChunkCount())
                .as("filter-survival evidence (AC-19a field 2) for %s", label)
                .isZero();
        // review round 2, correctness Low-5: 0 above must mean "the filter kept non-indexed chunks
        // out", not "no chunks were retrieved at all" -- sourceCorrect > 0 proves real retrieval
        // actually returned matching sources for at least one found-case in this combination.
        assertThat(row.sourceCorrect())
                .as("sourceCorrect for %s -- retrieval must have returned something for the 0 above to mean anything",
                        label)
                .isGreaterThan(0);
        assertThat(row.missingCostCount())
                .as("missingCostCount for %s -- non-zero means usage extraction regressed silently", label)
                .isZero();
        assertThat(row.totalCostUsd())
                .as("totalCostUsd for %s -- every case was a real paid call", label)
                .isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * [Purpose] Deletes every {@code messages}/{@code conversations}/{@code users} row this class
     *   created for {@code userId} (review round 1, security Medium-2 + performance Medium-1).
     * [Rationale] {@code AnonymousUserService#resolveOrCreate(null)} always inserts a new row and
     *   {@code app.session.max-anonymous-users}(1000) has no TTL/reclaim (that class's own
     *   Javadoc) -- left uncleaned, ~200 sweep runs would exhaust the production anonymous-user
     *   quota. Deletes in FK-safe order (messages -> conversations -> users); never touches the
     *   {@code documents}/{@code vector_store_bgem3} rows the sweep only reads (BL-023's FAILED
     *   document, the real indexed document) -- those are shared fixtures this class does not own.
     */
    private static void deleteSweepData(JdbcTemplate jdbcTemplate, UUID userId) {
        jdbcTemplate.update(
                "DELETE FROM messages WHERE conversation_id IN "
                        + "(SELECT id FROM conversations WHERE user_id = ?)",
                userId);
        jdbcTemplate.update("DELETE FROM conversations WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    /** [Purpose] Concatenates every chunk's {@code delta} in arrival order into the full answer text. */
    private static String concatenateDeltas(List<ChatChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (ChatChunk chunk : chunks) {
            if (chunk.delta() != null) {
                builder.append(chunk.delta());
            }
        }
        return builder.toString();
    }

    /**
     * [Purpose] Returns the first chunk carrying a non-null {@code finishReason} -- the terminal
     *   response, whose {@code sources} {@link ChatService#toChunk} also sets in the same branch.
     * [Rationale] "First", not "last": on the error path, {@code ChatService.error(...)} appends
     *   one more chunk after the real terminal one with {@code finishReason="error"} and {@code
     *   sources=null} -- picking "last" would let that synthetic error chunk masquerade as the
     *   real terminal response and silently drop the sources a successful run actually produced.
     */
    private static ChatChunk findTerminalChunk(List<ChatChunk> chunks) {
        for (ChatChunk chunk : chunks) {
            if (chunk.finishReason() != null) {
                return chunk;
            }
        }
        throw new IllegalStateException("No chunk with a non-null finishReason -- stream never terminated normally");
    }

    /**
     * [Purpose] Finds the last non-null {@code usage} across every chunk of one stream.
     * [Rationale] Smoke test (2026-09-01) found {@code cost=null} on every case despite the
     *   persisted {@code messages.cost_usd} being correct -- usage can land on a later chunk than
     *   the one carrying {@code finishReason} (a trailing usage-only chunk with empty choices is a
     *   common streaming-API shape), matching {@code ChatService#toChunk}'s own Javadoc ("the real
     *   token/cost summary lands only on the stream's last ChatResponse", not necessarily the same
     *   response that carries the finish reason).
     */
    private static ChatChunk.Usage findLastUsage(List<ChatChunk> chunks) {
        ChatChunk.Usage last = null;
        for (ChatChunk chunk : chunks) {
            if (chunk.usage() != null) {
                last = chunk.usage();
            }
        }
        return last;
    }

    /** [Purpose] Reports whether {@code documentId} currently sits at {@code documents.status=INDEXED}. */
    private static boolean isIndexed(JdbcTemplate jdbcTemplate, String documentId) {
        List<String> status = jdbcTemplate.queryForList(
                "SELECT status FROM documents WHERE id = ?::uuid", String.class, documentId);
        return !status.isEmpty() && "INDEXED".equals(status.get(0));
    }

    @Nested
    @Order(1)
    @SpringBootTest
    class VectorNoRerank {
        private static final String LABEL = "vector-no-rerank";

        @DynamicPropertySource
        static void combination(DynamicPropertyRegistry registry) {
            registry.add("app.search.mode", () -> "vector");
            registry.add("app.rerank.enabled", () -> "false");
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private NonIndexedDocumentLookup nonIndexedDocumentLookup;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @Autowired
        private AppProperties appProperties;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-7] " + LABEL + " 조합의 25문항 실제 스윕")
        void runs() {
            assertActiveConfiguration(appProperties, AppProperties.Search.Mode.VECTOR, false);
            EvalReportRow row = runCombinationSweep(LABEL, chatService, conversationService,
                    anonymousUserService, nonIndexedDocumentLookup, jdbcTemplate);
            assertRowInvariants(row, LABEL);
        }
    }

    @Nested
    @Order(2)
    @SpringBootTest
    class VectorRerank {
        private static final String LABEL = "vector-rerank";

        @DynamicPropertySource
        static void combination(DynamicPropertyRegistry registry) {
            registry.add("app.search.mode", () -> "vector");
            registry.add("app.rerank.enabled", () -> "true");
            // review round 1, security Medium-1: only the 2 rerank-on nested classes need the
            // common-level keys OpenRouterRerankAdapter reads -- see class [Rationale].
            Properties env = loadEnvProperties();
            String apiKey = requireApiKey(env);
            String baseUrl = env.getProperty("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");
            registry.add("spring.ai.openai.base-url", () -> baseUrl);
            registry.add("spring.ai.openai.api-key", () -> apiKey);
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private NonIndexedDocumentLookup nonIndexedDocumentLookup;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @Autowired
        private AppProperties appProperties;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-7] " + LABEL + " 조합의 25문항 실제 스윕")
        void runs() {
            assertActiveConfiguration(appProperties, AppProperties.Search.Mode.VECTOR, true);
            EvalReportRow row = runCombinationSweep(LABEL, chatService, conversationService,
                    anonymousUserService, nonIndexedDocumentLookup, jdbcTemplate);
            assertRowInvariants(row, LABEL);
        }
    }

    @Nested
    @Order(3)
    @SpringBootTest
    class HybridNoRerank {
        private static final String LABEL = "hybrid-no-rerank";

        @DynamicPropertySource
        static void combination(DynamicPropertyRegistry registry) {
            registry.add("app.search.mode", () -> "hybrid");
            registry.add("app.rerank.enabled", () -> "false");
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private NonIndexedDocumentLookup nonIndexedDocumentLookup;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @Autowired
        private AppProperties appProperties;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-7] " + LABEL + " 조합의 25문항 실제 스윕")
        void runs() {
            assertActiveConfiguration(appProperties, AppProperties.Search.Mode.HYBRID, false);
            EvalReportRow row = runCombinationSweep(LABEL, chatService, conversationService,
                    anonymousUserService, nonIndexedDocumentLookup, jdbcTemplate);
            assertRowInvariants(row, LABEL);
        }
    }

    @Nested
    @Order(4)
    @SpringBootTest
    class HybridRerank {
        private static final String LABEL = "hybrid-rerank";

        @DynamicPropertySource
        static void combination(DynamicPropertyRegistry registry) {
            registry.add("app.search.mode", () -> "hybrid");
            registry.add("app.rerank.enabled", () -> "true");
            // review round 1, security Medium-1: see VectorRerank#combination's own comment.
            Properties env = loadEnvProperties();
            String apiKey = requireApiKey(env);
            String baseUrl = env.getProperty("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");
            registry.add("spring.ai.openai.base-url", () -> baseUrl);
            registry.add("spring.ai.openai.api-key", () -> apiKey);
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private NonIndexedDocumentLookup nonIndexedDocumentLookup;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @Autowired
        private AppProperties appProperties;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-7] " + LABEL + " 조합의 25문항 실제 스윕")
        void runs() {
            assertActiveConfiguration(appProperties, AppProperties.Search.Mode.HYBRID, true);
            EvalReportRow row = runCombinationSweep(LABEL, chatService, conversationService,
                    anonymousUserService, nonIndexedDocumentLookup, jdbcTemplate);
            assertRowInvariants(row, LABEL);
        }
    }

    /**
     * [Purpose] Reads the 4 rows the nested classes above left in {@link #ROWS}, asserts {@code
     *   AC-7} (four rows, from four actually-different, order-verified configurations) and {@code
     *   AC-9b} (the pass/fail verdict a real sweep computes is exactly what the report exposes),
     *   writes the file-artifact report {@code eval-usage-dashboard} Spec §5/§7 OQ-6 require
     *   (review round 1, maintainability Medium-2), and logs a human-readable summary.
     * [Caution] No {@code @SpringBootTest} here -- this class only reads static state the prior
     *   4 nested classes already populated. {@code @TestClassOrder} guarantees it runs last.
     */
    @Nested
    @Order(5)
    class FinalReport {
        @Test
        @DisplayName("[eval-usage-dashboard:AC-7] 스윕 1회 실행이 4조합 전부에 대한 결과 행을 산출한다")
        void producesFourCombinationRows() {
            assertThat(LABELS)
                    .as("the 4 combinations must run in this exact order, exactly once each")
                    .containsExactly("vector-no-rerank", "vector-rerank", "hybrid-no-rerank", "hybrid-rerank");
            assertThat(ROWS).as("combinations executed: %s", LABELS).hasSize(4);
        }

        /**
         * [Purpose] AC-9b: the pass/fail verdict {@link EvalReport#anyCombinationPassed()} computes
         *   from the real 4-row sweep must be exactly the OR of each row's own AC-9a {@code
         *   passed()} -- proving the report neither reimplements nor drops that judgement.
         * [Rationale] Review round 1, correctness High-2 -- the only prior reference to {@code
         *   anyCombinationPassed()} was a {@code log.info} argument, so a regression that always
         *   returned {@code true} (or the whole {@code EvalReport} construction being deleted)
         *   passed silently.
         */
        @Test
        @DisplayName("[eval-usage-dashboard:AC-9b] 통과 조합 유무가 리포트에 정확히 표시된다")
        void reportsWhetherAnyCombinationPassed() {
            EvalReport report = new EvalReport(ROWS);
            boolean expected = ROWS.stream().anyMatch(EvalReportRow::passed);
            assertThat(report.anyCombinationPassed())
                    .as("anyCombinationPassed must equal OR of each row's own passed() -- rows: %s",
                            ROWS)
                    .isEqualTo(expected);
            // sanity: the fixed 25-item eval set (15 found + 10 not-found) must show up as such in
            // every row, or AC-9a's fail-closed denominator check would make `passed` meaningless.
            for (int i = 0; i < ROWS.size(); i++) {
                EvalReportRow row = ROWS.get(i);
                assertThat(row.foundTotal()).as("%s foundTotal", LABELS.get(i)).isEqualTo(15);
                assertThat(row.notFoundTotal()).as("%s notFoundTotal", LABELS.get(i)).isEqualTo(10);
            }
            log.info("[eval sweep] AC-9b: anyCombinationPassed={} (per-row passed={})",
                    report.anyCombinationPassed(), ROWS.stream().map(EvalReportRow::passed).toList());
        }

        /**
         * [Purpose] Writes the file-artifact report {@code eval-usage-dashboard} Spec §5 ("리포트는
         *   {@code {DOC_ROOT}/docs/eval/}") and §7 OQ-6/§8 risk#5 (T1 must be able to cite a report
         *   without re-running the sweep) require.
         * [Rationale] Review round 1, maintainability Medium-2 -- before this, the only output was
         *   {@code log.info}, which disappears once the Surefire console log is gone.
         * [Side effects] Writes/overwrites {@code {DOC_ROOT}/docs/eval/sweep-report-<date>.md}.
         */
        @Test
        @DisplayName("[eval-usage-dashboard:AC-7] 스윕 결과가 파일 산출물로 남는다")
        void writesReportFile() throws IOException {
            EvalReport report = new EvalReport(ROWS);
            BigDecimal totalCost = ROWS.stream()
                    .map(EvalReportRow::totalCostUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Files.createDirectories(REPORT_DIR);
            Path file = REPORT_DIR.resolve("sweep-report-" + LocalDate.now() + ".md");
            StringBuilder body = new StringBuilder();
            body.append("# E5 4-combination sweep report\n\n");
            body.append("Generated: ").append(OffsetDateTime.now()).append("\n\n");
            body.append("| combination | answerCorrect/found | notFoundCorrect/notFound | ")
                    .append("hallucination | avgLatencyMs | totalCostUsd | passed |\n");
            body.append("|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < ROWS.size(); i++) {
                EvalReportRow row = ROWS.get(i);
                body.append("| ").append(LABELS.get(i))
                        .append(" | ").append(row.answerCorrect()).append('/').append(row.foundTotal())
                        .append(" | ").append(row.notFoundCorrect()).append('/').append(row.notFoundTotal())
                        .append(" | ").append(row.hallucinationCount())
                        .append(" | ").append(row.avgLatencyMs())
                        .append(" | ").append(row.totalCostUsd())
                        .append(" | ").append(row.passed())
                        .append(" |\n");
            }
            body.append("\nTotal cost (4 combinations, 100 real calls): ").append(totalCost).append(" USD\n");
            body.append("Any combination passed (AC-9b): ").append(report.anyCombinationPassed()).append('\n');

            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
            log.info("[eval sweep] report written to {}", file);

            // review round 2, correctness Medium-1: assert the file's actual content, not just its
            // existence -- a truncated or empty write (e.g. Files.writeString(file, "")) previously
            // passed silently, and AC-9b's "표시된다" (displayed) is about content, not just presence.
            String written = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(written)
                    .as("report file must contain every combination's label")
                    .contains(LABELS.toArray(new String[0]));
            assertThat(written)
                    .as("report file must display the AC-9b verdict, not just carry the raw rows")
                    .contains("Any combination passed (AC-9b): " + report.anyCombinationPassed());
        }
    }

    /**
     * [Purpose] AC-20b's positive control: one deliberate low-{@code maxTokens} call proving a
     *   real {@code finish_reason=length} gets recorded, counted in {@code finishReasonCounts},
     *   and defers judgment ({@code judgmentDeferred()}) -- run through the same {@link
     *   ChatService#streamReply(Conversation, String, String, Integer)} production path (
     *   persistence, {@link ChatChunk#normalizeFinishReason}) the normal sweep uses, but with
     *   {@code ragEnabled=false} (review round 2, correctness Low-4 -- "the exact same path" over-
     *   stated this: the normal sweep uses {@code ragEnabled=true} and the 3-arg overload; this
     *   control only needs the 4-arg {@code maxTokens} path, not retrieval).
     * [Rationale] Review round 1, correctness High-3 -- the prior version stopped at "{@code
     *   finishReason=="length"} was observed and persisted", which never actually exercised {@link
     *   EvalReportAggregator}/{@link EvalReportRow#judgmentDeferred()} with a real value. This
     *   builds the same {@link JudgedCase} the normal sweep would and aggregates it alone (not
     *   merged into {@link #ROWS} -- see class [Caution] on why this stays separate).
     * [Caution] Deliberately excluded from {@link #ROWS} -- see class [Caution]. Uses {@code
     *   ragEnabled=false} (no RAG advisor attached) because this control targets {@code
     *   finish_reason} truncation only, not retrieval -- {@code ChatService#streamReply}'s own
     *   Javadoc historically over-stated this control as needing RAG attachment; that claim was
     *   corrected there (review round 1, correctness Low-1) to match this.
     * [AC] eval-usage-dashboard:AC-20b
     */
    @Nested
    @Order(6)
    @SpringBootTest
    class PositiveControls {
        private static final int TINY_MAX_TOKENS = 5;
        private static final String QUESTION =
                "인사이트에이아이 회사소개서의 연혁을 아주 자세히, 최대한 길게 설명해줘.";

        @DynamicPropertySource
        static void combination(DynamicPropertyRegistry registry) {
            registry.add("app.search.mode", () -> "vector");
            registry.add("app.rerank.enabled", () -> "false");
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-20b] maxTokens를 낮춘 실제 호출에서 finish_reason=length가 "
                + "관측·persist되고 리포트 분포·판정유보에 반영된다")
        void lowMaxTokensProducesRealLengthFinishReason() {
            UUID userId = anonymousUserService.resolveOrCreate(null);
            try {
                Conversation conversation = conversationService.createConversation(userId, null, false);

                long start = System.currentTimeMillis();
                List<ChatChunk> chunks = chatService.streamReply(conversation, null, QUESTION, TINY_MAX_TOKENS)
                        .collectList()
                        .block(Duration.ofSeconds(30));
                long latencyMs = System.currentTimeMillis() - start;

                ChatChunk terminal = findTerminalChunk(chunks);
                BigDecimal costUsd = findLastUsage(chunks) == null ? null : findLastUsage(chunks).cost();
                log.info("[eval sweep][AC-20b positive control] finishReason={} maxTokens={}",
                        terminal.finishReason(), TINY_MAX_TOKENS);
                assertThat(terminal.finishReason())
                        .as("a %d-token cap must truncate a long-answer request", TINY_MAX_TOKENS)
                        .isEqualTo("length");

                String persisted = jdbcTemplate.queryForObject(
                        "SELECT finish_reason FROM messages WHERE conversation_id = ? AND role = 'assistant' "
                                + "ORDER BY created_at DESC LIMIT 1",
                        String.class, conversation.getId());
                assertThat(persisted).as("persisted messages.finish_reason").isEqualTo("length");

                // review round 1, correctness High-3: route the real observed finishReason through
                // the same report pipeline the 4-combination sweep uses, isolated to this one case,
                // to prove AC-20a's finishReasonCounts/judgmentDeferred() actually fire on real data.
                EvalCase evalCase = new EvalCase.NotFound(QUESTION);
                EvalResponse response = new EvalResponse(
                        concatenateDeltas(chunks), List.of(), latencyMs, costUsd);
                JudgeResult result = EvalJudge.judge(evalCase, response);
                JudgedCase judgedCase = new JudgedCase(response, result, 0, terminal.finishReason());
                EvalReportRow controlRow = EvalReportAggregator.aggregate(List.of(judgedCase));

                assertThat(controlRow.finishReasonCounts())
                        .as("finishReasonCounts must count this real length observation")
                        .anyMatch(c -> "length".equals(c.finishReason()) && c.count() >= 1);
                assertThat(controlRow.judgmentDeferred())
                        .as("a length finish reason must defer judgment (AC-20a)")
                        .isTrue();
            } finally {
                deleteSweepData(jdbcTemplate, userId);
            }
        }
    }
}
