package art.insightai.chatbot.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * [Purpose] Shared base for full-context {@code @SpringBootTest} classes: wires the
 * {@link DatabaseGuardExtension} (fails fast before any Flyway auto-migration if
 * {@code infra/.env}'s {@code DB_URL} doesn't point at the expected local {@code chatbot}
 * database), {@link StubChatModel} (so no test ever reaches the real OpenRouter chat endpoint,
 * chat:AC-35), and {@link StubEmbeddingModel} (so no test ever reaches the real OpenRouter
 * embedding endpoint, document-ingestion Spec §6) automatically. A new integration test only
 * needs to {@code extends IntegrationTestBase} to get all three.
 * [Rationale] Card T1 review follow_up asked for the DB guard to auto-apply to future
 * {@code @SpringBootTest} classes instead of relying on each one copying
 * {@code DatabaseGuard}'s call sites by hand. Bundling both model stubs into the same base
 * (rather than opt-in mixins) reflects a requirement common to every Spec that reaches this
 * class: chat Spec §6 replaces the LLM with a stub, document-ingestion Spec §6 replaces the
 * embedding model with a stub, and both stem from the same reason -- a forgotten stub here is
 * not a flaky test, it's a real (billed) HTTP call to OpenRouter. The embedding stub carries an
 * additional structural reason beyond safety: {@code PgVectorStoreAutoConfiguration} requires
 * an {@code EmbeddingModel} bean to exist at all (mandatory constructor parameter, no
 * {@code ObjectProvider}) for *any* {@code @SpringBootTest} in this module, chat-domain or
 * rag-domain -- see {@link StubEmbeddingModelConfig}'s Javadoc.
 * [Caution] {@code @ActiveProfiles("test")} activates {@code application-test.yaml}, which
 * excludes {@code OpenAiChatAutoConfiguration}/{@code OpenAiEmbeddingAutoConfiguration} -- that
 * exclusion, and only that exclusion, is scoped to this profile (review
 * 2026-08-27_document-ingestion-T4-round2.md Medium-F: an earlier version of this Javadoc said
 * {@code application-test.yaml} also "points {@code spring.ai.openai.base-url} at an
 * intentionally unroutable local address", which stopped being true once that rework moved the
 * unroutable {@code base-url}/dummy {@code api-key} values to
 * {@code src/test/resources/config/application.yaml} -- a non-profile config-data file that
 * applies to EVERY {@code @SpringBootTest} in this module, not just subclasses of this class; see
 * that file's own comments for why, and
 * {@link ConfigApplicationYamlAppliesOutsideIntegrationTestBaseTest} for the regression probe
 * that proves it). A subclass that needs the *real* chat or embedding autoconfiguration (there
 * should be none in this project's test suite) must not extend this class -- but even such a
 * subclass would still inherit the unroutable {@code base-url}/dummy {@code api-key} from
 * {@code config/application.yaml} regardless, since that layer is not opt-in.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({StubChatModelConfig.class, StubEmbeddingModelConfig.class})
@ExtendWith(DatabaseGuardExtension.class)
public abstract class IntegrationTestBase {
}
