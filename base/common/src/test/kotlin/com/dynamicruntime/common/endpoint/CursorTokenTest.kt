package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.crc32Hex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf

/** The opaque cursor a cursor-paged listing hands out and takes back (issue #976). */
class CursorTokenTest : StringSpec({
    val query = "report:auditOverview:detail"

    fun problemOf(token: String, queryId: String = query): CursorProblem =
        CursorToken.decode(token, queryId).shouldBeInstanceOf<Parsed.Failed>().problems.single().code as CursorProblem

    "a key survives the round trip with its types" {
        val key = listOf("gd.fd.acme.e20260812130405123AbCd", 42L, 2.5, true, null, "a, \"quoted\" ] value")
        val token = CursorToken.encode(query, key)
        CursorToken.decode(token, query).valueOrNull() shouldBe key
        CursorToken.keyOf(token, query) shouldBe key
        // URL-safe as it stands: a caller pastes it into a query string.
        token shouldMatch Regex("[A-Za-z0-9._-]+")
    }

    // JSON does not keep every type, and the KDoc says which: this is what a codec's `fromValues` has to allow for.
    "a whole-number double comes back a long, and a date as its text" {
        val at = kotlin.time.Instant.parse("2026-08-12T13:04:05Z")
        val back = CursorToken.keyOf(CursorToken.encode(query, listOf(2.0, 7, at)), query).shouldNotBeNull()
        back[0] shouldBe 2L
        back[1] shouldBe 7L
        back[2].shouldBeInstanceOf<String>()
    }

    "the string codec reads one string and nothing else" {
        CursorKeys.string.fromValues(CursorKeys.string.toValues("id1")) shouldBe "id1"
        CursorKeys.string.fromValues(emptyList()).shouldBeNull()
        CursorKeys.string.fromValues(listOf(5)).shouldBeNull()
        CursorKeys.string.fromValues(listOf("a", "b")).shouldBeNull()
    }

    "a token longer than any real one is refused before it is decoded" {
        // Well-formed in every other way, so only its length refuses it.
        val huge = CursorToken.encode(query, listOf("x".repeat(CUR.maxTokenLength)))
        problemOf(huge) shouldBe CursorProblem.malformed
        // Just inside the bound still reads.
        CursorToken.decode(CursorToken.encode(query, listOf("x".repeat(1000))), query).valueOrNull() shouldBe listOf("x".repeat(1000))
    }

    "no token is the first page" {
        CursorToken.keyOf(null, query).shouldBeNull()
        CursorToken.keyOf("", query).shouldBeNull()
    }

    "a token of another query is refused, not answered" {
        val token = CursorToken.encode("report:yearlyNotes:detail", listOf("x"))
        problemOf(token) shouldBe CursorProblem.otherQuery
        // The same listing asked differently is a different query too.
        problemOf(CursorToken.encode("report:auditOverview:aggregate:review", listOf("x"))) shouldBe CursorProblem.otherQuery
        val e = shouldThrow<KdrException> { CursorToken.keyOf(token, query) }
        e.code shouldBe EXC.badInput
        e.message!! shouldContain "different query"
    }

    "text that is not a token is malformed" {
        val hash = query.crc32Hex()
        for (bad in listOf(
            "garbage", "a.b", "a.b.c.d",
            // Right shape and query, but the key is not base64, not JSON, or not a list.
            "${CUR.version}.$hash.***",
            "${CUR.version}.$hash.${"not json".toByteArray().base64Encode()}",
            "${CUR.version}.$hash.${"{\"k\":\"scalar\"}".toByteArray().base64Encode()}",
            "${CUR.version}.$hash.${"{\"other\":[1]}".toByteArray().base64Encode()}",
        )) {
            problemOf(bad) shouldBe CursorProblem.malformed
            shouldThrow<KdrException> { CursorToken.keyOf(bad, query) }.code shouldBe EXC.badInput
        }
    }

    "a token of a later format version is refused as such" {
        val parts = CursorToken.encode(query, listOf("x")).split(CUR.sep)
        problemOf(listOf("2", parts[1], parts[2]).joinToString(CUR.sep)) shouldBe CursorProblem.unsupportedVersion
    }
})
