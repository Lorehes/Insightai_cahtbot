package art.insightai.chatbot.chat.domain;

/**
 * [Purpose] Signals that a caller-supplied model id is not one of {@code app.models[]}'s
 *   catalog entries (chat Spec §5 A-11, T7).
 * [Rationale] Thrown from {@code ConversationService#createConversation} (model supplied at
 *   conversation creation) and {@code ConversationService#prepareMessageSend} (model supplied
 *   per message) — both validate through {@code AppProperties.Models#contains} so a caller can
 *   never reach a real OpenRouter call with an id CLAUDE.md P1 never confirmed to exist.
 *   {@code GlobalExceptionHandler} maps this to 400.
 * [Caution] The message embeds the caller-supplied {@code modelId} verbatim — safe to surface
 *   to the client since it is only echoing the caller's own input, not server-side
 *   configuration, unlike {@link ConversationQuotaExceededException}'s ceiling/property-key leak
 *   this project already fixed once (see that class's [Rationale]). This reflection is only safe
 *   because the callers bound {@code modelId}'s length before it ever reaches this constructor —
 *   {@code ConversationController.CreateConversationRequest.model} and {@code
 *   SendMessageRequest.model} both carry {@code @Size(max = 64)} (T7 review round 1 Low-2). If
 *   either of those constraints is ever removed or a new caller starts constructing this
 *   exception directly with an unbounded string, re-review this reflection.
 * [AC] chat:AC-22
 */
public class InvalidModelException extends RuntimeException {

    public InvalidModelException(String modelId) {
        super("invalid model id: " + modelId);
    }
}
