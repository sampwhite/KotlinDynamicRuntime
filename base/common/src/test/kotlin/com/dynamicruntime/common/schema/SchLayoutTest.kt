package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The pure `g-layout` plumbing (issue #584): reading a layout block into [SchLayout], collecting the per-type
 * layouts out of a `$defs` bag, stripping them from what the catalog serves, pruning an inherited layout to a
 * narrowed type, and the boot check. No store, no boot -- maps in, model or problems out. The strictness cases
 * are the point: every one is a way a layout could parse clean and render nothing.
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
    fun mapOfDefs(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf(*pairs)

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

    "parseSchLayout refuses an unknown block key -- the draft's old 'formFields' cannot parse clean and do nothing" {
        val old = mapOf("formFields" to listOf(mapOf(SL.field to "topic")))
        shouldThrow<KdrException> { parseSchLayout("Type 'X'", old) }.message shouldContain "formFields"
    }

    "parseSchLayout refuses an unknown key on a field entry" {
        val bad = mapOf(SL.schemaFields to listOf(mapOf(SL.field to "topic", "labl" to "Topic")))
        shouldThrow<KdrException> { parseSchLayout("Type 'X'", bad) }.message shouldContain "labl"
    }

    "parseSchLayout refuses a present block with no fields, or a field list that is not a list" {
        shouldThrow<KdrException> { parseSchLayout("Type 'X'", mapOf(SL.fragmentFileId to "acme")) }
        shouldThrow<KdrException> { parseSchLayout("Type 'X'", mapOf(SL.schemaFields to emptyList<Any?>())) }
        shouldThrow<KdrException> { parseSchLayout("Type 'X'", mapOf(SL.schemaFields to mapOf("topic" to mapOf<String, Any?>()))) }
    }

    "collectLayouts keys only the types that declare a g-layout, and leaves the defs untouched" {
        val defs = mapOfDefs("acme.Q" to typeBody(withLayout = true), "acme.Plain" to typeBody(withLayout = false))
        val layouts = collectLayouts(defs)
        layouts.keys shouldBe setOf("acme.Q")
        layouts["acme.Q"]!!.fieldNames shouldBe listOf("topic", "hasIssue")
        // Read-only: the source body still carries its g-layout for the per-client overlay to inherit.
        (defs["acme.Q"] as Map<*, *>).containsKey(SCH.layout) shouldBe true
    }

    "collectLayouts refuses a g-layout that is not an object" {
        shouldThrow<KdrException> { collectLayouts(mapOfDefs("acme.Q" to mapOf(SCH.type to SCT.kObject, SCH.layout to "nope"))) }
    }

    "collectLayouts refuses a g-layout nested on an inline sub-object -- it has no name to key by" {
        val nested = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "address" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("street" to mapOf(SCH.type to SCT.string)), SCH.layout to layoutBlock),
            ),
        )
        shouldThrow<KdrException> { collectLayouts(mapOfDefs("acme.Q" to nested)) }.message shouldContain "named type"
    }

    "withoutLayouts removes the g-layout and nothing else" {
        val stripped = withoutLayouts(mapOfDefs("acme.Q" to typeBody(withLayout = true), "acme.Plain" to typeBody(withLayout = false)))
        (stripped["acme.Q"] as Map<*, *>).containsKey(SCH.layout) shouldBe false
        (stripped["acme.Q"] as Map<*, *>).containsKey(SCH.properties) shouldBe true // the rest survives
        stripped["acme.Plain"] shouldBe typeBody(withLayout = false) // a body with no layout is unchanged
    }

    "prunedTo drops the fields a narrowed type no longer declares, and is identity when nothing is dropped" {
        val layout = parseSchLayout("Type 'X'", layoutBlock)
        // A client that kept only `topic` inherits this layout; `hasIssue` is moot for it and is pruned.
        layout.prunedTo(setOf("topic")).fieldNames shouldBe listOf("topic")
        // Nothing to drop: the very same object comes back, so an unchanged layout is shared, not copied.
        (layout.prunedTo(setOf("topic", "hasIssue")) === layout) shouldBe true
    }

    "layoutFieldProblems flags a field the type does not declare, and passes a clean one" {
        val type = parseSchemaTypes(mapOfDefs("acme.Q" to typeBody(withLayout = false)))["acme.Q"]
        layoutFieldProblems("Type 'acme.Q'", parseSchLayout("Type 'acme.Q'", layoutBlock), type) shouldBe emptyList()

        val badLayout = parseSchLayout("Type 'acme.Q'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "nope"))))
        layoutFieldProblems("Type 'acme.Q'", badLayout, type).size shouldBe 1
        // A layout whose type did not resolve is itself a problem.
        layoutFieldProblems("Type 'acme.Q'", badLayout, null).size shouldBe 1
    }

    "layoutFieldProblems refuses a layout on a non-object type by naming the real mistake" {
        // An array has no property set to render; the message says so rather than listing every field as undeclared.
        val arrayType = parseSchemaTypes(
            mapOfDefs("acme.List" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string))),
        )["acme.List"]
        val problems = layoutFieldProblems("Type 'acme.List'", parseSchLayout("Type 'acme.List'", layoutBlock), arrayType)
        problems.size shouldBe 1
        problems.single() shouldContain "object type"
    }
})
