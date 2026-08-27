package art.insightai.chatbot.chat.repository;

import art.insightai.chatbot.chat.domain.Conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [Purpose] Spring Data JPA repository for {@link Conversation}.
 * [Rationale] {@code findById(UUID)} inherited from {@link JpaRepository} covers T5's scope
 *   ("골격"). T6 adds the two ownership/soft-delete-aware finders Tech Review A-3 names:
 *   {@link #findByIdAndUserIdAndDeletedAtIsNull} folds ownership and soft-delete into a single
 *   query so {@code ConversationService} never has to compare {@code userId} in application code
 *   (chat:AC-14's isolation, T6's 404-for-everything ownership guard), and
 *   {@link #findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc} pushes the
 *   {@code updated_at DESC} ordering (chat:AC-14) into the query derivation instead of an
 *   in-memory sort. {@link #countByUserIdAndDeletedAtIsNull} backs the
 *   {@code app.session.max-conversations-per-user} check (T6 review 2026-08-27, Medium-2); it
 *   shares the same soft-delete exclusion as the two finders above so a deleted conversation frees
 *   up quota.
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<Conversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID userId);

    long countByUserIdAndDeletedAtIsNull(UUID userId);
}
