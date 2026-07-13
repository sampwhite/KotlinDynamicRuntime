package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Covers the Markdown fragment parser (issue #59): the namespace/key format, the inline vs. next-line value
 * variants and their differing empty-line termination, comment stripping, and the structured error codes.
 */
class MarkdownFragmentUtilTest : StringSpec({

    "an inline value is the text on the key line" {
        val md = """
            # @email
            # +code Your verification code is here.
        """.trimIndent()
        md.parseMarkdownFragments() shouldContainExactly mapOf(
            "email" to mapOf("code" to "Your verification code is here."),
        )
    }

    "an inline value continues over following lines until one empty line" {
        val md = """
            # @ns
            # +k first line
            second line

            # +k2 ignored-after-blank
        """.trimIndent()
        val ns = md.parseMarkdownFragments().getValue("ns")
        ns["k"] shouldBe "first line\nsecond line"
        ns["k2"] shouldBe "ignored-after-blank"
    }

    "a next-line value starts on the following line and keeps single blank lines" {
        val md = """
            # @ns
            # +k
            ## A heading in the value
            Paragraph one.

            Paragraph two.
        """.trimIndent()
        md.parseMarkdownFragments().getValue("ns")["k"] shouldBe
            "## A heading in the value\nParagraph one.\n\nParagraph two."
    }

    "a next-line value terminates on two consecutive empty lines" {
        val md = """
            # @ns
            # +k
            kept


            dropped
        """.trimIndent()
        md.parseMarkdownFragments().getValue("ns")["k"] shouldBe "kept"
    }

    "a '# ' line terminates a value but '## ' does not" {
        val md = """
            # @ns
            # +k
            body
            ## still body
            # +k2 next
        """.trimIndent()
        val ns = md.parseMarkdownFragments().getValue("ns")
        ns["k"] shouldBe "body\n## still body"
        ns["k2"] shouldBe "next"
    }

    "re-declaring a namespace shifts subsequent keys" {
        val md = """
            # @a
            # +k1 one
            # @b
            # +k2 two
        """.trimIndent()
        md.parseMarkdownFragments() shouldContainExactly mapOf(
            "a" to mapOf("k1" to "one"),
            "b" to mapOf("k2" to "two"),
        )
    }

    "comments are stripped, inline and multi-line" {
        val md = """
            # @ns
            # +k before /- inline comment -/ after
            # +k2
            line one /- start
            still comment -/ line two
        """.trimIndent()
        val ns = md.parseMarkdownFragments().getValue("ns")
        ns["k"] shouldBe "before  after"
        ns["k2"] shouldBe "line one  line two"
    }

    "an unterminated comment fails with unterminatedComment" {
        val ex = shouldThrow<KdrException> { "# @ns\n# +k /- oops".parseMarkdownFragments() }
        ex.extraData[KdrException.errorCodeKey] shouldBe MarkdownError.unterminatedComment
    }

    "a key before any namespace fails with keyBeforeNamespace" {
        val ex = shouldThrow<KdrException> { "# +k value".parseMarkdownFragments() }
        ex.extraData[KdrException.errorCodeKey] shouldBe MarkdownError.keyBeforeNamespace
    }

    "a duplicate key within a namespace fails with duplicateKey" {
        val ex = shouldThrow<KdrException> { "# @ns\n# +k one\n# +k two".parseMarkdownFragments() }
        ex.extraData[KdrException.errorCodeKey] shouldBe MarkdownError.duplicateKey
    }
})
