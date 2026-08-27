package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Verifies {@link OpenRouterRerankAdapter}'s enabled/disabled branch (AC-6, AC-7), its
 *   {@code topK} truncation, its malformed-response guards (out-of-range index, empty body), and
 *   its exception-propagation contract, against a mocked HTTP server — no real OpenRouter call is
 *   made (the schema this adapter targets is an unverified best-effort guess, Tech Review Risk
 *   R-C).
 */
class OpenRouterRerankAdapterTest {

    private static final String BASE_URL = "https://openrouter.test/api/v1";
    private static final String API_KEY = "dummy-test-key";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    /**
     * [Purpose] Builds the {@code spring.ai.openai.*} bean the adapter now reads base-url/api-key
     *   from (review 2026-08-27 Medium-3 replaced the earlier {@code @Value} injection).
     */
    private static OpenAiCommonProperties openAiCommonProperties() {
        OpenAiCommonProperties props = new OpenAiCommonProperties();
        props.setBaseUrl(BASE_URL);
        props.setApiKey(API_KEY);
        return props;
    }

    /**
     * [Purpose] Builds a real {@link AppProperties} for {@link OpenRouterRerankAdapter}'s
     *   constructor, overriding only {@code rerank} on {@link TestAppPropertiesFactory}'s valid
     *   baseline (T5 review 2026-08-27, Medium-4).
     * [Caution] The factory's default {@code AppProperties.Models} (chat T5's 6th record
     *   component) is unrelated to reranking -- it stays at its valid default purely to satisfy
     *   {@code AppProperties.Models}'s fail-fast compact constructor, since {@link
     *   OpenRouterRerankAdapter} never reads {@code AppProperties.models()}.
     */
    private OpenRouterRerankAdapter adapter(boolean rerankEnabled) {
        AppProperties appProperties = TestAppPropertiesFactory.builder()
                .rerank(new AppProperties.Rerank(
                        rerankEnabled, "cohere/rerank-v3.5", Duration.ofSeconds(5), Duration.ofSeconds(10)))
                .build();
        return new OpenRouterRerankAdapter(restClientBuilder, appProperties, openAiCommonProperties());
    }

