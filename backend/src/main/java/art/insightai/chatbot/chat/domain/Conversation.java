package art.insightai.chatbot.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * [Purpose] JPA entity for the {@code conversations} table — one row per chat thread owned by
 *   an anonymous user (chat Spec §5).
 * [Rationale] Column mapping follows {@code V1__init.sql} exactly (verified against the
 *   migration file at implementation time; {@code ddl-auto=validate} fails application startup
 *   on any mismatch). {@code id} uses {@code @GeneratedValue(strategy = GenerationType.UUID)}
 *   per Tech Review A-1 (id must stay hard to guess for AC-17 isolation) with the same
 *   {@code isNew()} reasoning as {@code user.User} — see that class's [Rationale].
 *   {@code userId} is mapped as a plain {@code UUID} column rather than a
 *   {@code @ManyToOne User} association: Tech Review A-3's ownership/soft-delete queries
 *   (e.g. {@code findByIdAndUserIdAndDeletedAtIsNull}) only ever compare the owning user's id,
 *   never navigate to other {@code User} fields, so a relation would add join/fetch overhead
 *   this Spec never uses.
 * [Caution] {@code title} starts {@code null} and is filled in once by {@code ChatService} on
 *   the first message (Tech Review A-12) — no card up to and including T8 implements that
 *   rule. {@code updatedAt} is bumped by application code on every message insert (A-12: no DB
 *   trigger); {@link #softDelete()}/{@link #updateRagEnabled} (T8) deliberately do not bump it
 *   either — see those methods' own [Caution]. Keeping a soft-deleted row invisible to callers
 *   (e.g. {@code GET /api/conversations}) is entirely a repository-query concern (T8 adds no
 *   {@code @Where}/global filter — Tech Review A-3); this entity only exposes {@link
 *   #softDelete()} to set the column. {@code @DynamicUpdate} (T8 review round 1, High-1) closes
 *   a lost-update bug: without it, Hibernate emits one static {@code UPDATE} naming every
 *   updatable column, so a {@code PATCH} that only meant to flip {@code ragEnabled} still carried
 *   the in-memory (possibly stale) {@code deletedAt} into that statement's {@code SET} clause —
 *   the {@code WHERE} matches on {@code id} alone, so a concurrently committed soft delete could
 *   be silently overwritten back to not-deleted regardless of commit order. With this annotation,
 *   Hibernate instead builds each {@code UPDATE} from only the fields dirtied on that specific
 *   entity instance; since {@link #softDelete()} and {@link #updateRagEnabled} each mutate
 *   exactly one field, neither statement's {@code SET} clause can carry the other's column
 *   anymore. This is not a general concurrency guarantee: two writers dirtying the same field
 *   still resolve last-commit-wins with no conflict detection, and the fix depends on every
 *   future entity method continuing to touch only the field(s) it owns per {@code save()} — a
 *   method that mutates {@code deletedAt} alongside another field would reopen this exact bug.
 *   There is a second, easier-to-miss precondition (T8 review round 2, correctness Medium-1):
 *   dirty checking always runs against the load-time snapshot held by the <em>current persistence
 *   context</em>, so this fix only holds while an entity is loaded, mutated and saved inside that
 *   same context. {@code spring.jpa.open-in-view} is {@code false} ({@code application.yaml}), so
 *   any {@code Conversation} handed out past its owning {@code @Transactional} method's return —
 *   e.g. the one {@link art.insightai.chatbot.chat.service.ConversationService#prepareMessageSend}
 *   returns to its controller (see that method's own [Caution]) — is detached. Passing a detached
 *   instance to {@code save()} later does not resume its original snapshot: Spring Data routes a
 *   non-new entity through {@code EntityManager.merge()}, which re-reads the row from the database
 *   into a new managed copy and then copies every field of the detached instance on top of it. If
 *   that detached instance's {@code deletedAt} is still the {@code null} it had at load time while
 *   the database row was soft-deleted by a concurrent request in between, the freshly-read
 *   snapshot now holds a non-null {@code deletedAt} and the copied-over {@code null} reads as
 *   dirty against it — {@code @DynamicUpdate} faithfully puts that dirty {@code null} into the
 *   {@code SET} clause, reproducing the 1R High-1 bug through a different door than a same-context
 *   two-field mutator would. No card up to and including T8 saves a detached {@code Conversation}
 *   this way — {@link #softDelete()} and {@link #updateRagEnabled}'s only caller each loads,
 *   mutates and saves inside one {@code @Transactional} method — but a future write path built on
 *   {@code prepareMessageSend}'s returned entity (T9 is the next card touching it) must re-load
 *   inside its own {@code @Transactional} method rather than saving that returned instance
 *   directly. {@code @Version} optimistic locking would close both this gap and the same-field
 *   race described above, but needs a schema migration (G5 approval), so it remains a follow-up
 *   rather than folded into this fix; to be precise about what it would and would not do here — it
 *   would not stop {@code deletedAt} from landing in a merge's {@code SET} clause the way {@code
 *   @DynamicUpdate} prevents that for a same-context save, but Hibernate's merge compares the
 *   detached instance's version against the version it just re-read for the new managed copy
 *   before that {@code UPDATE} is issued, and a concurrent soft delete would already have bumped
 *   that row's version — so the merge would fail fast with {@code OptimisticLockException} instead
 *   of silently committing the stale {@code null}.
 * [AC] chat:AC-16, chat:AC-14
 */
@Entity
@Table(name = "conversations")
@DynamicUpdate
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "rag_enabled", nullable = false)
    private boolean ragEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /** [Purpose] Required by JPA; not for application use. */
    protected Conversation() {
    }

    /**
     * [Purpose] Creates a new conversation row. {@code id} is left unset for Hibernate to
     *   populate on insert (see class-level [Rationale]); {@code title} starts {@code null}
     *   (A-12) and {@code deletedAt} starts {@code null} (not soft-deleted).
     *   {@code createdAt}/{@code updatedAt} are both stamped to the same instant at creation.
     */
    public Conversation(UUID userId, String model, boolean ragEnabled) {
        this.userId = userId;
        this.model = model;
        this.ragEnabled = ragEnabled;
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getModel() {
        return model;
    }

    public boolean isRagEnabled() {
        return ragEnabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    /**
     * [Purpose] Marks this conversation as soft-deleted by stamping {@code deletedAt} to now.
     * [Rationale] Physical delete is out of scope by design (chat Spec §7 OQ-13, Tech Review
     *   A-3): {@code conversations} and {@code messages} rows must survive a {@code DELETE} call
     *   (chat:AC-16) so the later {@code eval-usage-dashboard} Spec's cost aggregation can still
     *   see them. This method only sets the column — excluding a soft-deleted row from callers'
     *   results is entirely a repository-query concern (e.g. {@code
     *   findByIdAndUserIdAndDeletedAtIsNull}), not something this entity enforces on itself.
     * [Caution] Does not touch {@code updatedAt} — Tech Review A-12 ties that column's bump to
     *   message-insert time in application code, not to conversation-level mutations, so this
     *   method deliberately leaves it alone. Calling this twice simply overwrites {@code
     *   deletedAt} with a later timestamp; in practice {@code ConversationService} never reaches
     *   this a second time for the same row because its ownership guard already excludes
     *   already-soft-deleted rows before this method would ever run again (chat:AC-41).
     * [AC] chat:AC-16
     */
    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    /**
     * [Purpose] Updates {@code ragEnabled}.
     * [Rationale] Backs {@code PATCH /api/conversations/{id}} (chat Spec §5 A-11, D-6, §7
     *   OQ-14, T8) — {@code ragEnabled} is the only field that endpoint may change; OQ-14 was
     *   resolved with the toggle kept conversation-scoped rather than a per-message override.
     * [Caution] Does not touch {@code updatedAt} — same reasoning as {@link #softDelete()}'s
     *   [Caution].
     */
    public void updateRagEnabled(boolean ragEnabled) {
        this.ragEnabled = ragEnabled;
    }
}
