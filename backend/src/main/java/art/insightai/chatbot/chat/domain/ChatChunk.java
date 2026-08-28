package art.insightai.chatbot.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * [Purpose] One SSE chunk of {@code POST /api/conversations/{id}/messages}'s streamed response
 *   (chat Spec §5 A-6, A-11) — the BE/FE wire contract for {@code text/event-stream}.
 * [Rationale] Moved out of {@code ChatController}'s D1 nested record (chat Spec D-3) to gain
 *   {@code conversationId}/{@code finishReason}/{@code usage}, none of which the D1 shape could
 *   carry. T7 only defined the shape and returned an empty {@code Flux<ChatChunk>} from the new
 *   endpoint (chat:AC-18/22 forbade any LLM call at that stage, so nothing was ever emitted yet).
 *   T9 is the first card to actually emit populated chunks — see {@link
 *   art.insightai.chatbot.chat.service.ChatService#streamReply} for how {@code delta}/{@code
 *   finishReason} are filled in per streamed response; {@code usage} stays {@code null} on every
 *   chunk this card emits (token/cost accounting is T11's scope, not T9's).
 * [Caution] {@code usage}'s token fields must stay {@code snake_case}
 *   ({@code @JsonProperty}) — AC-32/33 fix the wire field names as
 *   {@code usage.prompt_tokens}/{@code usage.cost}. {@code cost} is {@link BigDecimal} per the
 *   monetary-type rule (global CLAUDE.md §7.2-4), never a floating-point type; pair it with
 *   {@code spring.jackson.generator.write-bigdecimal-as-plain: true} (A-6) once a card actually
 *   populates it — not added to application.yaml yet since no card before T11 ever sets a
 *   non-null {@code cost}. {@link #error}'s message becomes {@code delta} on the wire and is
 *   shown to the end user; as of chat T9 rework (2026-08-27, security L9) that text is a fixed
 *   {@link #STREAM_ERROR_MESSAGE} constant defined on this record, not a caller-supplied
 *   argument — {@link #error} takes only {@code conversationId}, so there is no longer a
 *   parameter a caller could accidentally fill with a caught exception's {@code getMessage()}
 *   (INC-007, recurred chat T4/T6: OpenRouter/SDK exception messages can embed internal
 *   configuration, model ids, or upstream response bodies). The real exception is still logged
 *   server-side by the caller ({@code ChatService#streamReply}'s error branch), never by this
 *   record.
 */
// TODO(chat): Add spring.jackson.generator.write-bigdecimal-as-plain=true to application.yaml before T11 populates Usage.cost (Tech Review A-6, AC-33 precondition risk)
public record ChatChunk(
        String conversationId,
        String delta,
        String finishReason,
        Usage usage
) {

    /**
     * [Purpose] {@code finishReason} value for a chunk emitted after the LLM stream fails
     *   mid-response (Tech Review A-6's value set, A-7).
     */
    public static final String FINISH_REASON_ERROR = "error";

    /**
     * [Purpose] {@code finishReason}/{@code messages.finish_reason} value for a turn whose
     *   stream was cancelled (client disconnect/abort) before it reached a normal completion or
     *   an upstream error (chat Spec §7 OQ-6, Tech Review A-6's value set; T9 rework 2026-08-27,
     *   correctness High-1). Unlike {@link #FINISH_REASON_ERROR}, no chunk carrying this value is
     *   ever sent to the client — by definition, nothing is listening anymore once a cancel
     *   signal fires. Only the persisted {@code messages} row uses it.
     */
    public static final String FINISH_REASON_CANCELLED = "cancelled";

    /**
     * [Purpose] Fixed, safe, client-facing text for the single chunk {@link #error} builds.
     * [Caution] Never replace this with a caught exception's {@code getMessage()} (INC-007,
     *   recurred chat T4/T6) — see this record's class-level [Caution].
     */
    private static final String STREAM_ERROR_MESSAGE = "an error occurred while generating the response";

    /**
     * [Purpose] Builds the single chunk emitted when the LLM stream fails mid-response, per Tech
     *   Review A-7's {@code onErrorResume(e -> Flux.just(ChatChunk.error(conversationId)))}.
     * [Rationale] {@code usage} is {@code null} here like every other chunk this card emits — an
     *   error mid-stream has no completed token/cost accounting to report. Takes only {@code
     *   conversationId} (chat T9 rework, security L9) — {@code delta} is always {@link
     *   #STREAM_ERROR_MESSAGE}, so there is no argument through which a caller could leak an
     *   exception message to the client; the caller logs the real exception separately.
     * [AC] chat:AC-34
     */
    public static ChatChunk error(String conversationId) {
        return new ChatChunk(conversationId, STREAM_ERROR_MESSAGE, FINISH_REASON_ERROR, null);
    }

    /** [Purpose] Token/cost summary carried only on the last chunk of a stream (AC-32, AC-33). */
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            BigDecimal cost
    ) {}
}
