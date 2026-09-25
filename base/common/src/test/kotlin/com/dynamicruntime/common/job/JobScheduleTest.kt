package com.dynamicruntime.common.job

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The schedule rules' slot arithmetic (issue #870): which slot a moment falls in, and whether its window is open. */
class JobScheduleTest : StringSpec({
    fun at(iso: String) = Instant.parse(iso)

    "a daily rule's slot is the latest of its times, reaching back to yesterday before the first" {
        val rule = JobSchedule.daily("14:30", "02:00", window = 1.hours)
        rule.slotAt(at("2026-09-25T01:59:00Z")) shouldBe at("2026-09-24T14:30:00Z")
        rule.slotAt(at("2026-09-25T02:00:00Z")) shouldBe at("2026-09-25T02:00:00Z")
        rule.slotAt(at("2026-09-25T14:29:59Z")) shouldBe at("2026-09-25T02:00:00Z")
        rule.slotAt(at("2026-09-25T23:59:00Z")) shouldBe at("2026-09-25T14:30:00Z")
    }

    "a slot's window is open from its start until its window has passed" {
        val rule = JobSchedule.daily("02:00", window = 1.hours)
        rule.openSlot(at("2026-09-25T01:59:00Z")) shouldBe null
        rule.openSlot(at("2026-09-25T02:00:00Z")) shouldBe at("2026-09-25T02:00:00Z")
        rule.openSlot(at("2026-09-25T02:59:59Z")) shouldBe at("2026-09-25T02:00:00Z")
        rule.openSlot(at("2026-09-25T03:00:00Z")) shouldBe null
    }

    "an interval rule's slots are counted from the epoch, so every node agrees on them" {
        val rule = JobSchedule.every(15.minutes)
        rule.slotAt(at("2026-09-25T10:14:59Z")) shouldBe at("2026-09-25T10:00:00Z")
        rule.slotAt(at("2026-09-25T10:15:00Z")) shouldBe at("2026-09-25T10:15:00Z")
        // By default the window is the whole interval: one slot's closes as the next opens.
        rule.openSlot(at("2026-09-25T10:29:59Z")) shouldBe at("2026-09-25T10:15:00Z")
    }

    "every node names a slot the same way" {
        val rule = JobSchedule.daily("02:00")
        rule.launchName(rule.slotAt(at("2026-09-25T02:10:00Z"))) shouldBe "slot-2026-09-25T02:00:00.000Z"
    }

    "a time of day that is not HH:mm, or an interval under a second, is refused" {
        shouldThrow<KdrException> { JobSchedule.daily("2am") }
        shouldThrow<KdrException> { JobSchedule.daily("24:00") }
        shouldThrow<KdrException> { JobSchedule.every(500.milliseconds) }
        // Under a minute is a test's schedule: the rule allows it, and the boot check decides where.
        JobSchedule.every(30.seconds).minSpacing() shouldBe 30.seconds
    }

    "a daily rule's closest slots include the gap round midnight" {
        JobSchedule.daily("23:50", "00:10").minSpacing() shouldBe 20.minutes
        JobSchedule.daily("02:00").minSpacing() shouldBe 24.hours
    }
})
