package art.insightai.chatbot.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * [Purpose] JUnit 5 extension form of {@link DatabaseGuard#assertConfiguredForLocalChatbotDatabase()}
 * -- applying {@code @ExtendWith(DatabaseGuardExtension.class)} (typically via
 * {@link IntegrationTestBase}) gets a new {@code @SpringBootTest} class the same
 * before-context-refresh abort that {@code BackendApplicationTests} previously wired by hand
 * with a static {@code @BeforeAll} method, without copy-pasting it.
 * [Rationale] Card T1 review (2026-08-25 follow_up) asked for this generalization so a third
 * {@code @SpringBootTest} class doesn't silently skip the guard by forgetting the manual
 * {@code @BeforeAll} boilerplate. {@link BeforeAllCallback#beforeAll} runs before JUnit even
 * instantiates the test class -- and {@code SpringExtension} only loads/refreshes the
 * {@code ApplicationContext} while creating and dependency-injecting that instance (its
 * {@code TestInstancePostProcessor} callback, which runs after all {@code BeforeAllCallback}
 * extensions) -- so this still runs early enough to abort before Flyway's
 * auto-migration (default {@code spring.flyway.enabled=true}) ever touches a database, exactly
 * like the static {@code @BeforeAll} it replaces (see {@code BackendApplicationTests}' prior
 * Javadoc, preserved in git history, for the empirical confirmation of this ordering).
 * [Caution] This only covers the pre-refresh, {@code infra/.env}-parsing guard variant.
 * {@code V1InitSchemaIntegrationTest} intentionally keeps its own live-connection variant
 * ({@link DatabaseGuard#verifyLocalChatbotDatabase}) instead of this extension -- it disables
 * {@code spring.flyway.enabled} and drives {@code Flyway.migrate()} itself after the context is
 * already up, so it has an open {@code JdbcTemplate} to check the *actual* connection against,
 * which is a stronger check than parsing the configured URL. Forcing it onto this extension
 * would lose that.
 */
public final class DatabaseGuardExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        DatabaseGuard.assertConfiguredForLocalChatbotDatabase();
    }
}
