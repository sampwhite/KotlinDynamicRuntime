package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.fragmentFiles
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

    val msg = KdrMsg("auth", "error", "emailNotAvailable")

    "a resolved template is rendered with its params, and is marked fromFragment" {
        val warnings = mutableListOf<String>()
        val rendered = RequestHandler.renderMsg(
            msg, mapOf("email" to "ghost@example.com"),
            resolve = { _, _, _ -> $$"The email ${email} is not available." },
            warn = { warnings.add(it) },
        )
        rendered.text shouldBe "The email ghost@example.com is not available."
        rendered.fromFragment shouldBe true
        warnings.isEmpty() shouldBe true
    }

    "a string param is sanitized before substitution -- a Markdown link cannot be injected" {
        @Suppress("HttpUrlsUsage") val rendered = RequestHandler.renderMsg(
            msg, mapOf("email" to "[click](http://evil.com)"),
            resolve = { _, _, _ -> $$"The email ${email} is not available." },
            warn = { },
        )
        // The link structure is stripped; the text survives, no clickable URL.
        @Suppress("HttpUrlsUsage")
        rendered.text shouldBe "The email clickhttp://evil.com is not available."
        rendered.fromFragment shouldBe true
    }

    "a missing template falls back to the key path, not fromFragment, and warns" {
        val warnings = mutableListOf<String>()
        val rendered = RequestHandler.renderMsg(msg, emptyMap(), resolve = { _, _, _ -> null }, warn = { warnings.add(it) })
        rendered.text shouldBe "auth/error/emailNotAvailable"
        rendered.fromFragment shouldBe false
        warnings.size shouldBe 1
    }

    "a substitution failure is contained -- key path, not fromFragment, not a thrown error" {
        val warnings = mutableListOf<String>()
        // The template references a param that was not supplied, so evalTemplate throws; renderMsg must swallow it.
        val rendered = RequestHandler.renderMsg(
            msg, emptyMap(),
            resolve = { _, _, _ -> $$"The email ${email} is missing a param on purpose." },
            warn = { warnings.add(it) },
        )
        rendered.text shouldBe "auth/error/emailNotAvailable"
        rendered.fromFragment shouldBe false
        warnings.size shouldBe 1
    }

    // --- the real resolver against the shipped auth.md --------------------------------------------------

    "resolveFragment reads a real key, and null for a missing one" {
        // The cxt is load-bearing since issue #456: a fragment's content is what its declared layers add up
        // to, so the lookup goes through the registry rather than straight to the classpath. Registered here
        // rather than by booting a node, which is what this seam actually needs. auth.md ships in
        // base/common's resources.
        val service = MarkdownFragmentService()
        val cxt = KdrCxt.mkSimpleCxt("resolveFragmentTest")
        cxt.instanceConfig.put(FRAG.registryKey, fragmentFiles("auth"))
        service.resolveFragment(cxt, "auth", "error", "codeIncorrect") shouldBe "The verification code is incorrect."
        service.resolveFragment(cxt, "auth", "error", "noSuchKey") shouldBe null
        service.resolveFragment(cxt, "noSuchFile", "error", "codeIncorrect") shouldBe null
    }

    "an undeclared fragment file resolves to nothing, even though its resource is right there" {
        // The tightening #456 brought, and worth pinning because it is a behavior change: this used to read
        // any file on the classpath. Declaring a file is what puts it under the startup and operator checks,
        // so a file nothing declares is a file nothing validates -- and error copy should not be the one
        // corner of the system that reads unvalidated content.
        val service = MarkdownFragmentService()
        val cxt = KdrCxt.mkSimpleCxt("undeclaredFragmentTest")
        service.resolveFragment(cxt, "auth", "error", "codeIncorrect") shouldBe null
    }
})
