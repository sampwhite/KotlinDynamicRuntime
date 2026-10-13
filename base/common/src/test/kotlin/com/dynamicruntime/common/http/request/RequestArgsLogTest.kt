package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

/**
 * The request log's view of a request's arguments (issue #1168): secrets redacted with their keys kept, addresses
 * masked wherever they appear, long values cut, nesting followed two levels, bytes as `file`, and the whole bounded.
 */
class RequestArgsLogTest : StringSpec({
    fun fmt(args: Map<String, Any?>, declared: Set<String> = emptySet()) = RequestArgsLog.format(args, declared)

    "short plain values are written as they are" {
        fmt(mapOf("limit" to 20, "active" to true, "q" to "plan", "none" to null)) shouldBe "{limit=20, active=true, q=plan, none=null}"
    }

    "a secret keeps its key and loses its value, by name or by declaration" {
        val line = fmt(
            mapOf("password" to "hunter2", "formAuthToken" to "abc.def", "newPassword" to "x", "pin" to "1234"),
            declared = setOf("pin"),
        )
        line shouldBe "{password=<redacted>, formAuthToken=<redacted>, newPassword=<redacted>, pin=<redacted>}"
        // A name the list does not cover, undeclared, is written: the declaration is how a new secret is caught.
        fmt(mapOf("pin" to "1234")) shouldBe "{pin=1234}"
        // Not every name ending in `key`: copy keys and sort keys are ordinary.
        fmt(mapOf("key" to "home.brand", "sortKey" to "name")) shouldBe "{key=home.brand, sortKey=name}"
    }

    "a secret is found at any depth by its name" {
        fmt(mapOf("login" to mapOf("verifyCode" to "424242"))) shouldBe "{login={verifyCode=<redacted>}}"
    }

    "an email address keeps its first character and its domain, wherever it appears" {
        fmt(mapOf("user" to "pat@acme.test")) shouldBe "{user=p***@acme.test}"
        fmt(mapOf("search" to "orders from pat.lee@acme.test today")) shouldBe "{search=orders from p***@acme.test today}"
    }

    "a long string is cut to its start" {
        val long = "x".repeat(100)
        fmt(mapOf("note" to long)) shouldBe "{note=${"x".repeat(RequestArgsLog.longValue)}...}"
    }

    "nesting is followed two levels, then reads obj; bytes and file parts read file" {
        fmt(mapOf("a" to mapOf("b" to mapOf("c" to 1)))) shouldBe "{a={b=obj}}"
        fmt(mapOf("ids" to listOf(1, 2, 3))) shouldBe "{ids=[1, 2, 3]}"
        fmt(mapOf("upload" to ContentData(ByteArray(4), "image/png"), "raw" to ByteArray(2))) shouldBe "{upload=file, raw=file}"
    }

    "past the budget values are abbreviated and nothing is followed; past more, keys alone; then it ends" {
        val big = (1..60).associate { "field$it" to "value number $it" }
        val line = fmt(big + mapOf("nested" to mapOf("x" to 1)))
        (line.length <= RequestArgsLog.cutAt + 40) shouldBe true
        line shouldContain "field1=value number 1"
        // Abbreviated once past the budget: six characters and dots.
        line shouldContain "=value ..."
        line shouldEndWith "...}"
        line shouldNotContain "nested={"
    }

    "nothing in a value can start a log line of its own" {
        fmt(mapOf("name" to "Eve\r\nINFO forged")) shouldBe "{name=Eve  INFO forged}"
    }

    "g-sensitive is a boolean on a property, read onto the parsed property" {
        val types = parseSchemaTypes(
            mapOf(
                "t.Login" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "pin" to mapOf(SCH.type to SCT.string, SCH.description to "A PIN.", SCH.sensitive to true),
                        "name" to mapOf(SCH.type to SCT.string, SCH.description to "A name."),
                    ),
                ),
            ),
        )
        types.getValue("t.Login").properties.filterValues { it.sensitive }.keys shouldBe setOf("pin")
        shouldThrow<KdrException> {
            parseSchemaTypes(
                mapOf("t.Bad" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("pin" to mapOf(SCH.sensitive to "yes")))),
            )
        }.message.orEmpty() shouldContain SCH.sensitive
    }
})
