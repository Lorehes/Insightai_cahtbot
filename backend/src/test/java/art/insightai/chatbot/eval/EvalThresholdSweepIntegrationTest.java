package art.insightai.chatbot.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.service.ChatService;
import art.insightai.chatbot.chat.service.ConversationService;
import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.lang.Language;
import art.insightai.chatbot.eval.evalset.EvalCase;
import art.insightai.chatbot.eval.evalset.EvalSet;
import art.insightai.chatbot.eval.evalset.EvalSetLoader;
import art.insightai.chatbot.eval.judge.EvalJudge;
import art.insightai.chatbot.eval.judge.EvalResponse;
import art.insightai.chatbot.eval.judge.JudgeResult;
import art.insightai.chatbot.eval.report.EvalLanguageReport;
import art.insightai.chatbot.eval.report.EvalLanguageReportRow;
import art.insightai.chatbot.eval.report.EvalReportAggregator;
import art.insightai.chatbot.eval.report.EvalReportRow;
import art.insightai.chatbot.eval.report.JudgedCase;
import art.insightai.chatbot.rag.retrieval.RagEmptyContextGuardAdvisor;
import art.insightai.chatbot.rag.retrieval.VectorSearchPort;
import art.insightai.chatbot.support.DatabaseGuardExtension;
import art.insightai.chatbot.user.AnonymousUserService;

/**
 * [Purpose] D7 sweep, stage 2 (T1): runs the production eval set through the real production
 *   pipeline (real bge-m3 embeddings, real chat completions, no rerank) at the axis-native
 *   thresholds / weights stage 1 proposed, and records the judge verdict per configuration to
 *   {@code {DOC_ROOT}/docs/eval/threshold-sweep-<date>.md}. Three configurations, in class order:
 *   (1) the confirmed defaults ({@link SweepConfirmedDefaults} -- {@code mode=hybrid}, raw
 *   thresholds on, weights 0.4/0.6), (2) the same thresholds in {@code mode=vector} (the mode
 *   comparison AC-12 needs at the final thresholds), (3) {@code mode=hybrid} with both raw
 *   thresholds off (isolates what the thresholds themselves change). A fourth nested class closes
 *   hybrid-rag:AC-9 clause ② with a real two-turn conversation.
 *   Card T9 (multilingual-support, 2026-09-02): T8 grew the production eval set from 25 (ko only)
 *   to 46 (25 ko + 21 en/zh/ja, {@code EvalCase#lang()}). AC-9a's pass/fail gate ({@link
 *   #assertRowSanity}, 15/10) stays scoped to the {@link Language#KO} subset only (AD-11 ②) --
 *   every configuration still runs the full 46-question set through the real pipeline, but only
 *   the ko-tagged {@link JudgedCase}s are handed to {@link EvalReportAggregator#aggregate(List)}
 *   for the gated {@link EvalReportRow}. The full 46-case list is additionally passed to {@link
 *   EvalReportAggregator#aggregateByLanguage(List)} to produce a per-language {@link
 *   EvalLanguageReport} (AC-10/AC-11 baseline) -- measurement only, no gate of its own (AD-11,
 *   {@code EvalLanguageReport}'s own Javadoc).
 * [Rationale] The 2026-09-01 E5 sweep ({@code sweep-report-2026-09-01.md}) scored every
 *   combination {@code notFoundCorrect 0/10, hallucination 10} -- and the persisted answers show
 *   that verdict was mostly a judge artifact: the model refused honestly in free-form Korean
 *   ("제공된 자료에는 … 정보가 포함되어 있지 않습니다") which {@code EvalJudge}'s fixed-phrase rule
 *   counts as hallucination, plus one real hallucination (특허 -- counted SW copyrights as patents).
 *   Stage 1 then showed no raw threshold separates found from not-found on this corpus. So the
 *   design under test here is: thresholds as a measured noise floor, the prompt instructing the
 *   model to answer "not found" with exactly the fixed sentence, and {@code ChatService} dropping
 *   {@code sources} for such an answer (hybrid-rag AC-23) -- which is what makes {@code
 *   notFoundCorrect} (refusal AND empty sources) reachable at all when chunks are retrieved.
 *   {@code app.search.score-threshold} (the post-fusion filter, AC-4) stays 0.0 in every
 *   configuration: BL-025 established it carries no relevance information.
 * [Caution] Real cost -- 3 x 46 = 138 chat completions + 4 for the multi-turn probe (+ embeddings),
 *   up from 75 pre-T9 now that the eval set carries the en/zh/ja items. Report the measured
 *   {@code usage.cost} sum, never an estimate. {@code @Tag("eval")}, run with {@code -Peval};
 *   needs {@code chatbot-db}, {@code infra/.env}, and the {@code Z:} drive. Each nested context
 *   deletes every user/conversation/message it creates. Not-found answers and the compressed
 *   multi-turn query are written to the report file (DOC_ROOT, not the repo) because they are the
 *   only evidence of *why* a verdict came out the way it did.
 * [AC] eval-usage-dashboard:AC-12
 * [AC] hybrid-rag:AC-9
 * [AC] multilingual-support:AC-10
 * [AC] multilingual-support:AC-11
 */
