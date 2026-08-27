package art.insightai.chatbot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * [Purpose] Confirms {@code AppProperties.Models}'s compact constructor actually fails
 *   application startup -- not just that it compiles -- for each of the three invariants T5's
 *   Javadoc claims it enforces (chat Spec T5, chat:AC-19/20/21).
 * [Rationale] Follows the same {@link ApplicationContextRunner} pattern as {@link
 *   AppPropertiesSearchBindingTest}/{@link AppPropertiesRerankBindingTest} for the same reason: a
 *   fast unit test with no datasource/Flyway dependency that still proves the validation runs
 *   through Spring's real constructor-binding path, not just a bare {@code new
 *   AppProperties.Models(...)} call in isolation. Each test supplies a full, otherwise-valid
 *   {@code app.models.*} property set and violates exactly one invariant, so a failure can only be
 *   attributed to the rule under test. {@code GET /api/models}'s happy-path shape (3-5 entries,
 *   exactly one default, no {@code :free} id) against the real {@code application.yaml} catalog is
 *   covered separately by {@code ModelsControllerIntegrationTest} -- this class only proves the
 *   fail-fast guard rails themselves trip.
 * [Caution] {@code app.models.default-id}/{@code app.models.items} have no {@code @DefaultValue}
 *   (chat Spec §5 A-10 requires the catalog itself, not just a shape, to come from
 *   application.yaml), so every {@link ApplicationContextRunner} in this module that resolves
 *   {@link AppProperties} -- including {@link AppPropertiesSearchBindingTest} and {@link
 *   AppPropertiesRerankBindingTest}, unrelated to models -- must now also supply a baseline valid
 *   {@code app.models.*} property set, mirroring the existing {@code UPLOAD_DIR_PROPERTY} baseline
 *   those classes already carry for {@code app.ingest.upload-dir}. That baseline lives in {@link
 *   TestAppPropertiesFactory#VALID_MODELS_CATALOG_PROPERTIES} (T5 review 2026-08-27, Medium-4) so
 *   the three classes stay in sync instead of hand-copying the same catalog; without it the compact
 *   constructor's own {@code defaultId == null} check trips first and masks whatever those classes
 *   actually test.
 */
class AppPropertiesModelsBindingTest {

    private static final String UPLOAD_DIR_PROPERTY =
            "app.ingest.upload-dir=" + System.getProperty("java.io.tmpdir");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(UPLOAD_DIR_PROPERTY)
            .withPropertyValues(TestAppPropertiesFactory.VALID_MODELS_CATALOG_PROPERTIES);

    @Test
    @DisplayName("[chat:AC-19] app.models.items 가 3개 미만이면 기동이 실패한다")
    void failsStartupWhenFewerThanThreeItems() {
        // given: only two entries, one short of the 3-5 range
        // when: the context is refreshed
        // then: startup fails with the compact constructor's item-count message
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(UPLOAD_DIR_PROPERTY)
                .withPropertyValues(
                        "app.models.default-id=upstage/solar-pro4",
                        "app.models.items[0].id=upstage/solar-pro4",
                        "app.models.items[0].display-name=Solar Pro 4",
                        "app.models.items[1].id=openai/gpt-5-mini",
                        "app.models.items[1].display-name=GPT-5 mini")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("app.models.items must contain between 3 and 5 entries");
                });
    }

    @Test
    @DisplayName("[chat:AC-19] app.models.items 가 5개 초과이면 기동이 실패한다")
    void failsStartupWhenMoreThanFiveItems() {
        // given: six entries, one over the 3-5 range
        // when: the context is refreshed
        // then: startup fails with the compact constructor's item-count message
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(UPLOAD_DIR_PROPERTY)
                .withPropertyValues(
                        "app.models.default-id=upstage/solar-pro4",
                        "app.models.items[0].id=upstage/solar-pro4",
                        "app.models.items[0].display-name=Solar Pro 4",
                        "app.models.items[1].id=openai/gpt-5-mini",
                        "app.models.items[1].display-name=GPT-5 mini",
                        "app.models.items[2].id=anthropic/claude-haiku-4.5",
                        "app.models.items[2].display-name=Claude Haiku 4.5",
                        "app.models.items[3].id=google/gemini-2.5-flash",
                        "app.models.items[3].display-name=Gemini 2.5 Flash",
                        "app.models.items[4].id=google/gemini-2.5-flash-lite",
                        "app.models.items[4].display-name=Gemini 2.5 Flash-Lite",
                        "app.models.items[5].id=google/gemini-2.5-pro",
                        "app.models.items[5].display-name=Gemini 2.5 Pro")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("app.models.items must contain between 3 and 5 entries");
                });
    }

    @Test
    @DisplayName("[chat:AC-20] app.models.items 의 id가 :free 로 끝나면 기동이 실패한다")
    void failsStartupWhenAnyItemIdEndsWithFreeSuffix() {
        // given: an otherwise-valid three-entry catalog, but one id carries the ":free" suffix
        // when: the context is refreshed
        // then: startup fails with the compact constructor's ":free" rejection message
        contextRunner
                .withPropertyValues(
                        "app.models.items[1].id=openai/gpt-5-mini:free")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("app.models.items must not contain a ':free' model id");
                });
    }

    @Test
    @DisplayName("[chat:AC-21] app.models.default-id 가 items 목록에 없으면 기동이 실패한다")
    void failsStartupWhenDefaultIdDoesNotMatchAnyItem() {
        // given: an otherwise-valid three-entry catalog, but default-id names a model not in items
        // when: the context is refreshed
        // then: startup fails with the compact constructor's default-id mismatch message
        contextRunner
                .withPropertyValues("app.models.default-id=some/unlisted-model")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("does not match any app.models.items id");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
