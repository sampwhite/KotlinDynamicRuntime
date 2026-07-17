package com.dynamicruntime.common.context

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class KdrCxtTest : StringSpec({

    "a simple context defaults its account to the user profile account" {
        val cxt = KdrCxt.mkSimpleCxt("root")
        cxt.account shouldBe cxt.userProfile.account
        cxt.account shouldBe AC.local
    }

    "a sub context inherits the parent account by default" {
        val root = KdrCxt.mkSimpleCxt("root")
        val sub = root.mkSubContext("child")
        sub.account shouldBe root.account
    }

    "a sub context can be bound to an alternate account without changing the parent" {
        val root = KdrCxt.mkSimpleCxt("root")
        val sub = root.mkSubContext("child", AC.public)
        sub.account shouldBe AC.public
        root.account shouldBe AC.local
    }

    "locals propagate as a clone to a sub context but session does not" {
        val root = KdrCxt.mkSimpleCxt("root")
        root.locals["a"] = 1
        root.session["b"] = 2

        val sub = root.mkSubContext("child")
        sub.locals.containsKey("a") shouldBe true
        sub.session.containsKey("b") shouldBe false

        // The clone is independent: mutating the sub does not affect the parent.
        sub.locals["c"] = 3
        root.locals.containsKey("c") shouldBe false
    }

    "logging ids are unique and the context path chains parents in order" {
        val root = KdrCxt.mkSimpleCxt("root")
        val sub = root.mkSubContext("child")

        (sub.loggingId == root.loggingId) shouldBe false
        sub.parentLoggingIds shouldContain root.loggingId
        sub.cxtPath() shouldBe "${root.loggingId}:${sub.loggingId}"
    }

    "a client trace id leads the context path and the log label (issue #105)" {
        val cxt = KdrCxt.mkSimpleCxt("request")
        cxt.traceId = "2026071712000012307"

        cxt.cxtPath() shouldBe "2026071712000012307:${cxt.loggingId}"
        // logInfo is what a log line actually renders, so the trace id has to reach it, not just cxtPath.
        cxt.logInfo() shouldContain "2026071712000012307:"
    }

    "request identity (appId, traceId) travels to sub contexts, so their logs correlate too" {
        val root = KdrCxt.mkSimpleCxt("request")
        root.appId = "kdr.en"
        root.traceId = "2026071712000012307"
        val sub = root.mkSubContext("child")

        sub.appId shouldBe "kdr.en"
        sub.traceId shouldBe "2026071712000012307"
        sub.cxtPath() shouldBe "2026071712000012307:${root.loggingId}:${sub.loggingId}"
    }

    "now is shifted by the time-travel offset" {
        val cxt = KdrCxt.mkSimpleCxt("root")
        val base = cxt.now().toEpochMilliseconds()
        cxt.nowTimeOffsetInSeconds = 60
        (cxt.now().toEpochMilliseconds() - base >= 59_000) shouldBe true
    }
})
