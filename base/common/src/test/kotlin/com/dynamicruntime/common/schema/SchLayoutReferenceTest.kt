package com.dynamicruntime.common.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The form layouts reference (issue #1609) says something for everything the layout parser reads: a key, a mode, an
 * option, or a form string added without a meaning fails here rather than publishing a blank row, and the page's
 * example is a layout the parser accepts.
 *
 * Theme: #DOCDR.
 */
class SchLayoutReferenceTest : StringSpec({
    "every key, mode, option, and form string has a meaning, and every form string its default wording" {
        SL.blockMeanings.keys shouldBe SL.blockKeys
        SL.fieldMeanings.keys shouldBe SL.fieldKeys
        SL.choiceMeanings.keys shouldBe SL.choiceKeys
        SLM.meanings.keys shouldBe SLM.values
        SLDM.meanings.keys shouldBe SLDM.values
        LAYSTR.meanings.keys shouldBe LAYSTR.keys
        LAYSTR.defaults.keys shouldBe LAYSTR.keys
        val all = SL.blockMeanings.values + SL.fieldMeanings.values + SL.choiceMeanings.values + SLM.meanings.values +
            SLDM.meanings.values + LAYSTR.meanings.values + LayoutError.entries.map { it.meaning }
        all.filter { it.isBlank() }.shouldBeEmpty()
    }

    "the page's example is a layout the parser accepts" {
        val layout = parseSchLayout("example", exampleLayout())
        layout.mode shouldBe SchLayoutMode.reorder
        layout.fieldNames shouldBe listOf("topic", "rating", "notes")
    }

    "the page lists the parser's vocabulary" {
        val page = formLayoutsMarkdown()
        for (key in SL.blockKeys + SL.fieldKeys + SLM.values + SLDM.values + LAYSTR.keys) {
            page shouldContain "`$key`"
        }
        page shouldContain LAYSTR.defaultSaveHint
        page shouldContain "`${LayoutError.omitsRequired.name}`"
    }
})
