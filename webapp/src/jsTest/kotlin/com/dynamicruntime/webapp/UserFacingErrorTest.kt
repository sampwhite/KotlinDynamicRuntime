package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for [userFacingError] -- how a caught error becomes what the user sees
 * (issues #111, #519): designed fragment copy is shown as an expected error; a raw message is shown so the
 * failure is legible, except an internal (5xx / statusless) message under an obfuscating deployment.
 */
class UserFacingErrorTest {

    private fun api(message: String, fromFragment: Boolean = false, status: Int? = 400, traceId: String? = null) =
        ApiError(message, fromFragment = fromFragment, status = status, errorCode = null, traceId = traceId)

    @Test
    fun fragmentCopyIsShownAsDesignedCopy() {
        val d = userFacingError(api("Please verify your email.", fromFragment = true), obfuscate = true)
        assertEquals(DisplayError.Kind.designed, d.kind) // Markdown-rendered, ordinary styling
        assertEquals("Please verify your email.", d.text)
    }

    @Test
    fun aRawClientErrorIsShownAsAMessageEvenWhenObfuscating() {
        // A 4xx is the caller's own fault and safe to show; the server has already redacted any sensitive one.
        // Shown as an ordinary message, not a dev fault box.
        val d = userFacingError(api("Name must not be blank.", status = 400), obfuscate = true)
        assertEquals(DisplayError.Kind.message, d.kind)
        assertEquals("Name must not be blank.", d.text)
    }

    @Test
    fun aRawServerErrorIsWithheldOnlyWhenObfuscating() {
        // Dev (not obfuscating): the raw internal detail is shown, set apart as a fault.
        val shown = userFacingError(api("NullPointer at Foo.kt:42", status = 500), obfuscate = false)
        assertEquals(DisplayError.Kind.fault, shown.kind)
        assertEquals("NullPointer at Foo.kt:42", shown.text)
        // Obfuscating: a generic apology, shown as an ordinary message, with the raw detail nowhere near it.
        val hidden = userFacingError(api("NullPointer at Foo.kt:42", status = 500), obfuscate = true)
        assertEquals(DisplayError.Kind.message, hidden.kind)
        assertTrue(hidden.text.startsWith("Something went wrong"))
        assertFalse(hidden.text.contains("NullPointer"))
    }

    @Test
    fun aStatuslessErrorReadsAsServerUnreachable() {
        // A network failure / client-side throw never reached the backend, so its raw message ("Failed to fetch")
        // is not shown; a plain "server could not be reached" is, in either mode. The raw cause is on the console.
        val plain = RuntimeException("Failed to fetch")
        assertEquals(DisplayError.Kind.message, userFacingError(plain, obfuscate = true).kind)
        assertTrue(userFacingError(plain, obfuscate = false).text.startsWith("The server could not be reached"))
        assertFalse(userFacingError(plain, obfuscate = false).text.contains("Failed to fetch"))
    }

    @Test
    fun aTraceIdIsAppendedForReference() {
        val d = userFacingError(api("Bad input.", status = 400, traceId = "abc123"), obfuscate = true)
        assertTrue(d.text.contains("abc123"), "expected the trace id in: ${d.text}")
    }
}
