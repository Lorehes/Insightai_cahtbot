package art.insightai.chatbot.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * [Purpose] JPA entity for the {@code documents} table — one row per uploaded source document
 *   tracked through the ingestion pipeline (upload -> parse -> embed -> index).
 * [Rationale] Column mapping follows {@code V1__init.sql} exactly (verified against the
 *   migration file at implementation time, document-ingestion Spec Tech Review NR-1):
 *   {@code ddl-auto=validate} fails application startup on any mismatch, so no field here may
 *   drift from that DDL without a corresponding chat-Spec migration change. {@code id} and
 *   {@code createdAt} are assigned by the application — not left to the database's
 *   {@code gen_random_uuid()}/{@code now()} defaults — so a caller that needs the id before the
 *   row is flushed (e.g. the upload endpoint, T5, which writes the original file to
 *   {@code {upload-dir}/{document.id}} per AC-4) can read {@link #getId()} immediately after
 *   construction.
 * [Caution] {@code title} is mapped but never populated by this card (Tech Review AD-8): no AC
 *   requires a value, and inventing a fill rule would be a scope violation. Chunk deletion and
 *   count aggregation against {@code vector_store_bgem3} is T3's responsibility (JdbcTemplate,
 *   AD-1) — this entity does not reach into that table. This entity implements
 *   {@link Persistable} because {@code id} is application-assigned (see [Rationale] above):
 *   without it, Spring Data JPA's default {@code isNew()} check (id == null) always finds a
 *   non-null id and calls {@code EntityManager.merge()} instead of {@code persist()} on
 *   {@code save()}. {@code merge()} returns a *different*, managed instance and leaves the
 *   argument detached — a caller that discards the return value of {@code repository.save()}
 *   and keeps mutating the original reference (e.g. more {@link #transitionTo}/{@link
 *   #markIndexed} calls) loses those changes with no exception. Always use the instance
 *   returned by {@code save()}, not the one passed in.
 */
@Entity
@Table(name = "documents")
public class Document implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DocumentStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "indexed_at")
    private OffsetDateTime indexedAt;

    /**
     * [Purpose] Backs {@link #isNew()} for the {@link Persistable} contract (see class-level
     *   [Caution]). {@code true} only between construction and the first persist/load.
     * [Rationale] {@code @Transient} keeps it out of the {@code documents} mapping; {@code
     *   @PostPersist}/{@code @PostLoad} flip it to {@code false} once Hibernate has actually
     *   written or read the row, so {@code isNew()} tracks real persistence state rather than a
     *   guess from the id field.
     */
    @Transient
    private boolean isNew = true;

    /** [Purpose] Required by JPA; not for application use. */
    protected Document() {
    }

    /**
     * [Purpose] Creates a new document row in its initial PENDING state.
     * [Rationale] {@code id}/{@code createdAt} are generated here rather than deferred to the
     *   database so they are available to the caller before persist (see class-level
     *   [Caution]). {@code title} is intentionally left unset (Tech Review AD-8).
     */
    public Document(String filename, String mimeType, Long sizeBytes) {
        this.id = UUID.randomUUID();
        this.filename = filename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.status = DocumentStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * [Purpose] Applies a status transition, enforcing the forward-only rule.
     * [Rationale] Delegates the allow/deny decision to {@link DocumentStatus#canTransitionTo},
     *   keeping this method a pure state change with no I/O — the transaction boundary and
     *   persistence belong to T8's {@code DocumentStatusTransitioner} (Tech Review AD-3/AD-4),
     *   not to this entity.
     * [AC] document-ingestion:AC-5
     */
    public void transitionTo(DocumentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition document " + id + " from " + status + " to " + target);
        }
        this.status = target;
    }

    /**
     * [Purpose] Convenience for the FAILED transition that also records the failure reason.
     * [Rationale] AC-6 requires FAILED to always carry a non-empty error_message; bundling the
     *   two keeps that invariant in one place instead of relying on every caller to remember it.
     *   The blank-check guard makes that invariant actually hold instead of merely being
     *   documented — a caller passing null/blank fails fast here instead of writing an
     *   unusable row.
     * [Caution] Chunk cleanup for the FAILED path (removing rows already written to
     *   {@code vector_store_bgem3}) is T8's responsibility, not this method's.
     * [AC] document-ingestion:AC-6 — partial: this method only enforces the state/error_message
     *   invariant; chunk cleanup is T8
     */
    public void markFailed(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be null or blank");
        }
        transitionTo(DocumentStatus.FAILED);
        this.errorMessage = errorMessage;
    }

    /**
     * [Purpose] Convenience for the terminal INDEXED transition that also records the chunk
     *   count and completion timestamp in one step.
     * [Rationale] AC-5 requires {@code indexed_at} to be non-null and AC-7 requires
     *   {@code chunk_count} to be at least 1; guarding both here prevents a caller from
     *   silently writing an INDEXED row that fails those criteria.
     * [AC] document-ingestion:AC-5, document-ingestion:AC-7
     */
    public void markIndexed(int chunkCount, OffsetDateTime indexedAt) {
        Objects.requireNonNull(indexedAt, "indexedAt must not be null");
        if (chunkCount < 1) {
            throw new IllegalArgumentException("chunkCount must be at least 1, was " + chunkCount);
        }
        transitionTo(DocumentStatus.INDEXED);
        this.chunkCount = chunkCount;
        this.indexedAt = indexedAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /**
     * [Purpose] Tells Spring Data JPA whether {@code save()} should {@code persist()} (new row)
     *   or {@code merge()} (existing row).
     * [AC] document-ingestion — no AC number; underlies the merge()-pitfall fix noted in the
     *   class-level [Caution]
     */
    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * [Purpose] Flips {@link #isNew} to {@code false} once this row has actually been written
     *   or read, so a later {@code save()} on the same instance correctly takes the merge path
     *   instead of attempting a duplicate insert.
     */
    @PostPersist
    @PostLoad
    void clearIsNew() {
        this.isNew = false;
    }

    public String getFilename() {
        return filename;
    }

    public String getTitle() {
        return title;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getIndexedAt() {
        return indexedAt;
    }
}
