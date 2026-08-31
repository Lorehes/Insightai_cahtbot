package art.insightai.chatbot.rag.ingest;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * [Purpose] Per-document mutual exclusion between {@link DocumentDeleteService#delete} and
 *   {@link DocumentIngestionPipeline}'s chunk-commit step, so the two can never interleave in the
 *   order that produces an orphaned chunk (document-ingestion:AC-17, review 2026-08-30 T10
 *   High-1, user-approved fix 1).
 * [Rationale] The review's High-1 traced the orphan to a race window the database's own
 *   transaction isolation cannot close on its own: the delete commits "0 chunks removed" and
 *   returns 204 while the pipeline is still between parsing and its own chunk insert, and the
 *   pipeline then inserts rows for a {@code documents} id that no longer exists -- rows the
 *   search-exclusion device ({@code NonIndexedDocumentLookup}) cannot filter because it only
 *   knows how to exclude a row that exists but is not INDEXED, not a chunk whose document row is
 *   entirely absent. Locking by document id turns the two operations into strict alternatives
 *   instead of closing the window with a database-level guarantee: whichever of {@link
 *   DocumentDeleteService#delete}/{@link DocumentIngestionPipeline}'s commit step gets here first
 *   for a given id runs to completion before the other is allowed to start, so the pipeline can
 *   always tell -- inside the same critical section -- whether the row it is about to commit
 *   chunks for is still there.
 *   <p>
 *   <b>Fixed 64-slot stripe array, not one {@link ReentrantLock} per id (review 2026-08-30 T10
 *   round 3, High-1).</b> The round-1/round-2 design keyed a never-evicted {@code
 *   ConcurrentHashMap<UUID, ReentrantLock>} by document id, on the premise that this project's
 *   document set is demo-scale and therefore the map stays small. Round 3 found that premise
 *   false on its own terms even without an attacker: {@code DELETE /api/admin/documents/{id}}
 *   is unauthenticated (§7 OQ-7), so a repeated call against distinct, arbitrary UUIDs -- found
 *   or not -- would have kept adding entries the map never removes, one per id ever seen,
 *   forever. A fixed-size array of {@link ReentrantLock} instances, indexed by {@code
 *   Math.floorMod(documentId.hashCode(), stripes.length)}, removes the growth path structurally:
 *   the number of lock objects this class ever allocates is {@link #DEFAULT_STRIPE_COUNT},
 *   decided once at construction, independent of how many distinct ids are ever looked up
 *   -- valid or not, attacker-supplied or not. 64 is a demo-scale placeholder sized the same way
 *   as this project's other unmeasured concurrency constants ({@code Rerank.connectTimeout} and
 *   {@code Ingest.deleteLockTimeout} in {@link art.insightai.chatbot.common.config.AppProperties}):
 *   generous enough that two genuinely unrelated documents rarely land on the same stripe at this
 *   project's real concurrency (a single-JVM demo deployment, see class [Caution] below), not a
 *   number backed by a measured collision rate.
 * [Side effects] None beyond the in-process locks themselves.
 * [Caution] <b>This lock only enforces mutual exclusion within a single JVM process.</b> It is an
 *   in-memory stripe array, not a database advisory lock or a distributed lock service, so it
 *   says nothing about a second application instance running against the same database. That is
 *   a correct trade for this project today -- the stack table (project CLAUDE.md) runs one
 *   backend instance for the local demo -- but if this service is ever scaled to more than one
 *   instance, this class stops preventing the orphan race it exists for: two instances each
 *   enforce exclusion only among their own threads, and the exact interleaving AC-17 guards
 *   against becomes reachable again across instances. Scaling out requires replacing this with a
 *   cross-process mechanism (e.g. a {@code pg_advisory_lock} keyed the same way) before this
 *   guarantee can be relied on again.
 *   <p>
 *   <b>Two distinct document ids can share a stripe.</b> {@code Math.floorMod(hashCode(),
 *   {@link #DEFAULT_STRIPE_COUNT})} is a many-to-one mapping, so an unrelated document's delete
 *   or ingest can block behind a completely different document's critical section purely because
 *   their {@link UUID#hashCode()} values collide modulo the stripe count. This is a deliberate
 *   trade for the fixed-size bound above and is harmless at this project's scale -- at most a
 *   few concurrent operations in a single-JVM demo -- but it means this class no longer offers
 *   true per-document isolation the way the round-1/round-2 one-lock-per-id map did. Do not rely
 *   on "locking id A never delays an operation on id B" holding in general; only "operations on
 *   id A never delay other operations on id A less than a full critical section" is guaranteed.
 *   <p>
 *   Only two call sites ever acquire a lock from this registry -- {@link
 *   DocumentDeleteService#delete} and {@link DocumentIngestionPipeline#ingest}'s chunk-commit
 *   step -- and neither acquires a second stripe, nor any other lock, while already holding one
 *   from here. That structural fact is what rules out deadlock without needing every acquisition
 *   to be timed: there is no cycle to form when no call site ever waits on a second lock while
 *   holding a first, and a stripe collision between two unrelated ids only serializes them, it
 *   does not introduce a wait-for cycle either. {@link DocumentDeleteService#delete} additionally
 *   uses {@link #tryLock} with a bounded timeout (review 2026-08-30 T10 round 3, Medium-1) so an
 *   unusually slow same-stripe holder cannot park its caller's request thread indefinitely; {@link
 *   DocumentIngestionPipeline} still uses the unbounded {@link #lock}, which is safe only because
 *   it is not reachable from an unauthenticated per-request path the way {@code DELETE
 *   /api/admin/documents/{id}} is. Do not add a third call site that acquires a lock from this
 *   registry while already holding one (for the same id or a different one) without
 *   re-examining this argument.
 */
@Component
public class DocumentIngestLockRegistry {

    /**
     * Number of {@link ReentrantLock} instances this registry ever allocates, fixed at
     * construction (review 2026-08-30 T10 round 3, High-1) -- see class-level [Rationale] for
     * why this replaces a per-id, never-evicted map, and class-level [Caution] for the
     * cross-id-contention trade this fixed size accepts.
     */
    private static final int DEFAULT_STRIPE_COUNT = 64;

    private final ReentrantLock[] stripes;

    public DocumentIngestLockRegistry() {
        this(DEFAULT_STRIPE_COUNT);
    }

    /**
     * [Purpose] Package-visible constructor letting a test pick a small stripe count to make a
     *   same-stripe collision between two distinct ids reproducible without depending on {@link
     *   UUID#hashCode()} happening to collide modulo 64.
     */
    DocumentIngestLockRegistry(int stripeCount) {
        this.stripes = new ReentrantLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * [Purpose] Resolves {@code documentId} to its fixed stripe.
     * [Rationale] {@link Math#floorMod} rather than {@code %} so a negative {@link
     *   UUID#hashCode()} still lands on a valid, non-negative array index.
     */
    private ReentrantLock stripeFor(UUID documentId) {
        return stripes[Math.floorMod(documentId.hashCode(), stripes.length)];
    }

    /**
     * [Purpose] Blocks until the stripe for {@code documentId} is free, then acquires it.
     * [Caution] Always pair with {@link #unlock} in a {@code finally} block. See class-level
     *   [Caution] for why this cannot deadlock as long as no caller nests a second acquisition
     *   inside the critical section this guards, and for the cross-id contention a stripe
     *   collision can cause. Unbounded wait -- prefer {@link #tryLock} for any caller reachable
     *   from an unauthenticated request path (document-ingestion T10 review 2026-08-30 round 3,
     *   Medium-1).
     */
    public void lock(UUID documentId) {
        stripeFor(documentId).lock();
    }

    /**
     * [Purpose] Attempts to acquire the stripe for {@code documentId}, giving up after {@code
     *   timeout} rather than waiting indefinitely (document-ingestion T10 review 2026-08-30
     *   round 3, Medium-1).
     * [Rationale] {@link DocumentDeleteService#delete} is reachable from an unauthenticated
     *   {@code DELETE /api/admin/documents/{id}} request, so a same-stripe holder that runs
     *   unusually long (e.g. a stalled OpenRouter embedding round-trip inside {@link
     *   DocumentIngestionPipeline}) must not be able to park the request thread handling that
     *   call forever -- see {@code AppProperties.Ingest#deleteLockTimeout}'s own [Rationale] for
     *   why 30 seconds is this project's current placeholder.
     * [Side effects] If the calling thread is interrupted while waiting, the interrupt flag is
     *   restored via {@link Thread#interrupt()} and this method returns {@code false} -- the same
     *   outcome as a timeout, since neither leaves the caller holding the stripe.
     * [Caution] Returns {@code false} instead of throwing on either a timeout or an interrupt.
     *   Only call {@link #unlock} if this method returned {@code true}.
     */
    public boolean tryLock(UUID documentId, long timeout, TimeUnit unit) {
        try {
            return stripeFor(documentId).tryLock(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * [Purpose] Releases the stripe for {@code documentId} previously acquired via {@link #lock}
     *   or a successful {@link #tryLock}.
     * [Caution] Must be called from the same thread that acquired the stripe, and exactly once
     *   per successful acquisition ({@link ReentrantLock}'s own contract). Because stripes are
     *   shared across ids (class-level [Caution]), calling this for an id that never actually
     *   acquired the stripe (e.g. after a {@link #tryLock} that returned {@code false}) would
     *   release a lock this call did not hold -- {@link ReentrantLock#unlock()} then throws
     *   {@link IllegalMonitorStateException} rather than silently corrupting the lock, so callers
     *   are structurally protected against that specific misuse.
     */
    public void unlock(UUID documentId) {
        stripeFor(documentId).unlock();
    }
}
