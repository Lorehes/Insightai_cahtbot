package art.insightai.chatbot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * [Purpose] Confirms {@code app.search.*} actually binds through Spring's
 *   {@code @ConfigurationProperties} machinery at runtime, not just that
 *   {@code AppProperties.Search} compiles and appears in
 *   {@code spring-configuration-metadata.json}.
 * [Rationale] document-ingestion T1 shipped a new {@code AppProperties} nested record
 *   verified only by {@code mvn compile} + a metadata.json grep, and review flagged that as
 *   "runtime binding never actually checked" (Medium). hybrid-rag T2 adds the same shape of
 *   change ({@code bm25TopK}/{@code vectorTopK}/{@code scoreThreshold}), so this closes that
 *   gap up front instead of waiting for the same review comment to repeat. {@link
 *   ApplicationContextRunner} is used instead of {@code @SpringBootTest} so this stays a fast
 *   unit test with no datasource/Flyway/infra dependency. {@code AppProperties.Ingest}
 *   requires a non-blank absolute {@code uploadDir} (see its compact constructor), so every
 *   run supplies one purely to satisfy binding — ingestion itself is out of scope here.
 * [Caution] This only proves the {@code app.search.*} prefix resolves and updates a live bean;
 *   it says nothing about whether 20/20/0.0 are the right defaults (that is a D7 tuning
 *   question per hybrid-rag Spec §8-D2/D3, not this test's job). Since chat T5,
 *   {@code app.models.default-id}/{@code app.models.items} carry no {@code @DefaultValue} (chat
 *   Spec §5 A-10 requires the catalog itself, not just a shape, to come from application.yaml), so
 *   {@code AppProperties.Models}'s compact constructor now fails startup on an empty {@code
 *   app.models.*} tree -- this class supplies a baseline valid catalog ({@link
 *   TestAppPropertiesFactory#VALID_MODELS_CATALOG_PROPERTIES}, T5 review 2026-08-27, Medium-4) for
 *   the same reason it already supplies {@code UPLOAD_DIR_PROPERTY}, purely to satisfy binding. See
 *   {@code AppPropertiesModelsBindingTest} for the models-specific fail-fast tests themselves.
 */
class AppPropertiesSearchBindingTest {

    private static final String UPLOAD_DIR_PROPERTY =
            "app.ingest.upload-dir=" + System.getProperty("java.io.tmpdir");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(UPLOAD_DIR_PROPERTY)
            .withPropertyValues(TestAppPropertiesFactory.VALID_MODELS_CATALOG_PROPERTIES);

    @Test
    @DisplayName("app.search.* 프로퍼티를 아무것도 지정하지 않으면 기본값(20/20/0.0)이 바인딩된다")
    void bindsSearchDefaultsWhenNoOverrideGiven() {
        // given: no app.search.* override beyond the mandatory upload-dir baseline
        // when: the context is refreshed and AppProperties is resolved
        // then: bm25TopK/vectorTopK/scoreThreshold carry the @DefaultValue-declared defaults
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties.Search search = context.getBean(AppProperties.class).search();
            assertThat(search.bm25TopK()).isEqualTo(20);
            assertThat(search.vectorTopK()).isEqualTo(20);
            assertThat(search.scoreThreshold()).isEqualTo(0.0);
        });
    }

    @Test
    @DisplayName("application.yaml 의 app.search.* 값을 오버라이드하면 바인딩 결과에 실제로 반영된다")
    void bindsSearchOverridesFromExternalProperties() {
        // given: app.search.* is overridden with kebab-case property keys, the same style
        //   application.yaml / an env var would use
        // when: the context is refreshed and AppProperties is resolved
        // then: the bound values reflect the override, not the Java-side @DefaultValue —
        //   this is what actually distinguishes "binds" from "the record has a default field"
        contextRunner
                .withPropertyValues(
                        "app.search.bm25-top-k=7",
                        "app.search.vector-top-k=13",
                        "app.search.score-threshold=0.42")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties.Search search = context.getBean(AppProperties.class).search();
                    assertThat(search.bm25TopK()).isEqualTo(7);
                    assertThat(search.vectorTopK()).isEqualTo(13);
                    assertThat(search.scoreThreshold()).isEqualTo(0.42);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
