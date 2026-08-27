package art.insightai.chatbot.rag.domain;

import java.util.UUID;

/**
 * [Purpose] Published after a new {@link Document} row commits, to hand off from the upload
 *   request to the async ingestion pipeline.
 * [Rationale] Event-based decoupling (Tech Review AD-2) lets the upload endpoint (T5) and the
 *   pipeline (T8) be implemented and compiled independently of each other.
 * [Caution] The publishing side must call {@code ApplicationEventPublisher.publishEvent} from
 *   within a {@code @Transactional} method. A {@code @TransactionalEventListener(phase =
 *   AFTER_COMMIT)} listener registered for this type is silently skipped if the publish happens
 *   outside an active transaction (Tech Review NR-3) — symptom: the upload succeeds but the
 *   document stays PENDING forever.
 */
public record DocumentUploadedEvent(UUID documentId) {
}
