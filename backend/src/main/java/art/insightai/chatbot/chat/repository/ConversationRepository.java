package art.insightai.chatbot.chat.repository;

import art.insightai.chatbot.chat.domain.Conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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
 *   up quota. T9 adds {@link #touchUpdatedAt}/{@link #assignTitleIfAbsent} as bulk {@code
 *   @Modifying} queries rather than {@code find-mutate-save} — see each method's own [Rationale].
 *   Neither carries its own {@code @Transactional} (T9 rework 2026-08-27, maintainability
 *   Medium-1 — an earlier attempt placed it here, which worked but put the transaction boundary
 *   in the Repository layer instead of Service, contrary to global CLAUDE.md §7.1): both are
 *   {@code @Modifying} bulk updates and therefore still require an active transaction to run at
 *   all (a custom {@code @Modifying @Query} method declared directly on this interface inherits
 *   no transaction from {@link JpaRepository}'s {@code SimpleJpaRepository} implementation the
 *   way {@code save()}/{@code delete()} do), but that transaction is now opened by {@code
 *   ConversationService#persistUserTurn}/{@code #persistAssistantTurn} — two {@code public
 *   @Transactional} methods on a different bean that {@code ChatService} calls into — rather than
 *   by these methods themselves. Calling either method here directly, outside one of those two
 *   {@code ConversationService} methods (or another {@code @Transactional} context), still fails
 *   with {@code jakarta.persistence.TransactionRequiredException}.
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<Conversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID userId);

    long countByUserIdAndDeletedAtIsNull(UUID userId);

    /**
     * [Purpose] Lists every non-soft-deleted conversation's id, across all users — chat Spec
     *   Tech Review A-4's {@code MessageChatMemoryRepository#findConversationIds} table entry
     *   ("conversations 에서 deleted_at IS NULL 인 id 목록"), T10.
     * [Rationale] No existing finder returns ids across every user — {@link
     *   #findByIdAndUserIdAndDeletedAtIsNull} and {@link
     *   #findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc} above are both scoped to one {@code
     *   userId}, but {@code ChatMemoryRepository#findConversationIds} has no per-user notion at
     *   all — so a dedicated query is needed. A projection {@code @Query} (selects just {@code
     *   c.id}, not full {@link Conversation} entities) rather than a derived {@code
     *   findByDeletedAtIsNull()} returning {@code List<Conversation>}, since the only caller
     *   ({@code MessageChatMemoryRepository}) needs ids alone.
     */
    @Query("select c.id from Conversation c where c.deletedAt is null")
    List<UUID> findIdsByDeletedAtIsNull();

    /**
     * [Purpose] Bumps {@code updated_at} to {@code updatedAt} for conversation {@code id} (chat
     *   Spec Tech Review A-12: bumped in application code on every {@code messages} INSERT, no DB
     *   trigger).
     * [Rationale] A bulk {@code UPDATE} instead of {@code findById} + entity mutation + {@code
     *   save} on purpose: {@link Conversation}'s class-level [Caution] documents that saving a
     *   detached instance — unavoidable here, since this is called both from a synchronous
     *   prelude and from inside a {@code Flux} operator that may run on a different thread after
     *   that prelude's own request handling has finished (chat Spec Tech Review A-8) — routes
     *   through {@code EntityManager.merge()}, which re-reads the row and copies every field of
     *   the detached instance on top of it: the exact lost-update mechanism chat T8 review round
     *   1 High-1 fixed for the synchronous controller-thread case. A single-column bulk {@code
     *   UPDATE} touches only {@code updated_at} at the SQL level, so it cannot clobber a
     *   concurrently written column (e.g. a racing {@code DELETE}'s {@code deleted_at}) regardless
     *   of what any Java-side snapshot believes.
     * [Caution] This method has no {@code @Transactional} of its own (T9 rework 2026-08-27,
     *   maintainability Medium-1 — see this interface's class-level [Rationale] for why an earlier
     *   attempt placing it here was reverted). As a bulk {@code @Modifying} query, it still
     *   requires an active transaction to run: call it only from inside {@code
     *   ConversationService#persistUserTurn}/{@code #persistAssistantTurn} (or another {@code
     *   @Transactional} context) — never add {@code @Transactional} back to this interface method
     *   itself, and never call it from a same-class {@code this} reference on a caller that
     *   expects Spring AOP to intercept it (self-invocation bypasses the proxy regardless of where
     *   the annotation lives).
     */
    @Modifying
    @Query("update Conversation c set c.updatedAt = :updatedAt where c.id = :id")
    int touchUpdatedAt(@Param("id") UUID id, @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * [Purpose] Sets {@code title} for conversation {@code id} only while it is still {@code
     *   null} (chat Spec Tech Review A-12/OQ-4: first user message, truncated to 30 chars).
     * [Rationale] The {@code and c.title is null} clause makes "only when absent" an atomic,
     *   database-level condition instead of a Java-side {@code if (title == null)} check that
     *   could race a concurrent call for the same conversation — at most one concurrent caller's
     *   {@code UPDATE} can ever match the row (whichever commits first turns {@code title}
     *   non-null, which excludes every later attempt). Same bulk-update reasoning as {@link
     *   #touchUpdatedAt} for why this is not {@code find-mutate-save}. {@code ChatService} skips
     *   calling this once it already knows (from the {@link Conversation} it already holds)
     *   {@code title} is non-null (T9 rework, performance L12) — the two concurrent-first-message
     *   callers that still race each other both still see a {@code null} title at that point and
     *   both still call through to this atomic {@code UPDATE}, so that race guarantee is
     *   unaffected; only the already-titled, common case skips the now-redundant zero-row
     *   {@code UPDATE}.
     * [Caution] No {@code @Transactional} of its own — same reason and same required caller shape
     *   as {@link #touchUpdatedAt}'s own [Caution]; see that method for the full explanation.
     */
    @Modifying
    @Query("update Conversation c set c.title = :title where c.id = :id and c.title is null")
    int assignTitleIfAbsent(@Param("id") UUID id, @Param("title") String title);
}
