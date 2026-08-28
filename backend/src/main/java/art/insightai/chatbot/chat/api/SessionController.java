package art.insightai.chatbot.chat.api;

import art.insightai.chatbot.common.session.SessionContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * [Purpose] Exposes {@code GET /api/session} so the frontend can bootstrap (or confirm) the
 *   anonymous session established by {@code AnonymousSessionFilter} (chat Spec Tech Review A-11).
 * [Rationale] All session resolution already happened in the filter before this method runs;
 *   the controller only reads the outcome via {@link SessionContext} (Tech Review A-8) and
 *   echoes it back. Per Tech Review A-2, the response body deliberately carries {@code users.id}
 *   verbatim — the same value already readable from the (non-Secure, non-HttpOnly-hidden-by-body)
 *   cookie — because AC-9/AC-10 require the body to expose that identifier; this is an accepted
 *   design trade-off, not an oversight. The trade-off traces to Tech Review A-2's decision and is
 *   bounded by Spec §8 R-6 (uuid-guessing risk, accepted for the stage 1 local demo only) —
 *   it is a design decision recorded in the Spec, not a security review sign-off (no review
 *   report covers A-2; see chat T4 review 2026-08-26, security Medium-2).
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionContext sessionContext;

    public SessionController(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
    }

    @GetMapping
    public SessionResponse currentSession() {
        UUID userId = sessionContext.currentUserId();
        return new SessionResponse(userId);
    }

    /** [Purpose] Response body for {@code GET /api/session} (Tech Review A-11). */
    public record SessionResponse(UUID userId) {}
}
