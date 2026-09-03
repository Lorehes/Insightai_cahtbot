package art.insightai.chatbot.rag.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.jdbc.core.RowMapper;

import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Maps one {@code vector_store_bgem3} row ({@code id, content, metadata, score}) to the
 *   axis-agnostic {@link RetrievedChunk}, shared by every ParadeDB-backed keyword axis.
 * [Rationale] Extracted from {@link ParadeDbBm25SearchAdapter} on 2026-09-03 when the n-gram axis
 *   ({@link ParadeDbNgramSearchAdapter}) needed the identical mapping; the JSON-text-metadata and
 *   null-metadata rules below are that adapter's original ones, moved verbatim so both axes keep
 *   producing the same shape for {@code SourceRefMapper}. Plain package-private class (not a bean)
 *   because it has no dependencies and each adapter owns its own instance.
 * [Caution] {@code metadata} arrives as a JSON text string (the column type is {@code json}, not
 *   {@code jsonb}); a null column value maps to an empty map rather than a null {@link
 *   RetrievedChunk#metadata()} so downstream consumers never need a null check. A malformed value
 *   means the row was written by something other than {@code PgVectorStore}/this project's own
 *   ingestion path -- surfaced as an unchecked exception rather than silently dropped metadata,
 *   since {@code document_id}/{@code filename} are relied on downstream. {@link JsonParser} (from
 *   {@code org.springframework.boot.json}) keeps this class decoupled from whichever JSON library
 *   {@link JsonParserFactory} selects at runtime (Jackson 3 here, transitively present anyway).
 */
final class ParadeDbChunkRowMapper implements RowMapper<RetrievedChunk> {

    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();

    @Override
    public RetrievedChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedChunk(
                rs.getString("id"),
                rs.getString("content"),
                parseMetadata(rs.getString("metadata")),
                rs.getDouble("score"));
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null) {
            return Map.of();
        }
        try {
            return jsonParser.parseMap(metadataJson);
        } catch (JsonParseException e) {
            throw new IllegalStateException("Malformed metadata JSON in vector_store_bgem3 row", e);
        }
    }
}
