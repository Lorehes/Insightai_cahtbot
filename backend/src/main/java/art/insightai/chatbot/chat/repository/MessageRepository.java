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
 *   order); {@code ChatService} remains the single writer of this table (Tech Review A-4), this
 *   finder is read-only. T10's {@code MessageChatMemoryRepository} is expected to reuse the same
 *   finder rather than duplicate it.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
