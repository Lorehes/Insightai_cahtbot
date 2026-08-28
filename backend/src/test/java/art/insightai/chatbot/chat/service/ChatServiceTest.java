package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.domain.MessageQuotaExceededException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link ChatService#streamReply}'s own logic in isolation: chunk mapping
 *   (chat:AC-25/27/28), title/model resolution, the message-quota short-circuit (chat:AC-45), and
 *   the exactly-once terminal recording across completion/error/cancellation (chat:AC-29, High-1)
 *   — with {@link ChatClient}'s fluent chain and {@link ConversationService} both mocked, no
 *   Spring context. This class only asserts on *what {@link ChatService} calls* ({@code
 *   conversationService.persistUserTurn}/{@code persistAssistantTurn}/{@code
 *   enforceMessageQuota}), not on what those methods do internally once called — that is {@code
 *   ConversationService}'s own responsibility. Integration-level coverage of the full HTTP/SSE
 *   path (Content-Type, actual JSON bytes, actual UTF-8 transport, actual DB rows — chat:AC-24/26/
 *   29/45) requires a live {@code MockMvc}/{@code StubChatModel}/database round trip and is {@code
 *   ConversationControllerIntegrationTest}'s scope; nothing here claims that evidence.
 * [Rationale] {@link ChatClient}'s fluent API returns interfaces at every step ({@code
 *   ChatClientRequestSpec}, {@code StreamResponseSpec}), so {@link #setUp} mocks the whole chain
 *   once, stubbing every {@code ChatClientRequestSpec} method {@link ChatService#streamReply}
 *   calls to return the same mock (mirroring the real fluent builder's self-returning shape), so
 *   each test method only needs to stub {@code streamResponseSpec.chatResponse()} with its own
 *   scripted {@code Flux<ChatResponse>} — {@link #scriptedResponses} builds that the same way
 *   {@code support.StubChatModel} does (tokens, then one empty-text response carrying {@code
 *   finishReason}). {@link #conversationStub()} never stubs {@code getTitle()}, so it defaults to
 *   {@code null} (Mockito's default for an unstubbed method returning an object) — this
 *   deliberately exercises {@code streamReply}'s "conversation has no title yet" branch in every
 *   test that does not care about titling, matching the common case in production (a
 *   newly-created conversation's first message).
 * [Round-2 rework, dev-qa-tester 2026-08-27] Rewritten end to end: {@link ChatService}'s
 *   constructor changed from {@code (ChatClient.Builder, ConversationRepository,
 *   MessageRepository)} to {@code (ChatClient.Builder, ConversationService)} (maintainability
 *   Medium-1 — the two repository mutations moved behind {@code ConversationService#persistUserTurn}/
 *   {@code #persistAssistantTurn}, two {@code public @Transactional} methods on a different bean),
 *   and {@link ChatService#streamReply} gained a {@code requestedModel} parameter (correctness
 *   D-4). Every test below was updated to mock/verify against {@link ConversationService} instead
 *   of the two repositories, and to pass {@code null} as {@code requestedModel} unless the test is
 *   specifically about model resolution (D-4 itself is black-box tested at the HTTP layer in
 *   {@code ConversationControllerIntegrationTest}, since the model actually used only becomes
 *   observable through {@code StubChatModel.capturedPrompts()} there). {@link
 *   #concatenatesLeadingSpaceTokensExactly}'s {@code [chat:AC-25]} tag was removed (review round 1
 *   correctness L2): this test never serializes to SSE bytes, so it structurally cannot catch the
 *   transport-layer leading-space loss AC-25 exists to guard against — that evidence is, and
 *   remains, {@code ConversationControllerIntegrationTest}'s job. Three tests are new: {@link
 *   #persistsMessagesWhenTerminalSignalArrivesOnADifferentThreadThanTheSubscriber} closes
 *   correctness L6 (no test exercised a terminal signal arriving on a thread other than the
 *   subscriber's) by scripting {@code Schedulers.boundedElastic()} directly and using {@code
 *   block()}, which — unlike {@code MockMvc}'s async-dispatch pattern, tried first and abandoned
 *   here for exactly this reason — reliably waits for a genuinely cross-thread {@code Flux}
 *   completion; {@link #persistsExactlyOneCancelledAssistantRowWithPartialContentWhenCancelledMidStream} locks the
 *   review's High-1 finding (§7 OQ-6's "저장한다, finishReason=cancelled" decision had no test and
 *   no implementation for the cancel termination signal) — deliberately untagged, since no AC
 *   number corresponds to it; {@link
 *   #propagatesMessageQuotaExceededWithoutCallingLlmOrPersistingAnything} is {@code chat:AC-45}'s
 *   unit-level half (zero LLM calls, zero persistence calls when the quota is already exceeded) —
 *   its HTTP/DB-level half (403 response, zero new {@code messages} rows, no config leak per
 *   INC-007) lives in {@code ConversationControllerIntegrationTest}, the same two-layer split T4's
 *   chat:AC-42/T6's chat:AC-43 already established.
 * [Post-Pass cleanup, dev-qa-tester 2026-08-27, T9 round-2 review correctness L4/L5] Two more
 *   tests close gaps the round-2 review found in how this class locks the three-hook termination
 *   design: {@link #assistantTurnIsAlreadyPersistedByTheTimeTheCompletionSignalReachesADownstreamSubscriber}
 *   (L5) decisively asserts the one property a single consolidated {@code doFinally} cannot
 *   provide — that {@code persistAssistantTurn} has already run *before* a subscriber positioned
 *   downstream of {@link #streamReply}'s own hooks observes the completion signal — by reading a
 *   flag the mock's completion sets, from inside a probe chained onto the {@link Flux} {@code
 *   streamReply} *returns* (so it only sees the signal after all three internal hooks have run
 *   their course). {@link #recordsAssistantTurnExactlyOnceWhenTheCompleteHookItselfThrowsAndReroutesToErrorResume}
 *   (L4) reproduces the one edge case where the three hooks genuinely overlap for the same
 *   subscription — {@code doOnComplete}'s callback itself throwing reroutes into {@code
 *   onErrorResume} (both hooks then call {@link ConversationService#persistAssistantTurn}) — which
 *   is what the {@code AtomicBoolean} guard actually exists to survive; the pre-existing {@code
 *   take(1)}-based cancellation test below only ever exercises {@code doOnCancel} alone (its own
 *   comment previously overclaimed guard coverage it structurally cannot provide, since {@code
 *   take(1)} never sends {@code onComplete} upstream — corrected in place). Neither new test
 *   modifies or reads {@code ChatService.java} differently than the tests above; the reactor-core
 *   ordering/rerouting mechanics both rely on were independently confirmed beforehand with a
 *   throwaway, standalone {@link Flux} experiment (not part of this class, not committed, and
 *   {@code backend/src/main/java/**} was never touched to do it — T9 round-2's file boundary keeps
 *   this class's own production counterpart off limits while {@code dev-backend-engineer} edits
 *   Javadoc there in parallel).
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final String MODEL = "upstage/solar-pro4";

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.StreamResponseSpec streamResponseSpec;

    @Mock
    private ConversationService conversationService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        // lenient(): propagatesMessageQuotaExceededWithoutCallingLlmOrPersistingAnything (chat:AC-45)
        // throws before streamReply() ever reaches the fluent chain below, so under Mockito's
        // strict stubs (MockitoExtension's default) these four would otherwise fail that one test
        // with UnnecessaryStubbingException even though every other test in this class does use them.
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.stream()).thenReturn(streamResponseSpec);

        service = new ChatService(chatClientBuilder, conversationService);
    }

    @Test
    @DisplayName("선행 공백을 가진 토큰을 순서대로 이어붙이면 원문과 정확히 일치한다 (청크 매핑 단계만 검증 "
            + "-- SSE 직렬화·전송 계층은 거치지 않으므로 chat:AC-25 자체의 근거는 아니다; 그 근거는 "
            + "ConversationControllerIntegrationTest#streamedDeltasConcatenateToExactOriginalTextWithLeadingSpacesPreserved "
            + "가 담당한다. 2026-08-27 T9 재작업 2회차 correctness L2 — 이 태그가 SSE 전송을 거치지 않는 "
            + "테스트에 달려 있어 통합 테스트가 사라져도 증거 원장이 태그만으로 ✅를 유지할 수 있었던 문제를 해소)")
    void concatenatesLeadingSpaceTokensExactly() {
        // given
        when(streamResponseSpec.chatResponse())
                .thenReturn(scriptedResponses(List.of("오늘", " 금요일", " 점검"), "stop"));
        Conversation conversation = conversationStub();

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, "점검 일정 알려줘").collectList().block();

        // then
        String concatenated = chunks.stream()
                .map(ChatChunk::delta)
                .filter(delta -> delta != null)
                .reduce("", String::concat);
        assertThat(concatenated).isEqualTo("오늘 금요일 점검");
    }

    @Test
    @DisplayName("[chat:AC-27] 방출되는 모든 청크가 요청한 conversationId 를 동일하게 담고 있다")
    void everyChunkCarriesTheSameConversationId() {
        // given
        when(streamResponseSpec.chatResponse()).thenReturn(scriptedResponses(List.of("hello", " world"), "stop"));
        Conversation conversation = conversationStub();

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, "hi").collectList().block();

        // then
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.conversationId())
                .isEqualTo(CONVERSATION_ID.toString()));
    }

    @Test
    @DisplayName("[chat:AC-28] 마지막 청크만 finishReason 을 가지고 그 뒤로 청크가 없다")
    void onlyTheLastChunkCarriesFinishReason() {
        // given
        when(streamResponseSpec.chatResponse()).thenReturn(scriptedResponses(List.of("hello", " world"), "stop"));
        Conversation conversation = conversationStub();

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, "hi").collectList().block();

        // then
        assertThat(chunks).hasSize(3); // 2 tokens + 1 terminal chunk
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).finishReason()).isNull();
        }
        assertThat(chunks.get(chunks.size() - 1).finishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("[chat:AC-29] 스트림 완료 후 conversationService 에 user 턴과 assistant 턴이 각각 정확히 1회 "
            + "위임되고 assistant content 는 이어붙인 전체 문자열과 같다")
    void persistsUserAndAssistantRowsMatchingStreamedContent() {
        // given
        when(streamResponseSpec.chatResponse())
                .thenReturn(scriptedResponses(List.of("오늘", " 금요일", " 점검"), "stop"));
        Conversation conversation = conversationStub();

        // when
        service.streamReply(conversation, null, "점검 일정 알려줘").collectList().block();

        // then: the user turn was delegated exactly once, with a freshly-computed title
        //   candidate (conversationStub()'s getTitle() defaults to null) and the right content
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> userMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistUserTurn(titleCaptor.capture(), userMessageCaptor.capture());
        assertThat(titleCaptor.getValue()).isEqualTo("점검 일정 알려줘");
        Message userRow = userMessageCaptor.getValue();
        assertThat(userRow.getRole()).isEqualTo("user");
        assertThat(userRow.getContent()).isEqualTo("점검 일정 알려줘");
        assertThat(userRow.getModel()).isNull();

        // then: the assistant turn was delegated exactly once, with the full concatenated answer
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        Message assistantRow = assistantMessageCaptor.getValue();
        assertThat(assistantRow.getRole()).isEqualTo("assistant");
        assertThat(assistantRow.getContent()).isEqualTo("오늘 금요일 점검");
        assertThat(assistantRow.getModel()).isEqualTo(MODEL);
        assertThat(assistantRow.getFinishReason()).isEqualTo("stop");
        assertThat(assistantRow.getPromptTokens()).isNull();
        assertThat(assistantRow.getCompletionTokens()).isNull();
        assertThat(assistantRow.getCostUsd()).isNull();

        // then: the quota was checked before any of the above
        verify(conversationService).enforceMessageQuota(CONVERSATION_ID);
    }

    @Test
    @DisplayName("[chat:AC-29] 스트림 완료 신호가 구독 스레드가 아닌 다른 스레드에서 도착해도 user/assistant "
            + "턴이 각각 정확히 1회 위임되고 assistant content 는 이어붙인 전체 문자열과 같다 (round-2 재작업 "
            + "correctness L6 -- 이 카드가 실제로 겪은 TransactionRequiredException 부류의 결함은 완료 콜백이 "
            + "구독 스레드가 아닌 다른 스레드에서 실행되는 경로를 어떤 테스트도 실행한 적이 없어 생긴 사각지대였다)")
    void persistsMessagesWhenTerminalSignalArrivesOnADifferentThreadThanTheSubscriber() {
        // given: unlike every other test in this class, the scripted responses are delivered on
        //   a Schedulers.boundedElastic() worker thread instead of the subscribing thread -- the
        //   shape a real streaming HTTP client has. An earlier attempt tested this same claim at
        //   the HTTP layer (ConversationControllerIntegrationTest, scripting StubChatModel to
        //   deliver on boundedElastic) but MockMvc's async-dispatch pattern does not reliably wait
        //   for a genuinely cross-thread Flux completion -- the persisting write raced past that
        //   test's own @AfterEach cleanup and corrupted sibling tests with a foreign-key violation.
        //   block() below does not have that gap: it correctly waits for every scheduled emission,
        //   including whichever of streamReply()'s three termination hooks
        //   (doOnComplete/doOnCancel/onErrorResume) fires, before returning.
        when(streamResponseSpec.chatResponse())
                .thenReturn(scriptedResponses(List.of("오늘", " 금요일", " 점검"), "stop")
                        .publishOn(Schedulers.boundedElastic()));
        Conversation conversation = conversationStub();

        // when
        service.streamReply(conversation, null, "점검 일정 알려줘").collectList().block();

        // then
        verify(conversationService).persistUserTurn(any(), any());
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        Message assistantRow = assistantMessageCaptor.getValue();
        assertThat(assistantRow.getRole()).isEqualTo("assistant");
        assertThat(assistantRow.getContent()).isEqualTo("오늘 금요일 점검");
        assertThat(assistantRow.getFinishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("(무태그 — 대응 AC 없음. 근거: chat:AC-29 자체가 아니라 그것을 지키는 세 갈래 종단 구성의 "
            + "순서 속성 그 자체 -- ChatService 클래스 Javadoc \"Do not consolidate the three termination "
            + "hooks into a single doFinally\" 경고, T9 review round-2 correctness L5) 종단 신호가 하류 "
            + "구독자에게 전달되는 시점에는 이미 assistant 턴 위임이 끝나 있다 -- 훅 3개를 doFinally 하나로 "
            + "되돌리면 이 순서가 뒤집혀 이 테스트가 실패한다(reactor-core 소스 독해뿐 아니라, ChatService.java는 "
            + "건드리지 않는 별도의 순수 Flux 실험으로 그 반전을 직접 실행해 확인함 — 이 카드의 파일 경계상 "
            + "backend/src/main/java 는 수정 대상이 아님)")
    void assistantTurnIsAlreadyPersistedByTheTimeTheCompletionSignalReachesADownstreamSubscriber() {
        // given: persistAssistantTurn flips a plain flag the instant it's called
        when(streamResponseSpec.chatResponse()).thenReturn(scriptedResponses(List.of("ok"), "stop"));
        Conversation conversation = conversationStub();
        AtomicBoolean assistantTurnPersisted = new AtomicBoolean(false);
        doAnswer(invocation -> {
            assistantTurnPersisted.set(true);
            return null;
        }).when(conversationService).persistAssistantTurn(any());

        // when: chain a probe onto the Flux streamReply() RETURNS (i.e. strictly downstream of its
        //   three internal hooks) rather than adding anything inside the method itself. doOnComplete/
        //   doOnCancel are backed by FluxPeek, whose callback runs and returns *before* forwarding
        //   the signal to the next operator downstream -- so by construction, a probe attached here
        //   can only observe the completion signal after every one of streamReply()'s own hooks has
        //   already run. A single doFinally does the opposite: it forwards the signal to the
        //   downstream subscriber first and only then runs its own consumer, so this same probe
        //   would observe the flag still false. This is a purely synchronous, single-threaded
        //   assertion -- no cross-thread timing involved, so it cannot be flaky.
        AtomicBoolean downstreamSawItAlreadyPersisted = new AtomicBoolean(false);
        service.streamReply(conversation, null, "hi")
                .doOnComplete(() -> downstreamSawItAlreadyPersisted.set(assistantTurnPersisted.get()))
                .collectList()
                .block();

        // then
        assertThat(downstreamSawItAlreadyPersisted).isTrue();
    }

    @Test
    @DisplayName("(무태그 — 대응 AC 없음. 근거: Spec §7 OQ-6 해결책 \"저장한다, finishReason=cancelled로 "
            + "구분\" · Tech Review A-6의 finishReason 값 집합 · 게이트 2 결정 D-8의 finish_reason 컬럼 신설) "
            + "스트림이 도중에 취소되면 assistant 턴이 정확히 1번 위임되고 finishReason이 cancelled이며 "
            + "content는 취소 시점까지 수신한 부분 응답이다")
    void persistsExactlyOneCancelledAssistantRowWithPartialContentWhenCancelledMidStream() {
        // given
        when(streamResponseSpec.chatResponse())
                .thenReturn(scriptedResponses(List.of("오늘", " 금요일", " 점검"), "stop"));
        Conversation conversation = conversationStub();

        // when: the caller disconnects after the first chunk -- take(1) requests exactly one
        //   element from the whole streamReply() chain, then cancels everything upstream of it,
        //   mirroring how Spring MVC cancels the subscription when a client aborts an SSE
        //   connection mid-stream (Reactor cancellation is neither onComplete nor onError)
        service.streamReply(conversation, null, "점검 일정 알려줘").take(1).blockLast();

        // then: the user turn still happened (it runs synchronously before the Flux even exists)
        verify(conversationService).persistUserTurn(any(), any());

        // then: the assistant turn was delegated exactly once, with the partial content collected
        //   before cancellation and finishReason=cancelled. This does NOT exercise the
        //   AtomicBoolean guard's actual purpose (T9 review round-2 correctness L4 -- this comment
        //   previously overclaimed that it did): take(1) requests one element then cancels, which
        //   sends only a cancel signal upstream, never onComplete -- doOnComplete never runs here
        //   at all, guard present or not, so this test cannot tell the two cases apart. See
        //   recordsAssistantTurnExactlyOnceWhenTheCompleteHookItselfThrowsAndReroutesToErrorResume
        //   below for a scenario where two of the three hooks genuinely fire for one subscription.
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        Message assistantRow = assistantMessageCaptor.getValue();
        assertThat(assistantRow.getRole()).isEqualTo("assistant");
        assertThat(assistantRow.getContent()).isEqualTo("오늘");
        assertThat(assistantRow.getFinishReason()).isEqualTo(ChatChunk.FINISH_REASON_CANCELLED);
    }

    @Test
    @DisplayName("(무태그 — 대응 AC 없음. 근거: onStreamTerminated 자신의 [Side effects] 마지막 문단이 "
            + "명시하는 바로 그 엣지 케이스 -- doOnComplete 콜백이 던지면 FluxPeek 가 이를 onError 로 재라우팅해 "
            + "onErrorResume 도 실행된다, T9 review round-1 correctness Low-5 / round-2 High-1 이 추적한 "
            + "시나리오) doOnComplete 훅의 콜백이 던지면 onErrorResume 훅도 실행되지만 AtomicBoolean 가드 "
            + "덕분에 assistant 턴은 정확히 1번만 위임된다 (T9 review round-2 correctness L4 -- take(1) "
            + "취소 테스트는 doOnComplete 자체가 아예 실행되지 않아 이 가드가 실제로 막는 상황을 재현하지 "
            + "못했다; 이 테스트가 두 훅이 실제로 겹치는 경로를 재현한다. 재라우팅 메커니즘 자체는 "
            + "ChatService.java를 건드리지 않는 별도의 순수 Flux 실험으로 먼저 확인함)")
    void recordsAssistantTurnExactlyOnceWhenTheCompleteHookItselfThrowsAndReroutesToErrorResume() {
        // given: persistAssistantTurn throws on its first invocation -- standing in for the
        //   completion hook's callback itself failing after onStreamTerminated's guard has already
        //   admitted it (e.g. a transient DB error inside the real recordAssistantMessage) -- then
        //   succeeds silently on any further invocation. If the AtomicBoolean guard in
        //   onStreamTerminated were missing, the rerouted onError signal below would reach
        //   onErrorResume's own onStreamTerminated call and invoke persistAssistantTurn a second
        //   time, which would then succeed instead of being skipped.
        when(streamResponseSpec.chatResponse()).thenReturn(scriptedResponses(List.of("ok"), "stop"));
        Conversation conversation = conversationStub();
        doThrow(new RuntimeException("boom in completion hook"))
                .doNothing()
                .when(conversationService).persistAssistantTurn(any());

        // when: reactor-core-3.8.7's FluxPeek catches an exception thrown from an onComplete
        //   callback and routes it to the downstream subscriber as a new onError signal (this
        //   class's own Javadoc cites the same sources; independently reconfirmed here with a
        //   throwaway, standalone Flux experiment before relying on it in this assertion) --
        //   onErrorResume's fallback below swallows that rerouted error into one error chunk, so
        //   nothing propagates out of block() as an exception.
        List<ChatChunk> chunks = service.streamReply(conversation, null, "hi").collectList().block();

        // then: the stream still terminated cleanly with a trailing error chunk -- proof the
        //   reroute actually happened (otherwise the first "boom" would have propagated out of
        //   block() as an unhandled exception instead of a normal chunk list)
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(chunks.size() - 1).finishReason()).isEqualTo(ChatChunk.FINISH_REASON_ERROR);

        // then: persistAssistantTurn was invoked exactly once (verify()'s default count) -- the
        //   guard swallowed the rerouted second attempt from onErrorResume before it could reach
        //   this mock again
        verify(conversationService).persistAssistantTurn(any());
    }

    @Test
    @DisplayName("[chat:AC-45] 대화의 메시지 상한에 도달하면 MessageQuotaExceededException 을 전파하고 "
            + "LLM 호출과 user/assistant 턴 위임이 전혀 발생하지 않는다 (HTTP/DB 수준 절반은 "
            + "ConversationControllerIntegrationTest 담당)")
    void propagatesMessageQuotaExceededWithoutCallingLlmOrPersistingAnything() {
        // given
        Conversation conversation = conversationStub();
        doThrow(new MessageQuotaExceededException(CONVERSATION_ID))
                .when(conversationService).enforceMessageQuota(CONVERSATION_ID);

        // when / then: the exception propagates synchronously out of streamReply() itself --
        //   this method builds no Flux until after the quota check, so there is nothing
        //   reactive to unwrap
        assertThatThrownBy(() -> service.streamReply(conversation, null, "한 번 더"))
                .isInstanceOf(MessageQuotaExceededException.class);

        // then: zero LLM interaction and zero persistence of either turn
        verify(chatClient, never()).prompt();
        verify(conversationService, never()).persistUserTurn(any(), any());
        verify(conversationService, never()).persistAssistantTurn(any());
    }

    @Test
    @DisplayName("30자를 넘는 메시지는 앞 30자 + … 로 잘라 제목 후보로 넘긴다 (Spec §8 N-2 — 대응 AC 없음)")
    void truncatesLongMessageForTitleCandidate() {
        // given
        when(streamResponseSpec.chatResponse()).thenReturn(scriptedResponses(List.of("ok"), "stop"));
        Conversation conversation = conversationStub();
        String longMessage = "1234567890".repeat(4); // 40 chars, over the 30-char cap

        // when
        service.streamReply(conversation, null, longMessage).collectList().block();

        // then
        verify(conversationService)
                .persistUserTurn(eq("123456789012345678901234567890…"), any());
    }

    @Test
    @DisplayName("[chat:AC-34] 스트림 도중 오류가 나면 오류 청크 1건을 방출하고 정상 종료하며 "
            + "예외 메시지를 그대로 노출하지 않는다")
    void emitsOneErrorChunkAndCompletesWithoutLeakingExceptionMessage() {
        // given: the exception message stands in for an SDK/OpenRouter message that could embed
        //   internal config (INC-007) -- assertions below check it never reaches the client.
        String sensitiveDetail = "upstream responded with api-key=sk-secret-12345";
        Flux<ChatResponse> partialThenError = Flux.concat(
                Flux.just(plainResponse("일부 답")),
                Flux.error(new RuntimeException(sensitiveDetail)));
        when(streamResponseSpec.chatResponse()).thenReturn(partialThenError);
        Conversation conversation = conversationStub();

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, "질문").collectList().block();

        // then: the partial chunk that streamed before the failure, plus exactly one chunk
        //   carrying the fixed error finishReason (AC-34 requires exactly one error-signaling
        //   chunk, not that the whole response contain only one chunk ever) -- no leaked detail.
        assertThat(chunks).hasSize(2);
        assertThat(chunks).filteredOn(chunk -> ChatChunk.FINISH_REASON_ERROR.equals(chunk.finishReason()))
                .hasSize(1);

        ChatChunk partialChunk = chunks.get(0);
        assertThat(partialChunk.delta()).isEqualTo("일부 답");
        assertThat(partialChunk.finishReason()).isNull();

        ChatChunk errorChunk = chunks.get(1);
        assertThat(errorChunk.finishReason()).isEqualTo(ChatChunk.FINISH_REASON_ERROR);
        assertThat(errorChunk.conversationId()).isEqualTo(CONVERSATION_ID.toString());
        assertThat(errorChunk.delta()).doesNotContain(sensitiveDetail);
        assertThat(errorChunk.usage()).isNull();

        // and: the assistant turn was delegated with the partial content collected before the
        //   error, finishReason="error", and the same no-leak guarantee
        ArgumentCaptor<Message> assistantMessageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationService).persistAssistantTurn(assistantMessageCaptor.capture());
        Message assistantRow = assistantMessageCaptor.getValue();
        assertThat(assistantRow.getRole()).isEqualTo("assistant");
        assertThat(assistantRow.getContent()).isEqualTo("일부 답");
        assertThat(assistantRow.getContent()).doesNotContain(sensitiveDetail);
        assertThat(assistantRow.getFinishReason()).isEqualTo(ChatChunk.FINISH_REASON_ERROR);
    }

    @Test
    @DisplayName("정상 완료 시에는 오류 청크를 방출하지 않는다")
    void doesNotEmitErrorChunkOnNormalCompletion() {
        // given
        when(streamResponseSpec.chatResponse()).thenReturn(scriptedResponses(List.of("ok"), "stop"));
        Conversation conversation = conversationStub();

        // when
        List<ChatChunk> chunks = service.streamReply(conversation, null, "hi").collectList().block();

        // then
        assertThat(chunks).noneMatch(chunk -> ChatChunk.FINISH_REASON_ERROR.equals(chunk.finishReason()));
        verify(conversationService, never()).persistAssistantTurn(argThat(
                message -> message != null && ChatChunk.FINISH_REASON_ERROR.equals(message.getFinishReason())));
    }

    private static Conversation conversationStub() {
        Conversation conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(CONVERSATION_ID);
        // lenient(): getModel() is read after the enforceMessageQuota() check in streamReply(), so
        // propagatesMessageQuotaExceededWithoutCallingLlmOrPersistingAnything (chat:AC-45) never
        // reaches it -- same reasoning as setUp()'s lenient() stubs above.
        lenient().when(conversation.getModel()).thenReturn(MODEL);
        return conversation;
    }

    /**
     * Mirrors {@code support.StubChatModel#streamOf}'s shape: one {@link ChatResponse} per token
     * (no metadata), then a final response with empty text and {@code finishReason} set.
     */
    private static Flux<ChatResponse> scriptedResponses(List<String> tokens, String finishReason) {
        List<ChatResponse> chunks = new ArrayList<>();
        for (String token : tokens) {
            chunks.add(plainResponse(token));
        }
        Generation finishGeneration = new Generation(
                new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        chunks.add(new ChatResponse(List.of(finishGeneration)));
        return Flux.fromIterable(chunks);
    }

    private static ChatResponse plainResponse(String token) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(token))));
    }
}
