package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.support.StubChatModel;

/**
 * [Purpose] Exercises the actual production wiring ({@link RagAdvisorConfig}'s two {@code @Bean}
 *   methods, called directly -- not reimplemented) attached to a real {@link ChatClient} built
 *   around {@link StubChatModel} (chat Spec's test double, read-only here). No Spring context is
 *   started -- {@link ChatClient#builder(org.springframework.ai.chat.model.ChatModel)} builds
 *   without one, and every collaborator below the {@link ChatClient} boundary is either this
 *   card's own production code or a plain Mockito mock of {@link HybridDocumentRetriever}. This
 *   is the closest thing to an integration test T9's scope allows: end-to-end attachment through
 *   {@code ChatService} is hybrid-rag T10's job (this class does not touch that file per the T9
 *   card brief), so this class proves the advisor *pair* behaves correctly together without
 *   proving the SSE/chat-endpoint path -- the same "partial evidence" split already established
 *   for AC-13/AC-14 in this Spec's Tech Review.
 * [Rationale] {@link #propagatesDocumentContextToResponseMetadataOnNonEmptyResult()} and {@link
 *   #propagatesDocumentContextOnlyOnTheFinalStreamChunk()} are this card's required §8 R-G
 *   empirical check: does {@link StubChatModel}'s scripted {@code finishReason} actually trigger
 *   {@link RetrievalAugmentationAdvisor#after}, and does {@code DOCUMENT_CONTEXT} actually reach
 *   {@code ChatResponse} metadata as a result? Both are observed directly here (no mocking of
 *   Spring AI's own advisor internals). <b>This does not clear R-G itself</b> -- {@link
 *   StubChatModel} always supplies a controlled, non-blank {@code finishReason} the test chooses,
 *   never the real OpenAI-SDK-mapped value R-G is actually about ({@code _UNKNOWN -> null} for an
 *   upstream {@code finish_reason} string the SDK's enum does not recognize). Confirming R-G
 *   itself requires a real OpenRouter call with a forced short {@code max_tokens}, which is out of
 *   this card's stub-only scope (§8-D7) and is explicitly left to D7 (eval-usage-dashboard) per
 *   the Spec's own R-G entry.
 * [Caution] {@link #shortCircuitsWithoutEverInvokingTheModel()} deliberately enqueues *nothing*
 *   into {@link StubChatModel} before calling the chain. {@link StubChatModel#call}/{@code
 *   stream} throw {@link IllegalStateException} when no script is queued -- so if {@link
 *   RagEmptyContextGuardAdvisor} ever failed to short-circuit, this test would fail with that
 *   exception rather than a silent wrong-answer, which is a stronger proof that the model was
 *   never reached than a Mockito {@code verify} could give (there is nothing here to verify
 *   against -- {@link StubChatModel} is a real, if fake, {@code ChatModel} bean, not a mock).
 */
class RagAdvisorAssemblyTest {

    private static final String QUERY_TEXT = "사내 정책이 뭐야?";

    private final HybridDocumentRetriever hybridDocumentRetriever = mock(HybridDocumentRetriever.class);
    private final StubChatModel stubChatModel = new StubChatModel();
    private final RagAdvisorConfig ragAdvisorConfig = new RagAdvisorConfig();

    private ChatClient chatClient() {
        SpringAiDocumentRetrieverAdapter adapter = new SpringAiDocumentRetrieverAdapter(hybridDocumentRetriever);
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorConfig.retrievalAugmentationAdvisor(adapter);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        return ChatClient.builder(stubChatModel).defaultAdvisors(ragAdvisor, guardAdvisor).build();
    }

