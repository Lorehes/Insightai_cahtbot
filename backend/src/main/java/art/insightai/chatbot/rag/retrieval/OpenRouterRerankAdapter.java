package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] {@link RerankPort} implementation that calls OpenRouter's {@code /rerank} endpoint
 *   (model configured by {@code app.rerank.model}, default {@code cohere/rerank-v3.5}) when
 *   {@code app.rerank.enabled=true}, and otherwise returns the fused candidates unchanged
 *   (hybrid-rag Spec AC-6, AC-7).
 * [Rationale] Reuses the same OpenRouter host Spring AI's chat/embedding clients already talk
 *   to ({@code spring.ai.openai.base-url}, which per CLAUDE.md fact #2 already includes
 *   {@code /v1}) and the same bearer-token auth ({@code spring.ai.openai.api-key}), so this
 *   adapter needs no new credential or host configuration beyond the existing properties. These
 *   two values are read from {@link OpenAiCommonProperties} (the {@code spring.ai.openai.*}
 *   {@code @ConfigurationProperties} bean spring-ai-autoconfigure-model-openai already registers
 *   via {@code @EnableConfigurationProperties} — verified in its sources — and therefore already
 *   a Spring-managed bean of its own) rather than {@code @Value}, so this class stays on the
 *   "settings via the configuration layer" rule (review 2026-08-27 Medium-3) without duplicating
 *   {@code spring.ai.openai.*} into {@code app.*} (which would need its own env var wiring in
 *   {@code infra/.env} and could drift from the values the chat/embedding clients actually use).
 *   Spring AI 2.0's {@code OpenAiApi} does not expose a rerank operation (Tech Review D-T4 scoped
 *   this card to a hand-built client), so a plain {@link RestClient} built from the
 *   auto-configured {@link RestClient.Builder} is used instead. {@code app.rerank.connect-timeout}/
 *   {@code read-timeout} are applied to that builder by {@link RerankRestClientConfig}'s {@code
 *   RestClientCustomizer} bean, not by this constructor — the auto-configured {@link
 *   RestClient.Builder} otherwise carries no timeout at all, and a stalled OpenRouter response
 *   would block a request thread indefinitely, which would also mean {@code
 *   HybridDocumentRetriever}'s (T7) AC-8 timeout fallback could never actually trigger (review
 *   2026-08-27 round-1 High-1, 3-axis merged finding). The timeout used to be applied here via a
 *   direct {@code restClientBuilder.requestFactory(...)} call, but that unconditionally overwrote
 *   the mock {@code ClientHttpRequestFactory} {@code OpenRouterRerankAdapterTest} pre-wires onto
 *   the same {@code RestClient.Builder} instance via {@code MockRestServiceServer.bindTo(...)},
 *   breaking every {@code enabled=true} test (review 2026-08-27 round-1 Block High-1 regression,
 *   fixed round 2 by moving the timeout into {@link RerankRestClientConfig} instead, which the
 *   test's bare {@code RestClient.builder()} never goes through). This constructor therefore only
 *   sets base URL and auth header; see {@link RerankRestClientConfig} for the timeout wiring and
 *   why a global {@link org.springframework.boot.restclient.RestClientCustomizer} is safe here.
 * [Caution] The request/response schema below ({@code model}/{@code query}/{@code documents}/
 *   {@code top_n} in, {@code results[].index}/{@code results[].relevance_score} out, assumed
 *   sorted by {@code relevance_score} descending) is a **user-approved, unverified best-effort
 *   guess** (Tech Review Risk R-C: {@code /v1/rerank} appears in the plan doc's §4 diagram and
 *   §7.4 pricing table but is NOT in Appendix B's verified-endpoint table). It has never been
 *   exercised against a real OpenRouter response. The eval-usage-dashboard Spec (D7) is expected
 *   to make a real call and confirm or correct this shape — if the real schema differs, only
 *   this adapter file should need to change; {@link RerankPort} and its callers (T7) are
 *   insulated from the wire format by design. Per {@link RerankPort}'s contract, API errors and
 *   timeouts are propagated as unchecked exceptions from {@link RestClient}, not swallowed here
 *   — {@code HybridDocumentRetriever} (T7) owns the AC-8 fallback-to-fused-order behavior, not
 *   this class. {@code candidates.get(result.index())} no longer blindly trusts the API's {@code
 *   index} to be a valid 0-based position into the {@code documents} array this adapter sent —
 *   {@link #rerank} range-checks it against {@code candidates.size()} first and converts an
 *   out-of-range value into an {@link IllegalStateException} (review 2026-08-27 Medium-1), the
 *   same exception type already used a few lines above for an empty response body, so both
 *   "malformed response" cases surface the same way to {@code HybridDocumentRetriever} (T7).
 *   {@code @JsonProperty} on {@code RerankRequest.topN} below is {@code
 *   com.fasterxml.jackson.annotation.JsonProperty}, not a {@code tools.jackson.*} type — this is
 *   intentional, not a leftover Jackson 2 mistake (review 2026-08-27 Medium-4 asked for a swap to
 *   {@code tools.jackson.annotation.JsonProperty}, but no such class exists). Verified directly:
 *   {@code jar tf} on both {@code tools.jackson.core:jackson-core:3.1.5} and {@code
 *   tools.jackson.core:jackson-databind:3.1.5} (the versions this project resolves) contains no
 *   {@code tools/jackson/annotation/*} package at all, and {@code javap -c} on Jackson 3's own
 *   {@code tools.jackson.databind.introspect.JacksonAnnotationIntrospector} shows it referencing
 *   {@code com/fasterxml/jackson/annotation/*} classes directly. Jackson 3 renamed the
 *   core/streaming/databind modules to the {@code tools.jackson} groupId but did not fork the
 *   annotations module — {@code @JsonProperty}, {@code @JsonIgnore}, etc. are still {@code
 *   com.fasterxml.jackson.annotation.*}, shipped by the {@code com.fasterxml.jackson.core:
 *   jackson-annotations} artifact that {@code tools.jackson.core:jackson-databind:3.1.5} itself
 *   depends on (confirmed both in that artifact's POM and in this project's resolved {@code mvn
 *   dependency:tree}, where it appears at {@code compile} scope under {@code flyway-core}). This
 *   also means the review's premise that "this project has zero {@code com.fasterxml.jackson.*}
 *   coordinates" no longer holds as of this dependency tree — flag for whoever tracks
 *   {@code incidents.jsonl} INC-002.
 */
@Component
public class OpenRouterRerankAdapter implements RerankPort {

    private final RestClient restClient;
    private final boolean enabled;
    private final String model;

    public OpenRouterRerankAdapter(
            RestClient.Builder restClientBuilder,
            AppProperties appProperties,
            OpenAiCommonProperties openAiCommonProperties
    ) {
        AppProperties.Rerank rerank = appProperties.rerank();
        this.enabled = rerank.enabled();
        this.model = rerank.model();
        this.restClient = restClientBuilder
                .baseUrl(openAiCommonProperties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openAiCommonProperties.getApiKey())
                .build();
    }

    /**
     * [Purpose] Returns the top {@code topK} of {@code candidates} — reordered by the rerank
     *   API's response when {@code app.rerank.enabled=true} (AC-7), or left in fused order when
     *   disabled (AC-6).
     * [Caution] {@link RetrievedChunk#score()} on every returned element is always the original
     *   fused RRF score from {@code candidates}, never {@code relevance_score} (Tech Review
     *   D-T6) — reordering only changes list position, never the {@code score} field. When {@code
     *   enabled} but {@code candidates} is empty, this returns {@code List.of()} without calling
     *   the API (review 2026-08-27 Medium-2) — an empty {@code documents} array in the request
     *   body is not a documented case in the unverified schema above, and every no-result query
     *   would otherwise cost a wasted OpenRouter round trip and risk tripping T7's AC-8 fallback
     *   for no reason.
     * [AC] hybrid-rag:AC-6
     * [AC] hybrid-rag:AC-7
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (!enabled) {
            return candidates.stream().limit(topK).toList();
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> documents = candidates.stream().map(RetrievedChunk::content).toList();
        RerankRequest request = new RerankRequest(model, query, documents, topK);

        RerankResponse response = restClient.post()
                .uri("/rerank")
                .body(request)
                .retrieve()
                .body(RerankResponse.class);

        if (response == null || response.results() == null) {
            throw new IllegalStateException("OpenRouter /rerank returned an empty body");
        }

        return response.results().stream()
                .limit(topK)
                .map(result -> {
                    int index = result.index();
                    if (index < 0 || index >= candidates.size()) {
                        throw new IllegalStateException(
                                "OpenRouter /rerank returned an out-of-range index " + index
                                        + " for " + candidates.size() + " candidates");
                    }
                    return candidates.get(index);
                })
                .toList();
    }

    /** Request body for the assumed {@code /rerank} schema (see class [Caution] — unverified). */
    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN
    ) {
    }

    /** Response body for the assumed {@code /rerank} schema (see class [Caution] — unverified). */
    private record RerankResponse(List<RerankResult> results) {
    }

    /** One reranked hit; {@code relevance_score} is not modeled since only ordering is used. */
    private record RerankResult(int index) {
    }
}
