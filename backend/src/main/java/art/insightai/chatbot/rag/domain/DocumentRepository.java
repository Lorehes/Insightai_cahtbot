package art.insightai.chatbot.rag.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * [Purpose] Spring Data JPA repository for {@link Document}.
 * [Rationale] {@code findById(UUID)} inherited from {@link JpaRepository} already covers
 *   "look up by document id" (Tech Review T2 scope), since {@code documents.id} is the same id
 *   referenced as {@code document_id} in {@code vector_store_bgem3.metadata} (AD-1). No custom
 *   finder is needed yet; later cards (T9 list, T10 delete) add their own query needs at their
 *   own layer rather than here.
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {
}
