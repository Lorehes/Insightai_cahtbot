package art.insightai.chatbot.rag.retrieval;

import java.util.List;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

/**
 * [Purpose] Type-safe bundle of the two advisors {@code ChatService} must always attach together
 *   when RAG is enabled for a conversation: {@link RetrievalAugmentationAdvisor} (search plus
 *   augmentation) and its companion {@link RagEmptyContextGuardAdvisor} (hybrid-rag:AC-5's "모른다"
 *   short-circuit). See {@link RagAdvisorConfig#ragAdvisors} for why this record replaced the plain
 *   {@code List<Advisor>} it used to return.
 * [Rationale] T10 재작업 2라운드 (2026-08-30, 리뷰 2R Block security Medium-2): a bare {@code
 *   List<Advisor>} constructor/injection point compiles equally well whether it holds one advisor,
 *   two, or ten -- {@code .advisors(ragAdvisors.get(0))} (RAG attached without the guard) is a
 *   valid call the compiler accepts just as readily as the correct two-element call. It was also
 *   matched into {@code ChatService}'s constructor by Spring only through the injected parameter's
 *   own *name* ({@code ragAdvisors}) -- {@code DefaultListableBeanFactory}'s by-name-then-by-type
 *   resolution for a generic {@code List<Advisor>} target, confirmed by reading that class's source
 *   -- not by any type distinct enough to guarantee "the RAG advisor and its guard, together, and
 *   nothing else" independent of that name staying exactly as written on both ends. Making the pair
 *   its own type closes both holes at once: {@link #toList()} is the only way to obtain a {@code
 *   List<Advisor>} from this record, and it always contains exactly these two advisors, in this
 *   fixed order -- there is no constructor or accessor on this type that yields one without the
 *   other, so "attach RAG without the guard" is no longer an expression the compiler accepts at
 *   this record's own boundary, and Spring resolves an injection point of this type by the type
 *   itself, not by a generic collection type plus a name that happens to match today.
 * [Caution] {@link #toList()}'s order (RAG first, guard second) is for readability only -- see
 *   {@link RagAdvisorConfig#ragAdvisors}'s own Javadoc for why {@code
 *   DefaultAroundAdvisorChain.Builder.reOrder()} re-sorts by {@code getOrder()} regardless of list
 *   order, so this record's own field/list order does not itself fix advisor execution order;
 *   {@link RagAdvisorConfig#RAG_ADVISOR_ORDER}/{@link RagEmptyContextGuardAdvisor#ORDER} do that.
 */
public record RagAdvisorPair(RetrievalAugmentationAdvisor rag, RagEmptyContextGuardAdvisor guard) {

    /**
     * [Purpose] Converts this pair to the {@code List<Advisor>} shape {@code
     *   ChatClient.ChatClientRequestSpec#advisors(List)} requires -- the only production call site
     *   that ever needs this pair as a plain list.
     */
    public List<Advisor> toList() {
        return List.of(rag, guard);
    }
}
