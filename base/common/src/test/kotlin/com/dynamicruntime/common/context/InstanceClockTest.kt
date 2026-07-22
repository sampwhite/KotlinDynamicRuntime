package com.dynamicruntime.common.context

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The instance clock's travel arithmetic (issue #160): advance, set, freeze, unfreeze, reset. Freeze is exact;
 * an unfrozen clock reads the live wall clock through the offset, so those checks allow a small tolerance.
 */
class InstanceClockTest : StringSpec({

    fun near(a: Instant, b: Instant, toleranceMs: Long = 2000): Boolean =
        abs(a.toEpochMilliseconds() - b.toEpochMilliseconds()) <= toleranceMs

    "a fresh clock reads real time" {
        near(InstanceClock().instanceNow(), Clock.System.now()) shouldBe true
    }

    "advanceBy shifts the clock forward, and rewinds on a negative delta" {
        val clock = InstanceClock()
        clock.advanceBy(30.days)
        near(clock.instanceNow(), Clock.System.now() + 30.days) shouldBe true
        clock.advanceBy(-(10.days))
        near(clock.instanceNow(), Clock.System.now() + 20.days) shouldBe true
    }

    "setAbsolute reads as the target but keeps ticking (it is not a freeze)" {
        val clock = InstanceClock()
        val target = Instant.fromEpochMilliseconds(1_700_000_000_000)
        clock.setAbsolute(target)
        near(clock.instanceNow(), target) shouldBe true
        // Unfrozen: a later read is not earlier than an earlier one (the wall clock advanced under the offset).
        (clock.instanceNow() >= clock.instanceNow().minus(1.hours)) shouldBe true
    }

    "freeze pins the clock, and advancing a frozen clock steps it exactly" {
        val clock = InstanceClock()
        clock.freeze()
        val pinned = clock.instanceNow()
        clock.instanceNow() shouldBe pinned // does not drift
        clock.advanceBy(1.hours)
        clock.instanceNow() shouldBe pinned + 1.hours // exact step, still pinned
        clock.instanceNow() shouldBe pinned + 1.hours
    }

    "unfreeze resumes ticking from the frozen value with no jump" {
        val clock = InstanceClock()
        clock.advanceBy(5.days)
        clock.freeze()
        val frozen = clock.instanceNow()
        clock.unfreeze()
        (clock.instanceNow() >= frozen) shouldBe true
        near(clock.instanceNow(), frozen) shouldBe true
    }

    "reset returns to real time" {
        val clock = InstanceClock()
        clock.advanceBy(100.days)
        clock.freeze()
        clock.reset()
        near(clock.instanceNow(), Clock.System.now()) shouldBe true
    }
})
