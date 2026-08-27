package art.insightai.chatbot.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * [Purpose] Self-verification for {@link StubEmbeddingModel} -- no Spec AC maps to card T4
 * (Tech Review: "AC 없는 카드는 T1·T3·T4"), so these carry no {@code [document-ingestion:AC-N]}
 * prefix and do not affect the evidence ledger. They exist so a future card that relies on this
 * stub (T6/T7) can trust its documented guarantees (1024 dimensions, determinism, no network,
 * token-overlap discriminates similarity) without re-deriving them.
 */
class StubEmbeddingModelTest {

    @Test
    void returns1024DimensionVector() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();

        // when
        float[] vector = model.embed("아무 문장");

        // then
        assertThat(vector).hasSize(1024);
        assertThat(model.dimensions()).isEqualTo(1024);
    }

    @Test
    void sameTextAlwaysProducesTheSameVector() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();

        // when
        float[] first = model.embed("인사이트에이아이는 사내 챗봇을 만든다");
        float[] second = model.embed("인사이트에이아이는 사내 챗봇을 만든다");

        // then
        assertThat(first).containsExactly(second);
    }

    @Test
    void differentTextProducesADifferentVector() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();

        // when
        float[] first = model.embed("첫 번째 문장");
        float[] second = model.embed("두 번째 문장");

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void callPreservesInputOrderAndCountForABatchRequest() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();
        List<String> texts = List.of("첫 문장", "둘째 문장", "셋째 문장");

        // when
        EmbeddingResponse response = model.call(new EmbeddingRequest(texts, null));

        // then: batch call returns one 1024-dim embedding per input, in the same order, and
        // each matches what a direct embed() call on that same text would produce
        assertThat(response.getResults()).hasSize(3);
        for (int i = 0; i < texts.size(); i++) {
            assertThat(response.getResults().get(i).getOutput()).containsExactly(model.embed(texts.get(i)));
            assertThat(response.getResults().get(i).getIndex()).isEqualTo(i);
        }
    }

    @Test
    void embedsADocumentsTextContent() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();
        Document document = Document.builder().text("문서 본문").build();

        // when
        float[] vector = model.embed(document);

        // then: identical to embedding the same text directly (same seed source)
        assertThat(vector).containsExactly(model.embed("문서 본문"));
    }

    @Test
    void reservesCapturedRequestsForInspectionAndClearsOnReset() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();
        model.call(new EmbeddingRequest(List.of("문장"), null));
        assertThat(model.capturedRequests()).hasSize(1);

        // when
        model.reset();

        // then
        assertThat(model.capturedRequests()).isEmpty();
    }

    /**
     * [Purpose] Self-verification for the property review 2026-08-26_document-ingestion-T4.md
     * High-1 requires of {@link StubEmbeddingModel#vectorFor}: a query scores meaningfully higher
     * against a chunk it shares tokens with than against an unrelated chunk. Runs against 3
     * independent pairs (review 2026-08-27_document-ingestion-T4-round2.md Medium-A: a single
     * {@code isGreaterThan} comparison had roughly 50% detection power against a regression to a
     * non-discriminative stub -- e.g. a uniform-random vector's expected cosine similarity is
     * ≈0 either way, so it would pass or fail this style of assertion by coin flip; absolute
     * margins plus 3 pairs make that kind of regression fail every pair, every run).
     * [Rationale] Both embedded vectors are L2-normalized by {@code vectorFor} (class Javadoc),
     * so cosine similarity between any two of this stub's vectors reduces to a plain dot
     * product -- no need to divide by norms here. Each pair's margin was hand-derived from exact
     * token counts (query token count N, source's extra prefix+suffix token count P+S, all tokens
     * assumed distinct: cosine(query, source) = sqrt(N / (N+P+S)) when every query token is
     * reused verbatim inside source and no other tokens repeat) and then confirmed by actually
     * running this test (per {@link StubEmbeddingModel}'s class Javadoc note on bucket-collision
     * risk) -- not assumed from the formula alone. The {@code unrelated} chunk in each pair
     * shares zero tokens with its query by construction, so its cosine is 0 barring a bucket
     * collision, comfortably under the 0.15 ceiling.
     */
    @ParameterizedTest(name = "판별력 마진: {0}")
    @MethodSource("discriminativePairs")
    void queryScoresSourceChunkHigherThanUnrelatedChunk(
            String label, String query, String sourceChunk, String unrelatedChunk) {
        // given: the query shares every token with the "source" chunk (a larger passage
        // containing the query verbatim, as a real chunk containing it would); the "unrelated"
        // chunk shares no token with the query
        StubEmbeddingModel model = new StubEmbeddingModel();

        // when
        float[] queryVector = model.embed(query);
        float[] sourceVector = model.embed(sourceChunk);
        float[] unrelatedVector = model.embed(unrelatedChunk);

        // then: absolute margins, not just a relative comparison -- see class Javadoc for why a
        // relative-only assertion has weak detection power
        double similarityToSource = dotProduct(queryVector, sourceVector);
        double similarityToUnrelated = dotProduct(queryVector, unrelatedVector);
        assertThat(similarityToSource).as("%s: cosine(query, source)", label).isGreaterThan(0.5);
        assertThat(similarityToUnrelated).as("%s: cosine(query, unrelated)", label).isLessThan(0.15);
    }

    private static Stream<Arguments> discriminativePairs() {
        return Stream.of(
                Arguments.of(
                        "회사 소개 문서 (기존 SampleDocumentFixtures 픽스처)",
                        SampleDocumentFixtures.KNOWN_SENTENCE_TEXT,
                        "회사 소개 챕터의 일부. " + SampleDocumentFixtures.KNOWN_SENTENCE_TEXT + " 이상 소개를 마친다.",
                        "연차휴가는 입사일을 기준으로 매년 갱신되며 최대 15일까지 사용할 수 있습니다."),
                Arguments.of(
                        "출장 경비 정산 공지",
                        "출장 경비는 정산 시스템에 영수증을 첨부해야 승인됩니다.",
                        "총무팀 공지: 출장 경비는 정산 시스템에 영수증을 첨부해야 승인됩니다. 문의는 총무팀으로.",
                        "야근 식대는 저녁 8시 이후 근무 시 1인당 12000원까지 지원됩니다."),
                Arguments.of(
                        "휴게실 원두 보충 안내",
                        "휴게실 커피머신 원두는 매주 월요일에 보충됩니다.",
                        "사내 시설 안내. 휴게실 커피머신 원두는 매주 월요일에 보충됩니다. 원두가 떨어지면 총무팀에 연락하세요.",
                        "분기별 예산안은 재무팀 검토 후 확정됩니다."));
    }

    @Test
    void failNextThrowsScriptedRuntimeExceptionOnNextCall() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();
        IllegalStateException scripted = new IllegalStateException("boom");
        model.failNext(scripted);

        // when/then: the exact instance propagates unwrapped
        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("문장"), null)))
                .isSameAs(scripted);
    }

    @Test
    void failNextWrapsCheckedExceptionInRuntimeException() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();
        IOException checked = new IOException("io fail");
        model.failNext(checked);

        // when/then: call()/embed() declare no checked exception, so this stub wraps it
        assertThatThrownBy(() -> model.embed("문장"))
                .isInstanceOf(RuntimeException.class)
                .hasCause(checked);
    }

    @Test
    void afterConsumingScriptedFailureSubsequentCallsSucceedNormally() {
        // given
        StubEmbeddingModel model = new StubEmbeddingModel();
        model.failNext(new IllegalStateException("boom"));

        // when: the scripted failure fires exactly once
        assertThatThrownBy(() -> model.embed("문장")).isInstanceOf(IllegalStateException.class);

        // then: the next call is unaffected
        float[] vector = model.embed("문장");
        assertThat(vector).hasSize(1024);
    }

    private static double dotProduct(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
