package art.insightai.chatbot.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Structural self-check that the T4 harness actually closes the door on the real
 * OpenRouter embedding endpoint -- the embedding-path counterpart of
 * {@link art.insightai.chatbot.chat.OutboundCallGuardIntegrationTest}, which only covers the
 * chat-model path (that class's own Javadoc explicitly scopes itself to "the chat-model path
 * only"). No document-ingestion Spec AC maps to this class (review 2026-08-26_document-
 * ingestion-T4.md Medium item, explicit instruction: do not tag these -- chat:AC-35's own
 * ledger row is T9's to close, and this is a different Spec's card besides).
 * [Rationale] Asserts the same two structural guarantees
 * {@code OutboundCallGuardIntegrationTest} proves for chat, mirrored for embedding: (1) the real
 * {@code OpenAiEmbeddingModel} bean does not exist in the context at all -- stronger than merely
 * observing the stub is reachable, since it also proves the real client was never even
 * constructed -- and (2) the {@code EmbeddingModel} bean any test wires is
 * {@link StubEmbeddingModel}. It then adds a third check specific to the embedding path: routing
 * a real document through {@link VectorStore#add(List)} (production's own ingestion entry point,
 * per {@code ChunkRepository}'s Javadoc: "chunk insertion goes through {@code VectorStore.add()}")
 * and confirming the stub actually observed that call via
 * {@link StubEmbeddingModel#capturedRequests()} -- proving the stub is not just present but
 * actually sits on the path production code uses, not bypassed by some other wiring. Confirmed by
 * reading {@code spring-ai-model:2.0.1}'s {@code EmbeddingModel} source (project rule P3): the
 * default {@code embed(List<Document>, EmbeddingOptions, BatchingStrategy)} method
 * {@code VectorStore.add()} uses internally calls {@link StubEmbeddingModel#call} directly (not
 * {@link StubEmbeddingModel#embed(Document)}), which is exactly the method
 * {@code capturedRequests()} records into -- so this assertion is not incidental.
 * [Caution] Like its chat counterpart, this complements but does not replace the {@code -Peval}
 * Maven profile split in {@code pom.xml} (no test in this Spec intentionally calls the real
 * embedding endpoint) -- it guards the default {@code ./mvnw test} (and IntelliJ direct-run) path
 * every other integration test runs under.
 */
class EmbeddingOutboundCallGuardIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID insertedDocumentId;

    @AfterEach
    void cleanUpInsertedRow() {
        // then: a direct JdbcTemplate delete, not any production repository, so a bug in the
        // class under test cannot also break cleanup and leak this row into another test/another
        // card's session sharing this live infra container (mirrors ChunkRepositoryIntegrationTest)
        if (insertedDocumentId != null) {
            jdbcTemplate.update(
                    "DELETE FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                    insertedDocumentId.toString());
            insertedDocumentId = null;
        }
    }

    @Test
    @DisplayName("컨텍스트에 실제 OpenAiEmbeddingModel 빈이 존재하지 않는다")
    void realOpenAiEmbeddingModelBeanDoesNotExist() {
        // when
        String[] realBeanNames = applicationContext.getBeanNamesForType(OpenAiEmbeddingModel.class);

        // then: the real client class was never even instantiated, not merely shadowed
        assertThat(realBeanNames).isEmpty();
    }

    @Test
    @DisplayName("EmbeddingModel 빈이 StubEmbeddingModel 이다")
    void wiredEmbeddingModelIsTheStub() {
        assertThat(embeddingModel).isInstanceOf(StubEmbeddingModel.class);
    }

    @Test
    @DisplayName("VectorStore.add() 로 문서를 색인하면 스텁이 실제로 그 요청을 받는다")
    void vectorStoreAddRoutesThroughTheStub() {
        // given: production code's own ingestion entry point (VectorStore.add()), not a direct
        // call to the EmbeddingModel bean -- proves the autoconfigured wiring path actually uses
        // the stub, not just that the raw bean happens to be one
        StubEmbeddingModel stub = (StubEmbeddingModel) embeddingModel;
        stub.reset();
        insertedDocumentId = UUID.randomUUID();
        Document document = Document.builder()
                .text("아웃바운드 차단 가드 테스트 문서")
                .metadata(Map.of("document_id", insertedDocumentId.toString()))
                .build();

        // when
        vectorStore.add(List.of(document));

        // then: if this had gone to a real endpoint it would either fail (unroutable base-url,
        // dummy api-key) or never reach this in-process capture at all
        assertThat(stub.capturedRequests()).isNotEmpty();
    }
}
