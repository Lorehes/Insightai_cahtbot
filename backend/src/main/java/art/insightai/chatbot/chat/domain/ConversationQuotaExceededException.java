package art.insightai.chatbot.chat.domain;

import java.util.UUID;

/**
 * [Purpose] Signals that {@code userId} already holds {@code
 *   app.session.max-conversations-per-user} non-soft-deleted {@link Conversation} rows and may
 *   not create another (chat T6 review 2026-08-27, Medium-2).
 * [Rationale] Thrown from {@code ConversationService#createConversation} before the row is
 *   inserted, mirroring {@code user.AnonymousUserQuotaExceededException}'s "check right before
 *   save" placement. {@code GlobalExceptionHandler} maps this to 403, not 429: unlike a rate
 *   limit, this ceiling is not time-windowed — retrying the same request later fails again with
 *   the same reason, and the response carries no meaningful {@code Retry-After}. The caller can
 *   only recover by deleting an existing conversation, which is what 403 ("understood the
 *   request, refusing to authorize it") communicates; 429 implies "wait, then retry", which would
 *   be misleading here.
 * [Caution] Do not throw this for the soft-deleted-conversation case — the repository count this
 *   exception is based on already excludes {@code deleted_at IS NOT NULL} rows, so deleting a
 *   conversation (T8's scope) frees up quota.
 * [AC] chat:AC-43
 */
public class ConversationQuotaExceededException extends RuntimeException {

    public ConversationQuotaExceededException(UUID userId, int maxConversationsPerUser) {
        super("conversation quota exceeded for user " + userId
                + " (app.session.max-conversations-per-user=" + maxConversationsPerUser + ")");
    }
}
