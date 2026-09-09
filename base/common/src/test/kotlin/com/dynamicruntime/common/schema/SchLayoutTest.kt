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

    "a block-level heading (label) round-trips and is boot-checked as copy (issue #605)" {
        val type = parseSchemaTypes(mapOfDefs("acme.Q" to typeBody(withLayout = false)))["acme.Q"]
        // A backend pull heading parses, round-trips through toJsonMap, and passes the frontend-pass check
        // (the `%{...}` block is invisible to the `$` pass, and resolution is validated elsewhere).
        val ok = parseSchLayout("Type 'acme.Q'", mapOf(
            SL.label to """%{@t("help.heading")}""",
            SL.schemaFields to listOf(mapOf(SL.field to "topic")),
        ))
        ok.label shouldBe """%{@t("help.heading")}"""
        ok.toJsonMap()[SL.label] shouldBe """%{@t("help.heading")}"""
        layoutTemplateProblems("Type 'acme.Q'", ok, type) shouldBe emptyList()

        // A frontend `${@t}` heading is refused -- a layout fragment pull uses the backend `%{@t}`.
        val frontend = parseSchLayout("Type 'acme.Q'", mapOf(
            SL.label to $$"""${@t("help.heading")}""",
            SL.schemaFields to listOf(mapOf(SL.field to "topic")),
        ))
        val problems = layoutTemplateProblems("Type 'acme.Q'", frontend, type)
        problems.size shouldBe 1
        problems.single() shouldContain "heading"
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

    // --- the fragment-pull resolution check (issue #620), driven by a fake resolver ---

    "layoutPullProblems resolves backend pulls per copy string and reports only the ones that miss" {
        val layout = SchLayout(
            fragmentFileId = "help",
            label = """%{@t("q.heading")}""",                 // two-part -> help.q.heading (resolves)
            fields = listOf(
                SchLayoutField("topic", """%{@t("other.q.topicHelp")}""", null, null), // three-part, key missing
                SchLayoutField("notes", """%{@t("q.notes") ?: "Notes"}""", null, null), // guarded -> skipped
                SchLayoutField("year", "Plain copy, no pull", null, null),                    // no %{...} -> skipped
            ),
        )
        fun resolve(fileId: String, nsKey: String): LayoutPullHit = when (fileId) {
            "help" if nsKey == "q.heading" -> LayoutPullHit(fileFound = true, backend = true, keyPresent = true)
            "other" if nsKey == "q.topicHelp" -> LayoutPullHit(fileFound = true, backend = true, keyPresent = false)
            else -> LayoutPullHit(fileFound = false, backend = false, keyPresent = false)
        }
        val problems = layoutPullProblems("Type 'X'", layout, ::resolve)
        problems.size shouldBe 1
        problems.single() shouldContain "topic"
        problems.single() shouldContain "q.topicHelp"
    }

    "layoutPullProblems tells a missing file from a frontend-file pull" {
        val layout = SchLayout("help", null, listOf(
            SchLayoutField("a", """%{@t("nofile.ns.k")}""", null, null),
            SchLayoutField("b", """%{@t("frontendFile.ns.k")}""", null, null),
        ))
        fun resolve(fileId: String, @Suppress("unused") nsKey: String): LayoutPullHit = when (fileId) {
            "nofile" -> LayoutPullHit(fileFound = false, backend = false, keyPresent = false)
            "frontendFile" -> LayoutPullHit(fileFound = true, backend = false, keyPresent = true) // found, not backend
            else -> LayoutPullHit(fileFound = true, backend = true, keyPresent = true)
        }
        val problems = layoutPullProblems("Type 'Y'", layout, ::resolve)
        problems.size shouldBe 2
        problems.any { it.contains("no fragment file 'nofile'") } shouldBe true
        problems.any { it.contains("frontend file") } shouldBe true
    }

    "layoutPullProblems flags a key that is not a fileId.namespace.key reference, without resolving" {
        // A bare one-part key composes to nothing that can name a file and key, so it is reported on its shape
        // alone -- the resolver is never consulted (it would have nothing to look up).
        val layout = SchLayout("help", null, listOf(
            SchLayoutField("a", """%{@t("heading")}""", null, null),
        ))
        val problems = layoutPullProblems("Type 'Z'", layout) { _, _ ->
            error("the resolver must not be called for a malformed pull key")
        }
        problems.single() shouldContain "is not a fileId.namespace.key reference"
    }

    // --- the error override (issue #588) ---

    // An object with an unbounded string and a bounded integer, for the per-code param checks below.
    val errType: SchType? = parseSchemaTypes(mapOfDefs("acme.Q" to mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf(
            "topic" to mapOf(SCH.type to SCT.string),
            "year" to mapOf(SCH.type to SCT.integer, SCH.minimum to 2000, SCH.maximum to 2100),
        ),
    )))["acme.Q"]

    "an error override round-trips through parseSchLayout and toJsonMap" {
        val raw = mapOf(SL.schemaFields to listOf(mapOf(
            SL.field to "topic",
            SL.errors to mapOf(SchFailCode.invalidOption.name to "Pick a real one.", SCH.errorDefault to "Bad topic."),
        )))
        val field = parseSchLayout("Type 'X'", raw).fieldFor("topic")!!
        field.errors shouldBe mapOf(SchFailCode.invalidOption.name to "Pick a real one.", SCH.errorDefault to "Bad topic.")
        // Re-serialized and re-parsed, the override survives -- the wire form carries it.
        parseSchLayout("Type 'X'", parseSchLayout("Type 'X'", raw).toJsonMap()).fieldFor("topic")!!.errors shouldBe field.errors
    }

    "a mistyped SchFailCode key in an error override fails the parse" {
        val raw = mapOf(SL.schemaFields to listOf(mapOf(
            SL.field to "topic",
            SL.errors to mapOf("typoWrong" to "Nope."),
        )))
        shouldThrow<KdrException> { parseSchLayout("Type 'X'", raw) }.message shouldContain "typoWrong"
    }

    "layoutTemplateProblems passes an error template that uses only its failure code's params" {
        val ok = SchLayout("acme", null, listOf(
            // invalidOption offers the value and the option list; belowMinimum offers the bound (year is bounded).
            SchLayoutField("topic", null, null, null, mapOf(SchFailCode.invalidOption.name to $$"Not ${value}; choose ${options}.")),
            SchLayoutField("year", null, null, null, mapOf(SchFailCode.belowMinimum.name to $$"At least ${min}.")),
        ))
        layoutTemplateProblems("Type 'acme.Q'", ok, errType) shouldBe emptyList()
    }

    "layoutTemplateProblems flags an error template referencing a param its failure code lacks" {
        // ${max} is not part of an invalidOption's context, so it is caught like a mistyped key.
        val bad = SchLayout("acme", null, listOf(
            SchLayoutField("topic", null, null, null, mapOf(SchFailCode.invalidOption.name to $$"Up to ${max}.")),
        ))
        layoutTemplateProblems("Type 'acme.Q'", bad, errType).single() shouldContain "max"
    }

    "a default error message may reference only the field name" {
        val bad = SchLayout("acme", null, listOf(
            SchLayoutField("topic", null, null, null, mapOf(SCH.errorDefault to $$"You gave ${value}.")),
        ))
        layoutTemplateProblems("Type 'acme.Q'", bad, errType).single() shouldContain "value"
        val ok = SchLayout("acme", null, listOf(
            SchLayoutField("topic", null, null, null, mapOf(SCH.errorDefault to $$"Problem with ${field}.")),
        ))
        layoutTemplateProblems("Type 'acme.Q'", ok, errType) shouldBe emptyList()
    }

    "a malformed error template fails the boot" {
        val bad = SchLayout("acme", null, listOf(
            SchLayoutField("topic", null, null, null, mapOf(SchFailCode.badValue.name to $$"Bad ${value")),
        ))
        layoutTemplateProblems("Type 'acme.Q'", bad, errType).single() shouldContain "malformed"
    }

    "errorContextNames and errorContextData agree, per failure code" {
        val topic = errType!!.properties["topic"]!!.valueType
        val year = errType.properties["year"]!!.valueType
        errorContextNames(SchFailCode.invalidOption, topic) shouldBe setOf("field", "value", "options")
        errorContextNames(SchFailCode.missingRequired, topic) shouldBe setOf("field")
        errorContextNames(SchFailCode.belowMinimum, year) shouldBe setOf("field", "value", "min")
        errorContextNames(null, topic) shouldBe setOf("field") // the `default` key

        val opts = listOf(SchOption("a", "Apples"), SchOption("b", "Bananas"))
        errorContextData(SchFailCode.invalidOption, topic, "topic", "x", opts) shouldBe
            mapOf("field" to "topic", "value" to "x", "options" to "Apples, Bananas")
        errorContextData(SchFailCode.belowMinimum, year, "year", 1999L, null).keys shouldBe setOf("field", "value", "min")
        errorContextData(SchFailCode.missingRequired, topic, "topic", null, null) shouldBe mapOf("field" to "topic")
    }
})
