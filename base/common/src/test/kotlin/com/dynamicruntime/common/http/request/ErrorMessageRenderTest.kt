package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.content.MarkdownFragmentService
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

    "a resolved template is rendered with its params" {
        val warnings = mutableListOf<String>()
        val text = RequestHandler.renderMsg(
            msg, mapOf("loginId" to "ghost@example.com"),
            resolve = { _, _, _ -> $$"No account was found for ${loginId}." },
            warn = { warnings.add(it) },
        )
        text shouldBe "No account was found for ghost@example.com."
        warnings.isEmpty() shouldBe true
    }

    "a missing template falls back to the key path and warns" {
        val warnings = mutableListOf<String>()
        val text = RequestHandler.renderMsg(msg, emptyMap(), resolve = { _, _, _ -> null }, warn = { warnings.add(it) })
        text shouldBe "auth/error/noAccount"
        warnings.size shouldBe 1
    }

    "a substitution failure is contained -- key path, not a thrown error" {
        val warnings = mutableListOf<String>()
        // The template references a param that was not supplied, so evalTemplate throws; renderMsg must swallow it.
        val text = RequestHandler.renderMsg(
            msg, emptyMap(),
            resolve = { _, _, _ -> $$"No account for ${loginId}." },
            warn = { warnings.add(it) },
        )
        text shouldBe "auth/error/noAccount"
        warnings.size shouldBe 1
    }

    // --- the real resolver against the shipped auth.md --------------------------------------------------

    "resolveFragment reads a real key, and null for a missing one" {
        // Present in base/common's md-fragments/auth.md (issue #108 error copy).
        MarkdownFragmentService.resolveFragment("auth", "error", "codeIncorrect") shouldBe
            "The verification code is incorrect."
        MarkdownFragmentService.resolveFragment("auth", "error", "noSuchKey") shouldBe null
        MarkdownFragmentService.resolveFragment("noSuchFile", "error", "codeIncorrect") shouldBe null
    }
})
