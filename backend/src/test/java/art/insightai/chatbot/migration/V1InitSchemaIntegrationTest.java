package art.insightai.chatbot.migration;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.support.DatabaseGuard;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * [Purpose] Verifies the schema produced by {@code V1__init.sql} directly against the
 * live ParadeDB instance — table/column existence, monetary/temporal column types,
 * foreign-key actions, the vector dimension + single ParadeDB BM25 index, and
 * re-apply idempotency.
 * [Rationale] chat Spec §6 classifies AC-1/2/3/4/5/8 as Integration-only: H2 cannot
 * host the {@code vector}/{@code pg_search} extensions the BM25 index (AC-5) depends
 * on, so this suite talks to the real {@code infra} ParadeDB container (chat Spec §7
 * OQ-9) via plain {@link JdbcTemplate} — no JPA entities exist yet (T3 introduces
 * them). Card T1 covers schema DDL only.
 * [Caution] {@code spring.flyway.enabled=false} (class-level {@link TestPropertySource})
 * disables Spring Boot's {@code FlywayAutoConfiguration} entirely for this test class —
 * that also removes the auto-configured {@code Flyway} bean, not just the
 * auto-migration step, because both are gated by the same
 * {@code @ConditionalOnBooleanProperty(name = "spring.flyway.enabled")} on the outer
 * configuration class (verified against {@code spring-boot-flyway-4.1.1} sources, not
 * guessed — project prohibition P3). Disabling it is required: with the default
 * {@code true}, Spring's {@code FlywayMigrationInitializer} auto-migrates during
 * {@code ApplicationContext} refresh, before {@link #guardAgainstUnexpectedDatabaseThenMigrate()}
 * (or even {@code @Autowired} injection into this test instance) ever runs, so the
 * guard could never run first (review 2026-08-25 M-1). {@link #guardAgainstUnexpectedDatabaseThenMigrate()}
 * therefore builds its own {@link Flyway} instance and is the single place that ever
 * calls {@code migrate()} for the first time; do not add JPA writes here — this suite
 * is otherwise read-only introspection plus one intentional re-migrate call (AC-8).
 */
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class V1InitSchemaIntegrationTest {

    // Ensures the real (first) migrate() call happens exactly once across the whole
    // test run, no matter which @Test method JUnit happens to execute first — Spring
    // caches the ApplicationContext (and this JdbcTemplate/DataSource) across test
    // methods in this class, so re-running it per @BeforeEach would silently turn
    // every other test's setup into AC-8's "reapply" scenario too.
    private static final AtomicBoolean MIGRATION_APPLIED = new AtomicBoolean(false);

    @Autowired
    private JdbcTemplate jdbc;

    private Flyway flyway;

    @BeforeEach
    void guardAgainstUnexpectedDatabaseThenMigrate() throws SQLException {
        // given/when/then: read what the active connection is actually pointed at and
        // abort before Flyway (or anything else) is allowed to touch the schema, if
        // it isn't the expected local chatbot database (review 2026-08-25 M-1, shared
        // with BackendApplicationTests via DatabaseGuard per review 2026-08-25
        // Medium-2 — see that class's Javadoc for why this class uses the
        // live-connection variant instead of the pre-refresh one).
        DatabaseGuard.verifyLocalChatbotDatabase(jdbc);

        // then: only once the guard above has passed is Flyway allowed near the
        // schema. Built manually (see class Javadoc [Caution]) since
        // spring.flyway.enabled=false removes the auto-configured bean; mirrors the
        // production spring.flyway.baseline-on-migrate=true (application.yaml).
        flyway = Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .baselineOnMigrate(true)
                .load();
        if (MIGRATION_APPLIED.compareAndSet(false, true)) {
            MigrateResult result = flyway.migrate();
            assertThat(result.success).as("initial V1 migration").isTrue();
        }
    }

    @Test
    @DisplayName("[chat:AC-1] 5개 테이블이 존재하고 각 컬럼 집합이 기획서 §5 목록을 빠짐없이 포함한다")
    void allFiveTablesExistWithRequiredColumns() {
        // given: minimum per-table column set required by the plan §5 (:183-204).
        // (superset allowed — this project pins both gate-2 decision columns as
        // required, not optional: conversations.deleted_at (D-7) and
        // messages.finish_reason (D-8) are additions beyond the original plan §5,
        // so we only check "includes all", not exact equality.)
        Map<String, Set<String>> expectedColumns = Map.of(
                "users", Set.of("id", "auth_provider", "external_id", "display_name", "created_at"),
                "conversations", Set.of("id", "user_id", "title", "model", "rag_enabled",
                        "created_at", "updated_at", "deleted_at"),
                "messages", Set.of("id", "conversation_id", "role", "content", "model",
                        "prompt_tokens", "completion_tokens", "cost_usd", "sources", "created_at",
                        "finish_reason"),
                "documents", Set.of("id", "filename", "title", "mime_type", "size_bytes",
                        "status", "error_message", "chunk_count", "created_at", "indexed_at"),
                "vector_store_bgem3", Set.of("id", "content", "metadata", "embedding")
        );

        // when: read the actual column set from information_schema.
        // then: every table includes all expected columns.
        for (Map.Entry<String, Set<String>> entry : expectedColumns.entrySet()) {
            List<String> actualColumns = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ?",
                    String.class, entry.getKey());

            assertThat(actualColumns)
                    .as("table %s columns", entry.getKey())
                    .isNotEmpty()
                    .containsAll(entry.getValue());
        }
    }

    @Test
    @DisplayName("[chat:AC-2] messages.cost_usd 의 타입이 numeric 이고 real·double precision·float 가 아니다")
    void costUsdColumnIsNumeric() {
        // when
        String dataType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'messages' AND column_name = 'cost_usd'",
                String.class);

        // then
        assertThat(dataType).isEqualToIgnoringCase("numeric");
        assertThat(dataType.toLowerCase())
                .doesNotContain("real")
                .doesNotContain("double precision")
                .doesNotContain("float");
    }

    @Test
    @DisplayName("[chat:AC-3] users·conversations·messages 의 모든 시각 컬럼이 timestamp with time zone 이다")
    void allTimestampColumnsAreTimestampWithTimeZone() {
        // given: the 5 timestamp columns present across the 3 target tables
        // (users.created_at, conversations.created_at/updated_at/deleted_at, messages.created_at)

        // when
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_name, column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name IN ('users', 'conversations', 'messages') "
                        + "AND column_name IN ('created_at', 'updated_at', 'deleted_at')");

        // then: first confirm the query actually caught all 5 columns, to avoid a vacuous pass.
        assertThat(rows).hasSize(5);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("data_type"))
                    .as("%s.%s", row.get("table_name"), row.get("column_name"))
                    .isEqualTo("timestamp with time zone");
        }
    }

    @Test
    @DisplayName("[chat:AC-4] messages.conversation_id 는 ON DELETE CASCADE, conversations.user_id 는 users.id 를 참조한다")
    void foreignKeysAreCorrectlyDefined() {
        // when: messages.conversation_id FK
        List<Map<String, Object>> messagesFk = jdbc.queryForList(
                fkQuery(), "messages", "conversation_id");

        // then
        assertThat(messagesFk).hasSize(1);
        assertThat(messagesFk.get(0).get("foreign_table")).isEqualTo("conversations");
        assertThat(messagesFk.get(0).get("foreign_column")).isEqualTo("id");
        assertThat(messagesFk.get(0).get("delete_rule")).isEqualTo("CASCADE");

        // when: conversations.user_id FK
        List<Map<String, Object>> conversationsFk = jdbc.queryForList(
                fkQuery(), "conversations", "user_id");

        // then
        assertThat(conversationsFk).hasSize(1);
        assertThat(conversationsFk.get(0).get("foreign_table")).isEqualTo("users");
        assertThat(conversationsFk.get(0).get("foreign_column")).isEqualTo("id");
    }

    private static String fkQuery() {
        return "SELECT ccu.table_name AS foreign_table, ccu.column_name AS foreign_column, rc.delete_rule "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name "
                + "JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name "
                + "JOIN information_schema.referential_constraints rc ON tc.constraint_name = rc.constraint_name "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ? AND kcu.column_name = ?";
    }

    @Test
    @DisplayName("[chat:AC-5] vector_store_bgem3.embedding 이 vector(1024) 이고 ParadeDB 인덱스가 정확히 1개, 정의가 일치한다")
    void vectorColumnAndSingleParadeDbIndexAreCorrect() {
        // when: column type — information_schema.columns only reports vector as
        // "USER-DEFINED" (pgvector is a custom type), so use
        // format_type(atttypid, atttypmod) to also verify the dimension.
        String fullType = jdbc.queryForObject(
                "SELECT format_type(a.atttypid, a.atttypmod) "
                        + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "WHERE c.relname = 'vector_store_bgem3' AND a.attname = 'embedding'",
                String.class);
        assertThat(fullType).isEqualTo("vector(1024)");

        // when: ParadeDB (BM25) index — use pg_indexes.indexdef to verify count and definition.
        List<String> paradeIndexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'vector_store_bgem3' "
                        + "AND indexdef ILIKE '%USING paradedb%'",
                String.class);

        // then: exactly one.
        assertThat(paradeIndexDefs).hasSize(1);

        // then: definition matches. PostgreSQL normalizes DDL on storage — it adds
        // parentheses around the column cast, re-quotes 'korean' as a string literal,
        // and strips the quotes from the key_field value. So we compare against the
        // actual stored value after this normalization (verified by querying the live
        // container directly, not a web example — project prohibition P3), not the
        // original DDL string.
        assertThat(paradeIndexDefs.get(0)).isEqualTo(
                "CREATE INDEX idx_vector_store_bgem3_bm25 ON public.vector_store_bgem3 "
                        + "USING paradedb (id, ((content)::pdb.lindera('korean'))) WITH (key_field=id)");
    }

    @Test
    @DisplayName("[chat:AC-8] 같은 DB에 마이그레이션을 2회 연속 적용해도 실패하지 않고 스키마가 동일하다")
    void reapplyingMigrationIsIdempotentAndSchemaUnchanged() {
        // given: the first real round has already been applied by
        // guardAgainstUnexpectedDatabaseThenMigrate() (@BeforeEach, exactly once per
        // test run — see MIGRATION_APPLIED). The call below is the second round of
        // "applied twice in a row", regardless of whether this test happened to run
        // first or not.
        //
        // then: V1 must have been genuinely migrated, not silently skipped via
        // baseline-on-migrate (review 2026-08-25 High-2) — otherwise a below-zero
        // migrationsExecuted count here would be a false positive: the schema could
        // have come from anywhere, not from this migration.
        Integer actualV1MigrationCount = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE version = '1' AND success = true AND type <> 'BASELINE'",
                Integer.class);
        assertThat(actualV1MigrationCount)
                .as("V1 must appear exactly once as a real (non-BASELINE) successful migration")
                .isEqualTo(1);

        List<String> schemaBefore = schemaSnapshot();

        // when
        MigrateResult result = flyway.migrate();

        // then: completes without failure, and since it's already at the latest
        // version, zero migrations are executed.
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isZero();

        // then: the schema (tables/columns/types) is unchanged before and after reapply.
        List<String> schemaAfter = schemaSnapshot();
        assertThat(schemaAfter).isEqualTo(schemaBefore);
    }

    private List<String> schemaSnapshot() {
        return jdbc.queryForList(
                "SELECT table_name || '.' || column_name || ':' || data_type AS col "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name IN ('users', 'conversations', 'messages', 'documents', 'vector_store_bgem3') "
                        + "ORDER BY table_name, ordinal_position",
                String.class);
    }
}
