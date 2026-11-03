package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.startup.SchemaCollector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The facts reference (issue #1561): every fact `base:common` declares is on the page, with what makes it true and
 * whether it reaches the page, and its description is written for a reader -- no issue numbers, which belong in KDoc.
 */
class FactsReferenceTest : StringSpec({

    "every declared fact is listed, with its description written for a reader" {
        val page = factsMarkdown()
        val defs = SchemaCollector().also { addCommonCFacts(it) }.cfacts.values
        for (def in defs) {
            def.description.isNotBlank() shouldBe true
            Regex("#[0-9]").containsMatchIn(def.description) shouldBe false
            page shouldContain "| `${def.name}` | ${def.description} | ${if (def.toFrontend) "Yes" else "No"} |"
        }
        // Each group is a section, and the operators are shown as a person writes them.
        page shouldContain "### ${CFGRP.caller}"
        page shouldContain "`a\\|b`"
        // No substitution is left unmade.
        page shouldNotContain "$$" + "{"
        // Made from code alone, so the same every time: its build id is a hash of this text.
        factsMarkdown() shouldBe page
    }
})
