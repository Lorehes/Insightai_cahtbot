package art.insightai.chatbot.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * [Purpose] Test double for {@link EmbeddingModel} that never opens a real HTTP connection --
 * every {@link #call(EmbeddingRequest)}/{@link #embed(Document)} invocation derives a
 * deterministic 1024-dimension vector from the input text via token hashing, instead of calling
 * the real OpenRouter {@code baai/bge-m3} embedding endpoint.
 * [Rationale] document-ingestion Spec §6 "공통 전제": "임베딩 호출은 통합 테스트에서 1024차원 고정
 * 벡터를 반환하는 스텁 EmbeddingModel로 대체한다... 차원은 반드시 1024여야 pgvector insert가
 * 통과한다." {@link #vectorFor(String)} satisfies that literally (always 1024 floats, no network)
 * and additionally makes the stub *discriminative*: the input is tokenized (split on runs of
 * non-letter/non-digit characters -- punctuation and whitespace alike) and each token's hash
 * accumulates weight into one of {@value #DIMENSIONS} buckets (bucket index via
 * {@code Math.floorMod(token.hashCode(), DIMENSIONS)}, which corrects negative hash codes), then
 * the whole vector is L2-normalized. This is a bag-of-words "hashing trick": two texts that share
 * more tokens are EXPECTED to score a higher cosine similarity than two that share fewer, in
 * proportion to token overlap -- not a guarantee for every arbitrary pair. With
 * {@value #DIMENSIONS} buckets, two distinct tokens land in the same bucket by chance
 * (birthday-paradox collision), which can nudge a specific pair's similarity up or down from the
 * "pure overlap" value; {@link String#hashCode()}'s determinism (see [Caution]) means any given
 * pair of literal strings either always exhibits the expected ordering or never does -- there is
 * no run-to-run flakiness, but a margin-sensitive assertion built on a specific pair of strings
 * must be verified once by actually running it, not assumed from the token-overlap math alone
 * (see {@link StubEmbeddingModelTest#queryScoresSourceChunkHigherThanUnrelatedChunk} for how this
 * project does that verification, and this class's own [Caution] for what to do if a chosen pair
 * collides). This still lets an AC-9-style vector-similarity test distinguish a relevant chunk
 * (many shared tokens) from an unrelated one (no shared tokens) in practice. (review
 * 2026-08-26_document-ingestion-T4.md High-1 rework: the prior version seeded an
 * independent {@link java.util.Random} Gaussian per call, which is deterministic across runs but
 * carries zero token-overlap signal -- the expected cosine similarity between any two vectors was
 * ≈0 regardless of whether the two texts were related or not, so a "does the right chunk rank
 * first" test built on it would pass or fail by chance, not by correctness. See
 * {@link StubEmbeddingModelTest#queryScoresSourceChunkHigherThanUnrelatedChunk()} for the
 * self-verification this class's guarantee now actually supports.) Registered by
 * {@link StubEmbeddingModelConfig}, which {@link IntegrationTestBase} imports -- see that class
 * for how the real {@code OpenAiEmbeddingAutoConfiguration} is excluded so this is not just
 * picked preferentially but is the *only* {@code EmbeddingModel} bean an
 * {@link IntegrationTestBase}-based test context can wire (mirrors {@link StubChatModel}/
 * chat:AC-35's structural-guarantee reasoning; review 2026-08-27_document-ingestion-T4-round2.md
 * Medium-F: that guarantee is scoped to contexts that actually extend {@link IntegrationTestBase}
 * -- a plain {@code @SpringBootTest} that does not, such as
 * {@code art.insightai.chatbot.migration.V1InitSchemaIntegrationTest}, still wires the real bean).
 * [Caution] This is still a bag-of-words hash, not a real embedding -- it has no notion of
 * synonyms, word order, or semantics beyond exact-token overlap, and Korean text without spaces
 * between morphemes (e.g. a word plus an attached particle) will NOT share a token with its bare
 * root form. Do not use this stub to validate real embedding relevance/quality; use it only to
 * confirm that code under test correctly plumbs vector scores/ordering through to callers.
 * {@link String#hashCode()} is guaranteed stable across JVM versions/runs by the Java Language
 * Specification, so the same input text always tokenizes and hashes the same way -- that is what
 * "deterministic" means here. If a new margin-sensitive test (à la
 * {@link StubEmbeddingModelTest#queryScoresSourceChunkHigherThanUnrelatedChunk}) picks a pair of
 * strings that happens to land on a bucket collision and the expected ordering/margin doesn't
 * hold, do not change this class to "fix" it (that would mask a real property of the hashing
 * trick, not a bug) -- pick a different pair of literal test strings instead and re-verify by
 * actually running the test, per the class Javadoc note above.
 */
public final class StubEmbeddingModel implements EmbeddingModel {

    /**
     * [Purpose] Matches {@code app.search.vectorstore.pgvector.dimensions} (application.yaml)
     * and the {@code vector_store_bgem3.embedding} column type ({@code vector(1024)}, chat
     * Spec's {@code V1__init.sql}) -- see class Javadoc for why this must stay 1024.
     */
    public static final int DIMENSIONS = 1024;

    /**
     * [Purpose] Splits input text into tokens for {@link #vectorFor(String)} -- any run of
     * characters that is not a Unicode letter or digit (whitespace, punctuation, symbols) is a
     * separator. Lower-cased first so e.g. "Solar" and "solar" hash to the same bucket.
     */
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final List<EmbeddingRequest> capturedRequests = new CopyOnWriteArrayList<>();

    /**
     * [Purpose] Deterministic failure script consumed by {@link #call(EmbeddingRequest)}/
     * {@link #embed(Document)} -- see {@link #failNext(Throwable)}.
     */
    private final Deque<Throwable> scriptedFailures = new ArrayDeque<>();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        capturedRequests.add(request);
        throwIfScripted();
        List<String> inputs = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(vectorFor(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        throwIfScripted();
        return vectorFor(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /**
     * [Purpose] Every {@link EmbeddingRequest} this stub has received, in call order -- lets a
     * test assert on what text was actually sent for embedding without needing a real model to
     * inspect.
     * [Caution] {@code embed(Document)} does NOT append here -- an asymmetry versus
     * {@link #call(EmbeddingRequest)} (review 2026-08-26_document-ingestion-T4.md Low item).
     * This does not weaken production ingestion assertions: {@code VectorStore.add()} embeds
     * documents via Spring AI's {@code EmbeddingModel#embed(List, EmbeddingOptions,
     * BatchingStrategy)} default method, which batches documents and calls
     * {@link #call(EmbeddingRequest)} directly -- never {@code embed(Document)} -- confirmed by
     * reading {@code spring-ai-model:2.0.1}'s {@code EmbeddingModel} source (project rule P3, not
     * guessed). {@code embed(Document)} is reachable only if a test or a future production caller
     * invokes it directly instead of going through {@code VectorStore}/batch embedding; such a
     * call would not show up here.
     */
    public List<EmbeddingRequest> capturedRequests() {
        return List.copyOf(capturedRequests);
    }

    /**
     * [Purpose] Clears captured requests and any unconsumed scripted failure.
     * [Caution] Call this in a {@code @BeforeEach}/{@code @AfterEach} when a test class shares a
     * cached Spring context with other test methods that also use this bean -- see
     * {@link StubChatModel#reset()} for the same reasoning (singleton bean, cached context).
     */
    public void reset() {
        capturedRequests.clear();
        scriptedFailures.clear();
    }

    /**
     * [Purpose] Queues a deterministic failure: the next {@link #call(EmbeddingRequest)} or
     * {@link #embed(Document)} invocation (whichever happens first) throws {@code error} instead
     * of returning a vector, then the queue reverts to normal behavior.
     * [Rationale] AC-6 ("임베딩 실패") has no deterministic way to force a failure through the
     * happy-path stub -- mirrors {@link StubChatModel#enqueueTokensThenError(List, Throwable)}'s
     * pattern of a small scripted-failure queue rather than a mocking framework, so this class
     * stays a plain hand-written double consistent with {@link StubChatModel}.
     * [Caution] Both {@link #call(EmbeddingRequest)} and {@code embed(Document)} consume the
     * scripted-failure queue BEFORE throwing, but only {@link #call(EmbeddingRequest)} also
     * records into {@link #capturedRequests()} first -- {@code embed(Document)} never appends
     * there, scripted failure or not (see {@link #capturedRequests()}'s own Caution for that
     * asymmetry; a prior version of this Javadoc incorrectly claimed both methods record into
     * {@code capturedRequests()}). So a test asserting "what was attempted right before the
     * failure" via {@code capturedRequests()} only works when the failure was scripted for
     * {@code call(EmbeddingRequest)}. {@link EmbeddingModel#call(EmbeddingRequest)} declares no
     * checked exception, so a checked {@code error} is wrapped in a {@link RuntimeException}; pass
     * a {@link RuntimeException} (or subclass) directly to have it propagate unwrapped.
     */
    public synchronized void failNext(Throwable error) {
        scriptedFailures.addLast(error);
    }

    private void throwIfScripted() {
        Throwable scripted = pollScriptedFailure();
        if (scripted == null) {
            return;
        }
        if (scripted instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (scripted instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(scripted);
    }

    private synchronized Throwable pollScriptedFailure() {
        return scriptedFailures.pollFirst();
    }

    private static float[] vectorFor(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text != null) {
            for (String token : tokenize(text)) {
                int bucket = Math.floorMod(token.hashCode(), DIMENSIONS);
                vector[bucket] += 1f;
            }
        }
        normalizeInPlace(vector);
        return vector;
    }

    private static List<String> tokenize(String text) {
        return Arrays.stream(TOKEN_SEPARATOR.split(text.toLowerCase(Locale.ROOT)))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    /**
     * [Purpose] L2-normalizes {@code vector} in place so cosine similarity between two of this
     * stub's outputs reduces to a plain dot product.
     * [Caution] A text with no tokens (empty/blank/all-punctuation input) leaves {@code vector}
     * as all zeros rather than dividing by a zero norm (which would fill it with NaN) --
     * cosine similarity against an all-zero vector is conventionally 0 (no signal), which is the
     * correct "no information" answer for that edge case.
     */
    private static void normalizeInPlace(float[] vector) {
        double sumOfSquares = 0;
        for (float component : vector) {
            sumOfSquares += (double) component * component;
        }
        if (sumOfSquares == 0) {
            return;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
