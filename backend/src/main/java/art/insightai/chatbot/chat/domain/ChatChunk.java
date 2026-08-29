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
 *   finishReason} are filled in per streamed response; {@code usage} stayed {@code null} on
 *   every chunk T9 emitted (token/cost accounting was T11's scope, not T9's) — T11 is what
 *   populates it, on the one final chunk of a successful stream (see that method's own
 *   [Rationale]/[Side effects] for the extraction path).
 * [Caution] {@code usage}'s token fields must stay {@code snake_case}
 *   ({@code @JsonProperty}) — AC-32/33 fix the wire field names as
 *   {@code usage.prompt_tokens}/{@code usage.cost}. {@code cost} is {@link BigDecimal} per the
 *   monetary-type rule (global CLAUDE.md §7.2-4), never a floating-point type; T11 pairs it with
 *   {@code spring.jackson.write.write-bigdecimal-as-plain: true} in {@code application.yaml} so
 *   this field never serializes in scientific notation (chat:AC-33) — note this is the Jackson
 *   3 (Boot 4's {@code tools.jackson}, confirmed via {@code spring-boot-jackson-4.1.1}'s
 *   {@code JacksonProperties} bytecode: its {@code write} map binds {@code
 *   tools.jackson.core.StreamWriteFeature}) property path, not classic Jackson 2's
 *   {@code spring.jackson.generator.*} (there is no {@code generator} map on this project's
 *   {@code JacksonProperties} — that name was this comment's earlier, pre-T11 guess). {@link
 *   #error}'s message becomes {@code delta} on the wire and is
 *   shown to the end user; as of chat T9 rework (2026-08-27, security L9) that text is a fixed
 *   {@link #STREAM_ERROR_MESSAGE} constant defined on this record, not a caller-supplied
 *   argument — {@link #error} takes only {@code conversationId}, so there is no longer a
 *   parameter a caller could accidentally fill with a caught exception's {@code getMessage()}
 *   (INC-007, recurred chat T4/T6: OpenRouter/SDK exception messages can embed internal
 *   configuration, model ids, or upstream response bodies). The real exception is still logged
 *   server-side by the caller ({@code ChatService#streamReply}'s error branch), never by this
 *   record.
 */
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
