package art.insightai.chatbot.support;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * [Purpose] Test double for {@link ChatModel} that never opens a real HTTP connection —
 * every {@link #call(Prompt)}/{@link #stream(Prompt)} invocation is served from a queue
 * of scripts the test enqueues beforehand via {@link #enqueueTokens} /
 * {@link #enqueueTokensThenError}.
 * [Rationale] chat Spec §6 "공통 전제": "LLM은 전부 스텁으로 대체한다... 토큰 시퀀스·usage·예외를
 * 테스트가 직접 지정한다." This is the single implementation every future SSE test (T9-T11)
 * is meant to reuse instead of hand-rolling a Mockito {@code ChatModel} per test class — a
 * shared, deterministic double keeps the "token sequence with leading spaces" (AC-25) and
 * "usage/cost surfaced in the last chunk" (AC-32, AC-33) shape consistent across cards.
 * Registered as the sole {@link ChatModel} bean by {@link StubChatModelConfig}, which
 * {@link IntegrationTestBase} imports — see that class for how the real
 * {@code OpenAiChatAutoConfiguration} is excluded so this is not just picked preferentially
 * but is the *only* {@code ChatModel} bean a test context can wire.
 * [Caution] Not thread-safe across concurrent test methods sharing one instance without
 * {@link #reset()} — Spring caches the {@code ApplicationContext} (and this singleton bean)
 * across test methods in the same test class/context configuration, so a test that doesn't
 * consume every scripted response it enqueues will leak state into the next test. Call
 * {@link #reset()} in a {@code @BeforeEach} (or {@code @AfterEach}) when a test class shares
 * a context with other test methods that also use this bean.
 */
public final class StubChatModel implements ChatModel {

    private final Deque<Supplier<Flux<ChatResponse>>> scripts = new ArrayDeque<>();
    private final List<Prompt> capturedPrompts = Collections.synchronizedList(new ArrayList<>());

    /**
     * [Purpose] Queues one streamed answer: each element of {@code tokens} arrives as a
     * separate {@link ChatResponse} chunk (mirrors how the real OpenAI/OpenRouter SDK streams
     * token-by-token), followed by one final chunk carrying an empty delta plus
     * {@code finishReason} and {@code usage} — the same "last chunk carries the summary"
     * shape Tech Review A-6/A-7 designs {@code ChatChunk} around.
     * [Caution] Pass tokens with their natural leading spaces intact (e.g. {@code " Friday"})
     * when a test needs to reproduce AC-25 — this stub does not trim or otherwise alter them.
     */
    public synchronized void enqueueTokens(List<String> tokens, String finishReason, Usage usage) {
        scripts.addLast(() -> streamOf(tokens, finishReason, usage));
    }

    /**
     * [Purpose] Queues a stream that emits {@code tokensBeforeError} and then terminates with
     * {@code error} instead of a normal finish chunk — for AC-34 ("스트림 도중 오류").
     */
    public synchronized void enqueueTokensThenError(List<String> tokensBeforeError, Throwable error) {
        scripts.addLast(() -> Flux.concat(
                Flux.fromIterable(tokensBeforeError).map(StubChatModel::plainChunk),
                Flux.error(error)));
    }

    /**
     * [Purpose] Queues a streamed answer identical to {@link #enqueueTokens}, except the chunk at
     *   {@code zeroUsageTokenIndex} additionally carries a zeroed {@link ChatResponseMetadata}
     *   {@code usage} (prompt=0/completion=0/total=0, with a matching zero-filled
     *   {@link CompletionUsage} nativeUsage) instead of no usage metadata at all.
     * [Rationale] chat T11's S1 spike (state.md, 2026-08-25) found every non-terminal delta chunk
     *   a real OpenRouter stream sends carries usage metadata that is *present but zeroed*, not
     *   absent — {@link #enqueueTokens}'s existing shape (bare chunks via {@link #plainChunk}, no
     *   {@link ChatResponseMetadata} at all, so {@link ChatResponseMetadata#getUsage()} falls back
     *   to {@link org.springframework.ai.chat.metadata.EmptyUsage}) happens to also yield
     *   {@code getTotalTokens() == 0}, so a test built only on {@link #enqueueTokens} cannot
     *   distinguish "no usage object" from "usage object with all-zero fields" for {@code
     *   ChatService}'s {@code > 0} guard (2026-08-29 review Medium-2). This method constructs the
     *   zeroed shape explicitly so a test can.
     * [Caution] {@code zeroUsageTokenIndex} must index into {@code tokens}, never the synthetic
     *   finish chunk this method also appends — the finish chunk always carries the real, non-zero
     *   {@code usage} built from {@code usage}.
     */
    public synchronized void enqueueTokensWithZeroUsageDelta(List<String> tokens, int zeroUsageTokenIndex,
            String finishReason, Usage usage) {
        scripts.addLast(() -> streamOfWithZeroUsageDelta(tokens, zeroUsageTokenIndex, finishReason, usage));
    }

    /**
     * [Purpose] Clears any unconsumed scripts and captured prompts.
     * [Caution] Call this between test methods that share a cached Spring context and both
     * use this bean — see class [Caution].
     */
    public synchronized void reset() {
        scripts.clear();
        capturedPrompts.clear();
    }

    /**
     * [Purpose] Every {@link Prompt} this stub has received, in call order — lets a test
     * assert on multi-turn history (AC-30: "LLM에 전달된 프롬프트에 1번째 턴의 메시지가 포함된다")
     * without needing a real model to inspect.
     */
    public List<Prompt> capturedPrompts() {
        return List.copyOf(capturedPrompts);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        capturedPrompts.add(prompt);
        List<ChatResponse> chunks = nextScript().get().collectList().block();
        String content = chunks.stream()
                .map(chunk -> chunk.getResult().getOutput().getText())
                .collect(Collectors.joining());
        ChatResponse lastChunk = chunks.get(chunks.size() - 1);
        Generation collapsed = new Generation(
                new AssistantMessage(content), lastChunk.getResult().getMetadata());
        return new ChatResponse(List.of(collapsed), lastChunk.getMetadata());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        capturedPrompts.add(prompt);
        return nextScript().get();
    }

    private synchronized Supplier<Flux<ChatResponse>> nextScript() {
        Supplier<Flux<ChatResponse>> script = scripts.pollFirst();
        if (script == null) {
            throw new IllegalStateException(
                    "StubChatModel has no scripted response queued -- call enqueueTokens()/"
                            + "enqueueTokensThenError() before invoking the ChatModel/ChatClient "
                            + "in this test.");
        }
        return script;
    }

    private static Flux<ChatResponse> streamOf(List<String> tokens, String finishReason, Usage usage) {
        List<ChatResponse> chunks = tokens.stream().map(StubChatModel::plainChunk).collect(Collectors.toList());

        Generation finishGeneration = new Generation(
                new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens(), null, nativeUsageOf(usage)))
                .build();
        chunks.add(new ChatResponse(List.of(finishGeneration), metadata));

        return Flux.fromIterable(chunks);
    }

    private static Flux<ChatResponse> streamOfWithZeroUsageDelta(List<String> tokens, int zeroUsageTokenIndex,
            String finishReason, Usage usage) {
        List<ChatResponse> chunks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (i == zeroUsageTokenIndex) {
                ChatResponseMetadata zeroMetadata = ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(0, 0, null, zeroFilledNativeUsage()))
                        .build();
                chunks.add(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(tokens.get(i)))), zeroMetadata));
            }
            else {
                chunks.add(plainChunk(tokens.get(i)));
            }
        }

        Generation finishGeneration = new Generation(
                new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens(), null, nativeUsageOf(usage)))
                .build();
        chunks.add(new ChatResponse(List.of(finishGeneration), metadata));

        return Flux.fromIterable(chunks);
    }

    private static CompletionUsage zeroFilledNativeUsage() {
        return CompletionUsage.builder()
                .promptTokens(0L)
                .completionTokens(0L)
                .totalTokens(0L)
                .build();
    }

    /**
     * [Purpose] Builds the {@code nativeUsage} object {@link #streamOf} stashes on the finish
     * chunk's {@link DefaultUsage}, mirroring the concrete type chat T11's {@code ChatService}
     * reads in production.
     * [Rationale] chat T11's S1 spike (state.md, 2026-08-25) determined {@code ChatService}
     * casts {@code org.springframework.ai.chat.metadata.Usage#getNativeUsage()} (the
     * Spring AI interface {@link DefaultUsage} implements -- not this class's own {@link Usage}
     * record below, which only carries the script a test enqueues) to
     * {@code com.openai.models.completions.CompletionUsage} and reads {@code cost} out of its
     * {@code _additionalProperties()} map. An earlier revision of this stub stashed the enqueued
     * {@link Usage} record itself as {@code nativeUsage} instead ("still decides how ChatService
     * will eventually read it" -- this class's own prior Javadoc, now resolved) --
     * that would have made the production {@code instanceof CompletionUsage} check silently
     * false for every enqueued script, so no test built on this stub could ever have exercised
     * the real extraction path. {@link JsonValue#fromJsonNode} with a {@link DoubleNode} (rather
     * than {@link JsonValue#from(Object)} on the {@link BigDecimal} directly, which would build
     * an exact, lossless {@code DecimalNode}) reproduces the one lossy step the real OpenAI SDK
     * performs before {@code ChatService} ever sees the value: {@code openai-java-core}'s own
     * Jackson mapper (confirmed by reading its {@code ObjectMappers.kt} source) never enables
     * {@code USE_BIG_DECIMAL_FOR_FLOATS}, so a wire floating-point literal always becomes a
     * {@code DoubleNode}, never a {@code DecimalNode}, before landing in
     * {@code additionalProperties} -- this stub matches that, rather than being more precise
     * than the real SDK it doubles for.
     * [Caution] {@code usage.cost() == null} (every enqueue predating T11, e.g. {@code
     * RagAdvisorAssemblyTest}) omits the {@code "cost"} additional property entirely, matching a
     * real response where OpenRouter's {@code extraBody} usage-include flag was never set --
     * {@code ChatService#extractCost} must return {@code null}, not throw, for that case.
     */
    private static CompletionUsage nativeUsageOf(Usage usage) {
        CompletionUsage.Builder builder = CompletionUsage.builder()
                .promptTokens(usage.promptTokens())
                .completionTokens(usage.completionTokens())
                .totalTokens(usage.promptTokens() + usage.completionTokens());
        if (usage.cost() != null) {
            builder.putAdditionalProperty("cost",
                    JsonValue.fromJsonNode(DoubleNode.valueOf(usage.cost().doubleValue())));
        }
        return builder.build();
    }

    private static ChatResponse plainChunk(String token) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(token))));
    }

    /** [Purpose] Scripted token/completion counts and cost for one enqueued answer. */
    public record Usage(int promptTokens, int completionTokens, BigDecimal cost) {}
}
