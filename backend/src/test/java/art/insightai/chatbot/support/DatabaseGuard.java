package art.insightai.chatbot.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Shared guard that aborts before Flyway (or any other schema-touching call)
 * can run against a database other than the local {@code chatbot} ParadeDB instance
 * this project's {@code infra/docker-compose.yml} starts.
 * [Rationale] Both {@code @SpringBootTest} classes in this module can trigger a Flyway
 * migration against whatever {@code infra/.env} currently points {@code DB_URL} at.
 * {@code V1InitSchemaIntegrationTest} disables Spring's auto-migration
 * ({@code spring.flyway.enabled=false}) and calls {@code migrate()} itself after the
 * {@code ApplicationContext} is already up, so it can check the actual live connection
 * ({@link #verifyLocalChatbotDatabase}). {@code BackendApplicationTests} leaves
 * {@code spring.flyway.enabled} at its default ({@code true}), which means
 * {@code FlywayMigrationInitializer} runs during {@code ApplicationContext} refresh —
 * before any {@code @BeforeEach}, before any {@code @Autowired} field is even populated
 * (review 2026-08-25 Medium-2). At that point there is no {@code DataSource} bean yet to
 * query, so {@code BackendApplicationTests} instead calls
 * {@link #assertConfiguredForLocalChatbotDatabase()} from a static {@code @BeforeAll}
 * method — JUnit runs {@code @BeforeAll} before the test instance is created, and Spring
 * only loads/refreshes the context while creating and injecting that instance (see
 * {@code BackendApplicationTests} for how this ordering was confirmed).
 * [Caution] Card T2 generalized {@link #assertConfiguredForLocalChatbotDatabase()} into
 * {@link DatabaseGuardExtension}, which {@link IntegrationTestBase} applies automatically —
 * new {@code @SpringBootTest} classes should extend that base instead of copying either
 * call site here by hand. This class stays intentionally minimal — just the two guard
 * variants (pre-refresh and live-connection); do not add unrelated assertions here.
 */
public final class DatabaseGuard {

    private static final String EXPECTED_DATABASE = "chatbot";
    private static final String EXPECTED_HOST = "localhost";
    private static final int EXPECTED_PORT = 5432;

    // Mirrors application.yaml's spring.config.import order (terminal cwd=backend/,
    // IntelliJ cwd=project root) so the pre-refresh check below reads the exact same
    // DB_URL value Spring itself would import.
    private static final List<String> DOT_ENV_CANDIDATES = List.of("../infra/.env", "./infra/.env");

    private DatabaseGuard() {
    }

    /**
     * [Purpose] Verifies an already-open Spring-managed connection is actually pointed
     * at the expected local chatbot database before a caller is allowed to run
     * migrations or other schema-touching operations against it.
     * [Caution] Must be called before the first migrate()/schema-touching call on the
     * given {@link JdbcTemplate}'s {@code DataSource} — callers are responsible for
     * that ordering.
     */
    public static void verifyLocalChatbotDatabase(JdbcTemplate jdbc) throws SQLException {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        String jdbcUrl;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }

        // The DB name comes from the server (current_database()); the host/port must
        // be read from the client-side JDBC URL, not server-side network
        // introspection — this project's DB runs in a Docker container reached via
        // port-forwarding, so inet_server_addr() reports the container's internal
        // bridge IP, not "localhost"/127.0.0.1, even for a legitimate local setup.
        assertThat(database).as("connected database").isEqualTo(EXPECTED_DATABASE);
        URI connectionUri = URI.create(jdbcUrl.substring("jdbc:".length()));
        assertThat(connectionUri.getHost()).as("connected JDBC host").isEqualTo(EXPECTED_HOST);
        assertThat(connectionUri.getPort()).as("connected JDBC port").isEqualTo(EXPECTED_PORT);
    }

    /**
     * [Purpose] Pre-context-refresh guard: parses the {@code DB_URL} that
     * {@code infra/.env} will hand to Spring and aborts (throws) before the
     * {@code ApplicationContext} — and with it Flyway's auto-migration — is ever
     * created, if it doesn't point at the expected local chatbot database.
     * [Side effects] Reads {@code infra/.env} from disk directly instead of going
     * through Spring's config layer, because no {@code ApplicationContext}/
     * {@code Environment} exists yet at the point this must run — the project rule
     * "환경변수는 설정 계층 경유" assumes a config layer that cannot exist before
     * refresh; this is that necessary exception, scoped to a safety check only, and it
     * mirrors {@code application.yaml}'s own {@code spring.config.import} path list so
     * it reads the exact same value Spring would.
     */
    public static void assertConfiguredForLocalChatbotDatabase() {
        String dbUrl = resolveConfiguredDbUrl();
        if (dbUrl == null || dbUrl.isBlank()) {
            throw new IllegalStateException(
                    "DB_URL is not configured (checked infra/.env at " + DOT_ENV_CANDIDATES
                            + ") -- refusing to let the ApplicationContext (and Flyway) start");
        }

        URI connectionUri = URI.create(dbUrl.substring("jdbc:".length()));
        boolean matchesExpected = EXPECTED_HOST.equals(connectionUri.getHost())
                && connectionUri.getPort() == EXPECTED_PORT
                && ("/" + EXPECTED_DATABASE).equals(connectionUri.getPath());
        if (!matchesExpected) {
            throw new IllegalStateException(
                    "DB_URL (" + redactQuery(dbUrl) + ") does not point at the expected local chatbot database ("
                            + EXPECTED_HOST + ":" + EXPECTED_PORT + "/" + EXPECTED_DATABASE
                            + ") -- refusing to let the ApplicationContext (and Flyway) start");
        }
    }

    /**
     * [Purpose] Strips a JDBC URL's query string before it is echoed back in an exception
     * message (review 2026-08-25_chat-T2.md security Low-3).
     * [Rationale] The host/port/database path is what this guard actually checks and is safe to
     * surface for debugging; a query string (e.g. driver options someone appended locally) is
     * not part of that check and has no reason to leak into a thrown message.
     */
    private static String redactQuery(String jdbcUrl) {
        int queryIndex = jdbcUrl.indexOf('?');
        return queryIndex < 0 ? jdbcUrl : jdbcUrl.substring(0, queryIndex) + "?<redacted>";
    }

    private static String resolveConfiguredDbUrl() {
        for (String candidate : DOT_ENV_CANDIDATES) {
            Path path = Path.of(candidate);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("DB_URL=")) {
                        return trimmed.substring("DB_URL=".length()).trim();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + path + " while guarding test DB target", e);
            }
        }
        return null;
    }
}
