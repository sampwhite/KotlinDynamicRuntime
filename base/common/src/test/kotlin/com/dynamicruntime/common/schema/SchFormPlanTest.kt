package com.dynamicruntime.common.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The kernel form-presentation seam (issue #777): [orderedFieldNames] decides the order and candidate set of an
 * object type's fields from the layout's [SchLayoutMode]. This is frontend render logic pulled into the kernel
 * so a JVM (backend) test pins it -- the behavior that was previously only assertable by driving a browser.
 *
 * The type `Rec` declares `a, b, c, d` in that schema order throughout; each case varies only the layout.
 */
class SchFormPlanTest : StringSpec({
    val type: SchType = parseSchemaTypes(
        mapOf(
            "t.Rec" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "a" to mapOf(SCH.type to SCT.string),
                    "b" to mapOf(SCH.type to SCT.string),
                    "c" to mapOf(SCH.type to SCT.string),
                    "d" to mapOf(SCH.type to SCT.string),
                ),
            ),
        ),
    ).getValue("t.Rec")

    /** A layout in [mode] listing [fields], in that order. */
    fun layout(mode: SchLayoutMode, vararg fields: String) =
        SchLayout(null, null, fields.map { SchLayoutField(it, null, null, null) }, mode = mode)

    "no layout: schema declaration order, every property" {
        orderedFieldNames(type, null) shouldBe listOf("a", "b", "c", "d")
    }

    "overlay: the schema wins order and membership, whatever the layout lists" {
        // The layout lists a subset in a different order; overlay ignores both facts. This is the invariant a
        // browser test could not cheaply pin: layout says [c, a], schema still decides [a, b, c, d].
        orderedFieldNames(type, layout(SchLayoutMode.overlay, "c", "a")) shouldBe listOf("a", "b", "c", "d")
    }

    "reorder: listed fields first in list order, then the rest in schema order" {
        orderedFieldNames(type, layout(SchLayoutMode.reorder, "c", "a")) shouldBe listOf("c", "a", "b", "d")
        // A field the layout does not mention is not dropped -- it follows, in schema order.
        orderedFieldNames(type, layout(SchLayoutMode.reorder, "d")) shouldBe listOf("d", "a", "b", "c")
    }

    "authoritative: only the listed fields, in list order" {
        orderedFieldNames(type, layout(SchLayoutMode.authoritative, "c", "a")) shouldBe listOf("c", "a")
    }

    "a layout field the type does not declare is dropped defensively (the boot already refuses one)" {
        orderedFieldNames(type, layout(SchLayoutMode.authoritative, "c", "zzz", "a")) shouldBe listOf("c", "a")
        orderedFieldNames(type, layout(SchLayoutMode.reorder, "zzz", "d")) shouldBe listOf("d", "a", "b", "c")
    }
})