@Tag("eval")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@ExtendWith(DatabaseGuardExtension.class)
class EvalThresholdSweepIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EvalThresholdSweepIntegrationTest.class);

    private static final Path EVAL_SET_PATH = Path.of("Z:/Work/02.insight-chat/docs/eval/eval-set.yaml");
    private static final Path REPORT_DIR = Path.of("Z:/Work/02.insight-chat/docs/eval");

    private record CaseDetail(EvalCase evalCase, String answer, List<String> sources, String finishReason,
            long latencyMs, BigDecimal costUsd, JudgeResult result) {
    }

    /**
     * [Purpose] One configuration's sweep result -- {@code row} is the AC-9a-gated {@link
     *   EvalReportRow} scoped to the {@link Language#KO} subset only; {@code languageReport} is the
     *   ungated per-language breakdown (AC-10/AC-11) over the full case list (T9).
     */
    private record SweepRun(String label, String description, EvalReportRow row, List<CaseDetail> cases,
            EvalLanguageReport languageReport) {
    }

    /**
     * [Purpose] {@code -Dsweep.repeats=N} runs every configuration N times (default 1) so the
     *   run-to-run noise of a 15/10-question set at temperature 0.7 can be measured instead of
     *   assumed -- insight-chat-7f, 2026-09-01: one run per configuration cannot tell a real
     *   threshold effect from a single differently-worded answer. {@code -Dsweep.report.suffix}
     *   names the report file so a repeat run does not overwrite the first one.
     */
    private static final int REPEATS = Integer.getInteger("sweep.repeats", 1);
    private static final String REPORT_SUFFIX = System.getProperty("sweep.report.suffix", "");

    private static final List<SweepRun> RUNS = new ArrayList<>();
    private static String compressedMultiTurnQuery;
    private static BigDecimal multiTurnCost = BigDecimal.ZERO;

    private static void confirmedThresholdsAndWeights(DynamicPropertyRegistry registry) {
        registry.add("app.search.rrf-k", () -> String.valueOf(SweepConfirmedDefaults.RRF_K));
        registry.add("app.search.weight-bm25", () -> String.valueOf(SweepConfirmedDefaults.WEIGHT_BM25));
        registry.add("app.search.weight-vector", () -> String.valueOf(SweepConfirmedDefaults.WEIGHT_VECTOR));
        registry.add("app.search.score-threshold", () -> String.valueOf(SweepConfirmedDefaults.SCORE_THRESHOLD));
        registry.add("app.search.bm25-raw-score-threshold",
                () -> String.valueOf(SweepConfirmedDefaults.BM25_RAW_SCORE_THRESHOLD));
        registry.add("app.search.vector-raw-score-threshold",
                () -> String.valueOf(SweepConfirmedDefaults.VECTOR_RAW_SCORE_THRESHOLD));
    }

    /** [Purpose] Runs one configuration {@link #REPEATS} times; returns the runs in order. */
    private static List<SweepRun> runSweep(String label, String description, ChatService chatService,
            ConversationService conversationService, AnonymousUserService anonymousUserService,
            JdbcTemplate jdbcTemplate) {
        List<SweepRun> runs = new ArrayList<>();
        for (int i = 1; i <= REPEATS; i++) {
            String runLabel = REPEATS == 1 ? label : label + " | run " + i;
            runs.add(runSweepOnce(runLabel, description, chatService, conversationService,
                    anonymousUserService, jdbcTemplate));
        }
        return runs;
    }

    private static SweepRun runSweepOnce(String label, String description, ChatService chatService,
            ConversationService conversationService, AnonymousUserService anonymousUserService,
            JdbcTemplate jdbcTemplate) {
        EvalSet evalSet = EvalSetLoader.load(EVAL_SET_PATH);
        UUID userId = anonymousUserService.resolveOrCreate(null);
        List<JudgedCase> judgedCases = new ArrayList<>();
        List<CaseDetail> details = new ArrayList<>();
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
                if ("error".equals(terminal.finishReason()) || "unknown".equals(terminal.finishReason())) {
                    throw new AssertionError("configuration=" + label + " question=\"" + evalCase.question()
                            + "\" ended with infra-level finishReason=" + terminal.finishReason());
                }
                List<String> sourceFilenames = terminal.sources() == null
                        ? List.of()
                        : terminal.sources().stream().map(ChatChunk.Source::filename).toList();
                ChatChunk.Usage usage = findLastUsage(chunks);
                BigDecimal costUsd = usage == null ? null : usage.cost();

                EvalResponse response = new EvalResponse(answer, sourceFilenames, latencyMs, costUsd);
                JudgeResult result = EvalJudge.judge(evalCase, response);
                // 5-arg canonical: carries the case's real declared language (AD-10) instead of the
                // 4-arg legacy overload's silent Language.KO default -- T9
                judgedCases.add(new JudgedCase(response, result, 0, terminal.finishReason(), evalCase.lang()));
                details.add(new CaseDetail(evalCase, answer, sourceFilenames, terminal.finishReason(),
                        latencyMs, costUsd, result));
                log.info("[threshold sweep] configuration={} question=\"{}\" latencyMs={} cost={} finishReason={} sources={}",
                        label, evalCase.question(), latencyMs, costUsd, terminal.finishReason(), sourceFilenames.size());
            }
        } finally {
            deleteSweepData(jdbcTemplate, userId);
        }
        // AC-9a's gate stays scoped to the ko subset only (AD-11 ②, T9) -- the full 46-case list
        // would desync foundTotal/notFoundTotal from the fixed 15/10 AC9_* constants EvalReportRow
        // .passed() checks, silently failing every configuration for the wrong reason.
        List<JudgedCase> koJudgedCases = judgedCases.stream()
                .filter(judgedCase -> judgedCase.questionLanguage() == Language.KO)
                .toList();
        EvalReportRow row = EvalReportAggregator.aggregate(koJudgedCases);
        // Ungated per-language breakdown over the full case list (AC-10/AC-11 baseline, T9).
        EvalLanguageReport languageReport = EvalReportAggregator.aggregateByLanguage(judgedCases);
        SweepRun run = new SweepRun(label, description, row, details, languageReport);
        RUNS.add(run);
        log.info("[threshold sweep] configuration={} row={}", label, row);
        log.info("[threshold sweep][multilingual] configuration={} languageReport={}", label, languageReport);
        return run;
    }

    private static void assertRowSanity(EvalReportRow row, String label) {
        assertThat(row.foundTotal()).as("%s foundTotal", label).isEqualTo(15);
        assertThat(row.notFoundTotal()).as("%s notFoundTotal", label).isEqualTo(10);
        assertThat(row.missingCostCount()).as("%s missingCostCount", label).isZero();
        assertThat(row.totalCostUsd()).as("%s totalCostUsd", label).isGreaterThan(BigDecimal.ZERO);
    }

    private static void deleteSweepData(JdbcTemplate jdbcTemplate, UUID userId) {
        jdbcTemplate.update(
                "DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE user_id = ?)",
                userId);
        jdbcTemplate.update("DELETE FROM conversations WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    private static String concatenateDeltas(List<ChatChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (ChatChunk chunk : chunks) {
            if (chunk.delta() != null) {
                builder.append(chunk.delta());
            }
        }
        return builder.toString();
    }

    private static ChatChunk findTerminalChunk(List<ChatChunk> chunks) {
        for (ChatChunk chunk : chunks) {
            if (chunk.finishReason() != null) {
                return chunk;
            }
        }
        throw new IllegalStateException("No chunk with a non-null finishReason -- stream never terminated normally");
    }

    private static ChatChunk.Usage findLastUsage(List<ChatChunk> chunks) {
        ChatChunk.Usage last = null;
        for (ChatChunk chunk : chunks) {
            if (chunk.usage() != null) {
                last = chunk.usage();
            }
        }
        return last;
    }

    /** [Purpose] The confirmed configuration -- must pass AC-9a's gate. */
    @Nested
    @Order(1)
    @SpringBootTest
    class ConfirmedHybrid {
        static final String LABEL = "hybrid | raw thresholds 1.3/0.35 | weights 0.4/0.6";

        @DynamicPropertySource
        static void configuration(DynamicPropertyRegistry registry) {
            OpenRouterEnv.openChatAndEmbedding(registry);
            registry.add("app.search.mode", () -> "hybrid");
            registry.add("app.rerank.enabled", () -> "false");
            confirmedThresholdsAndWeights(registry);
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @Autowired
        private AppProperties appProperties;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-12] 확정 조합(mode=hybrid, rerank=false)을 확정 임계값·가중치로 실제 스윕하면 "
                + "AC-9a 판정식(not_found 10/10 이고 answer >= 13/15)을 통과한다")
        void confirmedConfigurationPasses() {
            AppProperties.Search search = appProperties.search();
            assertThat(search.mode()).isEqualTo(SweepConfirmedDefaults.MODE);
            assertThat(appProperties.rerank().enabled()).isEqualTo(SweepConfirmedDefaults.RERANK_ENABLED);
            assertThat(search.bm25RawScoreThreshold()).isEqualTo(SweepConfirmedDefaults.BM25_RAW_SCORE_THRESHOLD);
            assertThat(search.vectorRawScoreThreshold()).isEqualTo(SweepConfirmedDefaults.VECTOR_RAW_SCORE_THRESHOLD);
            assertThat(search.weightBm25()).isEqualTo(SweepConfirmedDefaults.WEIGHT_BM25);
            assertThat(search.weightVector()).isEqualTo(SweepConfirmedDefaults.WEIGHT_VECTOR);

            List<SweepRun> runs = runSweep(LABEL, "confirmed defaults (application.yaml)", chatService,
                    conversationService, anonymousUserService, jdbcTemplate);
            for (SweepRun run : runs) {
                assertRowSanity(run.row(), run.label());
            }
            // every repetition must pass -- a single failing run is exactly the instability this
            // gate exists to surface, not something to average away
            assertThat(runs).as("AC-9a gate on every run of the confirmed configuration")
                    .allSatisfy(run -> assertThat(run.row().passed())
                            .as("%s -- row: %s", run.label(), run.row()).isTrue());
        }
    }

    /** [Purpose] Same thresholds/weights, {@code mode=vector} -- the comparison AC-12 rests on. */
    @Nested
    @Order(2)
    @SpringBootTest
    class VectorAtConfirmedThresholds {
        static final String LABEL = "vector | raw thresholds 1.3/0.35 | weights 0.4/0.6";

        @DynamicPropertySource
        static void configuration(DynamicPropertyRegistry registry) {
            OpenRouterEnv.openChatAndEmbedding(registry);
            registry.add("app.search.mode", () -> "vector");
            registry.add("app.rerank.enabled", () -> "false");
            confirmedThresholdsAndWeights(registry);
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @Autowired
        private AppProperties appProperties;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-12] 비교 조합(mode=vector)을 같은 임계값으로 실제 스윕해 결과 행을 남긴다")
        void vectorComparisonRuns() {
            assertThat(appProperties.search().mode()).isEqualTo(AppProperties.Search.Mode.VECTOR);
            for (SweepRun run : runSweep(LABEL, "mode comparison at the confirmed thresholds", chatService,
                    conversationService, anonymousUserService, jdbcTemplate)) {
                assertRowSanity(run.row(), run.label());
            }
        }
    }

    /** [Purpose] Isolation control -- what the raw thresholds themselves change. */
    @Nested
    @Order(3)
    @SpringBootTest
    class HybridWithoutRawThresholds {
        static final String LABEL = "hybrid | raw thresholds off (0.0/0.0) | weights 0.4/0.6";

        @DynamicPropertySource
        static void configuration(DynamicPropertyRegistry registry) {
            OpenRouterEnv.openChatAndEmbedding(registry);
            registry.add("app.search.mode", () -> "hybrid");
            registry.add("app.rerank.enabled", () -> "false");
            confirmedThresholdsAndWeights(registry);
            registry.add("app.search.bm25-raw-score-threshold", () -> "0.0");
            registry.add("app.search.vector-raw-score-threshold", () -> "0.0");
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @Autowired
        private AppProperties appProperties;

        @Test
        @DisplayName("[eval-usage-dashboard:AC-12] 대조 조합(hybrid, 원점수 임계값 비활성)을 실제 스윕해 임계값 효과를 분리한다")
        void controlWithoutThresholdsRuns() {
            assertThat(appProperties.search().bm25RawScoreThreshold()).isZero();
            assertThat(appProperties.search().vectorRawScoreThreshold()).isZero();
            for (SweepRun run : runSweep(LABEL, "control: same weights, raw thresholds disabled", chatService,
                    conversationService, anonymousUserService, jdbcTemplate)) {
                assertRowSanity(run.row(), run.label());
            }
        }
    }

    /**
     * [Purpose] hybrid-rag:AC-9 clause ② ("압축된 질의가 직전 턴의 핵심 명사를 포함한다") -- the one
     *   clause no stub-based test can judge (BL-022: with {@code StubChatModel} the compressed
     *   query is whatever the test enqueued). A real two-turn conversation through {@code
     *   ChatService} with the real compression model; the query string that actually reaches the
     *   vector axis on turn 2 is captured with a spy on the real {@link VectorSearchPort} bean.
     * [Rationale] BL-022 proposed a manual 🔶 check; the {@code @Tag("eval")} category is exactly
     *   the "isolated live integration test" its own invalidation condition ③ named, so this is
     *   automated evidence of the same observation, at the same cost (one compression call).
     * [AC] hybrid-rag:AC-9
     */
    @Nested
    @Order(4)
    @SpringBootTest
    class MultiTurnCompression {
        private static final String TURN_1 = "회사 설립일이 언제야?";
        private static final String TURN_2 = "그때 대표이사는 누구였어?";

        @DynamicPropertySource
        static void configuration(DynamicPropertyRegistry registry) {
            OpenRouterEnv.openChatAndEmbedding(registry);
            registry.add("app.search.mode", () -> "hybrid");
            registry.add("app.rerank.enabled", () -> "false");
            confirmedThresholdsAndWeights(registry);
        }

        @Autowired
        private ChatService chatService;
        @Autowired
        private ConversationService conversationService;
        @Autowired
        private AnonymousUserService anonymousUserService;
        @Autowired
        private JdbcTemplate jdbcTemplate;
        @MockitoSpyBean
        private VectorSearchPort vectorSearchPort;

        @Test
        @DisplayName("[hybrid-rag:AC-9] 실제 2턴 대화에서 대명사만 담긴 후속 질의(\"그때 대표이사는 누구였어?\")로 검색에 쓰인 "
                + "질의 문자열이 원문과 다르고 직전 턴의 핵심 명사(설립/2014/인사이트에이아이/회사)를 포함한다 (절② 종단 실측)")
        void compressedFollowUpQueryCarriesThePreviousTurnsKeyNoun() {
            UUID userId = anonymousUserService.resolveOrCreate(null);
            try {
                Conversation conversation = conversationService.createConversation(userId, null, true);
                List<ChatChunk> first = chatService.streamReply(conversation, null, TURN_1)
                        .collectList().block(Duration.ofSeconds(90));
                List<ChatChunk> second = chatService.streamReply(conversation, null, TURN_2)
                        .collectList().block(Duration.ofSeconds(90));
                multiTurnCost = costOf(first).add(costOf(second));

                ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
                verify(vectorSearchPort, atLeast(2)).search(queries.capture(), anyInt());
                String searchedOnTurn2 = queries.getAllValues().get(queries.getAllValues().size() - 1);
                compressedMultiTurnQuery = searchedOnTurn2;
                log.info("[threshold sweep][AC-9 ②] turn-2 search query=\"{}\" (original=\"{}\")",
                        searchedOnTurn2, TURN_2);

                assertThat(searchedOnTurn2).as("compressed query differs from the pronoun-only original")
                        .isNotEqualTo(TURN_2);
                assertThat(searchedOnTurn2)
                        .as("compressed query carries a key noun of turn 1 (\"%s\")", TURN_1)
                        .containsAnyOf("설립", "2014", "인사이트에이아이", "회사");
                assertThat(concatenateDeltas(second)).as("turn-2 answer").isNotBlank();
            } finally {
                deleteSweepData(jdbcTemplate, userId);
            }
        }

        private static BigDecimal costOf(List<ChatChunk> chunks) {
            ChatChunk.Usage usage = findLastUsage(chunks);
            return usage == null || usage.cost() == null ? BigDecimal.ZERO : usage.cost();
        }
    }

    /** [Purpose] Writes the file artifact T1 cites; reads only the static state the classes above left. */
    @Nested
    @Order(5)
    class FinalReport {
        @Test
        @DisplayName("[eval-usage-dashboard:AC-12] 2단계 스윕 결과(조합별 판정·비용·없는 질문 답변 실물)가 파일 산출물로 남는다")
        void writesReportFile() throws IOException {
            assertThat(RUNS).as("at least one configuration ran").isNotEmpty();
            BigDecimal totalCost = RUNS.stream().map(r -> r.row().totalCostUsd())
                    .reduce(BigDecimal.ZERO, BigDecimal::add).add(multiTurnCost);

            StringBuilder body = new StringBuilder();
            body.append("# D7 sweep stage 2 -- real-call verdict at the confirmed thresholds (eval T1)\n\n");
            body.append("Generated: ").append(OffsetDateTime.now()).append('\n');
            body.append("Judge: EvalJudge (fixed rules, no LLM judge). Rerank: off in every configuration. ")
                    .append("`app.search.score-threshold` (post-fusion, AC-4) = 0.0 in every configuration -- ")
                    .append("the swept thresholds are the two pre-fusion axis-native ones (BL-025): ")
                    .append("`bm25-raw-score-threshold` / `vector-raw-score-threshold`. rrf-k=60 everywhere.\n\n");
            body.append("Stage 1 (raw-score-distribution-2026-09-01.md) showed both axes' found/not-found ")
                    .append("distributions overlap, so the thresholds below are a noise floor, not a separator; ")
                    .append("\"not found\" is closed by the prompt's fixed-sentence rule + ChatService dropping ")
                    .append("sources for exactly that answer (hybrid-rag AC-23).\n\n");
            body.append("| configuration | answerCorrect/found | sourceCorrect/found | notFoundCorrect/notFound | ")
                    .append("hallucination | avgLatencyMs | totalCostUsd | passed (AC-9a) |\n");
            body.append("|---|---|---|---|---|---|---|---|\n");
            for (SweepRun run : RUNS) {
                EvalReportRow row = run.row();
                body.append("| ").append(run.label())
                        .append(" | ").append(row.answerCorrect()).append('/').append(row.foundTotal())
                        .append(" | ").append(row.sourceCorrect()).append('/').append(row.foundTotal())
                        .append(" | ").append(row.notFoundCorrect()).append('/').append(row.notFoundTotal())
                        .append(" | ").append(row.hallucinationCount())
                        .append(" | ").append(row.avgLatencyMs())
                        .append(" | ").append(row.totalCostUsd())
                        .append(" | ").append(row.passed())
                        .append(" |\n");
            }
            body.append("\nTotal measured cost (3 x 25 chat calls + 2-turn AC-9 probe): ").append(totalCost).append(" USD\n");

            // Fixed-sentence compliance (INC-024 guard): AC-23 only works while the model emits the
            // sentence *exactly*; an answer that merely contains it keeps its sources and is judged
            // as "refused but sources non-empty" (third state) -- count both shapes explicitly.
            long exactRefusals = 0;
            long looseRefusals = 0;
            for (SweepRun run : RUNS) {
                for (CaseDetail c : run.cases()) {
                    if (RagEmptyContextGuardAdvisor.isNotFoundAnswer(c.answer())) {
                        exactRefusals++;
                    } else if (c.answer().contains(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE.replace(".", ""))) {
                        looseRefusals++;
                    }
                }
            }
            body.append("\n## Fixed-sentence compliance (AC-23 precondition)\n\n");
            body.append("- answers that are exactly the fixed sentence (sources emptied): ").append(exactRefusals).append('\n');
            body.append("- answers that contain the sentence but say more (sources kept): ").append(looseRefusals).append('\n');
            body.append("- compliance: ").append(exactRefusals).append('/').append(exactRefusals + looseRefusals).append('\n');
            body.append("\n## hybrid-rag:AC-9 clause ② -- real compressed query\n\n");
            body.append("- turn 1: `회사 설립일이 언제야?`\n- turn 2 (sent): `그때 대표이사는 누구였어?`\n");
            body.append("- turn 2 (query that reached the vector axis): `").append(compressedMultiTurnQuery).append("`\n");

            // multilingual-support AC-10/AC-11 baseline (T9): measurement only -- no pass/fail gate
            // of its own (AD-11). AC-9a above is scored on the ko subset only; these rows show the
            // same four metrics plus the answer-language match rate for every language, including
            // languages that gate did not evaluate.
            body.append("\n## 언어별 측정 (multilingual-support AC-10/AC-11 -- 게이트 없음, 측정치만)\n\n");
            for (SweepRun run : RUNS) {
                body.append("\n### ").append(run.label()).append('\n');
                body.append("| language | answerCorrect/found | sourceCorrect/found | notFoundCorrect/notFound | ")
                        .append("hallucination | answerLangMatch/caseTotal |\n");
                body.append("|---|---|---|---|---|---|\n");
                for (EvalLanguageReportRow langRow : run.languageReport().rows()) {
                    EvalReportRow metrics = langRow.metrics();
                    body.append("| ").append(langRow.language())
                            .append(" | ").append(metrics.answerCorrect()).append('/').append(metrics.foundTotal())
                            .append(" | ").append(metrics.sourceCorrect()).append('/').append(metrics.foundTotal())
                            .append(" | ").append(metrics.notFoundCorrect()).append('/').append(metrics.notFoundTotal())
                            .append(" | ").append(metrics.hallucinationCount())
                            .append(" | ").append(langRow.answerLanguageMatchCount()).append('/').append(langRow.caseTotal())
                            .append(" |\n");
                }
            }

            for (SweepRun run : RUNS) {
                body.append("\n## ").append(run.label()).append(" -- per-case detail\n\n");
                body.append("| # | type | question | verdict | finish | sources | answer (first 160 chars) |\n|---|---|---|---|---|---|---|\n");
                int n = 0;
                for (CaseDetail c : run.cases()) {
                    n++;
                    String verdict = switch (c.result()) {
                        case JudgeResult.Found f -> (f.answerCorrect() ? "answer ok" : "answer WRONG")
                                + (f.sourceCorrect() ? ", source ok" : ", source MISSING");
                        case JudgeResult.NotFound nf -> nf.notFoundCorrect() ? "not_found ok"
                                : nf.hallucination() ? "HALLUCINATION (no fixed phrase)" : "refused but sources non-empty";
                    };
                    String answer = c.answer().replace('\n', ' ').replace('|', '/');
                    answer = answer.substring(0, Math.min(160, answer.length()));
                    body.append("| ").append(n)
                            .append(" | ").append(c.evalCase() instanceof EvalCase.Found ? "found" : "not_found")
                            .append(" | ").append(c.evalCase().question().replace('|', '/'))
                            .append(" | ").append(verdict)
                            .append(" | ").append(c.finishReason())
                            .append(" | ").append(c.sources().size())
                            .append(" | ").append(answer)
                            .append(" |\n");
                }
            }

            Files.createDirectories(REPORT_DIR);
            Path file = REPORT_DIR.resolve("threshold-sweep-" + LocalDate.now() + REPORT_SUFFIX + ".md");
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
            log.info("[threshold sweep] report written to {} (total cost {})", file, totalCost);
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("passed (AC-9a)");
        }
    }
}
