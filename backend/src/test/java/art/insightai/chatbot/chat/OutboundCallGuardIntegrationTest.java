package art.insightai.chatbot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.support.StubChatModel;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * bean production code (e.g. {@code ChatService}) would use, and
 * (2) the real {@code OpenAiChatModel} bean doesn't exist in the context at all -- stronger
 * than (1) alone, since (1) only proves the stub is *reachable*, not that the real client was
 * never even constructed.
 * [Caution] This complements, but does not replace, the {@code -Peval} Maven profile split in
 * {@code pom.xml} (excludedGroups=eval by default) -- that profile is the mechanism for tests
 * that *intentionally* call the real endpoint (none exist yet); this test guards the default
 * {@code ./mvnw test} path every other integration test runs under.
 * [T9 addition, dev-qa-tester 2026-08-27] {@link
 * #sendMessageSuccessPathRoutesThroughTheStubWithNoRealOpenAiChatModelBean} is the {@code
 * [chat:AC-35]}-tagged test this class's own Javadoc anticipated: the three tests above only
 * ever prove the stub is reachable via a manually-built {@code ChatClient} inside the test
 * itself, never through the actual production call path ({@code
 * ConversationController#sendMessage} -> {@code ChatService#streamReply} ->
 * {@code chatClient.prompt()}) -- and before T9, that path could not be exercised at all: every
 * prior test that reached {@code POST .../messages} was an entry-guard rejection (chat:AC-17/18/
 * 22), so "zero real HTTP calls" held only because the code physically never got far enough to
 * try. This addition drives a real, successful round trip through that exact production path via
 * {@code MockMvc} and confirms both halves still hold: the real {@code OpenAiChatModel} bean
 * still does not exist afterward, and the streamed content is exactly what {@link StubChatModel}
 * was scripted to return.
 * [T9 round-2 rework, dev-qa-tester 2026-08-27] Review round 1 correctness L3 found the AC-35
 * evidence had two argument gaps even though the test itself was correct: (1) "214/214 passed"
 * alone does not logically rule out a real call whose resulting exception a test happens to
 * swallow, and (2) nothing detected a class-level {@code @TestPropertySource} silently
 * overriding pom.xml's surefire-level {@code spring.ai.openai.base-url}. The {@link
 * #sendMessageSuccessPathRoutesThroughTheStubWithNoRealOpenAiChatModelBean} test now also reads
 * {@code Environment.getProperty("spring.ai.openai.base-url")} directly and asserts it is still a
 * loopback address at the moment of this successful call, closing both gaps for the one test
 * class that is chat:AC-35's direct evidence.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.session.max-anonymous-users=100000")
class OutboundCallGuardIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID insertedConversationId;
    private UUID insertedUserId;

    @AfterEach
    void cleanUpInsertedRows() {
        if (insertedConversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", insertedConversationId);
        }
        if (insertedUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", insertedUserId);
        }
        insertedConversationId = null;
        insertedUserId = null;
    }

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
        // given: production code (e.g. ChatService) builds its ChatClient the same way --
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

    @Test
    @DisplayName("[chat:AC-35] 실제 POST .../messages 성공 경로도 StubChatModel 로만 응답하고 "
            + "실제 OpenAiChatModel 빈은 여전히 존재하지 않는다")
    void sendMessageSuccessPathRoutesThroughTheStubWithNoRealOpenAiChatModelBean() throws Exception {
        // given: a real anonymous session and a real conversation row -- unlike
        //   chatClientRoutesThroughTheStub above, this drives the actual production entry point
        //   (ConversationController#sendMessage -> ChatService#streamReply) rather than building
        //   a ChatClient by hand inside the test
        MvcResult sessionResult = mockMvc.perform(get("/api/session")).andReturn();
        String userIdString = JsonPath.read(sessionResult.getResponse().getContentAsString(), "$.userId");
        insertedUserId = UUID.fromString(userIdString);
        String setCookieHeader = sessionResult.getResponse().getHeader("Set-Cookie");
        String cookieValue = setCookieHeader.split(";", 2)[0];
        cookieValue = cookieValue.substring(cookieValue.indexOf('=') + 1);

        Conversation conversation = conversationRepository.save(
                new Conversation(insertedUserId, "upstage/solar-pro4", false));
        insertedConversationId = conversation.getId();

        StubChatModel stub = (StubChatModel) chatModel;
        stub.reset();
        stub.enqueueTokens(List.of("스텁", " 응답"), "stop", new StubChatModel.Usage(3, 2, BigDecimal.ZERO));

        // when
        MvcResult started = mockMvc.perform(post("/api/conversations/{id}/messages", conversation.getId())
                        .cookie(new Cookie("chatbot_sid", cookieValue))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"아무 질문\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult result = mockMvc.perform(asyncDispatch(started)).andReturn();

        // then: the configured base-url is actually a loopback address at the moment this
        //   successful call runs -- closes two gaps T9 review round 1 correctness L3 raised: (1)
        //   "214/214 passed" alone is a logical gap (a test that makes a real call and then
        //   swallows the resulting exception would still show green), and (2) nothing previously
        //   asserted that THIS test class hadn't silently overridden pom.xml's surefire-level
        //   systemPropertyVariables via a class-level @TestPropertySource (which the pom.xml
        //   comment documents as taking precedence). Reading through Environment -- not a literal
        //   -- reflects whatever value actually won for this context, so a future override on this
        //   class would fail this line instead of silently going undetected.
        assertThat(environment.getProperty("spring.ai.openai.base-url")).startsWith("http://127.0.0.1:");

        // then: the real OpenAiChatModel bean still doesn't exist, even after driving a real,
        //   successful call through the actual production sendMessage -> streamReply ->
        //   chatClient.prompt() path -- not merely the manually-built ChatClient the test above
        //   uses
        assertThat(applicationContext.getBeanNamesForType(OpenAiChatModel.class)).isEmpty();

        // then: the stub actually served the request -- the streamed content is exactly what was
        //   scripted, structural proof this real call path landed on the stub and not a real
        //   network call (an unroutable base-url/dummy api-key would either fail outright or
        //   return unscripted content, same reasoning as chatClientRoutesThroughTheStub above)
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("스텁").contains("응답");
    }
}
