package art.insightai.chatbot.chat.domain;

import java.util.UUID;

/**
 * [Purpose] Signals that conversation {@code conversationId} already holds {@code
 *   app.session.max-messages-per-conversation} 'user' {@link Message} rows and may not receive
 *   another (chat T9 review 2026-08-27, security Medium-3; user decision D-3①).
 * [Rationale] Thrown from {@code ConversationService#enforceMessageQuota}, which {@code
 *   ChatService#streamReply} calls before {@code recordUserMessage} and before any LLM call —
 *   mirrors {@link ConversationQuotaExceededException}'s "check, then reject before the
 *   costly/paid step" placement, one level down the existing hierarchy (users ->
 *   conversations -> messages; T4/T6 precedent). {@code GlobalExceptionHandler} maps this to 403
 *   for the same reason as {@link ConversationQuotaExceededException}: the ceiling is not
 *   time-windowed — no endpoint deletes individual {@link Message} rows, so a conversation that
 *   hits it stays rejected forever and a 429's "wait, then retry" implication would be
 *   misleading. The caller can only recover by sending to a different (or new) conversation.
 * [Caution] Unlike {@link ConversationNotFoundException}/{@link InvalidModelException}, this
 *   message deliberately omits the configured property key and its numeric ceiling (INC-007,
 *   recurred chat T4/T6: a config key/number surfacing in a client-visible string is exactly what
 *   those incidents flagged) — only {@code conversationId} is included, and {@code
 *   GlobalExceptionHandler} still never echoes even that verbatim (see its own [Caution]).
 *   Operator-relevant detail (the actual configured ceiling) belongs in the WARN log at the throw
 *   site, not in this exception's message. This is a real divergence from this class's two true
 *   quota siblings, not just from the two unrelated classes named above: {@link
 *   ConversationQuotaExceededException} and {@code user.AnonymousUserQuotaExceededException}
 *   still embed both the property key and the numeric ceiling directly in {@code getMessage()}
 *   (e.g. {@code "(app.session.max-conversations-per-user=" + n + ")"}) despite this class's own
 *   [Rationale] describing itself as mirroring {@link ConversationQuotaExceededException}'s
 *   placement (chat T9 review round 2, maintainability Low-8) — that mirroring is about *when*
 *   the check runs, not about message content. This class's omission is the more defensive of the
 *   two choices; the two siblings are left as their existing (T4/T6-owned) legacy behavior, not
 *   retroactively brought in line by this card.
 * [AC] chat:AC-45
 */
public class MessageQuotaExceededException extends RuntimeException {

    public MessageQuotaExceededException(UUID conversationId) {
        super("message quota exceeded for conversation " + conversationId);
    }
}
