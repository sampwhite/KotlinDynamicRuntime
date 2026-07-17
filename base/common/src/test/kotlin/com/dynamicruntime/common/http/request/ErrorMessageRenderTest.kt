package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrMsg
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for rendering a [KdrMsg] error message from fragment copy (issue #108): the happy substitution path,
 * and the *contained* fallbacks -- a missing template or a bad substitution must yield the key path and a
 * warning, never an exception thrown from inside error handling.
 */
class ErrorMessageRenderTest : StringSpec({

    val msg = KdrMsg("auth", "error", "noAccount")

    "a resolved template is rendered with its params, and is marked fromFragment" {
        val warnings = mutableListOf<String>()
        val rendered = RequestHandler.renderMsg(
            msg, mapOf("loginId" to "ghost@example.com"),
            resolve = { _, _, _ -> $$"No account was found for ${loginId}." },
            warn = { warnings.add(it) },
        )
        rendered.text shouldBe "No account was found for ghost@example.com."
        rendered.fromFragment shouldBe true
        warnings.isEmpty() shouldBe true
    }

    "a string param is sanitized before substitution -- a Markdown link cannot be injected" {
        @Suppress("HttpUrlsUsage") val rendered = RequestHandler.renderMsg(
            msg, mapOf("loginId" to "[click](http://evil.com)"),
            resolve = { _, _, _ -> $$"No account was found for ${loginId}." },
            warn = { },
        )
        // The link structure is stripped; the text survives, no clickable URL.
        @Suppress("HttpUrlsUsage")
        rendered.text shouldBe "No account was found for clickhttp://evil.com."
        rendered.fromFragment shouldBe true
    }

    "a missing template falls back to the key path, not fromFragment, and warns" {
        val warnings = mutableListOf<String>()
        val rendered = RequestHandler.renderMsg(msg, emptyMap(), resolve = { _, _, _ -> null }, warn = { warnings.add(it) })
        rendered.text shouldBe "auth/error/noAccount"
        rendered.fromFragment shouldBe false
        warnings.size shouldBe 1
    }

    "a substitution failure is contained -- key path, not fromFragment, not a thrown error" {
        val warnings = mutableListOf<String>()
        // The template references a param that was not supplied, so evalTemplate throws; renderMsg must swallow it.
        val rendered = RequestHandler.renderMsg(
            msg, emptyMap(),
            resolve = { _, _, _ -> $$"No account for ${loginId}." },
            warn = { warnings.add(it) },
        )
        rendered.text shouldBe "auth/error/noAccount"
        rendered.fromFragment shouldBe false
        warnings.size shouldBe 1
    }

    // --- the real resolver against the shipped auth.md --------------------------------------------------

    "resolveFragment reads a real key, and null for a missing one" {
        // The cxt is threaded but unused today (a future version resolves per-context); a bare service instance
        // and simple cxt suffice to read the classpath fragment. auth.md ships in base/common's resources.
        val service = MarkdownFragmentService()
        val cxt = KdrCxt.mkSimpleCxt("resolveFragmentTest")
        service.resolveFragment(cxt, "auth", "error", "codeIncorrect") shouldBe "The verification code is incorrect."
        service.resolveFragment(cxt, "auth", "error", "noSuchKey") shouldBe null
        service.resolveFragment(cxt, "noSuchFile", "error", "codeIncorrect") shouldBe null
    }
})
