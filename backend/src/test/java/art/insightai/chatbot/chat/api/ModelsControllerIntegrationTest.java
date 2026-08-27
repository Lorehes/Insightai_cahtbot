package art.insightai.chatbot.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * [Purpose] Verifies {@code GET /api/models} end-to-end through the real servlet stack against
 *   the actual {@code app.models} catalog declared in {@code application.yaml} (chat Spec T5,
 *   chat:AC-19/20/21).
 * [Rationale] Follows the same {@link AutoConfigureMockMvc}-over-{@link IntegrationTestBase}
 *   pattern as {@code SessionControllerIntegrationTest} so response bodies are read via {@link
 *   JsonPath} (that class's Javadoc explains why: Spring Boot 4's default Jackson
 *   auto-configuration wires {@code tools.jackson.databind.ObjectMapper}, not the classic
 *   {@code com.fasterxml.jackson.databind.ObjectMapper} this test would otherwise autowire).
 *   {@code ModelsController} itself has no datasource dependency, but this project has no
 *   {@code @WebMvcTest} precedent (grepped before writing this class) -- reusing the established
 *   full-context pattern keeps this suite consistent rather than introducing a second, narrower
 *   test style for one controller. This class deliberately reads the real, unmodified {@code
 *   app.models} block (no {@code @TestPropertySource} override) because AC-19/20/21 are about
 *   what the actual deployed catalog looks like, not a synthetic fixture -- {@link
 *   AppPropertiesModelsBindingTest} in the {@code common.config} package already covers the
 *   fail-fast validation rules themselves against synthetic catalogs.
 * [Caution] {@code AppProperties.Models}'s compact constructor is what actually enforces
 *   AC-19/20/21 at startup (see its Javadoc) -- this test only proves the already-validated
 *   catalog reaches the HTTP response unmodified through {@link ModelsController}. If a future
 *   change replaces the hardcoded catalog with a runtime OpenRouter call, this test's assumption
 *   that no live OpenRouter HTTP call happens (chat:AC-35) will need re-checking, since {@link
 *   IntegrationTestBase} does not itself block outbound calls beyond stubbing the two Spring AI
 *   model beans.
 */
@AutoConfigureMockMvc
class ModelsControllerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("[chat:AC-19] GET /api/models 는 3~5개 항목을 반환하고 각 항목이 id·표시명을 가지며 "
            + "기본 모델로 표시된 항목이 정확히 1개다")
    void returnsBetweenThreeAndFiveEntriesWithExactlyOneDefault() throws Exception {
        // when
        MvcResult result = mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andReturn();

        // then: 3-5 entries, each with a non-blank id and displayName
        List<Map<String, Object>> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body.size()).isBetween(3, 5);
        for (Map<String, Object> entry : body) {
            assertThat((String) entry.get("id")).isNotBlank();
            assertThat((String) entry.get("displayName")).isNotBlank();
        }

        // then: exactly one entry is marked default
        long defaultCount = body.stream()
                .filter(entry -> Boolean.TRUE.equals(entry.get("default")))
                .count();
        assertThat(defaultCount).isEqualTo(1);
    }

    @Test
    @DisplayName("[chat:AC-20] GET /api/models 결과의 어떤 모델 id도 :free 로 끝나지 않는다")
    void noModelIdEndsWithFreeSuffix() throws Exception {
        // when
        MvcResult result = mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andReturn();

        // then
        List<Map<String, Object>> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body).allSatisfy(entry ->
                assertThat((String) entry.get("id")).doesNotEndWith(":free"));
    }

    @Test
    @DisplayName("[chat:AC-21] 기본 모델로 표시된 항목의 id가 CHAT_MODEL_DEFAULT(upstage/solar-pro4)와 "
            + "문자열이 일치한다")
    void defaultModelIdMatchesConfiguredEnvValue() throws Exception {
        // when
        MvcResult result = mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andReturn();

        // then
        List<Map<String, Object>> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        Map<String, Object> defaultEntry = body.stream()
                .filter(entry -> Boolean.TRUE.equals(entry.get("default")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry marked default"));
        assertThat(defaultEntry.get("id")).isEqualTo("upstage/solar-pro4");
    }
}
