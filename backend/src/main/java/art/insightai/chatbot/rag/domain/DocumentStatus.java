package art.insightai.chatbot.rag.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * [Purpose] Lifecycle states of a {@code documents} row through the ingestion pipeline.
 * [Rationale] Values and the forward-only shape (PENDING -> PARSING -> EMBEDDING -> INDEXED,
 *   with a transition to FAILED from any non-terminal state) come from the Spec's safeguard #3
 *   and Tech Review card T2. INDEXED and FAILED are treated as terminal: no AC requires
 *   re-processing a completed or already-failed document, and AC-6 ties the FAILED transition
 *   to an exception during the parsing/embedding stage, not to a post-completion event.
 * [Caution] {@link #canTransitionTo} is the single source of truth for "allowed transitions
 *   only, no regression". Do not duplicate this table elsewhere (e.g. in T8's
 *   {@code DocumentStatusTransitioner}) — call this method instead.
 * [AC] document-ingestion:AC-5
 */
public enum DocumentStatus {
    PENDING,
    PARSING,
    EMBEDDING,
    INDEXED,
    FAILED;

    private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(PARSING, FAILED),
            PARSING, EnumSet.of(EMBEDDING, FAILED),
            EMBEDDING, EnumSet.of(INDEXED, FAILED),
            INDEXED, EnumSet.noneOf(DocumentStatus.class),
            FAILED, EnumSet.noneOf(DocumentStatus.class)
    );

    /**
     * [Purpose] Reports whether moving from this state to {@code target} is a legal transition.
     * [AC] document-ingestion:AC-5
     */
    public boolean canTransitionTo(DocumentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }
}
