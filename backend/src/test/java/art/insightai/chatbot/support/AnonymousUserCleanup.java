package art.insightai.chatbot.support;

import jakarta.servlet.http.Cookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Tracks the {@code chatbot_sid} cookie each of a test's own HTTP responses carries and
 *   deletes the {@code users} row it names -- the {@code ChunkSeeder} shape (review round 1,
 *   maintainability Medium-2) applied to {@code AnonymousSessionFilter}'s own leak.
 * [Rationale] {@code AnonymousSessionFilter} mints a brand-new {@code users} row on every
 *   unauthenticated {@code /api/*} request that carries no {@code chatbot_sid} cookie -- every
 *   {@code rag/api} integration test drives its endpoint without ever setting that cookie, so each
 *   call leaks one row. {@code BL-027} raised {@code app.session.max-anonymous-users} to 100000
 *   (see {@code IntegrationTestBase}'s own Javadoc), which removed the 503 that used to surface
 *   this leak as a symptom -- the leak itself was already there, only the trip-wire is gone.
 *   Deletes only the ids this instance's own {@link #track} calls actually saw, the same
 *   "track exactly what this test created, delete only that" shape {@code
 *   EvalSweepIntegrationTest#deleteSweepData} uses, rather than a table-wide sweep, since {@code
 *   chatbot-db} is a real database shared by concurrently running sessions (BL-034). Centralized
 *   in {@code support/} per this package's convention ({@link DocumentRowCounter}, {@link
 *   ChunkSeeder}) instead of duplicated across six sibling classes -- review round 1,
 *   maintainability Medium-2 flagged the first version of this class (a stateless set of static
 *   methods) for making every caller declare its own tracked-id field and wrapper method, exactly
 *   the duplication {@link ChunkSeeder} already solved for {@code vector_store_bgem3} rows; this
 *   version follows that same instance shape instead.
 * [Caution] Not wired into {@code IntegrationTestBase} as a common {@code @AfterEach} hook -- 7 of
 *   that class's 28 direct subclasses already delete their own {@code users} rows directly via
 *   {@code JdbcTemplate} ({@code SessionControllerIntegrationTest}, {@code
 *   OutboundCallGuardIntegrationTest}, {@code UsageControllerIntegrationTest}, {@code
 *   UsageIntegrationTest}, {@code ConversationControllerIntegrationTest}, {@code
 *   SessionQuotaIntegrationTest}, {@code MessageRepositoryIntegrationTest} -- confirmed by
 *   grepping {@code "DELETE FROM users"} under {@code src/test/java}, review round 1 correctness
 *   Low-3/maintainability Medium-1: an earlier version of this note cited {@code
 *   IntegrationTestBase}'s own Javadoc for this count and put it at 8, which was wrong on both
 *   counts -- that Javadoc's "8" is a different fact entirely (subclasses that had NOT pinned
 *   {@code app.session.max-anonymous-users}), and one of the 8 classes actually running this
 *   delete, {@code EvalSweepIntegrationTest}, does not extend {@code IntegrationTestBase} at all,
 *   per that class's own Javadoc), and a base-class hook on top would double-delete and create an
 *   ordering dependency between the two (INC-010). Each caller must explicitly construct its own
 *   instance in {@code @BeforeEach} (this class is not thread-safe for concurrent use, mirroring
 *   {@link ChunkSeeder}'s own Javadoc) and call {@link #track} after every request, then {@link
 *   #cleanUp} from its own {@code @AfterEach}. {@link #cleanUp}'s {@code DELETE} is additionally
 *   scoped to {@code auth_provider = 'anonymous'} (review round 1, security Medium-1/correctness
 *   Low-4) -- this alone is NOT the structural guard against deleting a row this instance did not
 *   create: review round 2, security Medium-1 found {@code AnonymousUserService#resolveOrCreate}'s
 *   reuse branch only ever matches an already-{@code anonymous} row, so a reused id would still
 *   pass this filter. The real guard is {@link #track(MvcResult)}'s own request-cookie check (see
 *   that method's Javadoc) -- the {@code auth_provider} filter here only protects a future
 *   non-anonymous row (e.g. Phase 2 SSO) from ever being deletable through this class, a narrower
 *   but still real safety margin. Deletes only
 *   from {@code users} -- none of this package's callers create {@code conversations}/{@code
 *   messages} rows for the anonymous ids their {@code /api/admin/documents*} requests mint, so the
 *   FK-safe messages-then-conversations-then-users order {@code
 *   EvalSweepIntegrationTest#deleteSweepData} needs does not apply here; a caller that starts
 *   exercising a chat endpoint too would need that fuller cleanup instead of this one.
 */
public final class AnonymousUserCleanup {

    private static final String COOKIE_NAME = "chatbot_sid";

    private final JdbcTemplate jdbcTemplate;
    private final List<UUID> trackedUserIds = new ArrayList<>();

    public AnonymousUserCleanup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * [Purpose] Records the {@code chatbot_sid} id a {@code MockMvc} exchange's response carries,
     *   unless the request already carried a {@code chatbot_sid} cookie of its own.
     * [Rationale] Review round 2, security Medium-1 (round 1's fix was a no-op): {@code
     *   AnonymousUserService#resolveOrCreate}'s reuse branch only ever matches an existing row that
     *   is already {@code auth_provider = 'anonymous'}, so {@link #cleanUp}'s own {@code
     *   auth_provider} filter cannot distinguish "this call's own new row" from "a row some other
     *   caller's reused cookie pointed at" -- both are anonymous. Reading {@link
     *   MvcResult#getRequest()} lets this method skip tracking outright whenever the request itself
     *   already had a cookie, which is the only case a reused id can arise from ({@code
     *   AnonymousSessionFilter} always re-issues the cookie, reused or newly minted alike, so the
     *   response alone cannot tell the two apart). None of this package's current callers ever send
     *   a {@code chatbot_sid} request cookie, so this guard is currently a no-op in practice, but it
     *   closes the door structurally for a future caller that does, rather than by caller
     *   discipline alone.
     */
    public void track(MvcResult result) {
        if (hasChatbotSidCookie(result.getRequest())) {
            return;
        }
        Cookie cookie = result.getResponse().getCookie(COOKIE_NAME);
        trackId(cookie == null ? null : cookie.getValue());
    }

    private static boolean hasChatbotSidCookie(MockHttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * [Purpose] Same as {@link #track(MvcResult)}, for a caller that drives its request over a
     *   real socket (e.g. {@link org.springframework.web.client.RestClient}) and therefore only has
     *   the raw {@code Set-Cookie} response header values, not an {@link MvcResult}.
     * [Caution] Unlike {@link #track(MvcResult)}, this overload has no request to inspect and
     *   cannot itself detect a resent {@code chatbot_sid} cookie (review round 2, security
     *   Medium-1) -- the caller must not invoke this method for a request that sent one. This
     *   project's only caller ({@code DocumentUploadContainerSizeLimitIntegrationTest}) builds a
     *   fresh {@link org.springframework.web.client.RestClient#create()} per call with no cookie
     *   store, so it structurally never resends a cookie; a future caller that does must not use
     *   this overload without adding an equivalent guard.
     */
    public void trackFromSetCookieHeaders(List<String> setCookieHeaders) {
        trackId(parseChatbotSid(setCookieHeaders));
    }

    private void trackId(String rawValue) {
        if (rawValue != null) {
            trackedUserIds.add(UUID.fromString(rawValue));
        }
    }

    private static String parseChatbotSid(List<String> setCookieHeaders) {
        if (setCookieHeaders == null) {
            return null;
        }
        String prefix = COOKIE_NAME + "=";
        for (String header : setCookieHeaders) {
            if (header.startsWith(prefix)) {
                String value = header.substring(prefix.length());
                int semicolon = value.indexOf(';');
                return semicolon >= 0 ? value.substring(0, semicolon) : value;
            }
        }
        return null;
    }

    /**
     * [Purpose] Deletes every {@code users} row this instance has tracked and clears the tracked
     *   list -- call this from {@code @AfterEach} so one test's anonymous session rows never leak
     *   into another test/another card's session sharing the same live {@code infra} container.
     * [Side effects] Permanently removes rows from {@code users}. Uses a direct {@link
     *   JdbcTemplate} delete rather than any production repository, mirroring {@link ChunkSeeder
     *   #cleanUp}'s own reasoning.
     */
    public void cleanUp() {
        for (UUID userId : trackedUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ? AND auth_provider = 'anonymous'", userId);
        }
        trackedUserIds.clear();
    }
}
