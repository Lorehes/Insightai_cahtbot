package art.insightai.chatbot.usage;

import java.time.OffsetDateTime;

/**
 * [Purpose] Signals that {@code GET /api/admin/usage}'s {@code from} query parameter is later
 *   than its {@code to} query parameter (eval-usage-dashboard Spec, card U2).
 * [Rationale] Thrown from {@link UsageController#usage} — Tech Review D-8 assigns this check to
 *   the Controller layer ("검증 위치: from > to → 400은 Controller"), not {@link UsageService},
 *   so {@link UsageService#summarize} keeps accepting whatever period it is given (see that
 *   method's own [Rationale]). {@code GlobalExceptionHandler} maps this to 400.
 * [Caution] The message embeds {@code from}/{@code to} verbatim — safe to surface to the client
 *   since both are the caller's own query parameters, not server-side configuration (same
 *   reasoning as {@code chat.domain.InvalidModelException}'s [Caution], unlike {@code
 *   ConversationQuotaExceededException}'s ceiling/property-key leak this project already fixed
 *   once). Unlike {@code InvalidModelException}, whose reflected value is a caller-supplied
 *   {@code String} bounded by that controller's {@code @Size(max = 64)}, the values reflected
 *   here carry no {@code @Size} constraint at all — {@code from}/{@code to} are bound as {@link
 *   OffsetDateTime} (see {@link UsageController}'s [Rationale]), and that binding step is what
 *   caps their length: {@link OffsetDateTime#toString()} has a fixed, small shape (date + time +
 *   optional fraction + zone offset), around 25-30 characters for a normal value and no more than
 *   roughly 45 even at its worst case (a 9-digit proleptic year). This is a stronger guarantee
 *   than {@code @Size} — it holds at the type level, before any annotation is checked — so
 *   changing {@code from}/{@code to} to raw {@code String} parameters (bypassing {@link
 *   OffsetDateTime} binding) would remove this guarantee and reopen the length-based reflection
 *   risk {@code InvalidModelException}'s {@code @Size} guards against.
 * [AC] eval-usage-dashboard:AC-15
 */
public class InvalidUsagePeriodException extends RuntimeException {

    public InvalidUsagePeriodException(OffsetDateTime from, OffsetDateTime to) {
        super("from must not be after to: from=" + from + ", to=" + to);
    }
}
