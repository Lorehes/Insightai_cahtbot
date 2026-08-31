package art.insightai.chatbot.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * [Purpose] Business logic behind {@code POST /api/admin/documents/orphan-chunks/cleanup}
 *   (document-ingestion Spec §4 AC-18, Tech Review T10 High-1, user-approved fix 2): removes
 *   every {@code vector_store_bgem3} chunk whose {@code documents} row no longer exists and
 *   reports how many rows were removed.
 * [Rationale] A standing sweep alongside AC-17's per-document lock ({@link
 *   DocumentIngestLockRegistry}) rather than a replacement for it -- the lock prevents new
 *   orphans from being created by the delete/ingest race the review found, but it cannot remove
 *   orphans that already exist (this project ran without that lock before this card) or any
 *   orphan produced by an interleaving the lock does not cover. This endpoint is the operator's
 *   only way to reclaim those rows: unlike a document's own row, an orphaned chunk has no {@code
 *   documents} id to target with {@code DELETE /api/admin/documents/{id}} -- {@link
 *   DocumentDeleteService#delete} 404s for it (document-ingestion:AC-13), and {@code GET
 *   /api/admin/documents} never lists it in the first place, which is exactly the invisibility
 *   the review's High-1 flagged.
 * [Side effects] Permanently deletes rows from {@code vector_store_bgem3} via {@link
 *   ChunkRepository#deleteOrphaned}.
 * [Caution] Unauthenticated, same as every other {@code /api/admin/documents} endpoint in this
 *   Spec (§7 OQ-7 "무보호 유지") -- see {@link
 *   art.insightai.chatbot.rag.api.OrphanChunkCleanupController}'s own Javadoc for why that
 *   premise is not revisited for this specific endpoint despite it being the fix for a security
 *   finding.
 * [AC] document-ingestion:AC-18
 */
@Service
public class OrphanChunkCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OrphanChunkCleanupService.class);

    private final ChunkRepository chunkRepository;

    public OrphanChunkCleanupService(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    /**
     * [Purpose] Entry point for {@code POST /api/admin/documents/orphan-chunks/cleanup}. Deletes
     *   every orphaned chunk and returns the count removed.
     * [Side effects] See class-level [Side effects].
     * [AC] document-ingestion:AC-18
     */
    public int cleanup() {
        int deletedCount = chunkRepository.deleteOrphaned();
        log.info("고아 청크 정리 완료 (deletedCount={})", deletedCount);
        return deletedCount;
    }
}
