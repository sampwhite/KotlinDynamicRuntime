package com.dynamicruntime.common.exception

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KdrExceptionTest : StringSpec({

    "defaults to internal error / system / general" {
        val e = KdrException("boom")
        e.code shouldBe EXC.internalError
        e.source shouldBe SRC.system
        e.activity shouldBe ACT.general
        e.message shouldBe "boom"
    }

    "an explicit code can be supplied while keeping default source/activity" {
        val e = KdrException("nope", code = EXC.notFound)
        e.code shouldBe EXC.notFound
        e.source shouldBe SRC.system
    }

    "mkInput produces a bad-input error attributed to code" {
        val e = KdrException.mkInput("bad")
        e.code shouldBe EXC.badInput
        e.source shouldBe SRC.system
        e.activity shouldBe ACT.code
    }

    "mkInput inherits source and activity from a KdrException cause" {
        val cause = KdrException("io fail", source = SRC.database, activity = ACT.io)
        val e = KdrException.mkInput("bad", cause)
        e.code shouldBe EXC.badInput
        e.source shouldBe SRC.database
        e.activity shouldBe ACT.io
    }

    "mkConv marks a conversion activity" {
        KdrException.mkConv("nope").activity shouldBe ACT.conversion
    }

    "mkFileIo carries file source, io activity, and the given code" {
        val e = KdrException.mkFileIo("missing", null, EXC.notFound)
        e.source shouldBe SRC.file
        e.activity shouldBe ACT.io
        e.code shouldBe EXC.notFound
    }

    "canRetry is true for retriable internal errors and for notAvailable" {
        KdrException("x", code = EXC.internalError, activity = ACT.connection).canRetry() shouldBe true
        KdrException("x", code = EXC.internalError, activity = ACT.io).canRetry() shouldBe true
        KdrException("x", code = EXC.notAvailable).canRetry() shouldBe true
        KdrException("x", code = EXC.badInput).canRetry() shouldBe false
        KdrException("x", code = EXC.internalError, activity = ACT.general).canRetry() shouldBe false
    }

    "fullMessage joins the KdrException cause chain in order" {
        val root = KdrException("root cause")
        val mid = KdrException("middle", root)
        val top = KdrException("top", mid)
        top.fullMessage() shouldBe "top middle root cause"
    }

    "fullMessage is just this message when there is no KdrException cause" {
        KdrException("solo").fullMessage() shouldBe "solo"
        KdrException("wrapped", RuntimeException("other")).fullMessage() shouldBe "wrapped"
    }
})
