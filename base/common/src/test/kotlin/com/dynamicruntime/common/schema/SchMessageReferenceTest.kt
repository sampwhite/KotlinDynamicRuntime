package com.dynamicruntime.common.schema

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * The validation messages reference (issue #1559): made from the validator's codes and the parameters the boot check
 * holds a message to, so every code is on it with what it means, and a message's parameters are the ones that check
 * allows.
 *
 * Theme: #DOCDR.
 */
class SchMessageReferenceTest : StringSpec({

    "every code says what it means and what raises it, and the page lists each with its parameters" {
        val page = validationMessagesMarkdown()
        for (code in SchFailCode.entries) withClue(code.name) {
            code.meaning.shouldNotBeBlank()
            (code.raisedBy.isNotEmpty()) shouldBe true
            page shouldContain "| `${code.name}` | ${code.meaning} |"
        }
        page shouldContain "| `${SCH.errorDefault}` |"
        // Made from code alone, so the same every time: its build id is a hash of this text.
        validationMessagesMarkdown() shouldBe page
    }

    "a code's parameters on the page are the ones a message for it may use" {
        val page = validationMessagesMarkdown()
        val row = { code: SchFailCode -> page.lines().single { it.startsWith("| `${code.name}` |") } }
        // A bound is offered only where the field declares it, so the page shows it in parentheses.
        row(SchFailCode.belowMinimum) shouldContain $$"(`${$${LayoutCtx.min}}`)"
        row(SchFailCode.invalidOption) shouldContain $$"`${$${LayoutErrCtx.options}}`"
        // A missing field has no value to show.
        (row(SchFailCode.missingRequired).contains($$"${$${LayoutErrCtx.value}}")) shouldBe false
        // Every parameter any code offers is explained.
        val offered = (SchFailCode.entries.map { it } + listOf(null))
            .flatMap { errorContextNames(it, hasMin = true, hasMax = true) }.toSet()
        for (name in offered) withClue(name) { page shouldContain $$"| `${$$name}` |" }
    }
})
