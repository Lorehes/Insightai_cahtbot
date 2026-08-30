package art.insightai.chatbot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.MessageChatMemoryRepository;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.rag.retrieval.Bm25SearchPort;
import art.insightai.chatbot.rag.retrieval.HybridDocumentRetriever;
import art.insightai.chatbot.rag.retrieval.RagAdvisorConfig;
import art.insightai.chatbot.rag.retrieval.RagAdvisorPair;
import art.insightai.chatbot.rag.retrieval.RagEmptyContextGuardAdvisor;
import art.insightai.chatbot.rag.retrieval.ReciprocalRankFusion;
import art.insightai.chatbot.rag.retrieval.RerankPort;
import art.insightai.chatbot.rag.retrieval.SourceRefMapper;
import art.insightai.chatbot.rag.retrieval.SpringAiDocumentRetrieverAdapter;
import art.insightai.chatbot.rag.retrieval.VectorSearchPort;
import art.insightai.chatbot.support.StubChatModel;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * [Purpose] hybrid-rag Spec T10 ({@code "ChatService 결합 — rag_enabled 조건부 어드바이저 부착"}
 *   (quoted verbatim, Spec card title) — "{@code ChatService} wiring: conditional advisor
 *   attachment on {@code rag_enabled}"): end-to-end evidence, through {@link
 *   ChatService#streamReply} itself (not {@code RagAdvisorConfig}'s own beans called directly,
 *   which is {@code RagAdvisorAssemblyTest}'s scope), for the three ACs this card owns —
 *   hybrid-rag:AC-10 (no search when {@code rag_enabled=false}), hybrid-rag:AC-14 (final) (a
 *   search-axis exception does not fail the chat response), and hybrid-rag:AC-9 clause ① (final)
 *   (a real multi-turn request searches with the compressed query, not the raw follow-up). Every
 *   test here builds a *real* {@link ChatClient}
 *   (backed by {@link StubChatModel}) and real {@code RagAdvisorConfig}-assembled advisor beans,
 *   passed into {@link ChatService}'s constructor exactly as Spring would autowire them in
 *   production — {@link ChatServiceTest} deliberately does the opposite (a fully mocked fluent
 *   chain) for its own, unrelated AC coverage, and its two advisor constructor params stay
 *   unused mocks there because every one of its fixtures leaves {@code isRagEnabled()} at
 *   Mockito's boolean default ({@code false}).
 * [Rationale] {@link #chatMemoryRepository} is stubbed per test to a history list that already
 *   ends with the current turn's own question — mirroring what a real, DB-backed repository would
 *   already hold by the time {@link ChatService#streamReply} reads it (Tech Review A-4's call
 *   order: insert THEN read) — the same technique {@code ChatServiceTest
 *   #secondMessageInSameConversationIncludesFirstTurnInThePrompt} already established. {@link
 *   #conversationStub} always takes an explicit {@code ragEnabled} argument, never leaving it at
 *   Mockito's boolean default, per this Spec's own T10 hand-off note (Spec, {@code "AC-10 원문은
 *   rag_enabled=false 일 때의 동작만 규정하고 기본값이 무엇인지는 규정하지 않는다"} (quoted verbatim) —
 *   "AC-10's text only specifies behavior when {@code rag_enabled=false}; it does not specify what
 *   the default value is") — a fixture that
 *   relied on the default would silently prove nothing once {@code Conversation}'s own default
 *   ever changes.
 * [T11 additions, dev-backend-engineer 2026-08-30] Four tests close the {@code sources} wire/
 *   persistence contract this card adds on top of the pre-existing coverage above: {@link
 *   #neverSearchesWhenRagIsDisabled} and {@link #completesNormallyWhenSearchAxisThrows} each
 *   gained one extra assertion (hybrid-rag:AC-10 clause ②, AC-14 clause ② respectively) rather than a new
 *   test method, since both already reproduce the exact scenario each AC clause describes. Two
 *   new tests were added: {@link #emitsEmptySourcesWhenZeroDocumentsPassTheThreshold}
 *   (hybrid-rag:AC-5, the zero-document case reached via a normal empty search result, distinct
 *   from {@link #completesNormallyWhenSearchAxisThrows}'s exception-triggered fallback into the
 *   same guard) and {@link #populatesSourcesOnTheLastChunkAndPersistsTheIdenticalJsonWhenDocumentsAreFound}
 *   (hybrid-rag:AC-11 population through the full real pipeline, plus hybrid-rag:AC-12's storage
 *   axis — the persisted {@code messages.sources} JSON parses back to the exact list the wire's
 *   terminal chunk carried). {@link #sourceRefMapper}/{@link #objectMapper} are both real
 *   instances, not mocks — the former is T8's own already-unit-tested {@code @Component}
 *   ({@code SourceRefMapperTest}), and the latter must actually serialize/deserialize for the two
 *   JSON-round-trip assertions above to mean anything.
 */
class ChatServiceRagAttachmentTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final String MODEL = "upstage/solar-pro4";

    private final ConversationService conversationService = mock(ConversationService.class);
    private final MessageChatMemoryRepository chatMemoryRepository = mock(MessageChatMemoryRepository.class);
    private final RagAdvisorConfig ragAdvisorConfig = new RagAdvisorConfig();
    // [hybrid-rag T11] Both real, not mocked: sourceRefMapper is T8's own already-unit-tested
    // @Component (SourceRefMapperTest), and objectMapper is a real Jackson 3 mapper so
    // populatesSourcesOnTheLastChunkAndPersistsTheIdenticalJson below can assert on the actual
    // JSON text ChatService#serializeSources produces, not a mocked null.
    private final SourceRefMapper sourceRefMapper = new SourceRefMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // [T11 rework round 2] Attached/detached around every test (cheap no-op for the tests that
    // never assert on it) so toleratesPartialMappingFailureWithoutDroppingGoodSources below can
    // assert on ChatService's own WARN log -- same technique HybridDocumentRetrieverTest already
    // established for asserting a log level is itself part of an AC's own text.
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        chatServiceLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        chatServiceLogger().detachAppender(logAppender);
    }

    private static Logger chatServiceLogger() {
        return (Logger) LoggerFactory.getLogger(ChatService.class);
    }

    private static Conversation conversationStub(boolean ragEnabled) {
        Conversation conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(CONVERSATION_ID);
        when(conversation.getModel()).thenReturn(MODEL);
        // Explicit -- never rely on Mockito's boolean default here (see class [Rationale]).
        when(conversation.isRagEnabled()).thenReturn(ragEnabled);
        return conversation;
    }

    /**
     * [Purpose] Assembles the real RAG advisor pair from {@link RagAdvisorConfig} (T9/T12
     *   production wiring, called directly, never reimplemented) around {@code
     *   hybridDocumentRetriever}, sharing {@code stubChatModel} with the compression
     *   transformer's own internal {@link ChatClient} the same way {@code RagAdvisorAssemblyTest}
     *   does — see that class's own Javadoc [Caution] for why this sharing is safe.
     */
    private RetrievalAugmentationAdvisor ragAdvisor(
            HybridDocumentRetriever hybridDocumentRetriever, StubChatModel stubChatModel) {
        SpringAiDocumentRetrieverAdapter adapter = new SpringAiDocumentRetrieverAdapter(hybridDocumentRetriever);
        QueryTransformer compressionQueryTransformer =
                ragAdvisorConfig.compressionQueryTransformer(ChatClient.builder(stubChatModel));
        return ragAdvisorConfig.retrievalAugmentationAdvisor(adapter, compressionQueryTransformer);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-10] rag_enabled=false 이면 검색이 한 번도 실행되지 않고 일반 답변이 그대로 나가며 "
            + "(절②, T11 추가) 마지막 청크의 sources 는 빈 배열이다")
    void neverSearchesWhenRagIsDisabled() {
        // given: a real advisor pair IS wired (so this test proves the branch skips attaching it,
        //   not merely that nothing was ever connected) around a mocked HybridDocumentRetriever --
        //   if streamReply() ever called .advisors(...) here, this mock would be reachable
        HybridDocumentRetriever hybridDocumentRetriever = mock(HybridDocumentRetriever.class);
        StubChatModel stubChatModel = new StubChatModel();
        stubChatModel.enqueueTokens(
                List.of("네, ", "오늘 회식 있습니다"), "stop", new StubChatModel.Usage(5, 3, BigDecimal.ZERO));
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisor(hybridDocumentRetriever, stubChatModel);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        String question = "오늘 회식 있어?";
        when(chatMemoryRepository.findByConversationId(CONVERSATION_ID.toString()))
                .thenReturn(List.of(new UserMessage(question)));
        ChatService service = new ChatService(ChatClient.builder(stubChatModel), conversationService,
                chatMemoryRepository, TestAppPropertiesFactory.valid(), new RagAdvisorPair(ragAdvisor, guardAdvisor),
                sourceRefMapper, objectMapper);
        Conversation conversation = conversationStub(false);

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, question).collectList().block();

        // then: the retriever behind the (wired-but-unattached) advisor pair was never reached
        verify(hybridDocumentRetriever, never()).retrieve(anyString());

        // then: the plain model answer streamed through untouched -- no AC-5 "모른다" ("I don't know") short-circuit,
        //   which would only ever fire had the guard advisor actually been attached
        String content = chunks.stream()
                .map(ChatChunk::delta)
                .filter(delta -> delta != null)
                .reduce("", String::concat);
        assertThat(content).isEqualTo("네, 오늘 회식 있습니다");
        assertThat(content).doesNotContain(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);

        // then: [hybrid-rag:AC-10 clause ②] the terminal chunk still carries a non-null, explicitly
        //   empty sources array -- rag_enabled=false must never leave sources absent (null)
        assertThat(chunks.get(chunks.size() - 1).sources()).isEqualTo(List.of());
    }

    @Test
    @DisplayName("[hybrid-rag:AC-14] 검색 단계에서 예외가 발생해도 ChatService.streamReply() 는 정상적으로 "
            + "SSE 를 완결한다 (T7 폴백 → 빈 목록 → 가드의 고정 문구 → 정상 종료) — (절②, T11 추가) 마지막 청크와 "
            + "저장된 assistant 행의 sources 모두 빈 배열이다")
    void completesNormallyWhenSearchAxisThrows() {
        // given: a REAL HybridDocumentRetriever (T7's own class, not mocked) wired to a Bm25SearchPort
        //   mock that throws -- reproducing the "DB 예외" ("DB exception") this card's brief describes, and exercising
        //   T7's own existing fallback (retrieve() catches RuntimeException, returns List.of()) rather
        //   than re-implementing that fallback at this layer
        Bm25SearchPort bm25SearchPort = mock(Bm25SearchPort.class);
        VectorSearchPort vectorSearchPort = mock(VectorSearchPort.class);
        ReciprocalRankFusion reciprocalRankFusion = mock(ReciprocalRankFusion.class);
        RerankPort rerankPort = mock(RerankPort.class);
        when(bm25SearchPort.search(anyString(), anyInt())).thenThrow(new RuntimeException("DB connection lost"));
        HybridDocumentRetriever hybridDocumentRetriever = new HybridDocumentRetriever(
                vectorSearchPort, bm25SearchPort, reciprocalRankFusion, rerankPort,
                TestAppPropertiesFactory.valid());
        // no StubChatModel script is enqueued -- this test asserts the model is never reached
        // (RagEmptyContextGuardAdvisor short-circuits on the empty document list T7's fallback
        // produces), exactly as RagAdvisorAssemblyTest#shortCircuitsWithoutEverInvokingTheModel
        // does for the AC-5 case this fallback funnels into
        StubChatModel stubChatModel = new StubChatModel();
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisor(hybridDocumentRetriever, stubChatModel);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        String question = "회사 정책이 뭐야?";
        when(chatMemoryRepository.findByConversationId(CONVERSATION_ID.toString()))
                .thenReturn(List.of(new UserMessage(question)));
        ChatService service = new ChatService(ChatClient.builder(stubChatModel), conversationService,
                chatMemoryRepository, TestAppPropertiesFactory.valid(), new RagAdvisorPair(ragAdvisor, guardAdvisor),
                sourceRefMapper, objectMapper);
        Conversation conversation = conversationStub(true);

        // when: this must not throw -- a search-axis exception must not propagate out of
        //   streamReply()'s own onErrorResume as a chat:AC-34-style error chunk
        List<ChatChunk> chunks = service.streamReply(conversation, null, question).collectList().block();

        // then: the BM25 axis actually threw -- pins which axis this test exercises (correctness
        //   Low-1, T10 rework round 2). TestAppPropertiesFactory.valid()'s mode happens to be HYBRID
        //   today, which is why bm25SearchPort gets called at all; without this verify, silently
        //   switching that mode to VECTOR would leave bm25SearchPort uncalled and vectorSearchPort's
        //   own (unscripted) mock would return null/empty instead, funneling into the exact same
        //   guard fixed-phrase outcome below for an entirely different, untested reason -- passing
        //   for the wrong reason rather than failing
        verify(bm25SearchPort).search(anyString(), anyInt());

        // then: the stream completed with the guard's fixed answer, not an error chunk
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).noneMatch(chunk -> ChatChunk.FINISH_REASON_ERROR.equals(chunk.finishReason()));
        String content = chunks.stream()
                .map(ChatChunk::delta)
                .filter(delta -> delta != null)
                .reduce("", String::concat);
        assertThat(content).isEqualTo(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);

        // then: [hybrid-rag:AC-14 clause ②] the terminal chunk carries a non-null, explicitly empty
        //   sources array -- a search-axis exception must not leave sources absent (null) either
        assertThat(chunks.get(chunks.size() - 1).sources()).isEqualTo(List.of());

        // then: the persisted assistant row reflects the same non-error, normal-completion outcome
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        Message assistantRow = assistantMessageCaptor.getValue();
        assertThat(assistantRow.getContent()).isEqualTo(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);
        assertThat(assistantRow.getFinishReason()).isNotEqualTo(ChatChunk.FINISH_REASON_ERROR);

        // then: [hybrid-rag:AC-12 storage axis] the persisted row's sources JSON is the identical empty
        //   array the wire's terminal chunk carried
        assertThat(assistantRow.getSources()).isEqualTo("[]");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-5] 임계값을 통과한 문서가 0건이면(검색 자체는 성공) 마지막 청크의 sources 가 "
            + "빈 배열이다 (completesNormallyWhenSearchAxisThrows 의 예외 폴백 경로와 달리, 검색이 정상적으로 "
            + "0건을 반환하는 경로)")
    void emitsEmptySourcesWhenZeroDocumentsPassTheThreshold() {
        // given: HybridDocumentRetriever succeeds but legitimately finds nothing -- both this and
        //   completesNormallyWhenSearchAxisThrows above funnel into the same
        //   RagEmptyContextGuardAdvisor short-circuit, but via two distinct upstream causes
        //   (T7 review "AC-14 소유권 분할" (quoted verbatim, "AC-14 ownership split") already distinguishes them for the retriever layer)
        HybridDocumentRetriever hybridDocumentRetriever = mock(HybridDocumentRetriever.class);
        when(hybridDocumentRetriever.retrieve(anyString())).thenReturn(List.of());
        StubChatModel stubChatModel = new StubChatModel();
        // no answer-generation script is enqueued -- the guard must short-circuit before it, exactly
        // as completesNormallyWhenSearchAxisThrows's own comment documents for its exception case
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisor(hybridDocumentRetriever, stubChatModel);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        String question = "회사 워크숍은 언제야?";
        when(chatMemoryRepository.findByConversationId(CONVERSATION_ID.toString()))
                .thenReturn(List.of(new UserMessage(question)));
        ChatService service = new ChatService(ChatClient.builder(stubChatModel), conversationService,
                chatMemoryRepository, TestAppPropertiesFactory.valid(), new RagAdvisorPair(ragAdvisor, guardAdvisor),
                sourceRefMapper, objectMapper);
        Conversation conversation = conversationStub(true);

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, question).collectList().block();

        // then: the guard's fixed answer streamed through, not an error
        assertThat(chunks).isNotEmpty();
        String content = chunks.stream()
                .map(ChatChunk::delta)
                .filter(delta -> delta != null)
                .reduce("", String::concat);
        assertThat(content).isEqualTo(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);

        // then: the terminal chunk carries a non-null, explicitly empty sources array
        assertThat(chunks.get(chunks.size() - 1).sources()).isEqualTo(List.of());

        // then: [hybrid-rag:AC-12 storage axis] the persisted assistant row's sources JSON is the
        //   identical empty array
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        assertThat(assistantMessageCaptor.getValue().getSources()).isEqualTo("[]");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-9] 이전 턴이 있는 대화에서 실제 ChatService.streamReply() 를 호출하면 "
            + "검색에 압축된 질의 문자열이 쓰인다 (원문 대명사 질의가 아니라)")
    void searchesWithCompressedQueryOnRealMultiTurnRequest() {
        // given: HybridDocumentRetriever is mocked directly (not the real T7 class) -- this test's
        //   only concern is which query string reaches it, not T7's own pipeline. document_id must
        //   still be a valid UUID string (dev-qa-tester T11 QA, 2026-08-30) even though this test
        //   never asserts on `sources`: this same chunk now flows into ChatService#resolveSources
        //   (T11 addition) on the terminal response, and SourceRefMapper#readDocumentId throws on a
        //   non-UUID value -- resolveSources' own catch swallows that and falls back to List.of(),
        //   so a non-UUID id would have silently exercised that error-fallback branch rather than
        //   the intended mapping success path on every run of this test, without failing it.
        HybridDocumentRetriever hybridDocumentRetriever = mock(HybridDocumentRetriever.class);
        RetrievedChunk chunk = new RetrievedChunk("chunk-1", "2023년 인수 당시 B사 직원은 50명입니다",
                Map.of("document_id", UUID.randomUUID().toString(), "filename", "f.pdf"), 0.9);
        when(hybridDocumentRetriever.retrieve(anyString())).thenReturn(List.of(chunk));
        StubChatModel stubChatModel = new StubChatModel();
        // enqueued in call order: the compression call's answer first, the final answer-generation
        // call's answer second -- both the compression bean's own internal ChatClient and
        // ChatService's outer ChatClient share this one stubChatModel instance (see ragAdvisor()).
        stubChatModel.enqueueTokens(
                List.of("2023년 인수 당시 B사의 직원 수"), "stop", new StubChatModel.Usage(20, 8, null));
        stubChatModel.enqueueTokens(List.of("50명입니다"), "stop", new StubChatModel.Usage(15, 10, null));
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisor(hybridDocumentRetriever, stubChatModel);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        String followUp = "그럼 그때 직원은 몇 명이었어?";
        // history already ends with this turn's own question, per Tech Review A-4's call order --
        // same technique as ChatServiceTest#secondMessageInSameConversationIncludesFirstTurnInThePrompt
        when(chatMemoryRepository.findByConversationId(CONVERSATION_ID.toString())).thenReturn(List.of(
                new UserMessage("2023년에 있었던 스타트업 인수 계약 내용이 뭐야?"),
                new AssistantMessage("2023년 인수 계약은 A사가 B사를 인수하는 내용입니다."),
                new UserMessage(followUp)));
        ChatService service = new ChatService(ChatClient.builder(stubChatModel), conversationService,
                chatMemoryRepository, TestAppPropertiesFactory.valid(), new RagAdvisorPair(ragAdvisor, guardAdvisor),
                sourceRefMapper, objectMapper);
        Conversation conversation = conversationStub(true);

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, followUp).collectList().block();

        // then: the retriever received the compressed standalone query, not the raw follow-up text
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(hybridDocumentRetriever).retrieve(queryCaptor.capture());
        assertThat(queryCaptor.getValue()).isEqualTo("2023년 인수 당시 B사의 직원 수");
        assertThat(queryCaptor.getValue()).isNotEqualTo(followUp);

        // then: the final answer reached the client and the persisted row unchanged by any of this
        String content = chunks.stream()
                .map(ChatChunk::delta)
                .filter(delta -> delta != null)
                .reduce("", String::concat);
        assertThat(content).isEqualTo("50명입니다");

        // then: [correctness review, 2026-08-30, T11 rework round 1] pins the document_id-must-be-a-
        //   valid-UUID correction this fixture's own [given] comment documents -- without this
        //   assertion, a future revert to a non-UUID id would silently exercise resolveSources'
        //   own error-fallback branch (empty sources) instead of the intended mapping success path,
        //   and this test would keep passing for the wrong reason
        assertThat(chunks.get(chunks.size() - 1).sources()).hasSize(1);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11][hybrid-rag:AC-12] 검색이 문서를 찾으면 마지막 청크의 sources 가 "
            + "SourceRefMapper 결과와 동일한 필드로 채워지고, messages.sources 에 저장된 JSON 을 파싱한 "
            + "값이 그 마지막 청크의 값과 정확히 같다")
    void populatesSourcesOnTheLastChunkAndPersistsTheIdenticalJsonWhenDocumentsAreFound() {
        // given: one real RetrievedChunk carrying every SourceRefMapper-required metadata key
        HybridDocumentRetriever hybridDocumentRetriever = mock(HybridDocumentRetriever.class);
        UUID documentId = UUID.randomUUID();
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1", "이 문서는 2024년 휴가 정책을 설명합니다",
                Map.of("document_id", documentId.toString(), "filename", "휴가정책.pdf", "page", 3), 0.42);
        when(hybridDocumentRetriever.retrieve(anyString())).thenReturn(List.of(chunk));
        StubChatModel stubChatModel = new StubChatModel();
        stubChatModel.enqueueTokens(List.of("연차는 15일입니다"), "stop", new StubChatModel.Usage(10, 5, null));
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisor(hybridDocumentRetriever, stubChatModel);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        String question = "휴가는 며칠이야?";
        when(chatMemoryRepository.findByConversationId(CONVERSATION_ID.toString()))
                .thenReturn(List.of(new UserMessage(question)));
        ChatService service = new ChatService(ChatClient.builder(stubChatModel), conversationService,
                chatMemoryRepository, TestAppPropertiesFactory.valid(), new RagAdvisorPair(ragAdvisor, guardAdvisor),
                sourceRefMapper, objectMapper);
        Conversation conversation = conversationStub(true);

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, question).collectList().block();

        // then: the terminal chunk carries exactly one source, matching what the real
        //   SourceRefMapper produces for the same chunk directly (independent computation, not a
        //   tautological re-derivation from ChatService's own output)
        ChatChunk.Source expectedSource = sourceRefMapper.toSourceRefs(List.of(chunk)).stream()
                .map(ref -> new ChatChunk.Source(
                        ref.documentId().toString(), ref.filename(), ref.score(), ref.snippet(), ref.page()))
                .findFirst()
                .orElseThrow();
        List<ChatChunk.Source> lastSources = chunks.get(chunks.size() - 1).sources();
        assertThat(lastSources).containsExactly(expectedSource);

        // then: [hybrid-rag:AC-12 storage axis] the persisted assistant row's sources JSON parses back to
        //   the exact same list the wire's terminal chunk carried -- not a second, independently
        //   re-derived value that merely happens to agree
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        String storedJson = assistantMessageCaptor.getValue().getSources();
        List<ChatChunk.Source> parsedFromDb =
                objectMapper.readValue(storedJson, new TypeReference<List<ChatChunk.Source>>() {});
        assertThat(parsedFromDb).isEqualTo(lastSources);
    }

    /**
     * [Purpose] Pins the correctness+security review fix (2026-08-30, T11 rework round 2):
     *   {@code ChatService#resolveSources} must map each retrieved chunk individually and drop only
     *   the malformed one(s), never the whole {@code sources} list, when {@link SourceRefMapper}
     *   throws {@link IllegalStateException} for one candidate out of several.
     * [Rationale] Round 1's version of {@code resolveSources} wrapped the entire {@code
     *   sourceRefMapper.toSourceRefs(chunks)} call (list-in/list-out) in one {@code try/catch}, so
     *   one malformed chunk's exception discarded every other, already-successfully-mappable
     *   source in the same response -- under hybrid-rag:AC-20's pending FE contract that would
     *   render "no source documents" even though most sources were real. This fixture reproduces
     *   exactly that shape: three chunks retrieved, the middle one missing the required {@code
     *   document_id} key ({@link SourceRefMapper#toSourceRef}'s own [Caution] documents this throws
     *   {@link IllegalStateException}), the other two well-formed.
     * [AC] hybrid-rag:AC-11
     */
    @Test
    @DisplayName("[hybrid-rag:AC-11] DOCUMENT_CONTEXT 청크 중 하나의 메타데이터가 잘못돼도 나머지 정상 청크는 "
            + "sources 에 그대로 남고(전체가 비워지지 않는다), 실패 건수가 WARN 으로 로깅된다")
    void toleratesPartialMappingFailureWithoutDroppingGoodSources() {
        // given: three retrieved chunks -- two well-formed, one missing the required document_id
        //   key (SourceRefMapper#readDocumentId throws IllegalStateException for this shape)
        HybridDocumentRetriever hybridDocumentRetriever = mock(HybridDocumentRetriever.class);
        RetrievedChunk good1 = new RetrievedChunk(
                "chunk-1", "정상 청크 1", Map.of("document_id", UUID.randomUUID().toString(), "filename", "a.pdf"), 0.9);
        RetrievedChunk malformed = new RetrievedChunk(
                "chunk-2", "메타데이터가 망가진 청크", Map.of("filename", "b.pdf"), 0.7);
        RetrievedChunk good2 = new RetrievedChunk(
                "chunk-3", "정상 청크 2", Map.of("document_id", UUID.randomUUID().toString(), "filename", "c.pdf"), 0.5);
        when(hybridDocumentRetriever.retrieve(anyString())).thenReturn(List.of(good1, malformed, good2));
        StubChatModel stubChatModel = new StubChatModel();
        stubChatModel.enqueueTokens(List.of("답변입니다"), "stop", new StubChatModel.Usage(10, 5, null));
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisor(hybridDocumentRetriever, stubChatModel);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        String question = "질문입니다";
        when(chatMemoryRepository.findByConversationId(CONVERSATION_ID.toString()))
                .thenReturn(List.of(new UserMessage(question)));
        ChatService service = new ChatService(ChatClient.builder(stubChatModel), conversationService,
                chatMemoryRepository, TestAppPropertiesFactory.valid(), new RagAdvisorPair(ragAdvisor, guardAdvisor),
                sourceRefMapper, objectMapper);
        Conversation conversation = conversationStub(true);

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, question).collectList().block();

        // then: exactly the two well-formed sources survive, in retrieval order -- not an empty
        //   list wiped out by the one malformed chunk
        List<ChatChunk.Source> lastSources = chunks.get(chunks.size() - 1).sources();
        assertThat(lastSources).extracting(ChatChunk.Source::filename).containsExactly("a.pdf", "c.pdf");

        // then: the one dropped chunk is still observable in the logs (not silently swallowed),
        //   per this project's fallback philosophy (D4/D5) requiring partial data loss stay visible
        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("1개").contains("매핑에 실패");
                });

        // then: the persisted assistant row's sources JSON mirrors the same two-element list, not
        //   an empty array
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        String storedJson = assistantMessageCaptor.getValue().getSources();
        List<ChatChunk.Source> parsedFromDb =
                objectMapper.readValue(storedJson, new TypeReference<List<ChatChunk.Source>>() {});
        assertThat(parsedFromDb).isEqualTo(lastSources);
    }

    /**
     * [Purpose] Bug-report test (dev-qa-tester, 2026-08-30, coordinator escalation citing
     *   {@code insight-chat-7f}'s review) -- not an AC this project claims is satisfied. Reproduces
     *   BL-009's SDK-side shape (already-documented, pre-existing gap: {@link
     *   ChatChunk#FINISH_REASON_UNKNOWN}'s own Javadoc and {@code
     *   ChatService#onStreamTerminated}'s own [Caution] both describe the real OpenAI SDK mapping
     *   an unmappable/absent upstream {@code finish_reason} to {@code null} on the actual terminal
     *   response) interacting with this card's own {@code sources} resolution. <b>The actual
     *   tracked defect this test's assertions pin is {@code BL-019}, not {@code BL-009}</b>
     *   (correctness review, 2026-08-30, T11 rework round 1) -- {@code BL-009} covers only the
     *   {@code finishReason} gap, and the 2026-08-30 user approval that accepted {@code BL-009} does
     *   not extend to {@code sources} (global CLAUDE.md §6.1; see {@code
     *   ChatService#onStreamTerminated}'s own [Caution], "hybrid-rag T11 (BL-019, ...)" paragraph,
     *   for why the two are tracked separately). This test is named/reproduced in BL-009's terms
     *   because BL-009's SDK behavior is what *triggers* the scenario, but the {@code sources}-loss
     *   outcome it documents is BL-019's own, still-open scope.
     * [Rationale] {@code ChatService#toChunk} reuses the same {@code finishReason != null} branch
     *   that already identified the terminal response (pre-existing, {@code usage}'s own Javadoc)
     *   as the signal to also resolve {@code sources} (this card's addition). When the terminal
     *   response's finish reason is exactly the BL-009 {@code null} case, that branch never runs at
     *   all -- not just for {@code finishReason}, but now for {@code sources} too. Unlike {@code
     *   finishReason}, which {@code onStreamTerminated} still recovers with a fallback ({@link
     *   ChatChunk#FINISH_REASON_UNKNOWN}) for the persisted row, {@code sourcesHolder} has no
     *   equivalent fallback -- it simply stays at its initial {@code null}. This test documents the
     *   actual (defective) result on both axes: the wire's own terminal {@link ChatChunk} and the
     *   persisted {@code messages.sources} column. Unlike every other fixture in this class, this
     *   one does NOT go through {@link RagEmptyContextGuardAdvisor#notFoundResponse} (that class
     *   hardcodes a non-blank {@code finishReason="stop"} specifically because a synthesized
     *   response is never subject to R-G/BL-009 -- see that field's own Javadoc) -- this only
     *   reproduces on the real-model-answer path, i.e. exactly {@link
     *   #populatesSourcesOnTheLastChunkAndPersistsTheIdenticalJsonWhenDocumentsAreFound}'s own
     *   fixture with one field changed (finish reason null instead of "stop"), which is why
     *   AC-5/AC-10 clause ②/AC-14 clause ② (all guard-synthesized) are unaffected by this finding while
     *   AC-11/AC-12's real-answer path is not.
     * [Caution] No {@code [AC]} tag -- this is not a satisfied acceptance criterion, it is evidence
     *   for a design defect this card's own fix is out of this test's scope (per coordinator
     *   instruction: report, do not repair here). Do not tag this {@code hybrid-rag:AC-11} or
     *   {@code hybrid-rag:AC-12} even though it exercises the exact same code path as {@link
     *   #populatesSourcesOnTheLastChunkAndPersistsTheIdenticalJsonWhenDocumentsAreFound} -- doing so
     *   would make the evidence ledger cite a passing assertion of INCORRECT behavior as proof the
     *   AC holds.
     */
    @Test
    @DisplayName("(무태그 -- BL-009 상호작용 버그 리포트, 코디네이터 지시로 재현) 실제 마지막 응답의 finishReason 을 "
            + "SDK 가 인식하지 못해 null 로 남기면(BL-009), 실제로 문서를 찾았어도 와이어 마지막 청크와 저장된 "
            + "messages.sources 모두 sources 를 유실한다 (버그, AC 아님) "
            + "(BL-009 형태 SDK 동작이 유발, 실제 등재 항목은 BL-019)")
    void losesSourcesOnBothAxesWhenTerminalResponseFinishReasonIsUnrecognizedByTheSdk() {
        // given: identical fixture to populatesSourcesOnTheLastChunkAndPersistsTheIdenticalJsonWhenDocumentsAreFound
        //   above, except the terminal response's own finishReason is null (BL-009's reproduced shape)
        HybridDocumentRetriever hybridDocumentRetriever = mock(HybridDocumentRetriever.class);
        UUID documentId = UUID.randomUUID();
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1", "이 문서는 2024년 휴가 정책을 설명합니다",
                Map.of("document_id", documentId.toString(), "filename", "휴가정책.pdf", "page", 3), 0.42);
        when(hybridDocumentRetriever.retrieve(anyString())).thenReturn(List.of(chunk));
        StubChatModel stubChatModel = new StubChatModel();
        // finishReason=null on the terminal response reproduces BL-009's documented real-SDK shape
        // (ChatChunk#FINISH_REASON_UNKNOWN's own Javadoc, ChatService#onStreamTerminated's own
        // [Caution]) -- not a fictional/contrived value.
        stubChatModel.enqueueTokens(List.of("연차는 15일입니다"), null, new StubChatModel.Usage(10, 5, null));
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisor(hybridDocumentRetriever, stubChatModel);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        String question = "휴가는 며칠이야?";
        when(chatMemoryRepository.findByConversationId(CONVERSATION_ID.toString()))
                .thenReturn(List.of(new UserMessage(question)));
        ChatService service = new ChatService(ChatClient.builder(stubChatModel), conversationService,
                chatMemoryRepository, TestAppPropertiesFactory.valid(), new RagAdvisorPair(ragAdvisor, guardAdvisor),
                sourceRefMapper, objectMapper);
        Conversation conversation = conversationStub(true);

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, question).collectList().block();

        // then (documents the ACTUAL, defective behavior -- not an accepted contract): the wire's
        // own terminal chunk never resolved sources at all, even though a real document was found
        ChatChunk terminalChunk = chunks.get(chunks.size() - 1);
        assertThat(terminalChunk.finishReason()).isNull();
        assertThat(terminalChunk.sources()).isNull();

        // then: the persisted row fares no better -- messages.sources is also lost (null), not the
        // real citation that HybridDocumentRetriever actually returned
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        assertThat(assistantMessageCaptor.getValue().getSources()).isNull();
    }
}
