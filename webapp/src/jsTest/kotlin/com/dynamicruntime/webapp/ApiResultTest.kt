package com.dynamicruntime.webapp

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An API call's outcome as a value (issue #967): the two failures a call is expected to have come back told
 * apart, and nothing else is caught -- a bug in handling a response and a coroutine's cancellation propagate,
 * which is the difference from the `runCatching` the call sites used to wrap.
 */
class ApiResultTest {
    private val refusal = ApiError("Not yours.", fromFragment = false, status = 403, errorCode = null, traceId = "t1")

    @Test
    fun eachOutcomeIsItsOwnKind() {
        assertEquals(5, assertIs<ApiResult.Ok<Int>>(apiResult { 5 }).value)
        val refused = assertIs<ApiResult.Refused>(apiResult<Int> { throw refusal })
        assertEquals(403, refused.error.status)
        assertEquals("t1", refused.error.traceId)
        val failure = ApiFailure("GET /x could not be completed: Failed to fetch")
        assertSame(failure, assertIs<ApiResult.Failed>(apiResult<Int> { throw failure }).cause)
    }

    @Test
    fun aBugOrACancellationIsNeverAResult() {
        assertFailsWith<IllegalStateException> { apiResult { error("a bug mapping the response") } }
        assertFailsWith<CancellationException> { apiResult { throw CancellationException("page left") } }
        // Nor does mapping a value swallow a throw in the transform.
        assertFailsWith<IllegalStateException> { apiResult { 1 }.map { error("bad mapping") } }
    }

    @Test
    fun theReadersGiveTheValueOrSayWhatWentWrong() {
        val ok = apiResult { "v" }
        val refused = apiResult<String> { throw refusal }
        assertEquals("v", ok.valueOrNull())
        assertNull(refused.valueOrNull())
        assertEquals("dflt", refused.valueOr("dflt"))
        assertNull(ok.failureOrNull())
        assertSame(refusal, refused.failureOrNull())
        assertSame(refusal, assertFailsWith<ApiError> { refused.orThrow() })
        // A failure passes through a map untouched.
        assertIs<ApiResult.Refused>(refused.map { it.length })
        assertEquals(1, ok.map { it.length }.valueOrNull())
    }

    @Test
    fun aSuccessThatIsNotJsonIsNoUsableAnswer() {
        assertEquals(emptyMap(), jsonBody("GET", "/x", "").valueOrNull())
        assertEquals(mapOf<String, Any?>("a" to 1L), jsonBody("GET", "/x", """{"a": 1}""").valueOrNull())
        val failed = assertIs<ApiResult.Failed>(jsonBody("GET", "/x", "Not Found"))
        assertTrue("GET /x answered with a body that is not JSON" in failed.cause.message.orEmpty())
    }

    @Test
    fun aDisplaySiteLetsACancellationUnwind() {
        assertFailsWith<CancellationException> { userFacingError(CancellationException("page left"), obfuscate = false) }
        // An ApiFailure reads as the unreachable server it is.
        val shown = userFacingError(ApiFailure("GET /x could not be completed"), obfuscate = false)
        assertTrue("could not be reached" in shown.text)
    }
}
