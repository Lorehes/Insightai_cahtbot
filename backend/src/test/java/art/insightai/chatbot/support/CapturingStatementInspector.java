package art.insightai.chatbot.support;

import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * [Purpose] Records every SQL statement Hibernate hands to the JDBC driver, in issue order, so a
 * test can assert on the exact shape of a generated statement (e.g. an {@code UPDATE}'s {@code
 * SET} clause) instead of trusting a one-off manual read of the {@code org.hibernate.SQL} debug
 * log.
 * [Rationale] Written for {@code ConversationControllerIntegrationTest}'s {@code @DynamicUpdate}
 * regression lock (review 2026-08-27_chat-T8-round2.md correctness Low-1) -- {@code
 * Conversation}'s {@code @DynamicUpdate} (T8 review round 1 High-1) had until now been verified
 * only by a single manual run with {@code -Dlogging.level.org.hibernate.SQL=DEBUG}; removing the
 * annotation left the full 198/198 suite green. {@link StatementInspector#inspect(String)} is
 * Hibernate's own hook for observing the final SQL text right before execution, so it reproduces
 * that manual observation mechanically instead of parsing log output, and works independent of
 * any logging level. Returns each {@code sql} argument unmodified -- this class only observes, it
 * never rewrites a statement.
 * [Caution] One instance is shared by every test method in a given cached Spring context
 * (singleton scope, same reasoning as {@link StubChatModel}'s [Caution]) -- callers must call
 * {@link #reset()} right before the section under test, or an assertion may see statements a
 * previous test method already left behind. {@link #capturedStatements()} returns a live,
 * growing list backed by {@link CopyOnWriteArrayList} (concurrent-safe, though nothing in this
 * suite currently issues statements from more than one thread at a time).
 */
public class CapturingStatementInspector implements StatementInspector {

    @Serial
    private static final long serialVersionUID = 1L;

    private final List<String> capturedStatements = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        capturedStatements.add(sql);
        return sql;
    }

    /**
     * [Purpose] Returns every SQL statement captured since the last {@link #reset()}, in the
     * order Hibernate issued them.
     */
    public List<String> capturedStatements() {
        return Collections.unmodifiableList(capturedStatements);
    }

    /**
     * [Purpose] Clears captured statements. Call before the section under test -- see class-level
     * [Caution].
     */
    public void reset() {
        capturedStatements.clear();
    }
}
