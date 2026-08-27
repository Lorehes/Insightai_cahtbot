package art.insightai.chatbot.rag.domain;

import java.util.UUID;

/**
 * [Purpose] The citation shape exposed to callers: one entry per chunk backing a RAG answer,
 *   sent over SSE (T11) and persisted verbatim into {@code messages.sources} (T11, AC-12).
 * [Rationale] Field set is fixed by hybrid-rag Spec AC-11: {@code documentId}, {@code filename},
 *   {@code score}, {@code snippet}, {@code page}(nullable). {@code documentId} is typed as
 *   {@link java.util.UUID} to match {@code documents.id} (see {@link Document#getId()},
 *   {@link DocumentUploadedEvent}) rather than the raw string Spring AI's {@code Document}
 *   metadata carries — converting that string is T8's mapping responsibility, not this record's.
 *   {@code score} is always the RRF fusion score, never the rerank score (Tech Review D-T6): the
 *   two scales are not comparable, and swapping the source mid-pipeline (rerank enabled vs.
 *   fallback) would make the field mean different things across responses.
 * [Caution] {@code page} is nullable on purpose (hybrid-rag Spec §7 Q2 / document-ingestion
 *   OQ-5): Tika cannot recover page boundaries for every document format. Display formatting
 *   ("파일.pdf p.3" vs "파일.pdf") is frontend's concern, not this record's.
 * [AC] hybrid-rag:AC-11 — field set only. Population (T8) and transport/persistence (T11) are
 *   separate cards; this record does not by itself satisfy the AC.
 */
public record SourceRef(
        UUID documentId,
        String filename,
        double score,
        String snippet,
        Integer page
) {
}
