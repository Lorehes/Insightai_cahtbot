package art.insightai.chatbot.user;

import art.insightai.chatbot.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * [Purpose] Resolves the anonymous {@link User} behind a {@code chatbot_sid} cookie value,
 *   creating a new row when none exists yet (chat Spec §3.1-3, Tech Review A-9).
 * [Rationale] All database access for session resolution is concentrated in this single
 *   {@code @Transactional} method so that {@code AnonymousSessionFilter} — which must stay
 *   transaction-free per {@code spring.jpa.open-in-view: false} (Tech Review A-9) — never talks
 *   to {@link UserRepository} directly. Folding "look up" and "create if missing" into one call
 *   also avoids a lookup/insert race across two separate transactions for the same request.
 *   {@code app.session.max-anonymous-users} (T4 review, High-2) is checked only on the
 *   "issue a brand-new row" branch, right before {@code save} — cookie-reuse requests never
 *   count against it.
 * [Caution] {@code resolveOrCreate} treats a syntactically valid UUID that is simply absent from
 *   the {@code users} table the same as a malformed value: both fall through to creating a new
 *   row (AC-12). Do not special-case "valid but missing" into an error — Tech Review A-9 requires
 *   200 + a fresh {@code Set-Cookie}, never a 4xx/5xx, for this path. Once the anonymous-user
 *   ceiling is reached, that same branch instead throws {@link
 *   AnonymousUserQuotaExceededException}; {@code AnonymousSessionFilter} — not this class —
 *   decides how that becomes an HTTP response. That rejection-branch WARN log has no rate
 *   limiting and no upper bound on volume; if a file log sink ({@code logging.file.*}) is ever
 *   introduced, re-evaluate the log volume produced while the ceiling stays reached.
 */
@Service
public class AnonymousUserService {

    private static final Logger log = LoggerFactory.getLogger(AnonymousUserService.class);

    private static final String ANONYMOUS_AUTH_PROVIDER = "anonymous";

    private final UserRepository userRepository;
    private final AppProperties appProperties;

    public AnonymousUserService(UserRepository userRepository, AppProperties appProperties) {
        this.userRepository = userRepository;
        this.appProperties = appProperties;
    }

    /**
     * [Purpose] Returns the id of the user behind {@code cookieValue}, creating a new anonymous
     *   user when the value is missing, not a well-formed UUID, or not present in the database.
     * [AC] chat:AC-9, chat:AC-10, chat:AC-12, chat:AC-42
     */
    @Transactional
    public UUID resolveOrCreate(String cookieValue) {
        UUID candidate = parseUuidOrNull(cookieValue);
        if (candidate != null && userRepository.existsByIdAndAuthProvider(candidate, ANONYMOUS_AUTH_PROVIDER)) {
            return candidate;
        }
        int maxAnonymousUsers = appProperties.session().maxAnonymousUsers();
        if (userRepository.countByAuthProvider(ANONYMOUS_AUTH_PROVIDER) >= maxAnonymousUsers) {
            // T4 review round 2, Medium-1: there is no recovery mechanism (TTL cleanup / LRU
            // eviction) yet, so this WARN is the only operator-visible signal that new anonymous
            // sessions are being rejected until the ceiling is revisited.
            log.warn("익명 사용자 상한 도달, 신규 발급 거부 (app.session.max-anonymous-users={})", maxAnonymousUsers);
            throw new AnonymousUserQuotaExceededException(maxAnonymousUsers);
        }
        User user = new User(ANONYMOUS_AUTH_PROVIDER, null, null);
        return userRepository.save(user).getId();
    }

    /**
     * [Purpose] Parses {@code value} as a {@link UUID}, returning {@code null} instead of
     *   throwing when it is missing, blank, or not well-formed (AC-12's "never a validation
     *   error" contract).
     */
    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
