package art.insightai.chatbot.rag.ingest;

import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [Purpose] Business logic behind {@code GET /api/admin/documents} (document-ingestion Spec Tech
 *   Review T9): returns every {@code documents} row, most recently created first.
 * [Rationale] Mirrors {@code chat.service.ConversationService#listConversations}'s shape one
 *   Spec over — a single pass-through to a derived-query repository finder, with the sort
 *   expressed in the query itself ({@link DocumentRepository#findAllByOrderByCreatedAtDesc()})
 *   rather than an in-memory {@code Comparator}. No filtering/pagination is applied: AC-11 does
 *   not ask for either. The absence of pagination itself has no Spec/Tech Review citation behind
 *   it — Tech Review DD-4 is about splitting AC-14's card count (T5/T6-T8/T9-T10), not about this
 *   endpoint's response shape, so it does not support a "demo scale" justification. This is an
 *   implementer judgment call, documented as such rather than attributed to a Spec decision that
 *   does not exist.
 * [AC] document-ingestion:AC-11
 */
@Service
public class DocumentListService {

    private final DocumentRepository documentRepository;

    public DocumentListService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * [Purpose] Returns every document, {@code created_at} descending.
     * [AC] document-ingestion:AC-11
     */
    @Transactional(readOnly = true)
    public List<Document> listDocuments() {
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }
}
