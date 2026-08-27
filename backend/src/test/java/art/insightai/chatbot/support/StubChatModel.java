package art.insightai.chatbot.support;

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
                // usage's OpenRouter-specific "cost" field has no dedicated slot on the real
                // Usage interface (S1 spike, R-1, still decides how ChatService will eventually
                // read it from the live SDK) -- stashed in getNativeUsage() here so tests can
                // still assert on it via usage().cost() without this stub guessing that wiring.
                .usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens(), null, usage))
                .build();
        chunks.add(new ChatResponse(List.of(finishGeneration), metadata));

        return Flux.fromIterable(chunks);
    }

    private static ChatResponse plainChunk(String token) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(token))));
    }

    /** [Purpose] Scripted token/completion counts and cost for one enqueued answer. */
    public record Usage(int promptTokens, int completionTokens, BigDecimal cost) {}
}
