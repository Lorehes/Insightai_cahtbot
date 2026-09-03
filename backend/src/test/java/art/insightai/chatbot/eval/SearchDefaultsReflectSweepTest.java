package art.insightai.chatbot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * [Purpose] Pins {@code src/main/resources/application.yaml}'s {@code app.search.*} and {@code
 *   app.rerank.enabled} defaults to the values the D7 sweep confirmed ({@link
 *   SweepConfirmedDefaults}) -- eval-usage-dashboard:AC-11 ("스윕 결과로 확정된 값이 application.yaml
 *   기본값에 반영된다") and AC-12 (the confirmed mode/rerank combination matches the defaults).
 * [Rationale] Parses the YAML file directly rather than booting a Spring context: the test
 *   classpath's {@code config/application.yaml} deliberately overrides the two raw thresholds to
 *   0.0 for every {@code @SpringBootTest} (stub embedding scale), so a bound {@code AppProperties}
 *   would never show the production defaults. Reading the main resource by name proves what a
 *   production boot binds. No placeholder resolution is needed -- every key asserted here is a
 *   literal in the file. Runs in the default build (no {@code @Tag}) so a drift between the YAML
 *   and the sweep evidence fails every ordinary {@code ./mvnw test}.
 * [AC] eval-usage-dashboard:AC-11
 * [AC] eval-usage-dashboard:AC-12
 */
class SearchDefaultsReflectSweepTest {

    private static Properties mainApplicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yaml"));
        Properties properties = factory.getObject();
        assertThat(properties).as("src/main/resources/application.yaml must be on the classpath").isNotNull();
        // guard against the test-classpath file shadowing the main one (see config/application.yaml's own header)
        assertThat(properties.getProperty("qa.config-application-yaml-loaded"))
                .as("the parsed file must be the main application.yaml, not the test config-data file")
                .isNull();
        return properties;
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-11] 스윕으로 확정된 임계값(bm25-raw 1.3 / vector-raw 0.35 / score-threshold 0.0)과 "
            + "가중치(0.4/0.6, rrf-k 60)가 application.yaml 기본값에 그대로 실려 있다")
    void thresholdsAndWeightsMatchTheSweepVerdict() {
        Properties yaml = mainApplicationYaml();
        assertThat(Double.parseDouble(yaml.getProperty("app.search.bm25-raw-score-threshold")))
                .isEqualTo(SweepConfirmedDefaults.BM25_RAW_SCORE_THRESHOLD);
        assertThat(Double.parseDouble(yaml.getProperty("app.search.vector-raw-score-threshold")))
                .isEqualTo(SweepConfirmedDefaults.VECTOR_RAW_SCORE_THRESHOLD);
        assertThat(Double.parseDouble(yaml.getProperty("app.search.score-threshold")))
                .isEqualTo(SweepConfirmedDefaults.SCORE_THRESHOLD);
        assertThat(Integer.parseInt(yaml.getProperty("app.search.rrf-k"))).isEqualTo(SweepConfirmedDefaults.RRF_K);
        // 2026-09-03 re-sweep: the third (n-gram) axis' settings are pinned the same way.
        assertThat(Boolean.parseBoolean(yaml.getProperty("app.ngram.enabled")))
                .isEqualTo(SweepConfirmedDefaults.NGRAM_ENABLED);
        assertThat(Double.parseDouble(yaml.getProperty("app.ngram.weight")))
                .isEqualTo(SweepConfirmedDefaults.NGRAM_WEIGHT);
        assertThat(Integer.parseInt(yaml.getProperty("app.ngram.top-k"))).isEqualTo(SweepConfirmedDefaults.NGRAM_TOP_K);
        assertThat(Double.parseDouble(yaml.getProperty("app.ngram.raw-score-threshold")))
                .isEqualTo(SweepConfirmedDefaults.NGRAM_RAW_SCORE_THRESHOLD);
        assertThat(Double.parseDouble(yaml.getProperty("app.search.weight-bm25")))
                .isEqualTo(SweepConfirmedDefaults.WEIGHT_BM25);
        assertThat(Double.parseDouble(yaml.getProperty("app.search.weight-vector")))
                .isEqualTo(SweepConfirmedDefaults.WEIGHT_VECTOR);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-12] 스윕으로 확정된 조합(mode=hybrid, rerank=false)이 application.yaml 의 "
            + "app.search.mode / app.rerank.enabled 기본값과 일치한다")
    void modeAndRerankMatchTheConfirmedCombination() {
        Properties yaml = mainApplicationYaml();
        assertThat(yaml.getProperty("app.search.mode").toUpperCase(Locale.ROOT))
                .isEqualTo(SweepConfirmedDefaults.MODE.name());
        assertThat(Boolean.parseBoolean(yaml.getProperty("app.rerank.enabled")))
                .isEqualTo(SweepConfirmedDefaults.RERANK_ENABLED);
    }
}
