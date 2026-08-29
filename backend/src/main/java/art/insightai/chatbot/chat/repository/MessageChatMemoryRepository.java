package art.insightai.chatbot.chat.repository;

import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.common.config.AppProperties;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Read-only adapter that lets Spring AI's {@code MessageWindowChatMemory} read
 *   multi-turn history straight out of the {@code messages}/{@code conversations} tables —
 *   chat Spec Tech Review A-4 ("안전장치 #4"), T10.
 * [Rationale] Implements {@link ChatMemoryRepository}'s four methods exactly as Tech Review
 *   A-4's table assigns them:
 *   <ul>
 *   <li>{@link #findByConversationId} reads {@code messages} (via {@link MessageRepository},
 *   reused rather than duplicated per that interface's own class-level [Rationale] — "T10's
 *   MessageChatMemoryRepository is expected to reuse findByConversationIdOrderByCreatedAtAsc
 *   rather than duplicate it") and maps each row to a {@code UserMessage}/{@code
 *   AssistantMessage}. The "최근 N건" (most-recent-N) windowing this method performs in Java —
 *   trimming the full ascending list down to its last {@code
 *   appProperties.chat().memory().maxMessages()} elements — matters because {@code
 *   MessageWindowChatMemory#get} does NOT itself apply any window: reading that class's own
 *   source (spring-ai-model-2.0.1-sources.jar) shows {@code get(conversationId)} is a one-line
 *   delegation straight to {@code chatMemoryRepository.findByConversationId(conversationId)},
 *   with no trimming — the eviction logic in {@code MessageWindowChatMemory} lives entirely
 *   inside {@code add()} (merge, trim, {@code saveAll()}), a method this card's design never
 *   calls (see {@link #saveAll} below). Windowing therefore has to happen here or nowhere.</li>
 *   <li>{@link #findConversationIds} reads {@code conversations} (via {@link
 *   ConversationRepository#findIdsByDeletedAtIsNull}) per Tech Review A-4's table. No code path
 *   in this card actually calls it — neither {@code get} nor {@code add} on {@code
 *   MessageWindowChatMemory} do — but it is still required to satisfy the {@code
 *   ChatMemoryRepository} interface contract.</li>
 *   <li>{@link #saveAll} and {@link #deleteByConversationId} are <b>intentional no-ops</b> — the
 *   reasons are pinned here, per Tech Review A-4 and Spec risk N-6, so a maintainability review
 *   does not mistake either for an unfinished implementation:
 *   <ol>
 *   <li>chat:AC-29 requires exactly one {@code role='user'} row and exactly one {@code
 *   role='assistant'} row per request. {@code ChatService} already writes both (via {@code
 *   ConversationService#persistUserTurn}/{@code #persistAssistantTurn}) — if {@code saveAll}
 *   also wrote rows, every request would double-insert.</li>
 *   <li>Spring AI's own default JDBC {@code ChatMemoryRepository} implementation deletes and
 *   re-inserts an entire conversation's rows on every {@code saveAll} call. Doing that to
 *   {@code messages} would destroy {@code prompt_tokens}/{@code cost_usd}/{@code created_at}/
 *   {@code id} on every row it touches — chat:AC-32/AC-33 would break.</li>
 *   <li>{@code usage.cost} is only known once the stream completes; {@code ChatMemory.add()}
 *   (the only path that would ever reach {@code saveAll}) has no such value available even if
 *   this card wired it up.</li>
 *   </ol>
 *   Neither method is ever exercised in production: {@code ChatService} calls only {@code
 *   chatMemory.get(...)}, never {@code chatMemory.add(...)}/{@code clear(...)} — Tech Review A-4
 *   explicitly rejects registering a {@code MessageChatMemoryAdvisor} (the one thing that would
 *   call {@code add()} automatically) for exactly this reason.</li>
 *   </ul>
 *   This class sits in {@code chat/repository/} and is annotated {@link Repository} like its two
 *   peers, but — unlike them — it is a hand-written adapter over a third-party SPI ({@code
 *   ChatMemoryRepository}), not a Spring Data JPA interface, so it calls both {@link
 *   MessageRepository} and {@link ConversationRepository} directly. That cross-repository call is
 *   normally out of bounds for this project's Repository/DAO layer (global CLAUDE.md §7.1), but is
 *   unavoidable here: {@code ChatMemoryRepository}'s four-method contract inherently spans both
 *   tables (one conversation-scoped message read, one all-conversation id listing), and Tech
 *   Review A-4 places all four methods on one class rather than splitting the SPI across two. No
 *   business logic (quota checks, ownership, soft-delete decisions) lives here — only the
 *   read-shape translation the SPI requires.
 * [Caution] This class's presence as a {@link ChatMemoryRepository}-typed bean satisfies Spring
 *   AI's own {@code ChatMemoryAutoConfiguration}'s {@code @ConditionalOnMissingBean} on its
 *   {@code chatMemoryRepository()} method (so the library's default {@code
 *   InMemoryChatMemoryRepository} is never created), but that autoconfiguration's *second* bean
 *   method, {@code chatMemory(ChatMemoryRepository)}, still fires — nothing in this codebase
 *   defines its own {@code ChatMemory} {@code @Bean} (T10 builds the one actually used, in {@code
 *   ChatService}'s constructor, as a plain field, never a {@code @Bean}) — producing one extra,
 *   orphaned {@code MessageWindowChatMemory} bean (default {@code maxMessages=20}) that wraps this
 *   same class. Confirmed harmless by reading both autoconfiguration classes directly
 *   (spring-ai-autoconfigure-model-chat-memory-2.0.1 and
 *   spring-ai-autoconfigure-model-chat-client-2.0.1 sources): {@code ChatClientAutoConfiguration}
 *   never wires any memory advisor into the default {@code ChatClient.Builder}, and nothing else
 *   in this codebase {@code @Autowired}s {@code ChatMemory} — so the orphan bean is never invoked,
 *   and even if it somehow were, its {@code add()} would still resolve to this class's no-op
 *   {@link #saveAll}. {@link #toSpringAiMessage} throws {@link IllegalStateException} for any
 *   {@code messages.role} value other than {@link Message#ROLE_USER}/{@link
 *   Message#ROLE_ASSISTANT} — unreachable today (nothing else ever writes a different value) but
 *   fails fast instead of silently mis-mapping if that ever changes.
 * [AC] chat:AC-30, chat:AC-31
 */
@Repository
public class MessageChatMemoryRepository implements ChatMemoryRepository {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AppProperties appProperties;

    public MessageChatMemoryRepository(ConversationRepository conversationRepository,
            MessageRepository messageRepository, AppProperties appProperties) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.appProperties = appProperties;
    }

    /**
     * [Purpose] Lists every non-soft-deleted conversation id, as required by {@code
     *   ChatMemoryRepository}'s contract (Tech Review A-4).
     * [Side effects] None — read-only.
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> findConversationIds() {
        return conversationRepository.findIdsByDeletedAtIsNull().stream()
                .map(UUID::toString)
                .toList();
    }

    /**
     * [Purpose] Returns {@code conversationId}'s most recent {@code
     *   appProperties.chat().memory().maxMessages()} {@code messages} rows, oldest first, mapped
     *   to Spring AI {@code Message} instances — the history {@code ChatService} threads into the
     *   LLM prompt via {@code .messages(...)}.
     * [Rationale] See class [Rationale] for why the window is applied here, in Java, rather than
     *   in the reused {@link MessageRepository#findByConversationIdOrderByCreatedAtAsc} query
     *   itself.
     * [Caution] Assumes {@code conversationId} is a valid UUID string — true for every call this
     *   codebase actually makes (always {@code conversation.getId().toString()}, from {@code
     *   ChatService}); a malformed value throws {@link IllegalArgumentException} from {@link
     *   UUID#fromString}, not a graceful empty result.
     * [AC] chat:AC-30
     */
    @Override
    @Transactional(readOnly = true)
    public List<org.springframework.ai.chat.messages.Message> findByConversationId(String conversationId) {
        List<Message> rows =
                messageRepository.findByConversationIdOrderByCreatedAtAsc(UUID.fromString(conversationId));
        int maxMessages = appProperties.chat().memory().maxMessages();
        int fromIndex = Math.max(0, rows.size() - maxMessages);
        return rows.subList(fromIndex, rows.size()).stream()
                .map(MessageChatMemoryRepository::toSpringAiMessage)
                .toList();
    }

    /**
     * [Purpose] Intentional no-op — see class [Rationale] for the three reasons {@code
     *   ChatService} must remain the only writer of {@code messages}.
     * [Side effects] None. {@code messages} is never touched by this method.
     */
    @Override
    public void saveAll(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
        // Deliberate no-op -- see class [Rationale] (Tech Review A-4, Spec risk N-6).
    }

    /**
     * [Purpose] Intentional no-op — deletion is {@code ConversationService}'s soft-delete
     *   responsibility (Tech Review A-3), not this adapter's.
     * [Side effects] None. Neither {@code messages} nor {@code conversations} is touched.
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        // Deliberate no-op -- see class [Rationale] (Tech Review A-4); deletion is
        // ConversationService's soft-delete responsibility (Tech Review A-3), never this class's.
    }

    private static org.springframework.ai.chat.messages.Message toSpringAiMessage(Message row) {
        return switch (row.getRole()) {
            case Message.ROLE_USER -> new UserMessage(row.getContent());
            case Message.ROLE_ASSISTANT -> new AssistantMessage(row.getContent());
            default -> throw new IllegalStateException("Unexpected messages.role value: " + row.getRole());
        };
    }
}
