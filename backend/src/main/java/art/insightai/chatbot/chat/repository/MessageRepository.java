package art.insightai.chatbot.chat.repository;

import art.insightai.chatbot.chat.domain.Message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Spring Data JPA repository for {@link Message}.
 * [Rationale] {@code findById(Long)} inherited from {@link JpaRepository} covers T5's scope
 *   ("골격"). T6 adds {@link #findByConversationIdOrderByCreatedAtAsc} for
 *   {@code GET /api/conversations/{id}/messages} (chat:AC-15's {@code created_at} ascending
 *   order); {@code ChatService} remains the sole originator of writes to this table (Tech Review
 *   A-4 — see {@link Message}'s own class-level [Caution] for how that holds even though {@code
 *   ConversationService} now issues the literal {@code save()} call), this finder is read-only.
 *   T9 adds {@link #countByConversationIdAndRole}, backing the {@code
 *   app.session.max-messages-per-conversation} check (T9 review 2026-08-27, security Medium-3) —
 *   counting only {@code role='user'} rows ties the ceiling to the number of paid OpenRouter
 *   calls a conversation has triggered, one 'assistant' row per 'user' row notwithstanding. T10's
 *   {@code MessageChatMemoryRepository} is expected to reuse {@link
 *   #findByConversationIdOrderByCreatedAtAsc} rather than duplicate it.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    long countByConversationIdAndRole(UUID conversationId, String role);
}
