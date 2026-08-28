package art.insightai.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Smoke test that the full {@code ApplicationContext} boots, plus two schema-safety
 * assertions (chat:AC-6, AC-7) that only a full-context boot -- not the migration-only suite --
 * can exercise.
 * [Rationale] Extends {@link IntegrationTestBase}, so the pre-refresh database guard (Card T1
 * review 2026-08-25 Medium-2 -- the guard must run via {@code @BeforeAllCallback}, before
 * {@code FlywayMigrationInitializer} auto-migrates during context refresh, since a
 * {@code @BeforeEach}-based guard runs too late) and the stub {@code ChatModel} (chat:AC-35 --
 * this class exercises the real production wiring otherwise, including
 * {@code ChatService}, so without the stub it would need a real OpenRouter key and would
 * make a real call the moment anything invoked {@code ChatClient}) both apply automatically.
 * Card T2 replaced this class's previous hand-rolled static {@code @BeforeAll} guard call with
 * that inherited behavior -- see {@link IntegrationTestBase} and
 * {@code art.insightai.chatbot.support.DatabaseGuardExtension} for how the ordering guarantee
 * that made the old hand-rolled version correct is preserved.
 * Card T3 added {@link #contextLoadsWithDdlAutoValidate()}'s {@code @DisplayName} (AC-7 needs
 * nothing beyond "the context boots" -- {@code spring.jpa.hibernate.ddl-auto=validate} is the
 * unconditional {@code application.yaml} value, no test override exists) and
 * {@link #vectorStoreSchemaUnchangedAfterBootWithInitializeSchemaDisabled()} (AC-6: this suite's
 * full boot also runs {@code PgVectorStoreAutoConfiguration} -- unlike
 * {@code V1InitSchemaIntegrationTest}, which never boots the full context -- so it is the only
 * place that can prove {@code initialize-schema=false} actually kept that auto-configuration
 * from touching the table Flyway already built).
 * [Caution] This class intentionally leaves {@code spring.flyway.enabled} at its default
 * ({@code true}, inherited from {@code application.yaml}) -- unlike
 * {@code V1InitSchemaIntegrationTest}, which disables it to drive the first {@code migrate()}
 * call itself.
 */
class BackendApplicationTests extends IntegrationTestBase {

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("[chat:AC-7] ddl-auto=validate 상태에서 마이그레이션된 DB에 대해 애플리케이션 컨텍스트가 기동한다")
    void contextLoadsWithDdlAutoValidate() {
    }

    @Test
    @DisplayName("[chat:AC-6] initialize-schema=false 로 컨텍스트가 기동해도 vector_store_bgem3 스키마가 AC-5 상태 그대로다")
    void vectorStoreSchemaUnchangedAfterBootWithInitializeSchemaDisabled() {
        // given: application.yaml pins initialize-schema=false so PgVectorStoreAutoConfiguration
        // (which *is* active in this full-context boot, unlike the migration-only suite) never
        // DDLs the table itself.
        assertThat(environment.getProperty(
                "spring.ai.vectorstore.pgvector.initialize-schema", Boolean.class))
                .isFalse();

        // when: read the schema this already-booted context left behind -- same queries
        // V1InitSchemaIntegrationTest's AC-5 test uses, so a mismatch here means the full app
        // boot altered what Flyway built, not that the two suites disagree on how to query it.
        String fullType = jdbc.queryForObject(
                "SELECT format_type(a.atttypid, a.atttypmod) "
                        + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "WHERE c.relname = 'vector_store_bgem3' AND a.attname = 'embedding'",
                String.class);
        List<String> paradeIndexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'vector_store_bgem3' "
                        + "AND indexdef ILIKE '%USING paradedb%'",
                String.class);

        // then: identical to AC-5's assertion -- the full app boot left it untouched.
        assertThat(fullType).isEqualTo("vector(1024)");
        assertThat(paradeIndexDefs).hasSize(1);
        assertThat(paradeIndexDefs.get(0)).isEqualTo(
                "CREATE INDEX idx_vector_store_bgem3_bm25 ON public.vector_store_bgem3 "
                        + "USING paradedb (id, ((content)::pdb.lindera('korean'))) WITH (key_field=id)");
    }

}
