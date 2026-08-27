package art.insightai.chatbot.chat;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.support.StubChatModel;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * [Purpose] Structural self-check that the T2 harness actually closes the door on the real
 * OpenRouter chat endpoint -- not chat Spec AC-35's own evidence. AC-35 ("통합 테스트 전체
 * 실행 중 실제 OpenRouter 엔드포인트로 나가는 HTTP 요청이 0건이다") talks about the *whole*
 * integration test run, including paths this card doesn't touch (e.g. the embedding
 * autoconfiguration, still real here -- see {@code application-test.yaml}) and the real SSE
 * flow T9 introduces. Tech Review §3's AC coverage note now says so explicitly:
 * "AC-35 → T9 (T2는 chat 모델 자동구성 배제·surefire 레벨 아웃바운드 차단까지만 담당하고, ...
 * AC 원문을 완전히 충족하는 것은 실제 SSE 플로우가 붙는 T9다)" -- added 2026-08-25 during this
 * card's correctness re-review, which flagged the missing explicit ownership line as the
 * reason chat:AC-35 stayed unverified against this card. This card's own Tech Review row
 * describes its scope as "테스트 하네스 -- 스텁 ChatModel, 통합 테스트 베이스, 외부 호출 차단":
 * the chat-model path only (autoconfiguration exclusion + stub +, after this card's review
 * rework, Surefire-level systemPropertyVariables). Until T9's fuller evidence exists, tagging
 * these tests with {@code [chat:AC-N]} would let a future mechanical test run flip the AC-35
 * ledger row to ✅ prematurely, so they intentionally carry no such tag.
 * [Rationale] chat Spec §8 R-7 (not Tech Review) and §6 "공통 전제" together point away from
 * network-level interception (WireMock, an OkHttp interceptor on the OpenAI SDK client) as the
 * enforcement mechanism here -- that would add a dependency/customization this project's Tech
 * Review never approved (전역 §7.2-8), and the OpenAI SDK's OkHttp client (CLAUDE.md fact #1)
 * isn't exposed for that kind of interception by Spring AI's autoconfiguration without guessing
 * at an unverified API (project prohibition P3, P4-adjacent risk). (A different card -- S1 --
 * separately keeps an OkHttp-interceptor path open for a different purpose, reading
 * OpenRouter's non-standard `usage.cost` field; that is not what this guard is for.) Instead
 * this asserts the two
 * layers {@code application-test.yaml} + {@link art.insightai.chatbot.support.StubChatModelConfig}
 * actually establish:
 * (1) the {@code ChatModel} bean any test wires is the stub, not a real client -- proven by
 * routing a scripted answer through the exact same {@code ChatClient.Builder} autoconfigured
 * bean production code (e.g. {@code ChatController}) would use, and
 * (2) the real {@code OpenAiChatModel} bean doesn't exist in the context at all -- stronger
 * than (1) alone, since (1) only proves the stub is *reachable*, not that the real client was
 * never even constructed.
 * [Caution] This complements, but does not replace, the {@code -Peval} Maven profile split in
 * {@code pom.xml} (excludedGroups=eval by default) -- that profile is the mechanism for tests
 * that *intentionally* call the real endpoint (none exist yet); this test guards the default
 * {@code ./mvnw test} path every other integration test runs under. T9 is expected to add the
 * actual {@code [chat:AC-35]}-tagged test once the real SSE flow exists to exercise.
 */
class OutboundCallGuardIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    @DisplayName("컨텍스트에 실제 OpenAiChatModel 빈이 존재하지 않는다")
    void realOpenAiChatModelBeanDoesNotExist() {
        // when
        String[] realBeanNames = applicationContext.getBeanNamesForType(OpenAiChatModel.class);

        // then: the real client class was never even instantiated, not merely shadowed.
        assertThat(realBeanNames).isEmpty();
    }

    @Test
    @DisplayName("ChatModel 빈이 StubChatModel 이다")
    void wiredChatModelIsTheStub() {
        assertThat(chatModel).isInstanceOf(StubChatModel.class);
    }

    @Test
    @DisplayName("ChatClient.Builder 로 만든 클라이언트가 스텁 응답만 반환한다")
    void chatClientRoutesThroughTheStub() {
        // given: production code (e.g. ChatController) builds its ChatClient the same way --
        // this proves the autoconfigured wiring path, not just the raw bean, uses the stub.
        StubChatModel stub = (StubChatModel) chatModel;
        stub.reset();
        stub.enqueueTokens(
                List.of("스텁", " 응답"), "stop",
                new StubChatModel.Usage(3, 2, BigDecimal.ZERO));

        // when
        String content = chatClientBuilder.build()
                .prompt()
                .user("아무 질문")
                .stream()
                .content()
                .collectList()
                .block()
                .stream()
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .reduce("", String::concat);

        // then: the scripted text arrived unchanged -- if this had gone to a real endpoint it
        // would either fail (unroutable base-url, dummy api-key) or return unscripted content.
        assertThat(content).isEqualTo("스텁 응답");
    }
}
