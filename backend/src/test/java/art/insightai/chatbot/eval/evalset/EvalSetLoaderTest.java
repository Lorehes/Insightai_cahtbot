package art.insightai.chatbot.eval.evalset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Purpose] Verifies {@link EvalSetLoader}'s YAML parsing, found/not-found classification, and
 *   schema validation in isolation — no Spring context, no real eval-set.yaml (that file is
 *   R4-gated and does not exist yet; see card E2's instructions).
 */
class EvalSetLoaderTest {

    @Test
    @DisplayName("[eval-usage-dashboard:AC-1] 로더가 항목 수와 무관하게 있는 질문/없는 질문을 정확히 분류한다 "
            + "(실제 20문항 검증은 E1이 eval-set.yaml을 만든 뒤 완성됨 — 이 테스트는 분류 로직만 검증)")
    void classifiesFoundAndNotFoundCasesRegardlessOfCount() throws URISyntaxException {
        // given: a 5-item fixture (3 found + 2 not-found), not the real 20-item production file
        Path fixture = fixturePath("sample-cases.yaml");

        // when
        EvalSet evalSet = EvalSetLoader.load(fixture);

        // then: total count and the found/not-found split both match the fixture
        assertThat(evalSet.cases()).hasSize(5);
        assertThat(evalSet.foundCases()).hasSize(3);
        assertThat(evalSet.notFoundCases()).hasSize(2);
        assertThat(evalSet.foundCases().get(0).question()).isEqualTo("이 픽스처 챗봇은 언제 만들어졌어?");
        assertThat(evalSet.foundCases().get(0).expectSource()).isEqualTo("dummy-fixture.pdf");
        assertThat(evalSet.foundCases().get(0).expectContains()).containsExactly("2026");
        assertThat(evalSet.notFoundCases().get(0).question()).isEqualTo("이 픽스처 챗봇이 좋아하는 음식은?");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-1] expect_contains 다중 문자열을 순서대로 로드한다")
    void loadsMultipleExpectContainsStringsInOrder() throws URISyntaxException {
        // given
        Path fixture = fixturePath("sample-cases.yaml");

        // when
        EvalSet evalSet = EvalSetLoader.load(fixture);

        // then
        assertThat(evalSet.foundCases().get(1).expectContains()).containsExactly("인사이트봇", "챗봇");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] 필수 필드(expect_source/expect_contains/expect)가 전부 없는 항목은 로딩이 예외로 실패한다")
    void throwsWhenAllExpectationFieldsAreMissing() throws URISyntaxException {
        // given: fixture whose 2nd item has none of the three expectation keys
        Path fixture = fixturePath("missing-expectation.yaml");

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(fixture))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("item #1")
                .hasMessageContaining("expect_source")
                .hasMessageContaining("expect_contains")
                .hasMessageContaining("expect");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] expect_source는 있으나 expect_contains가 없는 항목은 로딩이 예외로 실패한다")
    void throwsWhenFoundItemIsMissingExpectContains() {
        // given
        String yaml = """
                - question: "소스는 있는데 contains가 없는 항목"
                  expect_source: "dummy-fixture.pdf"
                """;

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(inputStream(yaml)))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("expect_source")
                .hasMessageContaining("expect_contains");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] expect_contains가 빈 리스트인 항목은 로딩이 예외로 실패한다")
    void throwsWhenExpectContainsIsEmpty() {
        // given
        String yaml = """
                - question: "contains가 빈 리스트인 항목"
                  expect_source: "dummy-fixture.pdf"
                  expect_contains: []
                """;

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(inputStream(yaml)))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("expect_contains");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] expect 값이 NOT_FOUND가 아닌 다른 문자열이면 로딩이 예외로 실패한다")
    void throwsWhenExpectValueIsNotTheNotFoundMarker() {
        // given: a typo'd expect value instead of the literal NOT_FOUND
        String yaml = """
                - question: "오타가 있는 expect 값"
                  expect: NOT_FOUD
                """;

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(inputStream(yaml)))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("NOT_FOUD")
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] expect와 expect_source/expect_contains가 함께 있는 항목은 로딩이 예외로 실패한다")
    void throwsWhenExpectIsMixedWithFoundModeFields() {
        // given: an item that declares both judgement modes at once (expect: NOT_FOUND plus a
        //   found-mode expect_source/expect_contains pair) instead of exactly one
        String yaml = """
                - question: "두 모드가 섞인 항목"
                  expect: NOT_FOUND
                  expect_source: "dummy-fixture.pdf"
                  expect_contains: ["2026"]
                """;

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(inputStream(yaml)))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("expect")
                .hasMessageContaining("expect_source")
                .hasMessageContaining("expect_contains");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] question 필드가 없는 항목은 로딩이 예외로 실패한다")
    void throwsWhenQuestionIsMissing() {
        // given
        String yaml = """
                - expect: NOT_FOUND
                """;

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(inputStream(yaml)))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("question");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] YAML 루트가 리스트가 아니면 로딩이 예외로 실패한다")
    void throwsWhenRootIsNotAList() {
        // given: a mapping instead of a list at the document root
        String yaml = "question: \"이건 리스트가 아니라 맵이다\"\n";

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(inputStream(yaml)))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("must be a list");
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-2] 존재하지 않는 파일 경로를 넘기면 로딩이 예외로 실패한다")
    void throwsWhenFileDoesNotExist() {
        // given
        Path missing = Paths.get("does-not-exist", "eval-set.yaml");

        // when / then
        assertThatThrownBy(() -> EvalSetLoader.load(missing))
                .isInstanceOf(EvalSetValidationException.class)
                .hasMessageContaining("Failed to read");
    }

    private static Path fixturePath(String fileName) throws URISyntaxException {
        return Paths.get(EvalSetLoaderTest.class.getClassLoader().getResource("eval/" + fileName).toURI());
    }

    private static InputStream inputStream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
