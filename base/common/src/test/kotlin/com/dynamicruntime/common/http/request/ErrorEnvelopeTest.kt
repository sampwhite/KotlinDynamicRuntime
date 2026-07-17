package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

// A stand-in for a real logical code -- an enum, exactly as MarkdownError/ScriptError are. The wire value is
// its name, so promoting one to the client depends on that serialization holding.
@Suppress("EnumEntryName")
private enum class SampleCode { badThing }

/**
 * Coverage for the error-response envelope (issue #103): the HTTP code rides as `status`, the *logical* code is
 * promoted out of the exception's bag to the top level, and whatever remains of the bag is nested under
 * `extraData` where it cannot shadow a protocol field.
 */
class ErrorEnvelopeTest : StringSpec({

    "the plain case: status, message, the fragment flag, requestUri, and nothing else" {
        RequestHandler.errorEnvelope(500, "boom", false, "GET:/x", null) shouldContainExactly linkedMapOf(
            EP.status to 500,
            EP.errorMessage to "boom",
            EP.errorFromFragment to false,
            EP.requestUri to "GET:/x",
        )
    }

    "the fragment flag is always present, carrying whether the message is designed copy (issue #108)" {
        RequestHandler.errorEnvelope(400, "bad", true, "GET:/x", null)[EP.errorFromFragment] shouldBe true
        RequestHandler.errorEnvelope(400, "bad", false, "GET:/x", null)[EP.errorFromFragment] shouldBe false
    }

    "the HTTP code is `status`, not `errorCode` -- that name now means the logical code" {
        val env = RequestHandler.errorEnvelope(400, "bad", false, "GET:/x", null)
        env[EP.status] shouldBe 400
        // errorCode is reserved for the logical code and is absent when there is none.
        env.containsKey(EP.errorCode) shouldBe false
    }

    "a logical code is lifted out of the bag to the top level" {
        val env = RequestHandler.errorEnvelope(
            400, "bad", false, "GET:/x",
            mapOf(KdrException.errorCodeKey to SampleCode.badThing),
        )
        env[EP.errorCode] shouldBe SampleCode.badThing
        // It was the only bag entry, so no nested extraData is left behind.
        env.containsKey(EP.extraData) shouldBe false
    }

    "the rest of the bag is nested under extraData, with the logical code taken out of it" {
        val env = RequestHandler.errorEnvelope(
            400, "bad", false, "GET:/x",
            mapOf(KdrException.errorCodeKey to SampleCode.badThing, "offset" to 17, "line" to 3),
        )
        env[EP.errorCode] shouldBe SampleCode.badThing
        env[EP.extraData] shouldBe mapOf("offset" to 17, "line" to 3)
    }

    "a bag with no logical code still nests, and adds no errorCode" {
        val env = RequestHandler.errorEnvelope(400, "bad", false, "GET:/x", mapOf("offset" to 17))
        env.containsKey(EP.errorCode) shouldBe false
        env[EP.extraData] shouldBe mapOf("offset" to 17)
    }

    "the exception's own bag is not mutated" {
        val bag = mutableMapOf<String, Any?>(KdrException.errorCodeKey to SampleCode.badThing, "offset" to 17)
        RequestHandler.errorEnvelope(400, "bad", false, "GET:/x", bag)
        bag shouldContainExactly mapOf(KdrException.errorCodeKey to SampleCode.badThing, "offset" to 17)
    }

    "a promoted enum code serializes to its name on the wire" {
        val env = RequestHandler.errorEnvelope(
            400, "bad", false, "GET:/x",
            mapOf(KdrException.errorCodeKey to SampleCode.badThing),
        )
        // This is the thing that makes promotion useful: the client reads "badThing", not an object.
        env.toJsonStr() shouldContain "\"${EP.errorCode}\":\"badThing\""
    }
})
