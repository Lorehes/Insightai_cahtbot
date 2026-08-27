package art.insightai.chatbot.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * [Purpose] One SSE chunk of {@code POST /api/conversations/{id}/messages}'s streamed response
 *   (chat Spec §5 A-6, A-11) — the BE/FE wire contract for {@code text/event-stream}.
 * [Rationale] Moved out of {@code ChatController}'s D1 nested record (chat Spec D-3) to gain
 *   {@code conversationId}/{@code finishReason}/{@code usage}, none of which the D1 shape could
 *   carry. T7 only defines the shape and returns an empty {@code Flux<ChatChunk>} from the new
 *   endpoint (chat:AC-18/22 forbid any LLM call at this stage, so nothing is ever emitted yet)
 *   — T9 is the first card to actually emit a populated chunk.
 * [Caution] {@code usage}'s token fields must stay {@code snake_case}
 *   ({@code @JsonProperty}) — AC-32/33 fix the wire field names as
 *   {@code usage.prompt_tokens}/{@code usage.cost}. {@code cost} is {@link BigDecimal} per the
 *   monetary-type rule (global CLAUDE.md §7.2-4), never a floating-point type; pair it with
 *   {@code spring.jackson.generator.write-bigdecimal-as-plain: true} (A-6) once a card actually
 *   populates it — not added to application.yaml yet since no card before T9/T11 ever sets a
 *   non-null {@code cost}.
 */
// TODO(chat): T9/T11에서 Usage.cost를 채우기 전 application.yaml에 spring.jackson.generator.write-bigdecimal-as-plain=true 추가 필요 (Tech Review A-6, AC-33 선행 위험)
public record ChatChunk(
        String conversationId,
        String delta,
        String finishReason,
        Usage usage
) {

    /** [Purpose] Token/cost summary carried only on the last chunk of a stream (AC-32, AC-33). */
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            BigDecimal cost
    ) {}
}
