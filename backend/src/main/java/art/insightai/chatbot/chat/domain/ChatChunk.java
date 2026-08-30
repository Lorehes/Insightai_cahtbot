package art.insightai.chatbot.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

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
 *   record. <b>hybrid-rag T11 addition (2026-08-30):</b> {@code sources} follows the exact same
 *   "present only on the terminal chunk" pattern {@code usage} already established, on the
 *   terminal chunk of a stream's <i>normal completion path</i>: a non-null {@code finishReason}
 *   there carries a non-null {@code sources} value too (either the actual citation list, or an
 *   explicitly empty array — never {@code null} on that one chunk; see {@code
 *   ChatService#resolveSources}'s own {@code [Caution]} Javadoc for the full, evolving list of
 *   cases that produce an empty array — do not restate a fixed count here; it already drifted
 *   once (correctness review, 2026-08-30, T11 rework round 3) when a per-element mapping-failure
 *   fallback was added without updating a hardcoded "three cases" claim that used to live in this
 *   sentence). Every mid-stream chunk still carries {@code sources: null}. <b>Three documented
 *   exceptions to that pairing (correctness review, 2026-08-30, T11 rework round 1; item (3) added
 *   T11 rework round 2, correctness Low):</b> (1) {@link
 *   #error} builds a chunk with a non-null {@code finishReason} ({@link #FINISH_REASON_ERROR}) and
 *   a {@code null} {@code sources} — the stream never reached a terminal, {@code
 *   DOCUMENT_CONTEXT}-bearing response, so there is nothing to report; (2) the {@code BL-019} gap
 *   ({@code ChatService#onStreamTerminated}'s own Javadoc) — when the SDK never supplies a usable
 *   finish reason, this record's own wire chunk keeps both fields {@code null} together (no
 *   pairing violation on the wire itself), but the <i>persisted</i> {@code messages} row ends up
 *   with a non-null {@code finish_reason} (that method's {@link #FINISH_REASON_UNKNOWN} fallback)
 *   and a {@code null} {@code sources} column, because no equivalent fallback exists for {@code
 *   sources}; (3) the cancellation path ({@code ChatService#onStreamTerminated}'s {@code CANCEL}
 *   branch, chat Spec §7 OQ-6) — {@link #FINISH_REASON_CANCELLED} is never sent on a wire chunk at
 *   all (by definition nothing is listening once a cancel signal fires — see that constant's own
 *   [Purpose]), so this is not a wire-contract violation, but the <i>persisted</i> row still pairs
 *   a non-null {@code finish_reason} with whatever {@code sourcesHolder} last held at the moment
 *   the cancel signal arrived — {@code null} unless a terminal, {@code DOCUMENT_CONTEXT}-bearing
 *   response had already landed before that signal, a narrower window than the normal completion
 *   path enjoys. No {@code @JsonInclude} is used anywhere on this record (matching the pre-existing
 *   {@code usage} field) — a {@code null} value serializes as the JSON literal {@code null}, not
 *   an omitted key, on every wire chunk.
 */
public record ChatChunk(
        String conversationId,
        String delta,
        String finishReason,
        Usage usage,
        List<Source> sources
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
     * [Purpose] {@code finishReason}/{@code messages.finish_reason} value used when the stream
     *   ends without a usable finish reason from the SDK (BL-003, 2026-08-30 user decision). On
     *   this project's pinned SDK version (spring-ai-openai 2.0.1), that covers both "the SDK
     *   never supplied one" and "the SDK's enum could not map the upstream value" — the latter
     *   arrives as {@code null}, not the literal string {@code "_UNKNOWN"}: {@code
     *   OpenAiChatModel.java:393-396} (spring-ai-openai-2.0.1-sources.jar) only calls {@code
     *   finishReasonValue.name()} when the mapped value is not {@code
     *   ChatCompletion.Choice.FinishReason.Value._UNKNOWN}, and returns {@code null} otherwise —
     *   so both cases collapse to the same {@code null} input this constant folds.
     * [Rationale] Filled in only by {@link #normalizeFinishReason} (fold case) and by {@code
     *   ChatService#onStreamTerminated} (stream-end fallback when no chunk ever set a value) —
     *   never by {@code ChatService#toChunk} on a still-{@code null} per-chunk value, which would
     *   make every mid-stream chunk carry a finishReason and break chat:AC-28. {@link
     *   #normalizeFinishReason}'s {@code "_unknown"} string branch is not reachable through this
     *   SDK version's own null-only path above; it is a defensive guard kept in case a future SDK
     *   version (or a different provider adapter) ever passes the raw enum name through as a
     *   string instead of {@code null}.
     */
    public static final String FINISH_REASON_UNKNOWN = "unknown";

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
     *   {@code sources} (hybrid-rag T11) is {@code null} here for the same reason as {@code
     *   usage}: this chunk never reached the terminal {@link
     *   org.springframework.ai.chat.model.ChatResponse} whose metadata a real citation list would
     *   be read from, so there is nothing to report — not even an empty array.
     * [AC] chat:AC-34
     */
    public static ChatChunk error(String conversationId) {
        return new ChatChunk(conversationId, STREAM_ERROR_MESSAGE, FINISH_REASON_ERROR, null, null);
    }

    /**
     * [Purpose] Normalizes one raw {@code finishReason} string from the LLM SDK to this
     *   project's wire/storage convention: lower-case, with the SDK's unrecognized-value
     *   sentinel folded to {@link #FINISH_REASON_UNKNOWN} (BL-003, 2026-08-30 user decision).
     * [Rationale] The OpenAI Java SDK reports finish reasons via {@code enum.name()} — real
     *   values arrive upper-case ({@code STOP}, {@code LENGTH}), while this record's own values
     *   ({@link #FINISH_REASON_ERROR}, {@link #FINISH_REASON_CANCELLED}) were already lower-case;
     *   BL-003 picks lower-case as the one shared wire/{@code messages.finish_reason} convention.
     *   Folding happens in this single method, which both {@code ChatService#toChunk} (per-chunk,
     *   only when the SDK did supply a value) and {@code ChatService#onStreamTerminated}
     *   (stream-end fallback, when no chunk ever did) call into, so a future third caller cannot
     *   land a third, subtly different fold. {@code null} and an all-blank string are both
     *   returned as {@code null} rather than folded to {@link #FINISH_REASON_UNKNOWN} here — they
     *   are the same "no usable value" case (2026-08-30 review Low-4: a blank string is not a
     *   distinct signal from absence, only a differently-shaped one), and chat:AC-28 requires
     *   every *mid-stream* chunk's finishReason to stay {@code null} (only the terminal chunk
     *   carries one at all). This method has no way to tell a mid-stream call from a terminal one;
     *   only {@code onStreamTerminated}, which runs once the whole stream is known to have ended,
     *   may turn a still-{@code null} value into {@link #FINISH_REASON_UNKNOWN}. Folding blank to
     *   {@link #FINISH_REASON_UNKNOWN} here instead (as an earlier version of this method did)
     *   would let a blank value on any non-terminal {@link
     *   org.springframework.ai.chat.model.ChatResponse} in the stream mark that chunk's {@code
     *   finishReason} as {@code "unknown"} on the wire, breaking chat:AC-28 the same way an
     *   unfolded {@code null} passthrough would not.
     * [Caution] Lower-casing {@code "_UNKNOWN"} alone would leave a leading underscore ({@code
     *   "_unknown"}) — this method folds only that sentinel case to the clean {@link
     *   #FINISH_REASON_UNKNOWN} constant instead of returning it as-is. On this project's pinned
     *   SDK version that sentinel is not known to be reachable (see {@link #FINISH_REASON_UNKNOWN}'s
     *   own Javadoc) — this branch is a defensive guard, not a documented live path.
     * [AC] chat:AC-28
     */
    public static String normalizeFinishReason(String rawFinishReason) {
        if (rawFinishReason == null || rawFinishReason.isBlank()) {
            return null;
        }
        String lowered = rawFinishReason.toLowerCase(Locale.ROOT);
        return lowered.equals("_unknown") ? FINISH_REASON_UNKNOWN : lowered;
    }

    /** [Purpose] Token/cost summary carried only on the last chunk of a stream (AC-32, AC-33). */
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            BigDecimal cost
    ) {}

    /**
     * [Purpose] One citation entry carried only on the last chunk of a stream (hybrid-rag Spec
     *   AC-11), and the same shape persisted verbatim as one array element of {@code
     *   messages.sources} (hybrid-rag:AC-12).
     * [Rationale] Field set/types/order are fixed by the T11 card brief's already-finalized
     *   contract ("이미 확정된 계약"(quoted verbatim)) (not this
     *   record's own design choice): {@code documentId} is {@link String}, not the {@link
     *   java.util.UUID} {@code rag.domain.SourceRef#documentId} carries — the wire/persistence
     *   shape is a plain string, converted once by {@code ChatService#toChunkSource}. This record
     *   deliberately does not reference {@code rag.domain.SourceRef} as a field/supertype: the
     *   {@code chat} package must not depend on the {@code rag} domain package (T11 card brief,
     *   agreed design constraint) — {@code chat/service} owns the one field-by-field copy from
     *   {@code SourceRef} to this record instead. {@code score} is always the RRF fusion score,
     *   never a rerank score (Tech Review D-T6, {@code SourceRef}'s own Javadoc) — this record
     *   only carries whatever value it was constructed with, it does not itself enforce that
     *   invariant. {@code snippet} is the first 200 characters of the backing chunk's content —
     *   an unvalidated placeholder pending D7 re-evaluation (Tech Review D-T7, RK-T6), not a
     *   considered UX decision.
     * [Caution] {@code page} is nullable on purpose (document-ingestion Spec OQ-5) — Tika cannot
     *   recover page boundaries for every source document format.
     * [AC] hybrid-rag:AC-11, hybrid-rag:AC-12
     */
    public record Source(
            String documentId,
            String filename,
            double score,
            String snippet,
            Integer page
    ) {}
}
