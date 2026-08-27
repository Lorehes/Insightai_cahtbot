package art.insightai.chatbot.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * [Purpose] Spring Data JPA repository for {@link User}.
 * [Rationale] {@code findById(UUID)} inherited from {@link JpaRepository} already covers "look
 *   up the user behind a session cookie" (card T3 scope, "골격"). Simple derived-query methods
 *   that {@link AnonymousUserService} needs (counting, existence checks) are added here, same as
 *   any Spring Data repository — but the lookup-by-cookie-then-create *decision* logic
 *   (AC-9/AC-10/AC-12: reuse vs. create vs. reject) stays out of this file and lives in
 *   {@code AnonymousUserService}, which composes these queries rather than embedding that
 *   judgment in a repository method.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * [Purpose] Counts rows by {@code auth_provider}, used by {@link AnonymousUserService} to
     *   enforce {@code app.session.max-anonymous-users} (chat T4 review, High-2).
     */
    long countByAuthProvider(String authProvider);

    /**
     * [Purpose] Checks whether {@code id} exists AND belongs to {@code authProvider}, used by
     *   {@link AnonymousUserService#resolveOrCreate} to decide whether a {@code chatbot_sid}
     *   cookie value is reusable (chat T4 review round 2, Medium-2).
     * [Rationale] Plain {@code existsById(UUID)} would happily reuse a row that belongs to a
     *   future non-anonymous {@code auth_provider} (e.g. NAVER WORKS SSO, Phase 2) if the caller
     *   ever presented that row's id as a {@code chatbot_sid} value — this narrows the check to
     *   the anonymous population the cookie mechanism is actually meant to cover.
     */
    boolean existsByIdAndAuthProvider(UUID id, String authProvider);
}
