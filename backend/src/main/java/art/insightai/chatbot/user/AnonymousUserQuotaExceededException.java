package art.insightai.chatbot.user;

/**
 * [Purpose] Signals that a new anonymous {@link User} cannot be issued because {@code
 *   app.session.max-anonymous-users} has been reached (chat T4 review 2026-08-26, High-2).
 * [Rationale] Thrown only from the "issue a brand-new row" branch of {@link
 *   AnonymousUserService#resolveOrCreate}; the cookie-reuse branch never calls {@code save} and
 *   therefore never observes this. {@link
 *   art.insightai.chatbot.common.session.AnonymousSessionFilter} catches this exception and
 *   writes the response itself (503) rather than letting it propagate, because the filter runs
 *   ahead of {@code DispatcherServlet} — no {@code @ExceptionHandler}/{@code @ResponseStatus}
 *   machinery observes an exception thrown at that point.
 * [Caution] Do not throw this from the cookie-reuse path — existing sessions must keep working
 *   after the ceiling is hit; only new-issue requests are rejected.
 * [AC] chat:AC-42
 */
public class AnonymousUserQuotaExceededException extends RuntimeException {

    public AnonymousUserQuotaExceededException(long maxAnonymousUsers) {
        super("anonymous user quota exceeded (app.session.max-anonymous-users=" + maxAnonymousUsers + ")");
    }
}
