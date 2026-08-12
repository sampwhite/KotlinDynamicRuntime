package com.dynamicruntime.common.intern

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A stand-in for the ids this exists for: immutable, with structure extracted from its text form. */
private class Tag(val text: String) : Internable {
    val kind: String = text.substringBefore(':', "")
    override fun toInternString(): String = text
}

/** Deliberately spells itself as something other than what it was asked for. */
private class Liar(private val spelling: String) : Internable {
    override fun toInternString(): String = spelling
}

/**
 * The interner (issue #280).
 *
 * Two things are worth testing here and they are not the same thing. One is the contract a caller sees: one
 * instance per form, and a miss that means "nothing holds this form". The other is that the contract survives
 * the two-map machinery underneath — because every way this structure can break is a race, and a race that
 * breaks it produces a *plausible* answer (a second instance, or a null for a key that exists) rather than a
 * crash. Nothing in a single-threaded test would notice either.
 */
class InternCacheTest : StringSpec({

    "the same form yields the same instance, and the loser is discarded" {
        val cache = InternCache<Tag>("tags")
        val first = Tag("user:1")
        val second = Tag("user:1")

        cache.intern(first) shouldBe first
        // Not merely equal -- the same object, which is what makes `===` a legitimate identity test.
        (cache.intern(second) === first) shouldBe true
        (cache.intern(second) === second) shouldBe false
        cache.size shouldBe 1
    }

    "a miss is the answer that nothing holds this form" {
        val cache = InternCache<Tag>("tags")
        cache.intern(Tag("user:1"))
        cache.get("user:1").shouldNotBeNull().kind shouldBe "user"
        cache.get("user:2").shouldBeNull()
        cache.contains("user:2") shouldBe false
    }

    // The reason getOrIntern exists: on a hit there is nothing to build.
    "getOrIntern builds only the first time" {
        val cache = InternCache<Tag>("tags")
        val builds = AtomicInteger()
        val make: (String) -> Tag = { key -> builds.incrementAndGet(); Tag(key) }

        val one = cache.getOrIntern("user:1", make)
        (cache.getOrIntern("user:1", make) === one) shouldBe true
        cache.getOrIntern("user:2", make)
        builds.get() shouldBe 2
    }

    // A value filed under a key it does not spell is unreachable by its own form: the next lookup builds a
    // second one, and `===` starts answering false for values that are equal. Caught where it happens rather
    // than left to surface as an identity comparison failing somewhere unrelated.
    "a value that does not spell itself back is refused" {
        val cache = InternCache<Liar>("liars")
        val message = shouldThrow<KdrException> {
            cache.getOrIntern("asked-for") { Liar("something-else") }
        }.message
        message.shouldNotBeNull() shouldContain "asked-for"
        message shouldContain "something-else"
        cache.size shouldBe 0
    }

    "a rebuild settles what is pending, and is free when there is nothing to do" {
        val cache = InternCache<Tag>("tags")
        cache.intern(Tag("a"))
        cache.intern(Tag("b"))
        cache.settledSize shouldBe 0
        cache.size shouldBe 2

        cache.rebuild()
        cache.settledSize shouldBe 2
        cache.rebuildCount shouldBe 1

        // Nothing pending: no copy, no count.
        cache.rebuild()
        cache.rebuildCount shouldBe 1
    }

    // The self-tuning half of the design. Until a value settles, every lookup of it pays the second map; the
    // counter measures exactly that traffic, and enough of it buys a rebuild.
    "enough slow-path lookups settle the map on their own" {
        val cache = InternCache<Tag>("tags", promoteThreshold = 5)
        cache.intern(Tag("a"))
        cache.settledSize shouldBe 0

        repeat(4) { cache.get("a").shouldNotBeNull() }
        cache.rebuildCount shouldBe 0
        cache.fallThroughCount shouldBe 4

        cache.get("a").shouldNotBeNull()
        cache.rebuildCount shouldBe 1
        cache.settledSize shouldBe 1
        cache.fallThroughCount shouldBe 0

        // Settled now, so further lookups never reach the second map and never count.
        repeat(20) { cache.get("a").shouldNotBeNull() }
        cache.fallThroughCount shouldBe 0
        cache.rebuildCount shouldBe 1
    }

    // The boot-load path: hand over everything at once and it is settled on return, instead of the first few
    // hundred requests each paying the slow path to discover what was already known.
    "internAll settles in one pass and keeps the instances already held" {
        val cache = InternCache<Tag>("tags")
        val held = Tag("a")
        cache.intern(held)
        cache.rebuild()

        cache.internAll(listOf(Tag("a"), Tag("b"), Tag("c"))) shouldBe 2
        cache.settledSize shouldBe 3
        cache.rebuildCount shouldBe 2
        (cache.get("a") === held) shouldBe true
    }

    // --- the races ----------------------------------------------------------

    // The guarantee the whole structure exists to provide, under the condition that can break it. Many threads
    // interning one form at the same moment must agree on one instance; if they do not, `===` is a lie and
    // every cache keyed by these values silently grows duplicates.
    "concurrent interning of one form settles on a single instance" {
        val cache = InternCache<Tag>("tags")
        val threads = 8
        val start = CountDownLatch(1)
        val seen = ConcurrentHashMap<Tag, Boolean>()

        val workers = (1..threads).map {
            Thread {
                start.await()
                repeat(200) { i -> seen[cache.intern(Tag("shared:$i"))] = true }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }

        // One winner per form, whichever thread got there first -- 200 forms, 200 surviving instances.
        seen.size shouldBe 200
        cache.size shouldBe 200
    }

    // The window this design has to close, and the one a single-threaded test cannot reach. A rebuild
    // publishes the new settled map and then empties the pending one; a reader that read the OLD settled map
    // before the publish and the pending map after the clear looks in two places and finds a key in neither.
    // The failure is a null for a value that exists -- which, for a cache whose misses answer "no such id",
    // is a wrong answer rather than a slow one.
    "a lookup never misses a value that a concurrent rebuild is moving" {
        val cache = InternCache<Tag>("interleaved", promoteThreshold = Int.MAX_VALUE)
        val highest = AtomicInteger(-1)
        val done = AtomicBoolean(false)
        val missed = AtomicReference<String?>(null)

        val writer = Thread {
            for (i in 0 until 4000) {
                cache.intern(Tag("k:$i"))
                highest.set(i) // published only after the value is interned
                cache.rebuild()
            }
            done.set(true)
        }
        val readers = (1..3).map {
            Thread {
                while (!done.get()) {
                    val i = highest.get()
                    if (i >= 0 && cache.get("k:$i") == null) {
                        missed.compareAndSet(null, "k:$i")
                        return@Thread
                    }
                }
            }
        }

        writer.start()
        readers.forEach { it.start() }
        writer.join()
        readers.forEach { it.join() }

        missed.get().shouldBeNull()
        cache.settledSize shouldBe 4000
    }

    "reads stay correct while writers add and settle around them" {
        val cache = InternCache<Tag>("mixed", promoteThreshold = 50)
        val done = AtomicBoolean(false)
        val wrong = AtomicReference<String?>(null)

        val writers = (1..3).map { w ->
            Thread {
                repeat(500) { i -> cache.intern(Tag("w$w:$i")) }
            }
        }
        val reader = Thread {
            while (!done.get()) {
                // Every form this thread interned itself is one it must keep finding, whatever the writers
                // and the automatic rebuilds are doing to the maps underneath.
                val mine = cache.intern(Tag("reader:only"))
                if (cache.get("reader:only") !== mine) {
                    wrong.compareAndSet(null, "identity changed")
                    return@Thread
                }
            }
        }

        reader.start()
        writers.forEach { it.start() }
        writers.forEach { it.join() }
        done.set(true)
        reader.join()

        wrong.get().shouldBeNull()
        cache.rebuild()
        cache.settledSize shouldBe 1501
        cache.get("w2:499").shouldNotBeNull()
    }

    "a cache reports what it is holding" {
        val cache = InternCache<Tag>("named", promoteThreshold = 7)
        cache.name shouldBe "named"
        cache.promoteThreshold shouldBe 7
        cache.internAll(listOf(Tag("a"), Tag("b")))
        listOf(cache.size, cache.settledSize, cache.rebuildCount) shouldContainExactly listOf(2, 2, 1)
    }
})
