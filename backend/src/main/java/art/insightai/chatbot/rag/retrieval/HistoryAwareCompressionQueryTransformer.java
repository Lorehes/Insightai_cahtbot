package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * [Purpose] Decorates a library-provided {@link CompressionQueryTransformer} (hybrid-rag Tech
 *   Review T12) with three concerns {@link RagAdvisorConfig} used to own inline before this
 *   extraction: (1) a guard that skips the LLM call entirely when {@link Query#history()} carries
 *   no genuine previous turn, (2) a try/catch fallback to the original query on any delegate
 *   failure, and (3) a lightweight sanitize-and-cap pass on the delegate's output before it is
 *   allowed to reach the search axes as a control value.
 * [Rationale] 2026-08-30 재작업 3라운드 (리뷰 2R maintainability Medium-1): {@code RagAdvisorConfig}
 *   documented itself as "assembly only" (see that class's own Javadoc) while this bean method
 *   alone carried all three concerns above inline -- the exact opposite of how this same package
 *   already treats {@link RagEmptyContextGuardAdvisor} (custom logic extracted to its own
 *   top-level class the moment it stopped being trivial, leaving {@code RagAdvisorConfig} with a
 *   one-line {@code return new RagEmptyContextGuardAdvisor();}). This class restores that
 *   symmetry: {@link RagAdvisorConfig#compressionQueryTransformer} now only constructs the
 *   library delegate and wraps it here.
 *   <p>
 *   AC-9's own precondition is "이전 턴이 있는 대화에서" (Spec §4) -- {@link
 *   CompressionQueryTransformer#transform} itself has no such guard (spring-ai-rag 2.0.1 source:
 *   {@code formatConversationHistory} degrades an empty history to an empty string but the method
 *   still issues the LLM call regardless). Left unguarded, wiring this transformer into {@link
 *   RagAdvisorConfig#retrievalAugmentationAdvisor} would make {@code
 *   RetrievalAugmentationAdvisor.before()} invoke the model at its query-transformation step for
 *   *every* prompt -- including a brand-new conversation's first turn -- before document retrieval
 *   or {@link RagEmptyContextGuardAdvisor}'s zero-result short-circuit ever run (confirmed by
 *   reading {@code RetrievalAugmentationAdvisor.before()}'s source: {@code queryTransformers}
 *   always runs unconditionally, first). That would silently regress {@link RagAdvisorConfig}'s
 *   own AC-5 evidence: {@code RagAdvisorAssemblyTest.shortCircuitsWithoutEverInvokingTheModel}
 *   enqueues no {@code StubChatModel} script and relies on the model never being called when there
 *   is no history and zero documents come back.
 *   <p>
 *   <b>2026-08-30 재작업, 가드 조건 정정 (T12 라운드 1).</b> The original guard checked {@code
 *   query.history().isEmpty()}, which never holds for a single-turn request and broke {@code
 *   RagAdvisorAssemblyTest}'s AC-5 tests. Runtime dump (temporary {@code System.out.println}
 *   inside this logic's original home in {@code RagAdvisorConfig}'s bean lambda, removed after
 *   confirming, executed via {@code ./mvnw -o test
 *   -Dtest=RagAdvisorAssemblyTest#shortCircuitsWithoutEverInvokingTheModel} and a throwaway
 *   multi-turn test method in the same class, both since deleted) showed:
 *   <ul>
 *   <li>Single turn -- {@code chatClient().prompt(QUERY_TEXT).call()}, {@code
 *   RetrievalAugmentationAdvisor} attached only inside the test's own local assembly (T10 has not
 *   attached it to {@code ChatService} yet), no {@code .messages(...)} call anywhere in the
 *   request build: {@code history.size=1}, containing exactly one {@code USER} message (the
 *   current turn's own text). Confirmed by static reading too -- {@code
 *   RetrievalAugmentationAdvisor.before()} sets {@code Query.history(chatClientRequest.prompt()
 *   .getInstructions())} (source line 114), and {@code DefaultChatClientUtils
 *   .toChatClientRequest} (spring-ai-client-chat 2.0.1 source, lines 57-97) appends the current
 *   turn's user text as the *last* element of that same message list -- so {@code
 *   getInstructions()} is never just "prior turns", it always includes the message being answered
 *   right now.</li>
 *   <li>Multi turn -- same test-local assembly, request built via {@code chatClient().prompt()
 *   .messages(UserMessage, AssistantMessage).user(newText).call()}: {@code history.size=3} --
 *   {@code USER} (prior), {@code ASSISTANT} (prior), {@code USER} (current, last). The delegate
 *   was actually invoked (dump showed the expected {@code IllegalStateException} from {@code
 *   StubChatModel} for an unqueued script, proving the call reached the model rather than merely
 *   proving a boolean branch was taken).</li>
 *   </ul>
 *   Because {@code getInstructions()} always includes the current-turn message, {@code isEmpty()}
 *   can never be true and {@code size() == 1} is the only shape a genuinely first-turn request
 *   ever takes -- but {@code size() > 1} alone would also misfire on a first turn that happens to
 *   carry a system message (system text is prepended before the user text, per the same {@code
 *   toChatClientRequest} source), which is not "이전 턴" in the Spec's sense. {@link
 *   #hasPriorAssistantTurn} instead checks for the presence of an {@link MessageType#ASSISTANT}
 *   message in {@code history()} -- a completed prior turn is the only way an {@code ASSISTANT}
 *   message can be in that list, and a system message never has that type, so this is precise
 *   regardless of whether the request also carries system text. This keeps the AC-5 contract true
 *   (no assistant message in history -> no compression call -> the guard advisor still
 *   short-circuits on zero documents without ever touching the model) while satisfying AC-9
 *   exactly where it applies (a previous turn -- i.e. a prior assistant reply -- exists).
 *   <p>
 *   <b>2026-08-30 재작업 2라운드 (리뷰 Block High-1 performance).</b> {@code delegate.transform(query)}
 *   is wrapped in a try/catch that falls back to the original, uncompressed {@code query} on any
 *   {@link RuntimeException} (including a timeout surfaced as one -- Spring AI's default {@code
 *   spring.ai.openai.timeout} of 60s plus {@code max-retries=3} already bounds the call, confirmed
 *   against {@code AbstractOpenAiProperties} and this project's unmodified {@code
 *   application.yaml}, so this is not a hang-forever fix, only a propagation fix). This mirrors
 *   two decisions this Spec already made for the same category of risk: §8-D4 ("리랭크 실패 시 예외
 *   전파 대신 융합 결과로 폴백... 리랭크는 선택적 품질 향상 단계다. 외부 API 하나 때문에 대화 전체가
 *   실패하면 데모 중 사고가 된다", implemented at {@link HybridDocumentRetriever#retrieve}'s rerank
 *   catch block) and §8-D5 ("검색 실패 시 RAG 없이 대화 계속... 단 sources 는 빈 배열이어야 하며 «근거가
 *   있는 척» 하지 않는다", implemented at the same method's search catch block). Query compression is
 *   exactly the same category -- an optional quality step ahead of the mandatory search call -- so
 *   an unhandled exception here must not be allowed to fail the whole multi-turn response any more
 *   than a rerank or search failure is. Per D5's own precedent the failure is logged at {@code
 *   ERROR}, not {@code WARN}: a quietly-swallowed {@code WARN} would make it impossible to tell,
 *   from logs alone, "compression times out every call" apart from "compression legitimately never
 *   triggers" during D7 evaluation.
 * [Caution] This does not, and cannot, guarantee "0 model calls" once a conversation *does* have a
 *   prior assistant turn and its compressed query still returns 0 documents -- the compression
 *   call already happened by then. That scenario is outside every AC-5 test written so far (all
 *   history-free) and outside this card's own scope (AC-9 only); flagged here rather than silently
 *   assumed away.
 * [Caution] This transformer's compression call is outside {@code usage}/{@code cost_usd}
 *   aggregation (chat Spec's {@code ChatService}/{@code MessageChatMemoryRepository}
 *   usage-tracking path never sees the {@code ChatClient} the delegate was built from) and always
 *   runs against {@code CHAT_MODEL_DEFAULT} (the {@code ChatClient.Builder} {@link
 *   RagAdvisorConfig#compressionQueryTransformer} injects is the library's default autoconfigured
 *   chat model, not whatever model a given request selected) -- so a per-request model override
 *   does not apply to the compression step, and every compression call's token cost is currently
 *   unaccounted for in this project's cost dashboard. Registered as Spec §8 R-K (owner card 미정).
 * [Caution] {@code app.chat.memory.max-messages} (see {@link
 *   art.insightai.chatbot.common.config.AppProperties.Chat.Memory#maxMessages}) can be set as low
 *   as {@code 1} (its compact constructor's own floor). At that setting the chat-memory window
 *   {@code query.history()} is ultimately sourced from can never carry both a prior {@code
 *   ASSISTANT} reply and the current turn's {@code USER} message at once, so {@link
 *   #hasPriorAssistantTurn} is permanently {@code false} and this class never compresses --
 *   silently disabling AC-9 for the lifetime of that configuration, not merely degrading it. Not
 *   this card's scope to change (a config-level guard would belong to chat Spec's memory sizing),
 *   but the failure mode is easy to miss because it produces no error, only a silently
 *   uncompressed follow-up query.
 * [AC] hybrid-rag:AC-9
 */
class HistoryAwareCompressionQueryTransformer implements QueryTransformer {

    /**
     * [Rationale] A search query is expected to be short (this project's own retrieval axes
     *   accept whole sentences, not documents); {@code query} bounds an end user's original
     *   message at {@link
     *   art.insightai.chatbot.chat.api.ConversationController.SendMessageRequest#MAX_MESSAGE_LENGTH}
     *   (4000 chars) at the API boundary, but nothing bounds an LLM-generated compression result
     *   before this fix, and the compression prompt (see {@link CompressionQueryTransformer}'s
     *   {@code DEFAULT_PROMPT_TEMPLATE}) has no length instruction of its own. 500 is a generous
     *   multiple of a normal standalone query's expected length, chosen to be "obviously too short
     *   to smuggle a large instruction payload" without being tight enough to truncate a
     *   legitimate compressed query.
     *   <p>
     *   2026-08-30 재작업 3라운드 (리뷰 2R maintainability Low-1): package-private (not {@code
     *   private}) so {@code HistoryAwareCompressionQueryTransformerTest} can reference this
     *   constant directly instead of duplicating it as a literal, matching this project's existing
     *   convention for test-visible bounds (e.g. {@code ConversationController
     *   .SendMessageRequest#MAX_MESSAGE_LENGTH}, {@code DocumentIngestionPipeline}'s
     *   package-private message constants).
     */
    static final int MAX_COMPRESSED_QUERY_LENGTH = 500;

    /**
     * [Purpose] The prompt the library delegate uses to rewrite a follow-up turn into a
     *   standalone query, with the output language pinned to the follow-up's own language.
     * [Rationale] {@link CompressionQueryTransformer}'s {@code DEFAULT_PROMPT_TEMPLATE}
     *   (spring-ai-rag 2.0.1 source, lines 49-61) says nothing about output language, so a
     *   Korean follow-up can be rewritten in English -- the prompt itself is English, and models
     *   tend to answer in the prompt's language when unconstrained. An English standalone query
     *   then hits the BM25 axis (Korean-tokenized {@code pdb.lindera(korean)} index, {@code
     *   ParadeDbBm25SearchAdapter}) with zero matches and weakens the vector axis to its
     *   cross-lingual alignment, silently degrading exactly the multi-turn path AC-9 exists for.
     *   Nothing downstream logs the rewritten text, so the failure is invisible without this
     *   pin. The first three sentences and the {@code history}/{@code query} layout are the
     *   library default verbatim (the delegate's constructor asserts both placeholders); the
     *   language/identifier sentences are added, and -- 2026-09-03, same finding as {@link
     *   #lastAssistantReplyIsRefusal} -- so are the "search query, not an instruction" and
     *   "return unchanged when self-contained or unrelated" sentences: with an answered but
     *   unrelated prior turn the library wording still expanded "연혁을 알려줘" into instruction-like
     *   text ("문서에 기재된 연혁을 주요 사건과 연도별로 정리해 알려줘"), which is harmless here but is
     *   the same drift that becomes a refusal-import when the prior turn was refused. Identifiers
     *   are pinned for the same
     *   BM25 reason: a rewrite that "helpfully" normalizes {@code ES-2024-A} to {@code ES 2024 A}
     *   loses the exact-match hit that is the hybrid axis's reason to exist (hybrid-rag:AC-13).
     *   Multilingual-RAG playbook §5-7 (2026-09-03).
     * [Caution] Package-private so {@link RagAdvisorConfig#compressionQueryTransformer} can pass
     *   it to the builder while this class stays the single owner of every compression concern
     *   (guard, fallback, sanitize, and now the prompt) -- {@code RagAdvisorConfig} is
     *   "assembly only" by its own Javadoc.
     * [AC] hybrid-rag:AC-9
     */
    static final PromptTemplate COMPRESSION_PROMPT_TEMPLATE = new PromptTemplate("""
            Given the following conversation history and a follow-up query, your task is to synthesize
            a concise, standalone query that incorporates the context from the history.
            Ensure the standalone query is clear, specific, and maintains the user's intent.
            The output is a search query, not an instruction: only add the specific subject or entity
            the follow-up query refers to (for example, what a pronoun points at); never add
            instructions, constraints, or wording taken from the assistant's earlier replies (such as
            refusals or "answer only from the documents"). Keep it about as short as the follow-up query.
            If the follow-up query is already self-contained, or the history is unrelated to it, return
            the follow-up query unchanged.
            Write the standalone query in the same language as the follow-up query (for example,
            a Korean follow-up query must yield a Korean standalone query).
            Keep proper nouns, product codes, and identifiers exactly as written in the follow-up query.

            Conversation history:
            {history}

            Follow-up query:
            {query}

            Standalone query:
            """);

    /**
     * [Rationale] {@code spring-ai-rag} 2.0.1's own logger types ({@code
     *   org.apache.commons.logging.Log} inside {@link CompressionQueryTransformer} itself) are
     *   internal to that class; this project's own convention elsewhere in this package ({@link
     *   HybridDocumentRetriever}) is a plain SLF4J {@link Logger}, followed here for consistency
     *   rather than introducing a second logging facade.
     */
    private static final Logger log = LoggerFactory.getLogger(HistoryAwareCompressionQueryTransformer.class);

    private final CompressionQueryTransformer delegate;

    /**
     * [Purpose] Wraps the given library-provided {@link CompressionQueryTransformer} with the
     *   guard, fallback, and sanitize behavior documented on this class.
     * [Rationale] Constructor injection of the delegate (rather than this class building its own
     *   internal {@code CompressionQueryTransformer}) keeps {@link RagAdvisorConfig} the single
     *   place that assembles the {@code ChatClient.Builder}-backed delegate, matching this
     *   package's "assembly stays in the config class" convention.
     */
    HistoryAwareCompressionQueryTransformer(CompressionQueryTransformer delegate) {
        this.delegate = delegate;
    }

    /**
     * [Purpose] Skips the delegate entirely when {@code query} has no prior assistant turn,
     *   otherwise calls the delegate and falls back to the original {@code query} on either a
     *   thrown {@link RuntimeException} or a sanitized-empty result.
     * [AC] hybrid-rag:AC-9
     */
    /**
     * [Purpose] True when the most recent assistant message in {@code query.history()} is one of
     *   the fixed refusal phrases ({@link RefusalPhrases#isRefusal}).
     * [Rationale] 2026-09-03 (user-reported: "연혁을 알려줘" refused right after a refused
     *   off-topic turn, conversation bd09ad54). A refusal carries no referent the follow-up could
     *   depend on, so compression has nothing to resolve -- but the library rewrite still ran and,
     *   measured three times against the real model, absorbed the refusal as an instruction:
     *   "제공된 문서에 근거해 해당 기관의 연혁을 알려줘. 문서에서 확인할 수 없는 내용은 추측하지 말고 찾을 수
     *   없다고 답해줘." Those generic tokens (제공된/문서/확인/찾을 수 없다) match every chunk's context
     *   header and boilerplate, so the history pages fell out of the top 5 on all three runs
     *   (control without history: pages 3/2/5/15/22 incl. the history page; after the refusal:
     *   12/8/22/10/6, 3/21/12/2/13, 13/3/2/8/22), and the model refused again. Skipping the rewrite
     *   here is deterministic and costs nothing; the prompt change in
     *   {@link #COMPRESSION_PROMPT_TEMPLATE} covers the same failure when the prior turn was
     *   answered but is unrelated.
     * [Caution] Only the LAST assistant message is inspected: a refusal two turns back followed by
     *   a real answer still leaves a referent the follow-up may depend on.
     * [AC] hybrid-rag:AC-9
     */
    private static boolean lastAssistantReplyIsRefusal(Query query) {
        List<Message> history = query.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message.getMessageType() == MessageType.ASSISTANT) {
                return RefusalPhrases.isRefusal(message.getText());
            }
        }
        return false;
    }

    @Override
    public Query transform(Query query) {
        if (!hasPriorAssistantTurn(query) || lastAssistantReplyIsRefusal(query)) {
            return query;
        }
        Query compressed;
        try {
            compressed = delegate.transform(query);
        } catch (RuntimeException e) {
            // T10 재작업 2라운드 (2026-08-30, 리뷰 2R security Low-1): reverted round 1's fix here --
            // that change replaced this call with e.getClass().getName()/e.getMessage() as separate
            // string parameters specifically to avoid writing e's message into the log, but the
            // review found it did not achieve that goal: e.getMessage() was still passed and still
            // logged in full, so the one thing this change was meant to stop leaking was never
            // actually stopped, while the one thing it did remove -- the stack trace -- is what
            // this project's other fallback logs (e.g. HybridDocumentRetriever's rerank/search catch
            // blocks) all keep, and is what a real operator needs to tell "compression times out
            // every call" apart from "compression legitimately never triggers" during D7 evaluation
            // (this class's own Javadoc, unchanged, already makes that same argument for logging at
            // ERROR rather than WARN). Logging e itself restores that debugging value. The
            // theoretical leak risk this round-1 change was reacting to (an upstream response
            // echoing part of the prompt back into e.getMessage()) is not addressed in code here --
            // per this card's coordinator, it is recorded as a low-priority Spec §8 risk instead.
            log.error("질의 압축 실패, 원본 질의로 진행 (queryTextLength={})", query.text().length(), e);
            return query;
        }
        return sanitizeCompressedQuery(query, compressed);
    }

    /**
     * [Purpose] Determines whether {@link Query#history()} contains a completed previous
     *   conversational turn, as opposed to merely the current turn's own message (always present,
     *   see this class's own Javadoc for the runtime-confirmed shape).
     * [Rationale] 2026-08-30 재작업 2라운드, correctness/maintainability Low -- softened from an
     *   earlier, over-broad "the only way" claim: no system or few-shot message this codebase
     *   builds anywhere carries {@code MessageType.ASSISTANT}, so a message of that type in {@code
     *   history()} is, in practice today, only ever a prior model reply. An {@code ASSISTANT}
     *   message is never how the current turn's own user message is represented either way, so its
     *   presence remains a reliable signal of "이전 턴이 있는 대화" (AC-9's own wording) for as long
     *   as that composition holds -- but this is an empirical property of this project's prompt
     *   assembly, not a guarantee {@link MessageType} itself makes; a future change that injects an
     *   {@code ASSISTANT}-typed message for a non-reply purpose (e.g. a few-shot example) would
     *   need to revisit this guard.
     */
    private static boolean hasPriorAssistantTurn(Query query) {
        return query.history().stream().anyMatch(message -> message.getMessageType() == MessageType.ASSISTANT);
    }

    /**
     * [Purpose] Applies a lightweight defense (2026-08-30 재작업 2라운드, security Medium-2 + Low-1
     *   merged) against {@link CompressionQueryTransformer}'s output becoming a second
     *   prompt-injection entry point once it flows, unvalidated, into {@code
     *   query.mutate().text(...)} and from there into the search axes as a control value.
     * [Rationale] This Spec's §8 R-H already names the underlying risk class (untrusted text
     *   reaching a control position) for the original, user-authored query; this transformer
     *   creates a *second* instance of the same class by handing an LLM's own free-form output
     *   that same control role. A full fix (a dedicated, hardened {@code promptTemplate} for the
     *   compression call itself) is out of this card's scope. Registered as Spec §8 R-J (owner
     *   card 미정). What this method does instead: strips ISO control characters (C0/C1 -- line
     *   breaks, tabs, and other {@link Character#isISOControl(char)} code points; this predicate
     *   does not cover non-ISO-control format separators such as U+2028/2029 or zero-width
     *   characters, so this defense is scoped to C0/C1 only) that have no legitimate place in a
     *   single-line search query but every legitimate place in a multi-line injected instruction,
     *   replacing each contiguous run of them with a single space (rather than deleting them
     *   outright) so two words a control character happened to separate (e.g. {@code "직원\n수"}) do
     *   not get silently fused into one token ({@code "직원수"}), then caps the result at {@link
     *   #MAX_COMPRESSED_QUERY_LENGTH} without splitting a UTF-16 surrogate pair at that boundary
     *   (2026-08-30 재작업 3라운드, security Low-1 -- see [Caution]).
     * [Caution] If sanitization empties the text entirely (control characters only, no visible
     *   content survives), this falls back to {@code original} rather than building a {@link
     *   Query} with blank text -- {@link Query}'s compact constructor rejects blank text outright
     *   ({@code Assert.hasText}), so silently producing one here would trade one failure mode (an
     *   unsanitized injection risk) for a worse one (an unhandled exception on every such response,
     *   defeating this same class's own fallback above).
     * [Caution] The truncation cut point itself never lands strictly inside a surrogate pair: if
     *   the character immediately before the {@link #MAX_COMPRESSED_QUERY_LENGTH}-th index is a
     *   high surrogate ({@link Character#isHighSurrogate(char)}), the cut is moved one position
     *   earlier so the pair either survives whole or is dropped whole, never split into two
     *   unpaired halves (which would otherwise corrupt the trailing character, e.g. an emoji, into
     *   an invalid code unit).
     */
    private static Query sanitizeCompressedQuery(Query original, Query compressed) {
        String text = compressed.text();
        StringBuilder builder = new StringBuilder(text.length());
        boolean lastAppendedWasSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) {
                if (!lastAppendedWasSpace) {
                    builder.append(' ');
                    lastAppendedWasSpace = true;
                }
                continue;
            }
            builder.append(c);
            lastAppendedWasSpace = c == ' ';
        }
        String sanitized = builder.toString().trim();
        if (sanitized.length() > MAX_COMPRESSED_QUERY_LENGTH) {
            int cutIndex = MAX_COMPRESSED_QUERY_LENGTH;
            if (Character.isHighSurrogate(sanitized.charAt(cutIndex - 1))) {
                cutIndex -= 1;
            }
            sanitized = sanitized.substring(0, cutIndex).trim();
        }
        if (sanitized.isEmpty()) {
            return original;
        }
        return compressed.mutate().text(sanitized).build();
    }
}