    /**
     * [Purpose] Same wiring as {@link #chatClient()} but with {@code defaultAdvisors(...)}
     *   arguments swapped -- guard first, RAG second.
     * [Rationale] 2026-08-28 재작업 라운드 1 Low-4: {@code DefaultAroundAdvisorChain.Builder.reOrder()}
     *   (spring-ai-client-chat 2.0.1 소스로 직접 확인, {@code OrderComparator.sort(...)}) 가
     *   등록 순서와 무관하게 {@link org.springframework.core.Ordered#getOrder()} 로 실행 순서를
     *   재정렬한다. 이 메서드는 그 사실을 실제로 이용해 {@code getOrder()} 계약이 등록 순서에 대한
     *   방어가 됨을 증명하는 유일한 테스트다 -- T10 이 부착 순서를 잘못 잡아도 AC-5 가드가 살아있음을
     *   실증한다.
     */
    private ChatClient chatClientWithReversedAdvisorRegistrationOrder() {
        SpringAiDocumentRetrieverAdapter adapter = new SpringAiDocumentRetrieverAdapter(hybridDocumentRetriever);
        RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorConfig.retrievalAugmentationAdvisor(adapter);
        RagEmptyContextGuardAdvisor guardAdvisor = ragAdvisorConfig.ragEmptyContextGuardAdvisor();
        return ChatClient.builder(stubChatModel).defaultAdvisors(guardAdvisor, ragAdvisor).build();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-5] 검색 결과가 0건이면 모델을 아예 호출하지 않고 "
            + "고정 문구 '제공된 문서에서 찾을 수 없습니다.' 를 답변으로 반환하며 sources 근거는 빈 목록이다")
    void shortCircuitsWithoutEverInvokingTheModel() {
        // given -- HybridDocumentRetriever returns nothing; StubChatModel has NO script queued
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of());

        // when
        ChatResponse chatResponse = chatClient().prompt(QUERY_TEXT).call().chatResponse();

        // then
        assertThat(chatResponse).isNotNull();
        assertThat(chatResponse.getResult().getOutput().getText())
                .isEqualTo(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);
        Object sourcesEvidence = chatResponse.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        assertThat(sourcesEvidence).isEqualTo(List.of());
    }

    @Test
    @DisplayName("[hybrid-rag:AC-5] defaultAdvisors 에 가드를 RAG 보다 먼저 등록해도(등록 순서 역전) "
            + "getOrder() 계약에 따라 재정렬되어 여전히 짧은 회로가 걸린다 (T10 부착 순서 오배선 회귀 가드)")
    void shortCircuitsEvenWhenRegisteredInReverseOrder() {
        // given -- registered guardAdvisor before ragAdvisor, opposite of production order
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of());

        // when
        ChatResponse chatResponse =
                chatClientWithReversedAdvisorRegistrationOrder().prompt(QUERY_TEXT).call().chatResponse();

        // then
        assertThat(chatResponse).isNotNull();
        assertThat(chatResponse.getResult().getOutput().getText())
                .isEqualTo(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-5] 스트리밍 경로에서도 검색 결과가 0건이면 모델을 호출하지 않고 "
            + "고정 문구 1개 청크만 방출한다")
    void shortCircuitsStreamWithoutEverInvokingTheModel() {
        // given
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of());

        // when
        List<ChatResponse> chunks = chatClient()
                .prompt(QUERY_TEXT)
                .stream()
                .chatResponse()
                .collectList()
                .block();

        // then
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getResult().getOutput().getText())
                .isEqualTo(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);
        // [AC] hybrid-rag:AC-5 -- 2026-08-28 재작업 라운드 1 Low-3: 가드 Javadoc은 non-blank
        // finishReason이 AdvisorUtils.onFinishReason()을 참으로 만들어 after()를 발동시키는 것이
        // 전제라고 명시하는데, 이 단언이 없으면 FINISH_REASON을 null/""로 바꿔도 통과했다(리뷰
        // 변이 프로브). DOCUMENT_CONTEXT가 실제로 metadata에 실렸는지까지 확인해 그 전제를 고정한다.
        Object documentContext = chunks.get(0).getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        assertThat(documentContext).isEqualTo(List.of());
    }

    // 2026-08-28 재작업 라운드 1 Low-3 ②(여력 대응): 무태그 음성 대조군. finishReason=null 인
    // 스크립트를 정상 검색 경로(가드 미개입)에 흘려 AdvisorUtils.onFinishReason()이 거짓이 되고
    // RetrievalAugmentationAdvisor.after()가 끝내 발동하지 않아 DOCUMENT_CONTEXT가 어떤 청크의
    // metadata에도 실리지 않음을 스텁 수준에서 실증한다 -- Spec §8 R-G("_UNKNOWN -> null 위험")가
    // 가리키는 실패 메커니즘 자체를 재현한 것이지 R-G를 해소했다는 근거는 아니다(실호출 확인은 D7 몫,
    // 클래스 Javadoc 참고). AC-5는 finishReason이 "stop"으로 고정된 가드 경로에서만 검증되므로
    // 이 테스트는 어떤 AC도 태그하지 않는다.
    @Test
    @DisplayName("finishReason 이 null 이면 검색 결과가 있어도 DOCUMENT_CONTEXT 가 "
            + "어떤 청크의 metadata 에도 실리지 않는다 (R-G 실패 메커니즘의 스텁 수준 재현, AC 아님)")
    void neverPropagatesDocumentContextWhenFinishReasonIsBlank() {
        // given
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1", "회사는 서울에 있습니다", Map.of("document_id", "doc-1", "filename", "f.pdf"), 0.9);
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of(chunk));
        stubChatModel.enqueueTokens(List.of("서울", "입니다"), null, new StubChatModel.Usage(10, 5, null));

        // when
        List<ChatResponse> chunks = chatClient()
                .prompt(QUERY_TEXT)
                .stream()
                .chatResponse()
                .collectList()
                .block();

        // then
        assertThat(chunks).isNotEmpty();
        for (ChatResponse response : chunks) {
            Object documentContext = response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
            assertThat(documentContext).isNull();
        }
    }

    @Test
    @DisplayName("검색 결과가 있으면 모델이 정상 호출되고, RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT 가 "
            + "응답 metadata 로 옮겨진다 (T1 스파이크가 확인한 sources 추출 경로의 실측 재확인, §8 R-G 관련 확인 1/2)")
    void propagatesDocumentContextToResponseMetadataOnNonEmptyResult() {
        // given
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1", "회사는 서울에 있습니다", Map.of("document_id", "doc-1", "filename", "f.pdf"), 0.9);
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of(chunk));
        stubChatModel.enqueueTokens(
                List.of("서울", "입니다"), "stop", new StubChatModel.Usage(10, 5, null));

        // when
        ChatResponse chatResponse = chatClient().prompt(QUERY_TEXT).call().chatResponse();

        // then
        assertThat(chatResponse).isNotNull();
        assertThat(chatResponse.getResult().getOutput().getText()).isEqualTo("서울입니다");
        Object documentContext = chatResponse.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        assertThat(documentContext).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Document> documents = (List<Document>) documentContext;
        assertThat(documents).extracting(Document::getId).containsExactly("chunk-1");
    }

    @Test
    @DisplayName("스트리밍 경로에서 DOCUMENT_CONTEXT 는 finishReason 이 실린 마지막 청크에만 실리고 "
            + "그 이전 청크에는 실리지 않는다 (BaseAdvisor.adviseStream 의 after() 적용 시점, §8 R-G 관련 확인 2/2)")
    void propagatesDocumentContextOnlyOnTheFinalStreamChunk() {
        // given
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1", "회사는 서울에 있습니다", Map.of("document_id", "doc-1", "filename", "f.pdf"), 0.9);
        when(hybridDocumentRetriever.retrieve(QUERY_TEXT)).thenReturn(List.of(chunk));
        stubChatModel.enqueueTokens(
                List.of("서울", "입니다"), "stop", new StubChatModel.Usage(10, 5, null));

        // when
        List<ChatResponse> chunks = chatClient()
                .prompt(QUERY_TEXT)
                .stream()
                .chatResponse()
                .collectList()
                .block();

        // then
        assertThat(chunks).isNotEmpty();
        List<ChatResponse> allButLast = chunks.subList(0, chunks.size() - 1);
        ChatResponse last = chunks.get(chunks.size() - 1);
        for (ChatResponse response : allButLast) {
            Object metadataValue = response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
            assertThat(metadataValue).isNull();
        }
        Object lastMetadataValue = last.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        assertThat(lastMetadataValue).isInstanceOf(List.class);
    }
}
