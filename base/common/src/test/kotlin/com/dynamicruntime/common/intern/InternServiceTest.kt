package com.dynamicruntime.common.intern

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private class Marker(val text: String) : Internable {
    override fun toInternString(): String = text
}

/**
 * The instance's registry of intern caches, and the boot sweep it exists for (issue #280).
 *
 * The sweep is the part worth pinning: a cache loaded during startup is entirely pending, so without it every
 * lookup pays the slow path until enough of them have paid it to trigger a rebuild — correct, but the cost
 * lands on the first traffic the instance ever serves, which is exactly where it is least welcome.
 */
class InternServiceTest : StringSpec({

    "the sweep settles every registered cache" {
        val service = InternService()
        val ids = service.register(InternCache<Marker>("ids"))
        val names = service.register(InternCache<Marker>("names"))
        ids.intern(Marker("a"))
        names.intern(Marker("b"))
        ids.settledSize shouldBe 0
        names.settledSize shouldBe 0

        service.rebuildAll()
        ids.settledSize shouldBe 1
        names.settledSize shouldBe 1
    }

    "registering returns the cache, so wiring is one expression" {
        val service = InternService()
        val cache = InternCache<Marker>("ids")
        (service.register(cache) === cache) shouldBe true
        // Registering the same instance again is a no-op, not a complaint.
        service.register(cache)
        service.cacheNames() shouldContainExactly listOf("ids")
        (service.cache("ids") === cache) shouldBe true
        service.cache("absent") shouldBe null
    }

    // A name identifies a cache within an instance, so a collision means two subsystems each believe they own
    // the interning of some kind of value. Left silent, one of them would quietly get the other's cache.
    "two different caches cannot claim one name" {
        val service = InternService()
        service.register(InternCache<Marker>("ids"))
        shouldThrow<KdrException> { service.register(InternCache<Marker>("ids")) }
            .message.shouldNotBeNull() shouldContain "'ids'"
    }

    "an empty sweep is harmless" {
        val service = InternService()
        service.rebuildAll()
        service.cacheNames() shouldContainExactly emptyList()
    }
})
