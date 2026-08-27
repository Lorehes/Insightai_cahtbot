package art.insightai.chatbot.chat.domain;

import java.util.UUID;

/**
 * [Purpose] Signals that a conversation id resolves to no row visible to the current caller —
 *   the id does not exist at all, belongs to a different user, or has been soft-deleted (chat
 *   Spec §5 A-11, T6).
 * [Rationale] The three causes are deliberately not distinguished: {@code
 *   ConversationRepository.findByIdAndUserIdAndDeletedAtIsNull} already folds ownership and
 *   soft-delete into one query, so this class only ever carries the id that was looked up, never
 *   which of the three reasons applied. {@code GlobalExceptionHandler} maps this to 404, not 403
 *   for the ownership case — returning 403 would leak that the id exists at all (belongs to
 *   someone else), which a uniform 404 does not.
 * [Caution] Do not add a subtype per cause (e.g. a separate "forbidden" exception) or a
 *   differently-worded message per branch — that would reopen the exact existence leak this
 *   class is designed to close.
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(UUID conversationId) {
        super("conversation not found: " + conversationId);
    }
}
