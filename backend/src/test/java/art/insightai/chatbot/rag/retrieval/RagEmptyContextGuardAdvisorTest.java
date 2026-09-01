package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import reactor.core.publisher.Flux;

/**
 * [Purpose] Verifies {@link RagEmptyContextGuardAdvisor} in isolation -- no Spring context, no
 *   real {@link RetrievalAugmentationAdvisor}, both advisor chains mocked. Confirms the class
 *   [Caution]'s two failure modes it must NOT exhibit: (1) short-circuiting when {@code
 *   DOCUMENT_CONTEXT} is missing entirely (must pass through instead), and (2) forwarding to the
 *   model when the document list is genuinely empty (must short-circuit instead).
 * [Rationale] {@link CallAdvisorChain}/{@link StreamAdvisorChain} are mocked purely as
 *   "was the model path reached or not" sentinels ({@code verifyNoInteractions}/{@code verify}) --
 *   this class has no interest in what a real chain does beyond that boundary.
 * [AC] hybrid-rag:AC-5
 */
class RagEmptyContextGuardAdvisorTest {

    private final RagEmptyContextGuardAdvisor advisor = new RagEmptyContextGuardAdvisor();

    private static ChatClientRequest request(Map<String, Object> context) {
        return ChatClientRequest.builder().prompt(new Prompt("사내 정책이 뭐야?")).context(context).build();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-5] DOCUMENT_CONTEXT 가 빈 목록이면 체인을 호출하지 않고 "
            + "고정 문구 '제공된 문서에서 찾을 수 없습니다.' 를 그대로 담은 응답을 반환한다")
    void shortCircuitsCallWithFixedPhraseWhenDocumentContextIsEmpty() {
        // given
        ChatClientRequest chatClientRequest =
                request(Map.of(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.<Document>of()));
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);

        // when
        ChatClientResponse response = advisor.adviseCall(chatClientRequest, callAdvisorChain);

        // then -- the model is never reached
        verifyNoInteractions(callAdvisorChain);
        ChatResponse chatResponse = response.chatResponse();
        assertThat(chatResponse).isNotNull();
        // [AC] hybrid-rag:AC-5 -- literal from the AC text itself, not the production constant
        // (2026-08-28 재작업 라운드 1 Medium-2: 기존 isEqualTo(NOT_FOUND_MESSAGE)는 자기참조 비교라
        // 상수를 바꿔도 통과했다. contains(...)는 AC 원문 "…가 포함되며"를 그대로 옮긴 것.
        assertThat(chatResponse.getResult().getOutput().getText())
                .contains("제공된 문서에서 찾을 수 없습니다");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-23] 답변 전체가 고정 거절 문구(마침표·공백 유무 무관)일 때만 isNotFoundAnswer 가 참이고, "
            + "문구를 포함하되 다른 내용이 더 있는 답변·null·빈 문자열은 거짓이다")
    void recognizesOnlyAnAnswerThatIsExactlyTheFixedRefusalSentence() {
        // then -- literal from the AC/prompt text, not the production constant
        assertThat(RagEmptyContextGuardAdvisor.isNotFoundAnswer("제공된 문서에서 찾을 수 없습니다.")).isTrue();
        assertThat(RagEmptyContextGuardAdvisor.isNotFoundAnswer("제공된 문서에서 찾을 수 없습니다")).isTrue();
        assertThat(RagEmptyContextGuardAdvisor.isNotFoundAnswer("  제공된 문서에서 찾을 수 없습니다 .\n")).isTrue();
        // an answer that says more than the sentence answers something -- it keeps its sources
        assertThat(RagEmptyContextGuardAdvisor.isNotFoundAnswer(
                "매출은 제공된 문서에서 찾을 수 없습니다. 다만 직원 수는 17명입니다.")).isFalse();
        assertThat(RagEmptyContextGuardAdvisor.isNotFoundAnswer("죄송합니다, 제공된 문서에서 찾을 수 없습니다.")).isFalse();
        assertThat(RagEmptyContextGuardAdvisor.isNotFoundAnswer("")).isFalse();
        assertThat(RagEmptyContextGuardAdvisor.isNotFoundAnswer(null)).isFalse();
    }

    // 2026-08-28 재작업 라운드 1 Low-6: [AC-5] 태그를 제거했다. 이 테스트가 실측하는 것은
    // notFoundResponse()가 요청 context를 변형 없이 그대로 응답 context로 옮긴다는 계약뿐이며,
    // AC-5 ③(sources 빈 배열)의 대리 지표로서의 근거는 RagAdvisorAssemblyTest의 태그된 테스트가
    // 이미 Spec §4 AC-5 항목에 인용되어 있다 -- 같은 대리 지표를 여기서도 태그하면 원장이 이 단위
    // 테스트를 ③ 근거로 이중 인용할 여지가 생긴다. DisplayName도 "…이어질 수 있다"는 가설 서술
    // 대신 관측 사실만 남기도록 다시 썼다(전역 §5 원칙 1).
    @Test
    @DisplayName("가드 응답의 context()는 요청 context를 변형 없이 그대로 보존한다 "
            + "(DOCUMENT_CONTEXT=[] 포함 -- T11 sources 매핑 전제)")
    void forwardsEmptyDocumentContextInResponseForSourcesMapping() {
        // given
        ChatClientRequest chatClientRequest =
                request(Map.of(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.<Document>of()));
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);

        // when
        ChatClientResponse response = advisor.adviseCall(chatClientRequest, callAdvisorChain);

        // then
        assertThat(response.context()).containsEntry(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
    }

    @Test
    @DisplayName("DOCUMENT_CONTEXT 가 비어있지 않으면 체인으로 그대로 위임한다 (모델 호출 경로)")
    void delegatesToChainWhenDocumentContextIsNonEmpty() {
        // given
        Document oneDocument = Document.builder().text("본문").build();
        ChatClientRequest chatClientRequest =
                request(Map.of(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of(oneDocument)));
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);
        ChatClientResponse chainResponse = ChatClientResponse.builder().build();
        when(callAdvisorChain.nextCall(chatClientRequest)).thenReturn(chainResponse);

        // when
        ChatClientResponse response = advisor.adviseCall(chatClientRequest, callAdvisorChain);

        // then
        verify(callAdvisorChain).nextCall(chatClientRequest);
        assertThat(response).isSameAs(chainResponse);
    }

    @Test
    @DisplayName("DOCUMENT_CONTEXT 키 자체가 없으면(RAG 어드바이저가 체인에 없는 방어적 상황) "
            + "0건으로 간주하지 않고 체인으로 위임한다")
    void delegatesToChainWhenDocumentContextKeyIsAbsent() {
        // given
        ChatClientRequest chatClientRequest = request(Map.of());
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);
        ChatClientResponse chainResponse = ChatClientResponse.builder().build();
        when(callAdvisorChain.nextCall(chatClientRequest)).thenReturn(chainResponse);

        // when
        ChatClientResponse response = advisor.adviseCall(chatClientRequest, callAdvisorChain);

        // then
        verify(callAdvisorChain).nextCall(chatClientRequest);
        assertThat(response).isSameAs(chainResponse);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-5] 스트리밍 경로에서도 DOCUMENT_CONTEXT 가 빈 목록이면 "
            + "체인을 구독하지 않고 고정 문구 1개 청크만 방출한다")
    void shortCircuitsStreamWithFixedPhraseWhenDocumentContextIsEmpty() {
        // given
        ChatClientRequest chatClientRequest =
                request(Map.of(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.<Document>of()));
        StreamAdvisorChain streamAdvisorChain = mock(StreamAdvisorChain.class);

        // when
        List<ChatClientResponse> chunks =
                advisor.adviseStream(chatClientRequest, streamAdvisorChain).collectList().block();

        // then
        verifyNoInteractions(streamAdvisorChain);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chatResponse().getResult().getOutput().getText())
                .isEqualTo(RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);
    }

    @Test
    @DisplayName("스트리밍 경로에서 DOCUMENT_CONTEXT 가 비어있지 않으면 체인으로 그대로 위임한다")
    void delegatesStreamToChainWhenDocumentContextIsNonEmpty() {
        // given
        Document oneDocument = Document.builder().text("본문").build();
        ChatClientRequest chatClientRequest =
                request(Map.of(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of(oneDocument)));
        StreamAdvisorChain streamAdvisorChain = mock(StreamAdvisorChain.class);
        ChatClientResponse chainResponse = ChatClientResponse.builder().build();
        when(streamAdvisorChain.nextStream(chatClientRequest)).thenReturn(Flux.just(chainResponse));

        // when
        List<ChatClientResponse> chunks =
                advisor.adviseStream(chatClientRequest, streamAdvisorChain).collectList().block();

        // then
        verify(streamAdvisorChain).nextStream(chatClientRequest);
        assertThat(chunks).containsExactly(chainResponse);
    }

    // 2026-08-28 재작업 라운드 1 Low-4: orderIsStrictlyGreaterThanRagAdvisorOrder()를 제거했다.
    // ORDER = RAG_ADVISOR_ORDER + 1 로 파생 정의돼 있어 컴파일되는 한 항상 참인 항등식이라
    // 정보량이 0이었다(리뷰 지적). 같은 계약을 실제로 검증하려면 두 어드바이저를 등록 순서를
    // 뒤집어 실행해도 여전히 짧은 회로가 걸리는지를 봐야 한다 -- 그 회귀 가드는
    // RagAdvisorAssemblyTest.shortCircuitsEvenWhenRegisteredInReverseOrder()로 옮겼다.
}
