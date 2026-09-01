package art.insightai.chatbot.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
 * rag-domain -- see {@link StubEmbeddingModelConfig}'s Javadoc. {@code
 * app.session.max-anonymous-users} is pinned high (100000, BL-027, 2026-09-01) for the same
 * "protect the shared local test resource" reason as the two stubs above: of this class's 28
 * direct subclasses, 13 issue real HTTP requests (through {@code MockMvc}, so they pass through
 * {@code AnonymousSessionFilter}) and only 5 of those already pinned this key themselves ({@code
 * SessionControllerIntegrationTest}, {@code OutboundCallGuardIntegrationTest}, {@code
 * UsageControllerIntegrationTest}, {@code ConversationControllerIntegrationTest}, and {@code
 * SessionQuotaIntegrationTest} at {@code 0} to test the ceiling itself) -- the other 8 ran against
 * whatever the shared {@code chatbot-db}'s real {@code users} row count happened to be, so a full
 * local regression could start failing with 503s purely from accumulated data, independent of any
 * code change (`insight-chat-4d` diagnosed exactly this against 16 {@code rag/api} integration
 * tests, 2026-09-01). {@code EvalSweepIntegrationTest} does not extend this class at all (it wires
 * {@link DatabaseGuardExtension} directly instead) and is not part of either count above -- it
 * runs at the production default (1000) deliberately and defends the shared quota by deleting
 * every row it creates ({@code EvalSweepIntegrationTest#deleteSweepData}), not by raising the
 * ceiling; an earlier version of this note wrongly listed it as a sixth class that pinned this key
 * (review round 1, correctness Medium-1/maintainability Medium-2, 2026-09-01).
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
 * <p>
 * <b>{@code app.session.max-anonymous-users} (BL-027, 2026-09-01):</b> pinned high (100000) here
 * so that any subclass creating anonymous sessions/users does not draw down the shared local
 * {@code chatbot-db}'s real quota (production default 1000, no TTL/reclaim -- see {@code
 * AppProperties.Session}'s own Javadoc). Before this, only 6 of the ~24 classes that reach the
 * anonymous-user path pinned this themselves ({@code SessionControllerIntegrationTest}, {@code
 * OutboundCallGuardIntegrationTest}, {@code UsageControllerIntegrationTest}, {@code
 * ConversationControllerIntegrationTest}, {@code EvalSweepIntegrationTest}, and {@code
 * SessionQuotaIntegrationTest} at {@code 0} to test the ceiling itself) -- every other subclass
 * ran against whatever the shared DB's real row count happened to be, so a full local regression
 * could start failing with 503s purely from accumulated data, independent of any code change
 * (`insight-chat-4d` diagnosed exactly this against 16 {@code rag/api} integration tests,
 * 2026-09-01). A subclass-level {@code @TestPropertySource} for this same key still wins over
 * this one (Spring merges a class hierarchy's {@code @TestPropertySource} inlined properties with
 * the most-derived class's values taking precedence on key conflicts -- confirmed empirically
 * here, not merely assumed: {@code SessionQuotaIntegrationTest}'s own {@code
 * app.session.max-anonymous-users=0} was re-run after this base-class override was added and its
 * ceiling-rejection assertions still passed), so the 6 existing overrides needed no changes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({StubChatModelConfig.class, StubEmbeddingModelConfig.class})
@ExtendWith(DatabaseGuardExtension.class)
@TestPropertySource(properties = "app.session.max-anonymous-users=100000")
public abstract class IntegrationTestBase {
}
