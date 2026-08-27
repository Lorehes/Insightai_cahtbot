package art.insightai.chatbot.common.session;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * [Purpose] Reads the anonymous user id that {@link AnonymousSessionFilter} resolved for the
 *   current request (chat Spec Tech Review A-9's "컨텍스트 전달" row).
 * [Rationale] The filter deliberately avoids opening a transaction itself and just stashes the
 *   id {@link AnonymousUserService} resolved as a request attribute; this class is the single
 *   read side of that attribute so callers do not duplicate the attribute key or the cast.
 *   {@link RequestContextHolder} is thread-bound, which is exactly why Tech Review A-8 requires
 *   this to be called synchronously from a controller method body, before any {@code Flux} is
 *   built — a reactive operator (e.g. {@code .map()}) may run on a different thread once the
 *   controller method has already returned, where no request is bound.
 * [Caution] {@code currentUserId()} throws {@link IllegalStateException} when no request is
 *   bound, or when the attribute is absent (e.g. the current path is not mapped under
 *   {@code /api/*} and {@link AnonymousSessionFilter} never ran). That is a wiring bug, not
 *   client input, so it is intentionally not modeled as a 4xx validation error.
 */
@Component
public class SessionContext {

    /**
     * Request attribute key the filter writes and this class reads. Package-visible so only
     * {@link AnonymousSessionFilter} in this package may set it.
     */
    static final String USER_ID_ATTRIBUTE = SessionContext.class.getName() + ".userId";

    /**
     * [Purpose] Returns the resolved anonymous user id for the current request.
     * [AC] chat:AC-9, chat:AC-10
     */
    public UUID currentUserId() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new IllegalStateException("No HTTP request is bound to the current thread");
        }
        HttpServletRequest request = attributes.getRequest();
        Object value = request.getAttribute(USER_ID_ATTRIBUTE);
        if (!(value instanceof UUID userId)) {
            throw new IllegalStateException(
                    "No session user resolved for this request — is the path mapped under /api/*?");
        }
        return userId;
    }
}
