package art.insightai.chatbot.rag.retrieval;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

import art.insightai.chatbot.common.config.AppProperties;

/**
 * [Purpose] Registers a {@link RestClientCustomizer} that applies {@code
 *   app.rerank.connect-timeout}/{@code read-timeout} to the auto-configured {@link
 *   org.springframework.web.client.RestClient.Builder} bean {@link OpenRouterRerankAdapter}'s
 *   constructor receives.
 * [Rationale] Boot's {@code RestClientAutoConfiguration} collects every {@code
 *   RestClientCustomizer} bean via {@code RestClientBuilderConfigurer} and applies them to the
 *   shared {@code RestClient.Builder} bean before it is injected anywhere — so a {@link
 *   ClientHttpRequestFactory} set here already carries the timeout by the time {@link
 *   OpenRouterRerankAdapter}'s constructor runs, without that constructor calling {@code
 *   .requestFactory(...)} itself. Moving the call here fixes review 2026-08-27 round-1 Block
 *   High-1's regression: the adapter's constructor used to call {@code
 *   restClientBuilder.requestFactory(...)} directly on the exact same {@code RestClient.Builder}
 *   instance {@code OpenRouterRerankAdapterTest} pre-wires with {@code
 *   MockRestServiceServer.bindTo(...)}, which unconditionally overwrote that mock factory and
 *   made every {@code enabled=true} test try a real DNS lookup. A {@link RestClientCustomizer}
 *   bean, by contrast, is never invoked by the test — it only exists in the full Spring
 *   application context, so {@code MockRestServiceServer.bindTo(restClientBuilder)}, called
 *   directly on a bare {@code RestClient.builder()} the test constructs itself, is never touched
 *   by it. This project has exactly one {@code RestClient.Builder} consumer ({@link
 *   OpenRouterRerankAdapter}, confirmed by grep across {@code src/main/java}), so registering this
 *   customizer globally carries no risk of clobbering timeouts some other client relies on.
 * [Caution] If a second {@code RestClient} consumer with different timeout needs is ever added,
 *   this global customizer would apply to it too — at that point, switch to a named {@code
 *   RestClient.Builder} bean scoped to this adapter instead of widening this class.
 */
@Configuration
public class RerankRestClientConfig {

    @Bean
    public RestClientCustomizer rerankTimeoutRestClientCustomizer(AppProperties appProperties) {
        AppProperties.Rerank rerank = appProperties.rerank();
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(rerank.connectTimeout(), rerank.readTimeout()));
        return builder -> builder.requestFactory(requestFactory);
    }
}
