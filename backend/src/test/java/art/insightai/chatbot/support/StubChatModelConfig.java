package art.insightai.chatbot.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * [Purpose] Registers {@link StubChatModel} as the test context's {@link ChatModel} bean.
 * [Rationale] {@link IntegrationTestBase} pairs this with excluding
 * {@code OpenAiChatAutoConfiguration} (see {@code application-test.yaml}), so this is not
 * merely a preferred/primary bean shadowing a real one -- after that exclusion there is no
 * other {@code ChatModel} bean a test context could wire, which is what makes chat:AC-35
 * ("실제 OpenRouter 엔드포인트로 나가는 HTTP 요청이 0건") a structural guarantee rather than a
 * per-test discipline. Spring AI's {@code ChatClientAutoConfiguration} still needs exactly
 * one {@code ChatModel} bean to build its {@code ChatClient.Builder} -- without this
 * {@code @TestConfiguration}, excluding the real autoconfiguration alone would make any
 * {@code @SpringBootTest} that scans {@code ChatService} (or anything else depending on
 * {@code ChatClient.Builder}) fail to start.
 * [Caution] One {@link StubChatModel} instance is shared by every test method in a given
 * cached Spring context (singleton scope) -- see that class's [Caution] on {@code reset()}.
 */
@TestConfiguration
public class StubChatModelConfig {

    @Bean
    public StubChatModel stubChatModel() {
        return new StubChatModel();
    }
}
