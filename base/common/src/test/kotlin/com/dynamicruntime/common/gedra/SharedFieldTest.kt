package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLM
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * How a shared edit rewrites a type's body (issue #1029): a layout entry is replaced, or appended only where the
 * layout merely annotates -- a `reorder` or `authoritative` layout's list decides the form, and is never extended.
 */
class SharedFieldTest : StringSpec({
    fun body(mode: String?): Map<String, Any?> = mapOf(
        SCH.properties to mapOf<String, Any?>(
            "title" to mapOf<String, Any?>(SCH.type to "string"),
            "note" to mapOf<String, Any?>(SCH.type to "string"),
            "venue" to mapOf<String, Any?>(SCH.type to "string", SCH.options to listOf(mapOf(SCH.label to "Office", SCH.value to "office"))),
        ),
        SCH.layout to buildMap<String, Any?> {
            mode?.let { put(SL.mode, it) }
            put(SL.schemaFields, listOf(mapOf(SL.field to "title", SL.label to "Title")))
        },
    )
    fun fields(out: Map<String, Any?>) = ((out[SCH.layout] as Map<*, *>)[SL.schemaFields] as List<*>).map { (it as Map<*, *>)[SL.field] }

    "a listed field's entry is replaced in any mode" {
        for (mode in listOf(null, SLM.reorder, SLM.authoritative)) {
            val out = withSharedField(body(mode), "title", mapOf(SL.label to "Name"), null)
            fields(out) shouldBe listOf("title")
        }
    }

    "an annotating layout takes a new entry" {
        fields(withSharedField(body(null), "note", mapOf(SL.label to "Note"), null)) shouldBe listOf("title", "note")
    }

    "a layout that owns its list is never extended" {
        shouldThrow<KdrException> { withSharedField(body(SLM.authoritative), "note", mapOf(SL.label to "Note"), null) }
            .message.orEmpty() shouldContain "the form's fields and their order"
        shouldThrow<KdrException> { withSharedField(body(SLM.reorder), "note", mapOf(SL.label to "Note"), null) }
            .message.orEmpty() shouldContain "the form's field order"
    }

    "a choices-only edit of a field the layout leaves out writes no layout entry" {
        val options = listOf<Map<String, Any?>>(mapOf(SCH.value to "office", SCH.label to "At the office"), mapOf(SCH.value to "park", SCH.label to "A park"))
        val out = withSharedField(body(SLM.authoritative), "venue", null, options)
        fields(out) shouldBe listOf("title")
    }
})
