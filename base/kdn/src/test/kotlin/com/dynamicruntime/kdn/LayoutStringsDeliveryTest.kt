package com.dynamicruntime.kdn

import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.parseDeliveredLayouts
import com.dynamicruntime.common.schema.resolveDeliveredLayouts
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A layout's form-level strings (issue #641) are copy like its heading and field text, so a backend `%{@t(...)}` in
 * one is resolved at delivery rather than shipped as written and rendered as literal text (issue #814). And what is
 * delivered parses back on the page, a heading-only layout included.
 */
class LayoutStringsDeliveryTest : StringSpec({

    val cxt = TestInstances.default("layoutStrings")

    fun delivered(strings: Map<String, String>): Map<String, Any?> {
        val block = mapOf(
            SL.fragmentFileId to "mail",
            SL.schemaFields to listOf(mapOf(SL.field to "a")),
            SL.strings to strings,
        )
        return resolveDeliveredLayouts(cxt, mapOf("t.Form" to block))["t.Form"].toJsonMapOrEmpty()[SL.strings]
            .toJsonMapOrEmpty()
    }

    "a backend pull in a form-level string is resolved at delivery, and plain copy is left alone" {
        val strings = delivered(
            mapOf(
                // Two-part, against the block's fragmentFileId (a backend file of the core).
                LAYSTR.formErrorHint to """%{@t("common.footer")}""",
                LAYSTR.formErrorSummary to $$"Plain, with a frontend ${field} left for the page.",
            ),
        )
        strings[LAYSTR.formErrorHint] shouldBe
            "This message was sent automatically. If you were not expecting it, you can ignore it."
        strings[LAYSTR.formErrorSummary] shouldBe $$"Plain, with a frontend ${field} left for the page."
    }

    "a heading-only layout is delivered with no field list, so the page's parse takes it" {
        // A client's alteration giving a type only a heading (issue #1070): written with an empty field list, the
        // page's strict parse refused it and the forms page could not load.
        val delivered = resolveDeliveredLayouts(cxt, mapOf("t.Form" to mapOf(SL.label to "Site Visit Data")))
        delivered["t.Form"].toJsonMapOrEmpty().containsKey(SL.schemaFields) shouldBe false
        parseDeliveredLayouts(delivered).getValue("t.Form").label shouldBe "Site Visit Data"
    }

    // Only what no boot check can see (a computed key) reaches here unresolved; it degrades to the copy as written.
    "a pull that cannot resolve at delivery is delivered as written" {
        val computed = """%{@t(chosen)}"""
        delivered(mapOf(LAYSTR.formErrorHint to computed))[LAYSTR.formErrorHint] shouldBe computed
    }
})
