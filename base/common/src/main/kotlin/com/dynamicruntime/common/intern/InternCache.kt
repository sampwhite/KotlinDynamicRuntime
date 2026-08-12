package com.dynamicruntime.common.intern

import com.dynamicruntime.common.exception.KdrException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Tuning for [InternCache] (issue #280). */
@Suppress("ConstPropertyName")
object IC {
    /**
     * Slow-path hits allowed before the settled map is rebuilt. Deliberately small: a rebuild is O(size) and
     * the whole point is to spend it rarely, but a low threshold keeps the pending map short, which is what
     * the slow path costs. A few hundred lookups is a rounding error against the copy they avoid.
     */
    const val defaultPromoteThreshold = 200
}

/**
 * A read-optimized interner: one instance per canonical string, for values whose text form never changes
 * (issue #280).
 *
 * ### What it buys
 *
 * Two things, and the second is the one that shapes the design. Interned values can be compared with `===`
 * and make cheap hash keys. And where a cache holds *every* extant value of its kind, a lookup miss is itself
 * an answer: "no such id exists" is available without touching the database.
 *
 * That second property is why this is a backend structure and why a cache belongs to **one running instance**.
 * A cache reachable from a global singleton would be shared by every instance in the process — and a test
 * suite boots many — so one instance's ids would answer another instance's existence questions. Hold a cache
 * where the instance holds it, and register it with that instance's [InternService].
 *
 * ### How it reads
 *
 * Two maps. [settled] is an ordinary immutable map, replaced wholesale and published through a volatile
 * field, so a hit costs one volatile read and one map lookup with no lock, no CAS, and no bin-walking
 * machinery. [pending] is a [ConcurrentHashMap] holding whatever has been interned since the last rebuild.
 *
 * A lookup tries the settled map first and the pending map second. Every time it has to try the second, a
 * counter moves; at [promoteThreshold] the settled map is rebuilt to fold the pending entries in, and the
 * pending map empties. In the steady state the population is fully settled and nothing but the first lookup
 * ever runs. A [rebuild] can also be asked for directly, which is what boot does once the extant values are
 * loaded — see [InternService].
 *
 * The cost is the rebuild itself: an O(size) copy during which writers wait. That is the trade being made
 * deliberately — a hiccup on the rare path in exchange for no synchronization at all on the common one.
 *
 * ### Why not just a ConcurrentHashMap
 *
 * That was the alternative considered, and for a write-heavy structure it would be the right answer.
 * `ConcurrentHashMap.get` is already lock-free. But it is not free: it reads the table reference and each bin
 * head with volatile semantics and carries the branching for tree bins and resizes, none of which a map that
 * has stopped changing needs. This structure exists because after boot these maps *have* stopped changing, so
 * the reads should pay for a map that cannot change rather than one that might.
 *
 * The same shape is already in the codebase at smaller scale — `SqlColumnAliases` keeps immutable maps beside
 * a concurrent "feeder" and rebuilds from it — which is prior evidence it works here.
 */
class InternCache<T : Internable>(
    /** Names the cache in diagnostics and in [InternService]'s registry; unique within an instance. */
    val name: String,
    /** Slow-path hits before an automatic [rebuild]; see [IC.defaultPromoteThreshold]. */
    val promoteThreshold: Int = IC.defaultPromoteThreshold,
) {
    /**
     * Everything interned as of the last rebuild. Immutable and never mutated in place — a rebuild publishes a
     * *new* map — so a reader that has read the reference holds a consistent snapshot for as long as it likes.
     */
    @Volatile
    private var settled: Map<String, T> = emptyMap()

    /** Interned since the last rebuild. Read without a lock, so it must be a concurrent map. */
    private val pending = ConcurrentHashMap<String, T>()

    /** Lookups that had to fall through to [pending]; drives the automatic rebuild. */
    private val fallThroughs = AtomicInteger()

    /** Guards writes and rebuilds against each other. Readers never take it. */
    private val writeLock = Any()

    /** Rebuilds so far — a stat worth watching, since each one is the hiccup this design trades for. */
    @Volatile
    var rebuildCount: Int = 0
        private set

    /**
     * How many values are interned, settled and pending together. A diagnostic rather than a fact: reading the
     * two maps without the lock can catch a rebuild mid-flight, where an entry has been added to the settled
     * map and not yet removed from the pending one, and count it twice. Settling first gives an exact answer.
     */
    val size: Int get() = settled.size + pending.size

    /** How many are in the fast map; `size - settledSize` is what a rebuild would fold in. */
    val settledSize: Int get() = settled.size

    /** Lookups so far that fell through to the pending map, since the last rebuild reset the count. */
    val fallThroughCount: Int get() = fallThroughs.get()

    /**
     * The interned value for [key], or null when nothing has been interned under it.
     *
     * For a cache holding every extant value of its kind, null *is* the existence answer — see the class note.
     */
    fun get(key: String): T? {
        val current = settled
        current[key]?.let { return it }
        val found = pending[key]
        if (found == null) {
            // The window that makes this necessary: a rebuild can publish the new settled map and then empty
            // pending between the two lookups above, leaving a key that exists in neither of the maps THIS
            // call read. Re-reading the field closes it, because a settled map is only ever replaced by a
            // superset of itself -- so the latest one holds anything promoted while we were looking. The
            // reference check keeps the ordinary "no such key" answer at one comparison.
            val latest = settled
            return if (latest === current) null else latest[key]
        }
        // `==` rather than `>=` so exactly one thread elects itself per crossing. With `>=`, every reader
        // arriving after the threshold would queue on the write lock to discover the work was already done.
        if (fallThroughs.incrementAndGet() == promoteThreshold) {
            rebuild()
        }
        return found
    }

    /** Whether anything is interned under [key]; see [get] for what a false answer means. */
    fun contains(key: String): Boolean = get(key) != null

    /**
     * The single instance for [value]'s canonical form: [value] itself if it is the first of its form, or the
     * instance already holding that form. The caller must use the returned value and discard its own.
     *
     * Use [getOrIntern] instead where building the value is expensive — this one has to build first and may
     * then throw the result away.
     */
    fun intern(value: T): T {
        val key = value.toInternString()
        get(key)?.let { return it }
        synchronized(writeLock) {
            // Re-check under the lock. The gap between the read above and here is exactly where a concurrent
            // rebuild moves keys and a concurrent intern adds them; without this, two threads racing on a new
            // key would each keep their own instance and the identity guarantee would be quietly gone.
            lockedGet(key)?.let { return it }
            pending[key] = value
            return value
        }
    }

    /**
     * The interned value for [key], building it with [create] only if nothing holds that form yet. The
     * lookup happens first, so the common case costs no construction at all.
     *
     * [create] runs under the write lock, so it should do no more than parse — anything that could block, or
     * that reaches back into this cache, stalls every other writer.
     */
    fun getOrIntern(key: String, create: (String) -> T): T {
        get(key)?.let { return it }
        synchronized(writeLock) {
            lockedGet(key)?.let { return it }
            val value = create(key)
            val spelled = value.toInternString()
            if (spelled != key) {
                // Half of the round-trip contract, and the half that is checkable here. A value filed under a
                // key it does not spell is unreachable by its own form: the next lookup builds a second one,
                // and `===` starts answering false for values that are equal.
                throw KdrException(
                    "Interning '$key' in cache '$name' produced a value spelling itself '$spelled'. " +
                        "A value must be interned under the string it returns from toInternString().",
                )
            }
            pending[key] = value
            return value
        }
    }

    /**
     * Interns every value in [values] and rebuilds once, rather than paying a lock acquisition each and a
     * rebuild somewhere in the middle. This is the boot-time load path: read the extant values, hand them over
     * in one go, and the cache is settled when it returns.
     *
     * Returns the number newly interned; a value whose form is already held is skipped and its existing
     * instance kept.
     */
    fun internAll(values: Collection<T>): Int {
        synchronized(writeLock) {
            var added = 0
            for (value in values) {
                val key = value.toInternString()
                if (lockedGet(key) == null) {
                    pending[key] = value
                    added++
                }
            }
            rebuildLocked()
            return added
        }
    }

    /**
     * Folds the pending entries into the settled map and empties the pending one.
     *
     * Writers wait for the duration; readers do not, and go on seeing the previous settled map until the new
     * one is published. Cheap and safe to call when there is nothing pending.
     */
    fun rebuild() {
        synchronized(writeLock) { rebuildLocked() }
    }

    /** [get] without the fall-through accounting, for use where the write lock is already held. */
    private fun lockedGet(key: String): T? = settled[key] ?: pending[key]

    private fun rebuildLocked() {
        // Reset regardless: with nothing pending, every fall-through counted so far was for a key that has
        // since settled, so leaving the count standing would trigger an empty rebuild on the next lookup.
        fallThroughs.set(0)
        if (pending.isEmpty()) {
            return
        }
        val next = HashMap<String, T>(settled.size + pending.size)
        next.putAll(settled)
        next.putAll(pending)
        // A settled map is only ever replaced by a superset of itself. [get] leans on that to close the window
        // between publishing and clearing, so nothing here may prune.
        settled = next
        pending.clear()
        rebuildCount++
    }
}
