package art.insightai.chatbot.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
 *   the first message (Tech Review A-12) — this card does not implement that rule.
 *   {@code updatedAt} is bumped by application code on every message insert (A-12: no DB
 *   trigger); this entity does not enforce that itself since no AC in this card exercises it.
 *   Soft delete (A-3) is a repository-level query condition, not a field on this entity beyond
 *   the plain {@code deletedAt} column.
 */
@Entity
@Table(name = "conversations")
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
}