    private static RetrievedChunk chunk(String id, double fusedScore) {
        return new RetrievedChunk(id, "content-" + id, Map.of(), fusedScore);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-6] app.rerank.enabled=false 이면 API를 호출하지 않고 융합 결과 상위 topK건을 그대로 반환한다")
    void returnsFusedOrderWithoutCallingApiWhenDisabled() {
        // given: rerank disabled, three fused candidates, no MockRestServiceServer expectation set.
        //   "no HTTP call was made" is NOT enforced by server.verify() below — with zero
        //   expectations registered, verify() trivially passes regardless of whether a call
        //   happened. The actual enforcement is synchronous: MockRestServiceServer installs a
        //   ClientHttpRequestFactory interceptor that throws AssertionError the instant an
        //   unmatched request is attempted, so if adapter.rerank(...) below tried to call the API
        //   at all, the `when` step itself would fail with that AssertionError before this method
        //   ever reaches its `then` assertions. verify() only additionally confirms every
        //   *registered* expectation (none, here) was satisfied.
        OpenRouterRerankAdapter adapter = adapter(false);
        List<RetrievedChunk> candidates = List.of(chunk("a", 0.9), chunk("b", 0.8), chunk("c", 0.7));

        // when
        List<RetrievedChunk> result = adapter.rerank("query", candidates, 2);

        // then: top 2 of the fused order, untouched, and no HTTP call was made
        assertThat(result).extracting(RetrievedChunk::id).containsExactly("a", "b");
        server.verify();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-7] app.rerank.enabled=true 이면 응답 index 순서대로 상위 topK건을 반환하고 원래 융합 점수를 유지한다")
    void reordersByResponseIndexAndKeepsFusedScoreWhenEnabled() {
        // given: rerank enabled, response reorders to [c, a] (index 2, then index 0)
        OpenRouterRerankAdapter adapter = adapter(true);
        List<RetrievedChunk> candidates = List.of(chunk("a", 0.9), chunk("b", 0.8), chunk("c", 0.7));
        server.expect(requestTo(BASE_URL + "/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("cohere/rerank-v3.5"))
                .andExpect(jsonPath("$.query").value("query"))
                .andExpect(jsonPath("$.top_n").value(2))
                .andExpect(jsonPath("$.documents[0]").value("content-a"))
                .andExpect(jsonPath("$.documents[1]").value("content-b"))
                .andExpect(jsonPath("$.documents[2]").value("content-c"))
                .andRespond(withSuccess(
                        """
                        {"results":[{"index":2,"relevance_score":0.98},{"index":0,"relevance_score":0.75}]}
                        """,
                        MediaType.APPLICATION_JSON));

        // when
        List<RetrievedChunk> result = adapter.rerank("query", candidates, 2);

        // then: order is [c, a] per the response, but score() stays each chunk's original fused
        //   score (0.7, 0.9) — never overwritten by relevance_score (Tech Review D-T6)
        assertThat(result).extracting(RetrievedChunk::id).containsExactly("c", "a");
        assertThat(result.get(0).score()).isEqualTo(0.7);
        assertThat(result.get(1).score()).isEqualTo(0.9);
        server.verify();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-7] 응답 results가 topK보다 많으면 topK건으로 절단하고 응답 순서를 유지한다")
    void truncatesToTopKWhenResponseHasMoreResultsThanTopK() {
        // given: topK=2, but the mocked response returns all 3 candidates reordered — regression
        //   guard for review High-3: deleting the adapter's `.limit(topK)` call would make this
        //   assertion fail with size 3 instead of 2, which the pre-existing tests above could not
        //   catch because their stub responses already contained exactly topK results.
        OpenRouterRerankAdapter adapter = adapter(true);
        List<RetrievedChunk> candidates = List.of(chunk("a", 0.9), chunk("b", 0.8), chunk("c", 0.7));
        server.expect(requestTo(BASE_URL + "/rerank"))
                .andExpect(jsonPath("$.top_n").value(2))
                .andRespond(withSuccess(
                        """
                        {"results":[{"index":2,"relevance_score":0.98},{"index":0,"relevance_score":0.81},{"index":1,"relevance_score":0.5}]}
                        """,
                        MediaType.APPLICATION_JSON));

        // when
        List<RetrievedChunk> result = adapter.rerank("query", candidates, 2);

        // then: exactly topK=2 elements, in response order [c, a] — the third result (b) is
        //   dropped
        assertThat(result).hasSize(2);
        assertThat(result).extracting(RetrievedChunk::id).containsExactly("c", "a");
        server.verify();
    }

    @Test
    @DisplayName("응답 index가 candidates 범위를 벗어나면(index == candidates.size()) IllegalStateException을 던진다")
    void throwsIllegalStateExceptionWhenResponseIndexOutOfRange() {
        // given: only one candidate exists (valid index: 0 only), response claims index 1
        OpenRouterRerankAdapter adapter = adapter(true);
        List<RetrievedChunk> candidates = List.of(chunk("a", 0.9));
        server.expect(requestTo(BASE_URL + "/rerank"))
                .andRespond(withSuccess(
                        """
                        {"results":[{"index":1,"relevance_score":0.9}]}
                        """,
                        MediaType.APPLICATION_JSON));

        // when / then
        assertThatThrownBy(() -> adapter.rerank("query", candidates, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out-of-range");
        server.verify();
    }

    @Test
    @DisplayName("app.rerank.enabled=true 이어도 candidates가 비어 있으면 API를 호출하지 않고 빈 리스트를 반환한다")
    void returnsEmptyListWithoutCallingApiWhenCandidatesEmpty() {
        // given: rerank enabled but there is nothing to rerank; no MockRestServiceServer
        //   expectation is registered, so an attempted HTTP call would fail this test at the
        //   `when` step (same enforcement mechanism explained in
        //   returnsFusedOrderWithoutCallingApiWhenDisabled above)
        OpenRouterRerankAdapter adapter = adapter(true);

        // when
        List<RetrievedChunk> result = adapter.rerank("query", List.of(), 5);

        // then
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("응답에 동일한 index가 중복되면 해당 후보가 결과에도 그대로 중복 반환된다 (역직렬화 결과에 대한 중복 제거 없음)")
    void duplicateResponseIndexProducesDuplicateResultElement() {
        // given: the response lists index 0 twice — the adapter has no dedup step, so this
        //   documents the actual (undeduplicated) behavior rather than an intended contract.
        //   Follow-up: review Low-4 asks for this policy to also be recorded in the adapter's
        //   Javadoc — out of scope for this test file (main-code edit, dev-backend-engineer's).
        OpenRouterRerankAdapter adapter = adapter(true);
        List<RetrievedChunk> candidates = List.of(chunk("a", 0.9), chunk("b", 0.8));
        server.expect(requestTo(BASE_URL + "/rerank"))
                .andRespond(withSuccess(
                        """
                        {"results":[{"index":0,"relevance_score":0.9},{"index":0,"relevance_score":0.9}]}
                        """,
                        MediaType.APPLICATION_JSON));

        // when
        List<RetrievedChunk> result = adapter.rerank("query", candidates, 2);

        // then
        assertThat(result).extracting(RetrievedChunk::id).containsExactly("a", "a");
        server.verify();
    }

    @Test
    @DisplayName("리랭크 API가 오류를 반환하면 삼키지 않고 예외를 전파한다 (RerankPort 계약, AC-8 폴백은 T7 소관)")
    void propagatesExceptionOnApiError() {
        // given: rerank enabled, server responds 500
        OpenRouterRerankAdapter adapter = adapter(true);
        List<RetrievedChunk> candidates = List.of(chunk("a", 0.9));
        server.expect(requestTo(BASE_URL + "/rerank")).andRespond(withServerError());

        // when / then
        assertThatThrownBy(() -> adapter.rerank("query", candidates, 1))
                .isInstanceOf(RestClientException.class);
        server.verify();
    }
}
