package art.insightai.chatbot.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * [Purpose] Binds the {@code app.*} namespace of application.yaml.
 * [Rationale] Records give constructor binding and immutability for free, and
 *   spring-boot-configuration-processor turns them into IDE metadata, which removes the
 *   "cannot resolve configuration property" warnings and adds completion. Modes are enums
 *   rather than strings so a typo such as {@code hybird} fails at startup instead of
 *   silently falling back to vector search halfway through D6.
 * [Caution] The processor only runs because it is listed in the maven-compiler-plugin
 *   annotationProcessorPaths block. Adding the dependency alone is not enough once that
 *   block exists, because it replaces processor discovery from the classpath.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue Auth auth,
        @DefaultValue Search search,
        @DefaultValue Rerank rerank
) {

    /** [Purpose] Authentication strategy. NONE for the demo, WORKS (NAVER WORKS SSO) in Phase 2. */
    public record Auth(
            @DefaultValue("none") Mode mode
    ) {
        public enum Mode { NONE, WORKS }
    }

    /**
     * [Purpose] Retrieval strategy and hybrid fusion parameters.
     * [Rationale] VECTOR uses pgvector alone; HYBRID fuses BM25 and vector ranks with
     *   Reciprocal Rank Fusion. Weights and rrfK are tuned against the D7 evaluation set,
     *   so they are configuration rather than constants.
     */
    public record Search(
            @DefaultValue("vector") Mode mode,
            @DefaultValue("5") int topK,
            @DefaultValue("60") int rrfK,
            @DefaultValue("0.5") double weightBm25,
            @DefaultValue("0.5") double weightVector
    ) {
        public enum Mode { VECTOR, HYBRID }
    }

    /** [Purpose] Reranking stage. Enabled only after the D7 evaluation says it helps. */
    public record Rerank(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("cohere/rerank-v3.5") String model
    ) {}
}
