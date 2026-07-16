package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Instant

/**
 * Coverage for the guard on changing a response that has already gone out (issue #99).
 *
 * These tests exist because of a bug this class of check would have caught: the auth session cookie was added
 * *after* the JSON response had been written, so Jetty dropped the `Set-Cookie` and every login silently
 * stayed anonymous (issue #81). Nothing failed. The in-process client captures headers into a map without
 * regard to when they were set, so the test suite was perfectly happy; only a real browser noticed.
 *
 * That is the point here: this handler is what the tests see, so if *it* tolerates a late "write", no test can
 * be trusted to catch one. Hence, the guard, and hence these.
 */
class RequestHandlerSentGuardTest : StringSpec({

    fun handler() = RequestHandler("sentGuardTest", "GET", "/kda/thing", emptyMap(), mutableMapOf())

    /** A handler whose response has gone out -- the state every test below starts from. */
    fun sentHandler() = handler().also { it.sendJsonResponse(mapOf("ok" to true), 200) }

    "the cookie that started this: adding one after the response is refused, not dropped" {
        val h = sentHandler()
        val e = shouldThrow<KdrException> { h.addResponseCookie("kdrAuth", "token", null as Instant?) }
        e.message shouldContain "already been sent"
        // The message names the request, since the stack alone would only say "somebody wrote too late".
        e.message shouldContain "/kda/thing"
    }

    "adding a header after the response is refused" {
        shouldThrow<KdrException> { sentHandler().addResponseHeader("X-Thing", "v") }
    }

    "setting a header after the response is refused" {
        shouldThrow<KdrException> { sentHandler().setResponseHeader("X-Thing", "v") }
    }

    "setting the status after the response is refused" {
        val e = shouldThrow<KdrException> { sentHandler().setStatusCode(500) }
        // The message names the change attempted, not just that one was.
        e.message shouldContain "500"
    }

    "setting the content type after the response is refused" {
        shouldThrow<KdrException> { sentHandler().setResponseContentType("text/plain") }
    }

    "sending a second response is refused" {
        shouldThrow<KdrException> { sentHandler().sendJsonResponse(mapOf("ok" to false), 500) }
    }

    "redirecting after the response is refused" {
        shouldThrow<KdrException> { sentHandler().sendRedirect("/elsewhere") }
    }

    "a refused change leaves the sent response exactly as it was" {
        val h = sentHandler()
        val asSent = h.rptResponseData
        runCatching { h.setStatusCode(500) }
        runCatching { h.addResponseHeader("X-Late", "v") }

        h.rptStatusCode shouldBe 200
        h.rptResponseHeaders["x-late"] shouldBe null
        h.rptResponseData shouldBe asSent
    }

    // --- what must still work ---------------------------------------------------------------------------

    "everything the response needs can still be set before it is sent" {
        val h = handler()
        h.setStatusCode(201)
        h.setResponseHeader("X-Set", "v")
        h.addResponseHeader("X-Added", "v")
        h.addResponseCookie("kdrAuth", "token", null as Instant?)
        h.sendJsonResponse(mapOf("ok" to true), 200)

        h.hasResponseBeenSent() shouldBe true
        h.rptResponseHeaders["set-cookie"]?.size shouldBe 1
        h.rptResponseHeaders["x-added"] shouldBe mutableListOf("v")
    }

    "asking whether the response was sent is not a change, and stays allowed" {
        // The legitimate pattern: read the flag and stand down when someone else has already responded.
        val h = sentHandler()
        val asSent = h.rptResponseData
        h.hasResponseBeenSent() shouldBe true
        if (!h.hasResponseBeenSent()) {
            h.sendJsonResponse(mapOf("unreached" to true), 200)
        }
        h.rptResponseData shouldBe asSent
    }

    "a redirect still sets its status and Location when nothing has been sent" {
        val h = handler()
        h.sendRedirect("/elsewhere")

        h.rptStatusCode shouldBe 303
        h.rptResponseHeaders["location"] shouldBe mutableListOf("/elsewhere")
        h.hasResponseBeenSent() shouldBe true
    }
})
