package art.insightai.chatbot.rag.retrieval;

/**
 * [Purpose] One {@code documents} row returned by {@link NonIndexedDocumentLookup}: {@code id}
 *   (string form of the {@code documents.id} UUID, matching how {@code
 *   vector_store_bgem3.metadata} stores {@code document_id}) plus its current {@code status}.
 * [Rationale] {@code status} is carried even though this card's only consumers ({@link
 *   ParadeDbBm25SearchAdapter}, {@link VectorStoreSearchAdapter}) use {@code id} alone to build
 *   their exclusion filter -- see {@link NonIndexedDocumentLookup}'s Javadoc for why it is kept
 *   rather than dropped at the query site (coordinator note, 2026-08-28: {@code
 *   eval-usage-dashboard} Spec may need the excluded-chunk status distribution later, and the
 *   value is already in hand here at query time).
 */
public record NonIndexedDocument(String id, String status) {
}
