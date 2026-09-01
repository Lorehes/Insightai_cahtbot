package art.insightai.chatbot.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * [Purpose] Reads {@code infra/.env} for the {@code @Tag("eval")} classes that must reach the
 *   real OpenRouter endpoints, and un-poisons exactly the Spring AI keys a caller asks for.
 * [Rationale] {@code config/application.yaml} (test classpath) and pom.xml's surefire {@code
 *   systemPropertyVariables} both force every {@code spring.ai.openai.*} key to an unroutable
 *   address (chat:AC-35). {@code @DynamicPropertySource} is the only property source that outranks
 *   both, so each real-call test class registers the keys it needs through here. The same two
 *   relative locations {@code application.yaml}'s {@code spring.config.import} tries are checked
 *   (terminal cwd = {@code backend/}, IntelliJ cwd = repo root). Extracted from the private helpers
 *   in {@code EvalSweepIntegrationTest} (left untouched -- it is AC-7's evidence) so the D7 sweep
 *   classes added for T1 do not carry a third copy each.
 * [Caution] Never log or print anything read here -- the API key is a secret.
 */
final class OpenRouterEnv {

    private OpenRouterEnv() {
    }

    static Properties load() {
        for (String candidate : new String[] {"../infra/.env", "./infra/.env"}) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                Properties props = new Properties();
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    props.load(reader);
                    return props;
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + path, e);
                }
            }
        }
        throw new IllegalStateException("infra/.env not found at ../infra/.env or ./infra/.env");
    }

    static String requireApiKey(Properties env) {
        String apiKey = env.getProperty("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "infra/.env must set OPENROUTER_API_KEY to run @Tag(\"eval\") tests");
        }
        return apiKey;
    }

    static String baseUrl(Properties env) {
        return env.getProperty("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");
    }

    /** [Purpose] Opens only the embedding model keys (query embeddings for retrieval). */
    static void openEmbedding(DynamicPropertyRegistry registry) {
        Properties env = load();
        String apiKey = requireApiKey(env);
        String baseUrl = baseUrl(env);
        registry.add("spring.ai.openai.embedding.base-url", () -> baseUrl);
        registry.add("spring.ai.openai.embedding.api-key", () -> apiKey);
    }

    /** [Purpose] Opens the chat + embedding model keys (the full RAG answer path, no rerank). */
    static void openChatAndEmbedding(DynamicPropertyRegistry registry) {
        Properties env = load();
        String apiKey = requireApiKey(env);
        String baseUrl = baseUrl(env);
        registry.add("spring.ai.openai.chat.base-url", () -> baseUrl);
        registry.add("spring.ai.openai.chat.api-key", () -> apiKey);
        registry.add("spring.ai.openai.embedding.base-url", () -> baseUrl);
        registry.add("spring.ai.openai.embedding.api-key", () -> apiKey);
    }

    /**
     * [Purpose] Opens the common-level keys {@code OpenRouterRerankAdapter} reads -- only for a
     *   context that actually turns {@code app.rerank.enabled} on (see {@code
     *   EvalSweepIntegrationTest}'s security Medium-1 note for why the others must not).
     */
    static void openRerank(DynamicPropertyRegistry registry) {
        Properties env = load();
        String apiKey = requireApiKey(env);
        String baseUrl = baseUrl(env);
        registry.add("spring.ai.openai.base-url", () -> baseUrl);
        registry.add("spring.ai.openai.api-key", () -> apiKey);
    }
}
