package art.insightai.chatbot.rag.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Spring Data JPA repository for {@link Document}.
 * [Rationale] {@code findById(UUID)} inherited from {@link JpaRepository} already covers
 *   "look up by document id" (Tech Review T2 scope), since {@code documents.id} is the same id
 *   referenced as {@code document_id} in {@code vector_store_bgem3.metadata} (AD-1). {@link
 *   #findAllByOrderByCreatedAtDesc()} is T9's list-endpoint finder (document-ingestion:AC-11) —
 *   a derived query name, mirroring {@code chat.domain.ConversationRepository
 *   #findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc}'s sort-in-name shape one Spec over, so
 *   the ordering is enforced by the query itself rather than a later in-memory sort. T10's delete
 *   endpoint still needs no custom finder here (inherited {@code deleteById}/{@code existsById}
 *   suffice).
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * [Purpose] Backs {@code GET /api/admin/documents} — all documents, most recently created
     *   first.
     * [AC] document-ingestion:AC-11
     */
    List<Document> findAllByOrderByCreatedAtDesc();
}
