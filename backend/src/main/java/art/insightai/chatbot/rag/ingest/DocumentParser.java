package art.insightai.chatbot.rag.ingest;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * [Purpose] Parses an uploaded file's bytes into Spring AI {@link Document} instances via Apache
 *   Tika, covering every MIME type in the ingestion whitelist (PDF/DOCX/PPTX/XLSX/TXT/MD) with
 *   one auto-detecting reader.
 * [Rationale] document-ingestion Spec Tech Review card T6's "Tika 파싱" step. {@link
 *   TikaDocumentReader} auto-detects the format, so no per-MIME-type branching is needed in this
 *   class.
 * [Caution] {@link TikaDocumentReader#get()} always returns exactly one {@link Document} per
 *   resource (verified by reading {@code spring-ai-tika-document-reader:2.0.1} sources --
 *   {@code return List.of(toDocument(this.handler.toString()))}), and that document's metadata
 *   carries only a {@code source} key (the resource filename). It does NOT expose Apache Tika's
 *   own {@code Metadata} object -- e.g. page-count fields such as {@code xmpTPg:NPages} -- or any
 *   other page-boundary information, for any format. This was confirmed empirically against
 *   {@code fixtures/sample-document.pdf} (a genuine 2-page PDF) in a disposable spike test; see
 *   the {@code document-ingestion-T6} entry in {@code docs/dev-log/backend.jsonl} for the raw
 *   output. Callers must not assume this reader can ever populate a "page" value -- see {@link
 *   ChunkMetadataBuilder}'s Javadoc for how AC-8's nullable {@code page} requirement is satisfied
 *   given this constraint.
 */
@Component
public class DocumentParser {

    /**
     * [Purpose] Extracts the text content of the given resource as Spring AI {@link Document}s.
     * [Rationale] Delegates entirely to {@link TikaDocumentReader}; see class [Caution] for what
     *   its single returned {@link Document}'s metadata does and does not contain.
     */
    public List<Document> parse(Resource resource) {
        return new TikaDocumentReader(resource).get();
    }
}
