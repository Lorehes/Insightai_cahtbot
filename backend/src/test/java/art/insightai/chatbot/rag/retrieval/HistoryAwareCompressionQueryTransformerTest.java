package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import art.insightai.chatbot.support.StubChatModel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * [Purpose] Unit-level verification of {@link HistoryAwareCompressionQueryTransformer}
 *   (hybrid-rag Tech Review T12) -- the class is exercised directly, no Spring context and no
 *   {@code RetrievalAugmentationAdvisor} chain, mirroring how {@link RagAdvisorAssemblyTest} calls
 *   {@link RagAdvisorConfig}'s other {@code @Bean} methods as plain Java rather than
 *   reimplementing them.
 * [Rationale] This is deliberately *not* an end-to-end test. As of this card, {@code
 *   RetrievalAugmentationAdvisor} (T9's {@link RagAdvisorConfig#retrievalAugmentationAdvisor}) is
 *   still unattached to any {@code ChatClient} in production ({@code ChatService}'s constructor
 *   calls {@code chatClientBuilder.build()} with no {@code .advisors(...)}, still T10's scope per
 *   the Spec's own 2026-08-30 실측 정정) -- so {@code Query.history()} is never populated by a real
 *   request anywhere in this codebase yet. Confirming "given a {@link Query} whose {@code
 *   history()} already carries a previous turn, the transformer rewrites {@code text()} to a
 *   compressed, history-aware standalone query" is the strongest claim obtainable stand-alone; the
 *   Spec leaves the AC-9 ledger entry (whole vs. "T12 부분 / T10 최종" split, precedent: AC-5,
 *   AC-13, AC-14) to the coordinator once a real attached-chain request becomes possible.
 * [Caution] {@link #skipsTheModelCallEntirelyWhenHistoryIsCompletelyEmpty()}, {@link
 *   #skipsTheModelCallOnProductionShapedFirstTurn()}, and {@link
 *   #doesNotMisfireWhenHistoryContainsOnlySystemMessage()} each enqueue *nothing* into {@link
 *   StubChatModel} before calling {@code transform} -- the same technique {@code
 *   RagAdvisorAssemblyTest.shortCircuitsWithoutEverInvokingTheModel} uses (a queued-script
 *   underrun throws {@link IllegalStateException} loudly instead of silently producing a
 *   wrong-but-plausible result) -- because this guard (2026-08-30 재작업 정정: checks for a prior
 *   {@code ASSISTANT} message in {@code history()}, not mere emptiness -- see {@link
 *   HistoryAwareCompressionQueryTransformer}'s Javadoc for the runtime evidence behind that
 *   condition) is exactly what keeps that AC-5 test passing once this class is wired into {@link
 *   RagAdvisorConfig#retrievalAugmentationAdvisor} (see that bean's own Javadoc for why an
 *   unguarded {@code CompressionQueryTransformer} would otherwise call the model on every prompt,
 *   including history-free ones). **2026-08-30 QA (correctness Medium-2, round 2 재작업 QA)**: an
 *   empty {@code List.of()} history (the first of these three tests) never actually occurs in
 *   production -- {@code RetrievalAugmentationAdvisor.before()} always populates {@code
 *   Query.history()} with at least the current turn's own {@code USER} message (see {@link
 *   HistoryAwareCompressionQueryTransformer}'s Javadoc for the runtime-confirmed shape) -- so
 *   that case alone has zero discriminating power against the very defect this round fixed
 *   (the old {@code isEmpty()} guard vs. the new {@code ASSISTANT}-presence guard agree on it,
 *   since a completely empty list trivially has neither). {@link
 *   #skipsTheModelCallOnProductionShapedFirstTurn()} closes that gap by using the actual
 *   production first-turn shape ({@code history.size()==1}, one {@code USER} message only) --
 *   the old {@code isEmpty()} guard would have returned {@code false} for this input and let the
 *   call through, throwing {@link IllegalStateException} against {@link StubChatModel}'s empty
 *   script queue, so this case is discriminating. {@link
 *   #doesNotMisfireWhenHistoryContainsOnlySystemMessage()} locks down the companion false-positive
 *   direction named in {@code HistoryAwareCompressionQueryTransformer}'s own guard Javadoc ("no
 *   system or few-shot message this codebase builds anywhere carries {@code
 *   MessageType.ASSISTANT}") by actually running a {@code SYSTEM} + current-turn {@code USER}
 *   history through the guard and confirming it still skips the model call, rather than leaving
 *   that claim as an unexercised assertion in prose.
 *   <p>
 *   <b>2026-08-30 재작업 2라운드 (리뷰 Block High-1 performance, security Medium-2/Low-1).</b> {@link
 *   #fallsBackToOriginalQueryWhenCompressionCallThrows()} deliberately reuses the same "no script
 *   queued" technique as {@link #skipsTheModelCallEntirelyWhenHistoryIsCompletelyEmpty()}, but with a
 *   history that *does* carry a prior {@code ASSISTANT} turn -- so the guard now lets the call
 *   through, {@link StubChatModel} throws {@link IllegalStateException} from inside {@code
 *   delegate.transform(query)}, and the transformer's try/catch (see {@link
 *   HistoryAwareCompressionQueryTransformer}'s Javadoc for the §8-D4/D5 rationale) must catch it
 *   and fall back to the original {@code query} rather than letting it propagate. {@link
 *   #sanitizesControlCharactersAndCapsLength()} covers the companion defense ({@link
 *   HistoryAwareCompressionQueryTransformer}'s Medium-2/Low-1 fix): a compressed result containing
 *   control characters and one far longer than {@link
 *   HistoryAwareCompressionQueryTransformer#MAX_COMPRESSED_QUERY_LENGTH} must come back with
 *   control-character runs collapsed to a single space and the total length capped.
 *   <p>
 *   <b>2026-08-30 재작업 3라운드 (리뷰 2R maintainability Medium-1/Low-1, security Low-1).</b> This
 *   file now targets {@link HistoryAwareCompressionQueryTransformer} directly (previously {@code
 *   RagAdvisorConfigCompressionTest} drove the same logic through {@code
 *   RagAdvisorConfig#compressionQueryTransformer} before that method's inline guard/fallback/
 *   sanitize logic was extracted to this standalone class) and references {@link
 *   HistoryAwareCompressionQueryTransformer#MAX_COMPRESSED_QUERY_LENGTH} directly (now
 *   package-private) instead of duplicating its value as a literal. {@link
 *   #capsLengthWithoutSplittingASurrogatePair()} is new: it covers the surrogate-pair-safe
 *   truncation fix (security Low-1) with a compressed result engineered so the naive
 *   {@code substring(0, MAX_COMPRESSED_QUERY_LENGTH)} cut point would fall strictly inside a
 *   surrogate pair (an emoji placed so its low surrogate is the character immediately after the
 *   cut boundary).
 *   <p>
 *   <b>2026-08-30 QA (correctness Medium-1, round 3 재작업 QA -- final round).</b> {@link
 *   #fallsBackToOriginalQueryWhenCompressionCallThrows()}, {@link
 *   #sanitizesControlCharactersAndCapsLength()}, and {@link
 *   #capsLengthWithoutSplittingASurrogatePair()} carried a {@code [hybrid-rag:AC-9]} tag that made
 *   the ledger's tagged-test count (8, this file's 7 plus {@code
 *   RagAdvisorAssemblyTest#searchesWithCompressedQueryOnMultiTurnRequest}'s 1) diverge from what
 *   the Spec §4 AC-9 evidence cell actually enumerates (6). None of the three supports AC-9's
 *   "절①" claim ("검색에 사용되는 질의 문자열이 원문과 달라지고") -- the first asserts the *opposite*
 *   (fallback leaves {@code text()} unchanged from the original query, which is a defense against
 *   §8-D4/D5's failure mode, not evidence the string changes), and the other two exercise a
 *   length-cap/control-character/surrogate-pair sanitization policy that AC-9's text never
 *   mentions. All three tags are removed here (same precedent as this Spec's
 *   {@code NonIndexedDocumentLookupTest.returnsEmptyListWhenNoRowsMatch}, T14 correctness Low-3);
 *   the {@code @DisplayName} text is left as an observational statement of what each test actually
 *   confirms, which it already was.
 */
class HistoryAwareCompressionQueryTransformerTest {

    private final StubChatModel stubChatModel = new StubChatModel();

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        transformerLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        transformerLogger().detachAppender(logAppender);
    }

    private static Logger transformerLogger() {
        return (Logger) LoggerFactory.getLogger(HistoryAwareCompressionQueryTransformer.class);
    }

    private QueryTransformer compressionQueryTransformer() {
        CompressionQueryTransformer delegate = CompressionQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(stubChatModel))
                .build();
        return new HistoryAwareCompressionQueryTransformer(delegate);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-9] 이전 턴이 있는 Query 를 압축하면 text() 가 원문과 달라지고 압축 결과 문자열로 대체된다 (단위 수준 부분 근거 -- 종단 검증은 T10, 아래 클래스 Javadoc 참고)")
    void compressesQueryWhenHistoryIsPresent() {
        // given -- previous turn discussed a 2023 acquisition, follow-up is pronoun-only
        Query query = Query.builder()
                .text("그럼 그때 직원은 몇 명이었어?")
                .history(
                        new UserMessage("2023년에 있었던 스타트업 인수 계약 내용이 뭐야?"),
                        new AssistantMessage("2023년 인수 계약은 A사가 B사를 인수하는 내용입니다."))
                .build();
        stubChatModel.enqueueTokens(
                List.of("2023년 인수 당시 B사의 ", "직원 수"), "stop", new StubChatModel.Usage(20, 8, null));

        // when
        Query transformed = compressionQueryTransformer().transform(query);

        // then
        assertThat(transformed.text()).isEqualTo("2023년 인수 당시 B사의 직원 수");
        assertThat(transformed.text()).isNotEqualTo(query.text());
    }

    @Test
    @DisplayName("[hybrid-rag:AC-9] 압축 호출 시 LLM 에 전달되는 프롬프트에 직전 턴의 핵심 명사와 원 질의가 모두 포함된다 (단위 수준 부분 근거 -- 종단 검증은 T10, 아래 클래스 Javadoc 참고)")
    void includesPreviousTurnContentInTheCompressionPrompt() {
        // given
        Query query = Query.builder()
                .text("그럼 그때 직원은 몇 명이었어?")
                .history(
                        new UserMessage("2023년에 있었던 스타트업 인수 계약 내용이 뭐야?"),
                        new AssistantMessage("2023년 인수 계약은 A사가 B사를 인수하는 내용입니다."))
                .build();
        stubChatModel.enqueueTokens(List.of("압축된 질의"), "stop", new StubChatModel.Usage(20, 8, null));

        // when
        compressionQueryTransformer().transform(query);

        // then -- CompressionQueryTransformer's DEFAULT_PROMPT_TEMPLATE fills {history}/{query}
        // into the single user message it sends (spring-ai-rag 2.0.1 source, read directly from
        // the local sources jar to confirm the placeholder names and formatting)
        Prompt capturedPrompt = stubChatModel.capturedPrompts().get(0);
        String sentText = capturedPrompt.getUserMessage().getText();
        assertThat(sentText).contains("스타트업 인수 계약");
        assertThat(sentText).contains("그럼 그때 직원은 몇 명이었어?");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-9] 완전히 빈 이력(history()==List.of())인 Query 는 모델을 호출하지 않는다 "
            + "(프로덕션에서는 발생하지 않는 형태 -- 판별력 참고용, 아래 두 테스트가 실제 판별 근거)")
    void skipsTheModelCallEntirelyWhenHistoryIsCompletelyEmpty() {
        // given -- no scripted response queued
        Query query = Query.builder().text("사내 정책이 뭐야?").history(List.of()).build();

        // when
        Query transformed = compressionQueryTransformer().transform(query);

        // then -- capturedPrompts() is asserted (not just the returned text) because
        // HistoryAwareCompressionQueryTransformer's try/catch would silently swallow a
        // delegate.transform() failure and also return the original query by reference -- so
        // checking only transformed.text()/isSameAs(query) would pass whether the guard skipped
        // the call or let it through and the call then failed. StubChatModel adds to
        // capturedPrompts() *before* it can throw (source: capturedPrompts.add(prompt) precedes
        // nextScript()), so an empty list here is direct evidence the model was never invoked at
        // all, immune to that fallback.
        assertThat(transformed.text()).isEqualTo("사내 정책이 뭐야?");
        assertThat(transformed).isSameAs(query);
        assertThat(stubChatModel.capturedPrompts()).isEmpty();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-9] 이전 턴 없는 프로덕션 첫 턴 형태(history 크기 1, 현재 턴 USER 메시지 하나뿐)인 Query 는 "
            + "모델을 호출하지 않는다 (isEmpty() 가드였다면 이 케이스에서 실패했을 것 -- 재작업 정정의 실제 판별력 증거)")
    void skipsTheModelCallOnProductionShapedFirstTurn() {
        // given -- mirrors RetrievalAugmentationAdvisor.before()'s real first-turn shape
        // (HistoryAwareCompressionQueryTransformer Javadoc, runtime dump: history.size=1,
        // containing only the current turn's own USER message). No scripted response queued.
        Query query = Query.builder()
                .text("사내 정책이 뭐야?")
                .history(new UserMessage("사내 정책이 뭐야?"))
                .build();

        // when
        Query transformed = compressionQueryTransformer().transform(query);

        // then -- see skipsTheModelCallEntirelyWhenHistoryIsCompletelyEmpty()'s comment for why
        // capturedPrompts() (not just the returned query) is the discriminating assertion: with
        // the old isEmpty() guard, history.isEmpty() is false here (size 1), so the call would
        // have gone through, StubChatModel would have thrown IllegalStateException from inside
        // nextScript(), and the round-2 try/catch would have caught it and returned the same
        // `query` reference anyway -- making transformed.text()/isSameAs(query) alone agree with
        // the correct behavior and hide the regression. capturedPrompts() does not have that
        // blind spot: it is populated the instant call()/stream() is invoked, before the throw.
        assertThat(transformed.text()).isEqualTo("사내 정책이 뭐야?");
        assertThat(transformed).isSameAs(query);
        assertThat(stubChatModel.capturedPrompts()).isEmpty();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-9] 이력에 SYSTEM 메시지만 있고 ASSISTANT 가 없으면(이전 턴 아님) 모델을 호출하지 않는다 "
            + "(시스템 메시지를 이전 턴으로 오인하는 false positive 를 배제)")
    void doesNotMisfireWhenHistoryContainsOnlySystemMessage() {
        // given -- a system message plus the current turn's own user message, no ASSISTANT
        // anywhere in history(); no scripted response queued
        Query query = Query.builder()
                .text("사내 정책이 뭐야?")
                .history(new SystemMessage("당신은 사내 챗봇입니다."), new UserMessage("사내 정책이 뭐야?"))
                .build();

        // when
        Query transformed = compressionQueryTransformer().transform(query);

        // then -- see skipsTheModelCallEntirelyWhenHistoryIsCompletelyEmpty()'s comment for why
        // capturedPrompts() is the discriminating assertion (the round-2 try/catch fallback would
        // otherwise mask a guard that let a SYSTEM-only history through as a false positive)
        assertThat(transformed.text()).isEqualTo("사내 정책이 뭐야?");
        assertThat(transformed).isSameAs(query);
        assertThat(stubChatModel.capturedPrompts()).isEmpty();
    }

    @Test
    @DisplayName("압축 호출이 예외를 던지면 원본 질의로 폴백하고 예외를 전파하지 않으며 ERROR 로그를 남긴다 "
            + "(§8-D4/D5와 동일한 폴백 원칙, 리뷰 Block High-1 performance -- 2026-08-30 correctness "
            + "Medium-1 QA로 [hybrid-rag:AC-9] 태그 제거, correctness Low-3 QA로 로그 레벨 단언 추가)")
    void fallsBackToOriginalQueryWhenCompressionCallThrows() {
        // given -- prior turn present so the guard lets the call through, but no script is queued
        // so StubChatModel throws IllegalStateException from inside delegate.transform(query)
        Query query = Query.builder()
                .text("그럼 그때 직원은 몇 명이었어?")
                .history(
                        new UserMessage("2023년에 있었던 스타트업 인수 계약 내용이 뭐야?"),
                        new AssistantMessage("2023년 인수 계약은 A사가 B사를 인수하는 내용입니다."))
                .build();

        // when
        Query transformed = compressionQueryTransformer().transform(query);

        // then -- no exception propagated, original query returned unchanged, and the catch
        // block's log.error(...) call actually produced an ERROR-level event (not merely a
        // reasonable-sounding claim in the catch block's own Javadoc -- same ListAppender
        // technique as HybridDocumentRetrieverTest's AC-8/AC-14 WARN/ERROR assertions)
        assertThat(transformed.text()).isEqualTo(query.text());
        assertThat(transformed).isSameAs(query);
        assertThat(logAppender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("압축 결과의 제어 문자는 공백으로 치환되고 500자를 초과하면 잘라낸다 "
            + "(security Medium-2/Low-1 병합 처리, 경량 방어 -- 2026-08-30 correctness Medium-1 QA로 "
            + "[hybrid-rag:AC-9] 태그 제거, AC-9 원문이 다루지 않는 길이상한/제어문자 정책이라서)")
    void sanitizesControlCharactersAndCapsLength() {
        // given -- previous turn present so compression runs; the LLM's scripted reply is
        // deliberately over-long (600 'a' characters) and interrupted by a newline+tab
        Query query = Query.builder()
                .text("그럼 그때 직원은 몇 명이었어?")
                .history(
                        new UserMessage("2023년에 있었던 스타트업 인수 계약 내용이 뭐야?"),
                        new AssistantMessage("2023년 인수 계약은 A사가 B사를 인수하는 내용입니다."))
                .build();
        String withControlChars = "직원\n수\t조회 " + "a".repeat(600);
        stubChatModel.enqueueTokens(List.of(withControlChars), "stop", new StubChatModel.Usage(20, 8, null));

        // when
        Query transformed = compressionQueryTransformer().transform(query);

        // then -- control-character runs became single spaces (words stay separated) and length
        // is capped at MAX_COMPRESSED_QUERY_LENGTH (referenced directly now that it is
        // package-private -- 2026-08-30 재작업 3라운드, maintainability Low-1)
        assertThat(transformed.text()).doesNotContain("\n", "\t");
        assertThat(transformed.text()).startsWith("직원 수 조회 ");
        assertThat(transformed.text()).hasSize(HistoryAwareCompressionQueryTransformer.MAX_COMPRESSED_QUERY_LENGTH);
    }

    @Test
    @DisplayName("500자 절단 시 서로게이트 쌍을 쪼개지 않는다 (security Low-1, 재작업 3라운드 -- 2026-08-30 "
            + "correctness Medium-1 QA로 [hybrid-rag:AC-9] 태그 제거, 같은 사유로 위 sanitizes 테스트와 동일 처리)")
    void capsLengthWithoutSplittingASurrogatePair() {
        // given -- previous turn present so compression runs; the scripted reply is engineered
        // so the naive substring(0, MAX_COMPRESSED_QUERY_LENGTH) cut point would fall strictly
        // inside a surrogate pair: 499 plain 'a' characters, then a 2-char-wide emoji (a
        // surrogate pair) straddling index 499/500, then more filler text.
        Query query = Query.builder()
                .text("그럼 그때 직원은 몇 명이었어?")
                .history(
                        new UserMessage("2023년에 있었던 스타트업 인수 계약 내용이 뭐야?"),
                        new AssistantMessage("2023년 인수 계약은 A사가 B사를 인수하는 내용입니다."))
                .build();
        String surrogatePair = "😀"; // U+1F600 GRINNING FACE, a high+low surrogate pair
        String engineered = "a".repeat(HistoryAwareCompressionQueryTransformer.MAX_COMPRESSED_QUERY_LENGTH - 1)
                + surrogatePair + "tail";
        stubChatModel.enqueueTokens(List.of(engineered), "stop", new StubChatModel.Usage(20, 8, null));

        // when
        Query transformed = compressionQueryTransformer().transform(query);

        // then -- the cut point moves one earlier rather than splitting the pair, so the result
        // is 499 chars long (the trailing high surrogate is dropped whole, not split) and does
        // not end with an unpaired (and therefore invalid) high surrogate
        String text = transformed.text();
        assertThat(text).hasSize(HistoryAwareCompressionQueryTransformer.MAX_COMPRESSED_QUERY_LENGTH - 1);
        assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1))).isFalse();
    }
}
