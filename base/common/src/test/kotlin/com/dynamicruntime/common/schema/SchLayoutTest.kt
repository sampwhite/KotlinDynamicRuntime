package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The pure `g-layout` plumbing (issue #584): reading a layout block into [SchLayout], collecting the per-type
 * layouts out of a `$defs` bag, stripping them from what the catalog serves, and the boot check that a layout
 * cannot name a field the type does not declare. No store, no boot -- maps in, model or problems out.
 */
class SchLayoutTest : StringSpec({
    // One questionnaire-shaped type with a g-layout over two of its fields.
    val layoutBlock = mapOf(
        SL.fragmentFileId to "acme",
        SL.schemaFields to listOf(
            mapOf(SL.field to "topic", SL.label to "Topic", SL.description to $$"${topic.help}"),
            mapOf(SL.field to "hasIssue", SL.label to "Has issue?"),
        ),
    )
    fun typeBody(withLayout: Boolean) = buildMap {
        put(SCH.type, SCT.kObject)
        put(
            SCH.properties,
            mapOf("topic" to mapOf(SCH.type to SCT.string), "hasIssue" to mapOf(SCH.type to SCT.boolean)),
        )
        if (withLayout) put(SCH.layout, layoutBlock)
    }

    "parseSchLayout reads the block-level fragment file and the per-field copy in order" {
        val layout = parseSchLayout("Type 'X'", layoutBlock)
        layout.fragmentFileId shouldBe "acme"
        layout.fieldNames shouldBe listOf("topic", "hasIssue")
        layout.fields[0].label shouldBe "Topic"
        layout.fields[0].description shouldBe $$"${topic.help}"
        layout.fields[1].label shouldBe "Has issue?"
        layout.fields[1].description shouldBe null
    }

    "parseSchLayout refuses a field entry with no 'field'" {
        val bad = mapOf(SL.schemaFields to listOf(mapOf(SL.label to "Orphan")))
        shouldThrow<KdrException> { parseSchLayout("Type 'X'", bad) }
    }

    "collectLayouts keys only the types that declare a g-layout, and leaves the defs untouched" {
        val defs = mapOf("acme.Q" to typeBody(withLayout = true), "acme.Plain" to typeBody(withLayout = false))
        val layouts = collectLayouts(defs)
        layouts.keys shouldBe setOf("acme.Q")
        layouts["acme.Q"]!!.fieldNames shouldBe listOf("topic", "hasIssue")
        // Read-only: the source body still carries its g-layout for the per-client overlay to inherit.
        (defs["acme.Q"] as Map<*, *>).containsKey(SCH.layout) shouldBe true
    }

    "collectLayouts refuses a g-layout that is not an object" {
        shouldThrow<KdrException> { collectLayouts(mapOf("acme.Q" to mapOf(SCH.type to SCT.kObject, SCH.layout to "nope"))) }
    }

    "withoutLayouts removes the g-layout and nothing else" {
        val stripped = withoutLayouts(mapOf("acme.Q" to typeBody(withLayout = true), "acme.Plain" to typeBody(withLayout = false)))
        (stripped["acme.Q"] as Map<*, *>).containsKey(SCH.layout) shouldBe false
        (stripped["acme.Q"] as Map<*, *>).containsKey(SCH.properties) shouldBe true // the rest survives
        stripped["acme.Plain"] shouldBe typeBody(withLayout = false) // a body with no layout is unchanged
    }

    "layoutFieldProblems flags a field the type does not declare, and passes a clean one" {
        val type = parseSchemaTypes(mapOf("acme.Q" to typeBody(withLayout = false)))["acme.Q"]
        layoutFieldProblems("Type 'acme.Q'", parseSchLayout("Type 'acme.Q'", layoutBlock), type) shouldBe emptyList()

        val badLayout = parseSchLayout("Type 'acme.Q'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "nope"))))
        layoutFieldProblems("Type 'acme.Q'", badLayout, type).size shouldBe 1
        // A layout whose type did not resolve is itself a problem.
        layoutFieldProblems("Type 'acme.Q'", badLayout, null).size shouldBe 1
    }
})
