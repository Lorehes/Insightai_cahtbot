package art.insightai.chatbot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * [Purpose] Confirms {@code AppProperties.Chat.Memory}'s compact constructor actually fails
 *   application startup -- not just that it compiles -- when {@code app.chat.memory.max-messages}
 *   is less than 1 (chat T10 review 2026-08-28, correctness Medium-2).
 * [Rationale] Follows the same {@link ApplicationContextRunner} pattern as {@link
 *   AppPropertiesModelsBindingTest} for the same reason: a fast unit test with no
 *   datasource/Flyway dependency that still proves the validation runs through Spring's real
 *   constructor-binding path, not just a bare {@code new AppProperties.Chat.Memory(...)} call in
 *   isolation. Before this guard existed, {@code 0} silently made {@code
 *   MessageChatMemoryRepository#findByConversationId} return an empty history -- and because
 *   {@code ChatService#streamReply} (T10) no longer sends a separate {@code
 *   .user(userMessage)} call, the current turn's own question was then never sent to the LLM at
 *   all -- while any negative value made that same method throw {@code
 *   IndexOutOfBoundsException} on every {@code POST .../messages} call. The {@code 0} case is
 *   also caught (as a symptom, not as a startup guard) by {@code
 *   ConversationControllerIntegrationTest}'s AC-30 test, since an empty prompt fails its {@code
 *   hasSize(3)} assertion -- but the Medium-2 review found no test anywhere caught the negative
 *   case. This class closes both gaps at the fast, fail-fast-at-startup layer instead of relying
 *   on that slow integration symptom.
 * [Caution] Like {@link AppPropertiesModelsBindingTest}, every {@link ApplicationContextRunner}
 *   here must also supply a baseline valid {@code app.ingest.upload-dir} and {@code app.models.*}
 *   catalog ({@link TestAppPropertiesFactory#VALID_MODELS_CATALOG_PROPERTIES}) purely to satisfy
 *   those two records' own no-{@code @DefaultValue} compact constructors -- otherwise their
 *   exceptions would trip first and mask whatever this class actually tests. {@code
 *   app.chat.memory.max-messages} itself does carry {@code @DefaultValue("40")}, so unlike {@link
 *   AppPropertiesModelsBindingTest} no separate baseline override is needed for the happy path;
 *   each test below only overrides the one property it violates.
 */
class AppPropertiesChatMemoryBindingTest {

    private static final String UPLOAD_DIR_PROPERTY =
            "app.ingest.upload-dir=" + System.getProperty("java.io.tmpdir");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(UPLOAD_DIR_PROPERTY)
            .withPropertyValues(TestAppPropertiesFactory.VALID_MODELS_CATALOG_PROPERTIES);

    @Test
    @DisplayName("app.chat.memory.max-messages 가 0이면 기동이 실패한다")
    void failsStartupWhenMaxMessagesIsZero() {
        // given: max-messages set to 0 -- Math.max(0, rows.size() - 0) == rows.size(), so
        //   findByConversationId's subList(rows.size(), rows.size()) would silently return an
        //   empty history instead of failing loudly
        // when: the context is refreshed
        // then: startup fails with the compact constructor's message instead
        contextRunner
                .withPropertyValues("app.chat.memory.max-messages=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("app.chat.memory.max-messages must be at least 1");
                });
    }

    @Test
    @DisplayName("app.chat.memory.max-messages 가 음수이면 기동이 실패한다")
    void failsStartupWhenMaxMessagesIsNegative() {
        // given: max-messages set to -1 -- Math.max(0, rows.size() - (-1)) exceeds rows.size(),
        //   so findByConversationId's subList(...) would throw IndexOutOfBoundsException on every
        //   POST .../messages call instead of failing at startup
        // when: the context is refreshed
        // then: startup fails with the compact constructor's message instead
        contextRunner
                .withPropertyValues("app.chat.memory.max-messages=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("app.chat.memory.max-messages must be at least 1");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
