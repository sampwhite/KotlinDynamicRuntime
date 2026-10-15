package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

/** The omnibus fragment format (issue #1217): a `# !<fileId>` heading above the ordinary namespaces and keys. */
class OmnibusFragmentsTest : StringSpec({

    fun codeOf(md: String): Any? =
        shouldThrow<KdrException> { md.parseOmnibusFragments() }.extraData[KdrException.errorCodeKey]

    "each file heading opens a section in the ordinary format" {
        val md = """
            # !home
            # @welcome
            # +title Hello

            # !profile
            # @page
            # +heading
            Two
            lines
        """.trimIndent()
        md.parseOmnibusFragments() shouldContainExactly mapOf(
            "home" to mapOf("welcome" to mapOf("title" to "Hello")),
            "profile" to mapOf("page" to mapOf("heading" to "Two\nlines")),
        )
    }

    "a section's namespaces are its own" {
        // A key directly under the second file heading has no namespace, though the first section declared one.
        val md = """
            # !home
            # @welcome
            # +title Hello
            # !profile
            # +heading Lost
        """.trimIndent()
        codeOf(md) shouldBe MarkdownError.keyBeforeNamespace
    }

    "comments and empty lines may come before the first file heading, and nothing else" {
        "/- About this file. -/\n\n# !home\n# @w\n# +t Hi".parseOmnibusFragments().keys shouldBe setOf("home")
        // An ordinary fragment file passed here by mistake: its content would belong to no file.
        codeOf("# @welcome\n# +title Hi\n# !home") shouldBe MarkdownError.contentBeforeFile
    }

    "a comment may span a file heading" {
        val md = "# !home\n# @w\n# +t Hi\n/- dropped:\n# !profile\n-/\n# +u There"
        md.parseOmnibusFragments() shouldContainExactly mapOf("home" to mapOf("w" to mapOf("t" to "Hi", "u" to "There")))
    }

    "a section may be empty, and overlays nothing" {
        "# !home\n# !profile\n# @p\n# +h Hi".parseOmnibusFragments()["home"] shouldBe emptyMap()
    }

    "a file heading needs a plain, single-use file id" {
        codeOf("# !\n# @w\n# +t Hi") shouldBe MarkdownError.emptyFileId
        codeOf("# !home.page\n# @w\n# +t Hi") shouldBe MarkdownError.dottedName
        codeOf("# !home\n# @w\n# +t Hi\n# !home\n# @w\n# +u Again") shouldBe MarkdownError.duplicateFile
    }

    "an error inside a section is the ordinary parser's" {
        codeOf("# !home\n# @w\n# +t Hi\n# +t Again") shouldBe MarkdownError.duplicateKey
    }
})
