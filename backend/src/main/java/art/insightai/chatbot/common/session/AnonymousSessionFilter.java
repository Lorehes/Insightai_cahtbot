package art.insightai.chatbot.common.session;

import art.insightai.chatbot.user.AnonymousUserQuotaExceededException;
import art.insightai.chatbot.user.AnonymousUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * [Purpose] Resolves the anonymous session behind every {@code /api/*} request (registered by
 *   {@link SessionFilterConfig}, per Tech Review A-9) and guarantees a valid {@code chatbot_sid}
 *   cookie is present on the response.
 * [Rationale] The filter itself opens no transaction — {@code spring.jpa.open-in-view: false}
 *   means one is not available on this thread anyway — and delegates all persistence to
 *   {@link AnonymousUserService#resolveOrCreate}, a single {@code @Transactional} call (A-9).
 *   The cookie is re-issued on every response rather than only when it changes: this keeps the
 *   logic branch-free (one code path for "reuse" and "issue new") and gives the session a
 *   sliding 90-day expiry, at the cost of one small header on every response — no AC forbids
 *   this. A malformed or unknown cookie value is never treated as an error (AC-12): it is passed
 *   straight into {@code resolveOrCreate}, which falls back to creating a new user, so this
 *   filter never needs to distinguish "missing" from "invalid" itself.
 * [Caution] This filter must run before any controller reads {@link SessionContext}. It writes
 *   the resolved id under {@link SessionContext#USER_ID_ATTRIBUTE} as a request attribute, not a
 *   thread-local — controllers must read it synchronously in their own method body (Tech Review
 *   A-8), not from inside a reactive operator that may run on a different thread. When {@link
 *   AnonymousUserService#resolveOrCreate} throws {@link AnonymousUserQuotaExceededException}
 *   (T4 review, High-2), this filter writes a 503 directly via {@link
 *   HttpServletResponse#sendError} and never calls {@code filterChain.doFilter} — no controller
 *   or {@code @ExceptionHandler} sees that request, since this filter runs ahead of {@code
 *   DispatcherServlet}. That 503 body uses a generic message rather than {@code
 *   AnonymousUserQuotaExceededException#getMessage()} (T4 review round 2, Low) — the exception
 *   message embeds the configured property key and its numeric ceiling, which has no business
 *   being visible to a client; the WARN log in {@code AnonymousUserService} still carries that
 *   detail for operators. Every response also carries {@code Cache-Control: no-store} (T4 review
 *   round 2, Low, carried over twice) so neither the browser nor an intermediary caches a
 *   session-bearing response. {@code GET /api/models} (chat Spec §5 A-11) is a static,
 *   session-free catalog with no {@link SessionContext} reader, so a cookie-less request to that
 *   path skips {@code resolveOrCreate} entirely and falls straight through the chain (T5 review
 *   round 1, Medium-1) — without this, every cookie-less hit (including a drive-by no-cors GET
 *   from an unrelated page) minted a new {@code users} row and could exhaust {@code
 *   app.session.max-anonymous-users} on its own, 503-ing unrelated new chat sessions. A request to
 *   that same path that already carries a {@code chatbot_sid} cookie still falls through to the
 *   normal path below, so its sliding 90-day expiry (AC-11) keeps refreshing.
 */
@Component
public class AnonymousSessionFilter extends OncePerRequestFilter {

    static final String COOKIE_NAME = "chatbot_sid";

    /**
     * {@code GET /api/models} never needs a session (chat Spec §5 A-11) — see this class's
     * {@code [Caution]} for why a cookie-less request to this exact path bypasses
     * {@code resolveOrCreate} (T5 review round 1, Medium-1).
     */
    static final String SESSION_EXEMPT_PATH = "/api/models";

    /** 90 days in seconds (chat Spec AC-11). */
    private static final int MAX_AGE_SECONDS = 7_776_000;

    /**
     * Generic 503 body (T4 review round 2, Low): the real ceiling and property key stay in the
     * WARN log ({@link AnonymousUserService#resolveOrCreate}), not in a client-visible message.
     */
    private static final String QUOTA_EXCEEDED_CLIENT_MESSAGE = "server busy, please try again later";

    private final AnonymousUserService anonymousUserService;

    public AnonymousSessionFilter(AnonymousUserService anonymousUserService) {
        this.anonymousUserService = anonymousUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // T4 review round 2, Low: neither the browser nor an intermediary should cache a
        // session-bearing response, success or 503 alike.
        response.addHeader("Cache-Control", "no-store");
        String cookieValue = readCookie(request);
        if (cookieValue == null && SESSION_EXEMPT_PATH.equals(request.getRequestURI())) {
            // T5 review round 1, Medium-1: no cookie and no session need on this path -- skip
            // AnonymousUserService so this endpoint never counts against
            // app.session.max-anonymous-users. A caller that already has a cookie still takes
            // the normal path below (see this class's [Caution]).
            filterChain.doFilter(request, response);
            return;
        }
        UUID userId;
        try {
            userId = anonymousUserService.resolveOrCreate(cookieValue);
        } catch (AnonymousUserQuotaExceededException e) {
            // T4 review High-2: reject only new-issue requests once the ceiling is hit; the
            // response is written here (503), not delegated further, since a raw servlet
            // Filter runs ahead of DispatcherServlet and no @ExceptionHandler observes this.
            // The message stays generic (T4 review round 2, Low) -- e.getMessage() carries the
            // configured property key and numeric ceiling, which must not reach the client.
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, QUOTA_EXCEEDED_CLIENT_MESSAGE);
            return;
        }
        response.addHeader("Set-Cookie", buildSetCookieHeader(userId));
        request.setAttribute(SessionContext.USER_ID_ATTRIBUTE, userId);
        filterChain.doFilter(request, response);
    }

    /** [Purpose] Reads the {@code chatbot_sid} cookie value from the request, or {@code null}
     *   when no cookie header or no matching cookie is present. */
    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * [Purpose] Builds the {@code Set-Cookie} header value with the exact attributes Tech
     *   Review A-9 requires: {@code HttpOnly}, {@code Path=/}, {@code SameSite=Lax}, no
     *   {@code Secure}, {@code Max-Age=7776000} (AC-11). Built as a plain string rather than via
     *   {@link jakarta.servlet.http.Cookie} because the servlet {@code Cookie} API has no
     *   portable way to set {@code SameSite} across servlet container versions.
     * [AC] chat:AC-11
     */
    static String buildSetCookieHeader(UUID userId) {
        return COOKIE_NAME + "=" + userId
                + "; Path=/; Max-Age=" + MAX_AGE_SECONDS
                + "; HttpOnly; SameSite=Lax";
    }
}
