package art.insightai.chatbot.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * [Purpose] JPA entity for the {@code messages} table — one row per user or assistant turn in a
 *   conversation (chat Spec §5).
 * [Rationale] Column mapping follows {@code V1__init.sql} exactly (verified against the
 *   migration file at implementation time; {@code ddl-auto=validate} fails application startup
 *   on any mismatch). {@code id} is {@code bigserial} in the DDL, mapped here with
 *   {@code @GeneratedValue(strategy = GenerationType.IDENTITY)} — unlike {@code User}/
 *   {@code Conversation}, this needs no {@code isNew()} workaround: DB IDENTITY columns leave
 *   the Java field {@code null} until the row is actually inserted, same as {@code bigserial}'s
 *   sequence default (Tech Review A-1). {@code conversationId} is a plain {@code UUID} column,
 *   not a {@code @ManyToOne Conversation} association, for the same reason
 *   {@code Conversation.userId} is plain (see that class's [Rationale]) — A-4's repository
 *   queries only ever compare the id. {@code costUsd} is {@code BigDecimal} per the project's
 *   monetary-type rule (global CLAUDE.md §7.2-4); no floating-point type is used anywhere on
 *   this entity. {@code sources} is mapped as a Java {@code String} carrying a JSON array
 *   literal, but the JDBC binding is not the driver's default (VARCHAR) — the PostgreSQL JDBC
 *   driver rejects a VARCHAR-bound parameter against a {@code jsonb} column
 *   ("column \"sources\" is of type jsonb but expression is of type character varying"), so
 *   {@code @JdbcTypeCode(SqlTypes.JSON)} tells Hibernate to bind this column through its JSON
 *   descriptor (OTHER/jsonb-aware) instead of the String default. This uses only
 *   {@code hibernate-core} (already a transitive dependency via
 *   {@code spring-boot-starter-data-jpa}, confirmed via {@code mvnw dependency:tree}), not a new
 *   library (project rule: library additions need card/Spec justification), and a future
 *   card (T9/T11/hybrid-rag) that needs structured access can still add a converter without
 *   changing this column's DB type.
 * [Caution] Per Tech Review A-4, {@code ChatService} is the *only originator* of writes to this
 *   table — no {@code ChatMemory} integration writes here. As of chat T9 rework (2026-08-27,
 *   maintainability Medium-1), the literal {@code save()} call is issued by {@code
 *   ConversationService#persistUserTurn}/{@code #persistAssistantTurn} on {@code ChatService}'s
 *   behalf, purely so the insert (and the {@code conversations.title}/{@code updated_at} bump
 *   alongside it) runs inside a {@code @Transactional} method on a *different* Spring bean — the
 *   self-invocation pitfall documented on {@code ConversationRepository}'s own bulk {@code
 *   @Modifying} queries. Only {@code ChatService} ever constructs a {@link Message} row or decides
 *   its content/timing; {@code ConversationService} never does so on its own initiative. This
 *   entity is intentionally immutable after construction (no setters): the 'user' row and the
 *   'assistant' row for one turn are two separate INSERTs (A-4's call order ①user row → ②history
 *   read → ③stream → ④assistant row), never an update of the same row, so no mutation API is
 *   needed.
 */
@Entity
@Table(name = "messages")
public class Message {

    /** [Purpose] {@code messages.role} value for the caller's turn (chat Spec §5 A-4). */
    public static final String ROLE_USER = "user";

    /** [Purpose] {@code messages.role} value for the LLM's turn (chat Spec §5 A-4). */
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private String role;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "model", length = 64, updatable = false)
    private String model;

    @Column(name = "prompt_tokens", updatable = false)
    private Integer promptTokens;

    @Column(name = "completion_tokens", updatable = false)
    private Integer completionTokens;

    @Column(name = "cost_usd", updatable = false)
    private BigDecimal costUsd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", updatable = false, columnDefinition = "jsonb")
    private String sources;

    @Column(name = "finish_reason", length = 32, updatable = false)
    private String finishReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** [Purpose] Required by JPA; not for application use. */
    protected Message() {
    }

    /**
     * [Purpose] Creates a new message row. {@code id} is left unset for the DB identity column
     *   to populate on insert; {@code createdAt} is stamped here, mirroring
     *   {@code user.User}/{@code Conversation}. All fields besides {@code conversationId},
     *   {@code role} and {@code content} are nullable per the DDL — callers pass {@code null}
     *   for whatever is not yet known (e.g. the 'user' row has no {@code model}/token/cost
     *   data; see class-level [Caution]).
     */
    public Message(UUID conversationId, String role, String content, String model,
            Integer promptTokens, Integer completionTokens, BigDecimal costUsd,
            String sources, String finishReason) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.costUsd = costUsd;
        this.sources = sources;
        this.finishReason = finishReason;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public String getSources() {
        return sources;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
