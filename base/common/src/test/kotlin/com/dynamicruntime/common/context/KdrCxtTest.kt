package com.dynamicruntime.common.context

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class KdrCxtTest : StringSpec({

    "a simple context defaults its client to the user profile client" {
        val cxt = KdrCxt.mkSimpleCxt("root")
        cxt.client shouldBe cxt.userProfile.client
        cxt.client shouldBe CL.hub
    }

    "a sub context inherits the parent client by default" {
        val root = KdrCxt.mkSimpleCxt("root")
        val sub = root.mkSubContext("child")
        sub.client shouldBe root.client
    }

    "a sub context can be bound to an alternate client without changing the parent" {
        val root = KdrCxt.mkSimpleCxt("root")
        val sub = root.mkSubContext("child", CL.public)
        sub.client shouldBe CL.public
        root.client shouldBe CL.hub
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

    "hasDebug matches a whole comma-separated word, not a substring (issue #107)" {
        val cxt = KdrCxt.mkSimpleCxt("request")
        val explainInput = DebugTagDef("explainInput", "Echoes the input.")
        val explain = DebugTagDef("explain", "A prefix of the word above.")
        val foo = DebugTagDef("foo", "Another word.")
        val bar = DebugTagDef("bar", "And another.")

        cxt.hasDebug(explainInput) shouldBe false // no _debug tag at all

        cxt.debug = "explainInput"
        cxt.hasDebug(explainInput) shouldBe true

        // A tag among several, and tolerant of the whitespace splitComma trims.
        cxt.debug = "foo, explainInput ,bar"
        cxt.hasDebug(explainInput) shouldBe true
        cxt.hasDebug(foo) shouldBe true
        cxt.hasDebug(bar) shouldBe true

        // The bug this replaces: a substring must NOT match.
        cxt.debug = "explainInputFully"
        cxt.hasDebug(explainInput) shouldBe false
        cxt.debug = "explainInput"
        cxt.hasDebug(explain) shouldBe false
    }

    "a word no loaded component declared is refused when checked, sent or not (issue #1334)" {
        val declared = DebugTagDef("declaredWord", "Declared by a component.")
        val cxt = KdrCxt.mkSimpleCxt("request")
        cxt.instanceConfig.put(DebugTags.registryKey, DebugTags.of(listOf(declared)))

        cxt.hasDebug(declared) shouldBe false
        cxt.debug = "declaredWord"
        cxt.hasDebugDiagnostic(declared) shouldBe false // declared and sent, but no test access here
        cxt.hasDebug(declared) shouldBe true

        shouldThrow<KdrException> { cxt.hasDebug(DebugTagDef("undeclaredWord", "Nobody declared it.")) }
            .message shouldContain "no loaded component declares it"
        // A second def spelling a declared word is a second description of it, and refused too.
        shouldThrow<KdrException> { cxt.hasDebug(DebugTagDef("declaredWord", "A rival description.")) }
    }

    "a word declared twice refuses the registry, and a def must be a word of a known kind" {
        shouldThrow<KdrException> {
            DebugTags.of(listOf(DebugTagDef("twice", "One."), DebugTagDef("twice", "Two.")))
        }.message shouldContain "'twice' is declared more than once"
        shouldThrow<IllegalArgumentException> { DebugTagDef("not a word", "Spaces.") }
        shouldThrow<IllegalArgumentException> { DebugTagDef("word", "Kindless.", kind = "other") }
    }

    "now is shifted by the time-travel offset" {
        val cxt = KdrCxt.mkSimpleCxt("root")
        val base = cxt.now().toEpochMilliseconds()
        cxt.nowTimeOffsetInSeconds = 60
        (cxt.now().toEpochMilliseconds() - base >= 59_000) shouldBe true
    }
})
