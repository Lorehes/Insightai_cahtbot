package art.insightai.chatbot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * [Purpose] Confirms {@code app.rerank.connect-timeout}/{@code read-timeout} actually bind
 *   through Spring's {@code @ConfigurationProperties} machinery at runtime, including the
 *   duration-suffix syntax ({@code 5s}) application.yaml uses.
 * [Rationale] {@code art.insightai.chatbot.rag.retrieval.OpenRouterRerankAdapter} reads these two
 *   fields to build its {@code RestClient}'s request factory (review 2026-08-27 High-1) — a stalled OpenRouter
 *   response blocking a request thread indefinitely is exactly the failure mode these properties
 *   exist to prevent. Actually forcing a network stall in a unit test is impractical (and the
 *   review's own guidance allows falling back to a binding check when direct verification isn't
 *   feasible), so this test's job is narrower than "the timeout works" — it only proves the
 *   values application.yaml declares (5s/10s) resolve to the {@link Duration} the adapter's
 *   constructor receives, not just that {@code AppProperties.Rerank} compiles with those fields.
 *   Follows the same {@link ApplicationContextRunner} pattern as
 *   {@link AppPropertiesSearchBindingTest} for the same reason: a fast unit test with no
 *   datasource/Flyway dependency.
 * [Caution] This does not exercise the adapter itself — see {@code OpenRouterRerankAdapterTest}
 *   for the adapter's own behavior (AC-6/AC-7, topK truncation, malformed-response guards). Since
 *   chat T5, {@code app.models.default-id}/{@code app.models.items} carry no {@code
 *   @DefaultValue} (chat Spec §5 A-10 requires the catalog itself, not just a shape, to come from
 *   application.yaml), so {@code AppProperties.Models}'s compact constructor now fails startup on
 *   an empty {@code app.models.*} tree -- this class supplies a baseline valid catalog ({@link
 *   TestAppPropertiesFactory#VALID_MODELS_CATALOG_PROPERTIES}, T5 review 2026-08-27, Medium-4) for
 *   the same reason it already supplies {@code UPLOAD_DIR_PROPERTY}, purely to satisfy binding. See
 *   {@code AppPropertiesModelsBindingTest} for the models-specific fail-fast tests themselves.
 */
class AppPropertiesRerankBindingTest {

    private static final String UPLOAD_DIR_PROPERTY =
            "app.ingest.upload-dir=" + System.getProperty("java.io.tmpdir");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(UPLOAD_DIR_PROPERTY)
            .withPropertyValues(TestAppPropertiesFactory.VALID_MODELS_CATALOG_PROPERTIES);

    @Test
    @DisplayName("app.rerank.* 프로퍼티를 아무것도 지정하지 않으면 기본값(connect=5s, read=10s)이 바인딩된다")
    void bindsRerankTimeoutDefaultsWhenNoOverrideGiven() {
        // given: no app.rerank.* override beyond the mandatory upload-dir baseline
        // when: the context is refreshed and AppProperties is resolved
        // then: connectTimeout/readTimeout carry the @DefaultValue-declared defaults
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties.Rerank rerank = context.getBean(AppProperties.class).rerank();
            assertThat(rerank.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(rerank.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    @DisplayName("application.yaml 의 app.rerank.connect-timeout/read-timeout 값을 오버라이드하면 바인딩 결과에 실제로 반영된다")
    void bindsRerankTimeoutOverridesFromExternalProperties() {
        // given: overridden with the same kebab-case, duration-suffix style application.yaml uses
        // when: the context is refreshed and AppProperties is resolved
        // then: the bound values reflect the override, not the Java-side @DefaultValue
        contextRunner
                .withPropertyValues(
                        "app.rerank.connect-timeout=2s",
                        "app.rerank.read-timeout=30s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties.Rerank rerank = context.getBean(AppProperties.class).rerank();
                    assertThat(rerank.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(rerank.readTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
