package art.insightai.chatbot.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.completions.CompletionUsage;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * [Purpose] Unit tests for {@link StubChatModel} itself -- no Spring context, plain JUnit --
 * since every future SSE card (T9-T11) builds on top of it; a scripting bug here would
 * otherwise only surface much later as a confusing failure in an unrelated card's test.
 * [Rationale] Not AC-tagged: this verifies the test double's own contract, not an Acceptance
 * Criterion of the chat Spec (dev-qa-tester "테스트 명명 규약" -- AC 없는 테스트는 접두사 없이).
 */
class StubChatModelTest {

    @Test
    void streamEmitsOneChunkPerTokenThenAFinalChunkWithFinishReasonAndUsage() {
        // given
        StubChatModel stub = new StubChatModel();
        stub.enqueueTokens(
                List.of("오늘", " 금요일", " 점검"), "stop",
                new StubChatModel.Usage(7, 4, new BigDecimal("0.0012")));

        // when
        List<ChatResponse> chunks = stub.stream(new Prompt("아무 질문")).collectList().block();

        // then: 3 token chunks + 1 finish chunk.
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).getResult().getOutput().getText()).isEqualTo("오늘");
        assertThat(chunks.get(1).getResult().getOutput().getText()).isEqualTo(" 금요일");
        assertThat(chunks.get(2).getResult().getOutput().getText()).isEqualTo(" 점검");

        ChatResponse finishChunk = chunks.get(3);
        assertThat(finishChunk.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
        assertThat(finishChunk.getMetadata().getUsage().getPromptTokens()).isEqualTo(7);
        assertThat(finishChunk.getMetadata().getUsage().getCompletionTokens()).isEqualTo(4);

        // then: nativeUsage is the concrete SDK type chat T11's ChatService actually casts to
        //   (StubChatModel's own [Rationale] on nativeUsageOf explains why this must be a real
        //   CompletionUsage, not this test's own scripted StubChatModel.Usage record) -- and its
        //   "cost" additional property carries the scripted amount.
        Object nativeUsage = finishChunk.getMetadata().getUsage().getNativeUsage();
        assertThat(nativeUsage).isInstanceOf(CompletionUsage.class);
        CompletionUsage completionUsage = (CompletionUsage) nativeUsage;
        assertThat(completionUsage.promptTokens()).isEqualTo(7L);
        assertThat(completionUsage.completionTokens()).isEqualTo(4L);
        assertThat(completionUsage._additionalProperties().get("cost").asNumber())
                .contains(0.0012);
    }

    @Test
    void zeroUsageDeltaChunkCarriesZeroFilledUsageAndDoesNotAffectTheFinalChunksUsage() {
        // given: chat T11's S1 spike found real non-terminal delta chunks carry usage metadata
        //   that is present but zeroed, not entirely absent (2026-08-29 review Medium-2)
        StubChatModel stub = new StubChatModel();
        stub.enqueueTokensWithZeroUsageDelta(
                List.of("오늘", " 금요일", " 점검"), 1, "stop",
                new StubChatModel.Usage(59, 4, null));

        // when
        List<ChatResponse> chunks = stub.stream(new Prompt("아무 질문")).collectList().block();

        // then: the mid-stream chunk at index 1 carries a present-but-zeroed usage object, not
        //   EmptyUsage's implicit default that enqueueTokens()'s bare chunks fall back to
        assertThat(chunks.get(1).getMetadata().getUsage().getTotalTokens()).isZero();
        assertThat(chunks.get(1).getMetadata().getUsage().getNativeUsage()).isInstanceOf(CompletionUsage.class);

        // then: the terminal chunk's usage is untouched by the zeroed mid-stream chunk
        ChatResponse finishChunk = chunks.get(chunks.size() - 1);
        assertThat(finishChunk.getMetadata().getUsage().getPromptTokens()).isEqualTo(59);
        assertThat(finishChunk.getMetadata().getUsage().getCompletionTokens()).isEqualTo(4);
        assertThat(finishChunk.getMetadata().getUsage().getTotalTokens()).isEqualTo(63);
    }

    @Test
    void callCollapsesTheScriptedStreamIntoOneResponseWithTheFullText() {
        // given
        StubChatModel stub = new StubChatModel();
        stub.enqueueTokens(
                List.of("한", "글", " 응답"), "stop",
                new StubChatModel.Usage(1, 1, BigDecimal.ZERO));

        // when
        ChatResponse response = stub.call(new Prompt("아무 질문"));

        // then
        assertThat(response.getResult().getOutput().getText()).isEqualTo("한글 응답");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
    }

    @Test
    void enqueueTokensThenErrorEmitsTheTokensThenTheError() {
        // given
        StubChatModel stub = new StubChatModel();
        RuntimeException scriptedError = new RuntimeException("scripted mid-stream failure");
        stub.enqueueTokensThenError(List.of("부분", " 응답"), scriptedError);

        // when / then
        List<String> deltas = stub.stream(new Prompt("아무 질문"))
                .map(chunk -> chunk.getResult().getOutput().getText())
                .collectList()
                .onErrorResume(e -> reactor.core.publisher.Mono.just(List.of("<errored>")))
                .block();

        // the error terminates the Flux before collectList() can complete normally, so the
        // recovery branch above is what actually runs -- confirm the error propagated instead
        // of being silently swallowed as a normal completion.
        assertThat(deltas).containsExactly("<errored>");
    }

    @Test
    void throwsAHelpfulErrorWhenNoScriptIsQueued() {
        // given
        StubChatModel stub = new StubChatModel();

        // when / then
        assertThatThrownBy(() -> stub.stream(new Prompt("아무 질문")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enqueueTokens");
    }

    @Test
    void resetClearsQueuedScriptsAndCapturedPrompts() {
        // given
        StubChatModel stub = new StubChatModel();
        stub.enqueueTokens(List.of("토큰"), "stop", new StubChatModel.Usage(1, 1, BigDecimal.ZERO));
        stub.stream(new Prompt("첫 질문")).blockLast();
        assertThat(stub.capturedPrompts()).hasSize(1);

        // when
        stub.reset();

        // then: captured history is gone and the (already consumed) script queue is empty too.
        assertThat(stub.capturedPrompts()).isEmpty();
        assertThatThrownBy(() -> stub.stream(new Prompt("두번째 질문")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void capturedPromptsRecordsEveryPromptInCallOrder() {
        // given
        StubChatModel stub = new StubChatModel();
        stub.enqueueTokens(List.of("첫"), "stop", new StubChatModel.Usage(1, 1, BigDecimal.ZERO));
        stub.enqueueTokens(List.of("둘째"), "stop", new StubChatModel.Usage(1, 1, BigDecimal.ZERO));

        // when
        stub.stream(new Prompt("first")).blockLast();
        stub.stream(new Prompt("second")).blockLast();

        // then: AC-30-style multi-turn assertions read the prompt history in order.
        assertThat(stub.capturedPrompts())
                .extracting(Prompt::getContents)
                .containsExactly("first", "second");
    }
}
