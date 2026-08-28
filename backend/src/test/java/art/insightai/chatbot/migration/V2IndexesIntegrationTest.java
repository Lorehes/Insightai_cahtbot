package art.insightai.chatbot.migration;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Regression-locks the two indexes {@code V2__add_messages_and_conversations_indexes.sql}
 * added on top of the schema {@link V1InitSchemaIntegrationTest} already locks: without this
 * class, dropping either V2 index -- or never applying V2 at all -- fails none of the tests that
 * existed before it (coordinator-verified against the live container: full regression stayed
 * 229/229 with only the V1 schema locked). {@link V1InitSchemaIntegrationTest} only asserts what
 * {@code V1__init.sql} created; nothing previously asserted these two V2 indexes exist, let alone
 * that their definitions (target columns, the partial {@code WHERE} clause) are correct.
 * [Rationale] Each index traces to a specific performance review, matching {@code
 * V2__add_messages_and_conversations_indexes.sql}'s own header citations: {@code
 * idx_messages_conversation_id_role} closes chat-T9-round2 review performance Medium-2 ({@code
 * ConversationService#enforceMessageQuota} always filters on both {@code conversation_id} and
 * {@code role} together, every message send); {@code idx_conversations_user_id_updated_at}
 * closes chat-T8-round2 review round-1 Low-6 ({@code
 * ConversationRepository#findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc}'s {@code WHERE
 * user_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC}, chat:AC-14's list endpoint). Both
 * were applied under user approval G5 (2026-08-27) -- see the migration file's own header comment
 * for the full EXPLAIN evidence that justified the approval; this class does not re-derive that
 * evidence, only locks the resulting schema shape in place.
 * <p>Separate class, not new methods on {@link V1InitSchemaIntegrationTest}, for two independent
 * reasons checked against this project's existing conventions before deciding: (1) naming -- a
 * class literally named {@code V1Init...} asserting {@code V2} migration content reads wrong, and
 * mirroring the {@code V1__.../V2__...} migration filenames with matching {@code V1.../V2...}
 * test classes keeps future per-version schema tests discoverable the same way as the migrations
 * themselves; (2) structural -- {@link V1InitSchemaIntegrationTest} intentionally disables
 * Spring's Flyway auto-migration ({@code spring.flyway.enabled=false}) and drives {@code
 * Flyway.migrate()} itself, because at the time it was written that class had to perform the
 * database's very first migration under direct control (also required for its own AC-8
 * idempotent-reapply assertion) -- none of that machinery is relevant here; this class only ever
 * reads catalog state after migration has already happened elsewhere. {@code DatabaseGuard}'s own
 * Javadoc already steers new {@code @SpringBootTest} classes toward {@link IntegrationTestBase}
 * instead of copying {@code V1InitSchemaIntegrationTest}'s hand-rolled guard/migration pattern,
 * which is what this class does instead. Extending {@link IntegrationTestBase} needs no extra
 * migration driving because {@code application.yaml} leaves {@code spring.flyway.enabled=true}/
 * {@code baseline-on-migrate=true} at their defaults (checked: neither {@code
 * application-test.yaml} nor {@code config/application.yaml} overrides either property) -- Spring
 * Boot's own {@code FlywayMigrationInitializer} auto-migrates to the latest version available on
 * the classpath (V1 then V2) during {@code ApplicationContext} refresh, before any {@code @Test}
 * method (or even {@code @Autowired} field injection into this instance) runs, exactly like every
 * other {@link IntegrationTestBase} subclass in this module.
 * [Caution] Neither {@code @DisplayName} below carries a {@code [chat:AC-N]} prefix -- these two
 * indexes are a pure performance decision the chat Spec has no AC governing (index shape/presence
 * was never made a testable claim in §4), so per project convention (chat Spec §4 header; mirrored
 * by {@code OutboundCallGuardIntegrationTest}'s untagged structural-guard tests) they must never
 * be picked up by the evidence-ledger auto-derivation. Discriminative power was verified by hand,
 * not assumed: each index was actually dropped against the live container and the corresponding
 * test method below was confirmed to fail, independently, while the other (still-present) index's
 * test method kept passing -- proof each assertion depends on its own index, not a shared
 * assertion coincidentally failing together -- then restored via the exact {@code CREATE INDEX}
 * statement from {@code V2__add_messages_and_conversations_indexes.sql} (never the rollback
 * script, which also deletes the {@code flyway_schema_history} row for version 2 and would make a
 * later {@code flyway migrate()} attempt to reapply it -- see that rollback script's own header
 * comment). See dev-qa-tester's dev-log entry for this card for the exact commands and the
 * {@code pg_indexes} output that confirmed restoration.
 * <p>{@code idx_conversations_user_id_updated_at}'s definition string alone cannot capture one
 * nuance in the migration file's comment: it claims the composite+partial shape "정렬을 위한 별도
 * Sort 단계를 없앤다" (eliminates the separate Sort step), but the backend engineer's own EXPLAIN
 * run after applying V2 (dev-log 2026-08-28T08:40:00+09:00) showed PostgreSQL still choosing a
 * Bitmap Heap Scan plus a small in-memory Sort (32-40 rows, 27kB quicksort) for that query, not a
 * sort-free plan -- the cost/buffer improvement itself (cost 1256-&gt;115, buffers 631-&gt;5) is
 * real and unaffected by this nuance; only the "없앤다" wording is stronger than what was measured.
 * The V2 SQL comment cannot be corrected post-hoc (Flyway checksums it -- outside this card's file
 * boundary), so this Javadoc is where the accurate wording lives, on the one class that directly
 * exercises this exact index. {@code ConversationRepository} carries no such overstated claim
 * today (checked directly -- its Javadoc only cites the {@code updated_at DESC} ordering itself,
 * chat:AC-14, nothing about removing a Sort step), so there is nothing there to correct yet; if a
 * future change adds a Sort-elimination claim to that repository's Javadoc, it should cite this
 * same measurement instead of the migration comment's wording.
 */
class V2IndexesIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("messages(conversation_id, role) 복합 인덱스가 존재하고 정의(컬럼 구성)가 일치한다"
            + " -- V2 회귀 잠금, 근거: chat-T9-round2 review performance Medium-2, 사용자 G5 승인(2026-08-27)")
    void messagesConversationIdRoleIndexExistsWithExpectedDefinition() {
        // when: pg_indexes.indexdef reflects PostgreSQL's own normalized, stored form of the
        // DDL (same approach V1InitSchemaIntegrationTest#vectorColumnAndSingleParadeDbIndexAreCorrect
        // uses) -- filtering by the specific index name is safe because PostgreSQL enforces
        // unique index names per schema, so at most one row can ever come back.
        List<String> indexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'messages' "
                        + "AND indexname = 'idx_messages_conversation_id_role'",
                String.class);

        // then: the index exists at all (this is the line a DROP makes fail, with a clear
        // "expected size 1 but was 0" message rather than a raw queryForObject exception)...
        assertThat(indexDefs).hasSize(1);
        // ...and its definition -- not just its name -- matches: both columns, in this order,
        // plain btree (no partial WHERE on this one).
        assertThat(indexDefs.get(0)).isEqualTo(
                "CREATE INDEX idx_messages_conversation_id_role ON public.messages "
                        + "USING btree (conversation_id, role)");
    }

    @Test
    @DisplayName("conversations(user_id, updated_at DESC) WHERE deleted_at IS NULL 부분 인덱스가 존재하고"
            + " 정의(컬럼·부분조건)가 일치한다 -- V2 회귀 잠금, 근거: chat-T8-round2 review 1R Low-6,"
            + " 사용자 G5 승인(2026-08-27)")
    void conversationsUserIdUpdatedAtPartialIndexExistsWithExpectedDefinition() {
        // when
        List<String> indexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'conversations' "
                        + "AND indexname = 'idx_conversations_user_id_updated_at'",
                String.class);

        // then: exists...
        assertThat(indexDefs).hasSize(1);
        // ...and the definition matches exactly, including the partial WHERE clause -- PostgreSQL
        // stores it with added parentheses ("WHERE (deleted_at IS NULL)"), same normalization
        // pattern as V1InitSchemaIntegrationTest's ParadeDB index check, confirmed by querying the
        // live container directly rather than assumed. Without this WHERE-clause comparison, a
        // future edit that (for example) dropped the partial condition and rebuilt this as a
        // full-table index -- silently reintroducing the dead-row growth chat:AC-16's permanent
        // soft-delete retention would otherwise cause -- would still pass a name-only check.
        assertThat(indexDefs.get(0)).isEqualTo(
                "CREATE INDEX idx_conversations_user_id_updated_at ON public.conversations "
                        + "USING btree (user_id, updated_at DESC) WHERE (deleted_at IS NULL)");
    }
}
