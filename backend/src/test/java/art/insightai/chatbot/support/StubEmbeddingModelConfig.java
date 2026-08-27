package art.insightai.chatbot.support;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * [Purpose] Registers {@link StubEmbeddingModel} as the test context's {@link EmbeddingModel}
 * bean.
 * [Rationale] Mirrors {@link StubChatModelConfig}: {@link IntegrationTestBase} pairs this with
 * excluding {@code OpenAiEmbeddingAutoConfiguration} (see {@code application-test.yaml}), so
 * this is the only {@code EmbeddingModel} bean an {@link IntegrationTestBase}-based test context
 * can wire (review 2026-08-27_document-ingestion-T4-round2.md Medium-F corrects an earlier,
 * broader claim here -- a {@code @SpringBootTest} that does NOT extend {@link IntegrationTestBase}
 * and does NOT import this configuration, such as
 * {@code art.insightai.chatbot.migration.V1InitSchemaIntegrationTest} or
 * {@link ConfigApplicationYamlAppliesOutsideIntegrationTestBaseTest}, still gets the REAL
 * {@code OpenAiEmbeddingModel} bean, because the autoconfigure exclusion below is itself scoped
 * to the {@code test} profile this class's importer activates -- it is not global). That matters
 * for a second reason beyond the network-safety one {@code StubChatModelConfig} documents:
 * {@code PgVectorStoreAutoConfiguration} (spring-ai-autoconfigure-vector-store-pgvector:2.0.1,
 * read directly from its sources jar rather than guessed -- CLAUDE.md P3) takes
 * {@code EmbeddingModel} as a *mandatory* {@code @Bean} method parameter with no
 * {@code ObjectProvider}/nullable fallback, so every {@code @SpringBootTest} in this module
 * needs exactly one {@code EmbeddingModel} bean to exist at all -- not just a safe one -- or the
 * {@code ApplicationContext} fails to start.
 * [Caution] Bundled into the shared {@link IntegrationTestBase} rather than a
 * document-ingestion-only subclass, because that mandatory-parameter requirement applies to
 * *every* {@code @SpringBootTest} in this module (e.g. chat Spec's), not just this Spec's --
 * see {@link IntegrationTestBase}'s Javadoc for the same reasoning applied to
 * {@code StubChatModelConfig}.
 */
@TestConfiguration
public class StubEmbeddingModelConfig {

    @Bean
    public StubEmbeddingModel stubEmbeddingModel() {
        return new StubEmbeddingModel();
    }
}
