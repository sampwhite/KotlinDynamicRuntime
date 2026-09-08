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

    // --- delivery (issue #585) ---

    "toJsonMap round-trips through parseSchLayout, writing no null override" {
        val layout = parseSchLayout("Type 'X'", layoutBlock)
        val wire = layout.toJsonMap()
        wire[SL.fragmentFileId] shouldBe "acme"
        val entries = wire[SL.schemaFields] as List<*>
        // `hasIssue` has only a label: no `description` key written, rather than one carrying null.
        (entries[1] as Map<*, *>).containsKey(SL.description) shouldBe false
        val again = parseSchLayout("Type 'X'", wire)
        again.fieldNames shouldBe layout.fieldNames
        again.fields[0].description shouldBe layout.fields[0].description
        // A pruned layout re-serializes as the pruned form -- what a narrowed client's page must receive.
        (layout.prunedTo(setOf("topic")).toJsonMap()[SL.schemaFields] as List<*>).size shouldBe 1
    }

    "the layout builder writes the block the parser reads" {
        val built = SchLayoutBuilder("acme").apply {
            field("topic", label = "Topic", description = $$"${topic.help}")
            field("hasIssue", label = "Has issue?")
        }.build()
        built shouldBe layoutBlock
    }

    "deliveredLayouts keys only the closure's types that have a layout -- absent, not empty, for the rest" {
        val layouts = collectLayouts(mapOfDefs("acme.Q" to typeBody(withLayout = true), "acme.Plain" to typeBody(withLayout = false)))
        val delivered = deliveredLayouts(layouts, listOf("acme.Q", "acme.Plain", "acme.Unknown"))
        delivered.keys shouldBe setOf("acme.Q")
        delivered["acme.Q"] shouldBe layoutBlock
        // A closure that reaches no layout type delivers an empty map, not a failure.
        deliveredLayouts(layouts, listOf("acme.Plain")) shouldBe emptyMap()
    }

    "parseDeliveredLayouts is the inverse, and reads an absent map as no layouts" {
        val parsed = parseDeliveredLayouts(mapOf("acme.Q" to layoutBlock))
        parsed.keys shouldBe setOf("acme.Q")
        parsed["acme.Q"]!!.fieldNames shouldBe listOf("topic", "hasIssue")
        parseDeliveredLayouts(null) shouldBe emptyMap()
        // A malformed entry is a fault (the backend built it from a parsed model), not a case to render around.
        shouldThrow<KdrException> { parseDeliveredLayouts(mapOf("acme.Q" to mapOf("formFields" to emptyList<Any?>()))) }
    }

    // --- the bounds context and the hint boot check (issue #587) ---

    // A type whose `year` field declares a minimum and a maximum, and `note` declares neither.
    val boundedDefs = mapOfDefs(
        "acme.R" to buildMap {
            put(SCH.type, SCT.kObject)
            put(
                SCH.properties,
                mapOf(
                    "year" to mapOf(SCH.type to SCT.integer, SCH.minimum to 2000, SCH.maximum to 2100),
                    "note" to mapOf(SCH.type to SCT.string),
                ),
            )
        },
    )
    val boundedType = parseSchemaTypes(boundedDefs)["acme.R"]!!

    "boundsContextNames and boundsContextData reflect exactly the bounds a field declares" {
        val year = boundedType.properties.getValue("year").valueType
        boundsContextNames(year) shouldBe setOf(LayoutCtx.min, LayoutCtx.max)
        boundsContextData(year) shouldBe mapOf(LayoutCtx.min to 2000.0, LayoutCtx.max to 2100.0)
        // A field with neither bound provides nothing -- so a hint referencing one is a boot failure.
        val note = boundedType.properties.getValue("note").valueType
        boundsContextNames(note) shouldBe emptySet()
        boundsContextData(note) shouldBe emptyMap()
    }

    "layoutTemplateProblems passes a hint that references only the params its field provides" {
        val layout = parseSchLayout("Type 'acme.R'", mapOf(SL.schemaFields to listOf(
            mapOf(SL.field to "year", SL.hint to $$"From ${min} to ${max}."),
        )))
        layoutTemplateProblems("Type 'acme.R'", layout, boundedType) shouldBe emptyList()
    }

    "layoutTemplateProblems refuses a hint referencing a bound the field does not declare" {
        // `${max}` on `note`, which has no maximum -- caught at boot like a mistyped key.
        val layout = parseSchLayout("Type 'acme.R'", mapOf(SL.schemaFields to listOf(
            mapOf(SL.field to "note", SL.hint to $$"At most ${max}."),
        )))
        val problems = layoutTemplateProblems("Type 'acme.R'", layout, boundedType)
        problems.size shouldBe 1
        problems.single() shouldContain "max"
    }

    "layoutTemplateProblems refuses a malformed hint template" {
        val layout = parseSchLayout("Type 'acme.R'", mapOf(SL.schemaFields to listOf(
            mapOf(SL.field to "year", SL.hint to $$"From ${min"),  // unterminated block
        )))
        layoutTemplateProblems("Type 'acme.R'", layout, boundedType).size shouldBe 1
    }

    "layoutTemplateProblems ignores a field with no hint, and a hint with no substitution" {
        val layout = parseSchLayout("Type 'acme.R'", mapOf(SL.schemaFields to listOf(
            mapOf(SL.field to "year", SL.label to "Year"),                 // no hint
            mapOf(SL.field to "note", SL.hint to "Anything you like."),    // plain hint, references nothing
        )))
        layoutTemplateProblems("Type 'acme.R'", layout, boundedType) shouldBe emptyList()
    }

    "layoutTemplateProblems refuses a fragment pull in any copy until #605 wires it" {
        // A `@t` pull in a description (or label/hint) would render raw today; caught at boot instead.
        val layout = parseSchLayout("Type 'acme.R'", mapOf(SL.schemaFields to listOf(
            mapOf(SL.field to "note", SL.description to $$"""See ${@t("acme.noteHelp")}."""),
        )))
        val problems = layoutTemplateProblems("Type 'acme.R'", layout, boundedType)
        problems.size shouldBe 1
        problems.single() shouldContain "605"
    }

    "layoutTemplateProblems refuses a malformed label or description, not only a hint" {
        val layout = parseSchLayout("Type 'acme.R'", mapOf(SL.schemaFields to listOf(
            mapOf(SL.field to "note", SL.label to $$"Broken ${oops"),   // unterminated block on a label
        )))
        layoutTemplateProblems("Type 'acme.R'", layout, boundedType).size shouldBe 1
    }

    "layoutTemplateProblems leaves a field-data reference in a label alone (dynamic, checked at render)" {
        // `${note}` on a label is a field-data path, not a fragment pull; label paths are not boot-checked.
        val layout = parseSchLayout("Type 'acme.R'", mapOf(SL.schemaFields to listOf(
            mapOf(SL.field to "note", SL.label to $$"Note: ${note}"),
        )))
        layoutTemplateProblems("Type 'acme.R'", layout, boundedType) shouldBe emptyList()
    }
})
