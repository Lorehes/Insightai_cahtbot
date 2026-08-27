package art.insightai.chatbot.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * [Purpose] JPA entity for the {@code users} table — one row per anonymous browser session
 *   (chat Spec §3.1-3), keyed by the same {@code uuid} that is set verbatim as the
 *   {@code chatbot_sid} cookie value (Tech Review A-1: no separate {@code session_token}
 *   column exists, so the PK doubles as the session identifier).
 * [Rationale] Column mapping follows {@code V1__init.sql} exactly (verified against the
 *   migration file at implementation time; {@code ddl-auto=validate} fails application startup
 *   on any mismatch, so no field here may drift from that DDL without a corresponding chat-Spec
 *   migration change). {@code id} uses {@code @GeneratedValue(strategy = GenerationType.UUID)}
 *   per Tech Review A-1 rather than assigning {@code UUID.randomUUID()} in the constructor —
 *   the id field stays {@code null} until Hibernate populates it on insert, so Spring Data's
 *   default {@code isNew()} check (id == null) correctly takes the {@code persist()} path on
 *   {@code save()} without needing a {@link org.springframework.data.domain.Persistable}
 *   override (contrast with {@code rag.domain.Document}, whose id is assigned by the
 *   application before the row exists and therefore does need that override).
 * [Caution] {@code externalId}/{@code displayName} are nullable per the DDL and are not
 *   populated by this card — {@code auth_provider='anonymous'} rows (T4,
 *   {@code AnonymousUserService}) leave both null. A future non-anonymous auth provider would
 *   populate them; no such provider exists yet.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "auth_provider", nullable = false, length = 32)
    private String authProvider;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** [Purpose] Required by JPA; not for application use. */
    protected User() {
    }

    /**
     * [Purpose] Creates a new user row. {@code id} is left unset for Hibernate to populate on
     *   insert (see class-level [Rationale]); {@code createdAt} is stamped here rather than
     *   left to the DB's {@code now()} default so the value is visible on the in-memory
     *   instance immediately after construction, mirroring {@code rag.domain.Document}.
     */
    public User(String authProvider, String externalId, String displayName) {
        this.authProvider = authProvider;
        this.externalId = externalId;
        this.displayName = displayName;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAuthProvider() {
        return authProvider;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
