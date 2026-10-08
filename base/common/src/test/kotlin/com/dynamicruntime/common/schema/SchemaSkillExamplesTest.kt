package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Holds `.claude/skills/kdr-schema-builder` to the code, by running its worked example and checking the claims
 * it makes about the result.
 *
 * A skill is read *instead of* working the API out, so a wrong one is followed rather than noticed — which is
 * how that skill came to show examples that could not compile and describe a layer that had moved modules. A
 * signature change now breaks this instead of quietly staling the documentation.
 *
 * **What this does not do:** it cannot read the Markdown, so it cannot prove the prose is right. It pins the
 * *example* — if you change the DSL and land here, the skill needs the same edit. Keep the code below a
 * faithful transcription of the skill's example; do not "improve" it past what the skill shows.
 *
 * The DSL itself is covered by [SchTypeBuilderTest] / [SchValidatorTest]. This is about the documentation.
 */
class SchemaSkillExamplesTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // Transcribed from the skill's "The DSL" section.
    fun example(): Map<String, Any?> = schemaDefs(cxt, "abc.people") {
        val name = property("name", "A name")
        val active = property("active", "Active flag") { type = SCT.boolean }

        type("Count") { type = SCT.integer; description = "A counting integer" }

        type("Person") {
            type = SCT.kObject
            property(name, required = true)
            property(active) { description = "Currently active" }
            property("age", "Age in years") { type = SCT.integer }
            property("nickname", "Informal name")
            property("count", "How many") { ref("Count") }
        }
    }

    $$"the skill's example builds the $defs it says it does" {
        // "returns the $defs contents keyed by fully-qualified namespace.Name (here abc.people.Count, abc.people.Person)"
        example().keys shouldContainExactlyInAnyOrder listOf("abc.people.Count", "abc.people.Person")
    }

    "required is on the side, not per field" {
        val person = example()["abc.people.Person"].toJsonMapOrEmpty()
        (person[SCH.required] as List<*>) shouldContain "name"
        // The field itself carries no required flag.
        person[SCH.properties].toJsonMapOrEmpty()["name"].toJsonMapOrEmpty().keys shouldContain SCH.description
        person[SCH.properties].toJsonMapOrEmpty()["name"].toJsonMapOrEmpty().keys.contains("required") shouldBe false
    }

    "a field defaults to string unless the block sets a type or a ref" {
        val props = example()["abc.people.Person"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        props["nickname"].toJsonMapOrEmpty()[SCH.type] shouldBe SCT.string
        props["age"].toJsonMapOrEmpty()[SCH.type] shouldBe SCT.integer
    }

    $$"ref(\"Count\") points at #/$defs/abc.people.Count" {
        val props = example()["abc.people.Person"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        props["count"].toJsonMapOrEmpty()[SCH.dRef] shouldBe $$"#/$defs/abc.people.Count"
    }

    "a reused property is cloned per use, so mutating one does not touch the other" {
        val props = example()["abc.people.Person"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        // The skill's example clones `active` and overrides its description.
        props["active"].toJsonMapOrEmpty()[SCH.description] shouldBe "Currently active"
        // ... while the template it came from kept its own (built fresh here, same declaration).
        val fresh = schemaDefs(cxt, "abc.people") {
            val active = property("active", "Active flag") { type = SCT.boolean }
            type("Other") { type = SCT.kObject; property(active) }
        }
        fresh["abc.people.Other"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()["active"].toJsonMapOrEmpty()[SCH.description] shouldBe
            "Active flag"
    }

    // The skill's "Formats" section: each format is declared with a helper and asked about with a predicate.
    "the format helpers produce what the skill documents" {
        val defs = schemaDefs(cxt, "fmt") {
            type("Holder") {
                type = SCT.kObject
                property("day", "A day") { dayOnlyDate() }
                property("at", "A timestamp") { dateTime() }
                property("file", "File content") { binaryContent() }
            }
        }
        val props = defs["fmt.Holder"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()

        // The literal values, not just the constants: the skill quotes these, and comparing a constant to
        // itself would pass even if its value changed out from under the documentation.
        props["day"].toJsonMapOrEmpty()[SCH.format] shouldBe "date"
        props["at"].toJsonMapOrEmpty()[SCH.format] shouldBe "date-time"

        // "binaryContent() -> {"type": "string", "format": "binary"} ... OpenAPI's spelling for a file".
        // The value is OpenAPI's, so it is not ours to change: a different string is a different contract.
        props["file"].toJsonMapOrEmpty()[SCH.type] shouldBe "string"
        props["file"].toJsonMapOrEmpty()[SCH.format] shouldBe "binary"

        isDateFormat(SFMT.date) shouldBe true
        isBinaryFormat(SFMT.binary) shouldBe true
        isBinaryFormat(SFMT.date) shouldBe false
    }

    // "A binary-format field is exempt from all of it, though required still applies."
    "a binary field passes through validation untouched, but is still required" {
        val defs = schemaDefs(cxt, "fmt") {
            type("Upload") {
                type = SCT.kObject
                property("file", "The file") { binaryContent() }
            }
        }
        val type = parseSchemaTypes(defs)["fmt.Upload"]!!
        // Not a String, and not coerced into one: the value comes back exactly as handed in.
        val content = Any()
        val result = coerceAndValidate(type, mapOf("file" to content))
        result.failures.size shouldBe 0
        result.value.toJsonMapOrEmpty()["file"] shouldBe content
    }

    // Transcribed from the skill's "Choice lists: written down, or sourced at render time" section.
    "a sourced choice list declares an id and nothing else" {
        val defs = schemaDefs(cxt, "cat") {
            type("Query") {
                type = SCT.kObject
                property(EI.client, "Which client to look at.") { optionsSource(CLD.clientOptions) }
            }
        }
        val field = defs["cat.Query"].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[EI.client].toJsonMapOrEmpty()
        field[SCH.optionsSource] shouldBe CLD.clientOptions
        // "A sourced list takes no part in validation": the parser leaves the field unbounded, so nothing
        // here can produce an `invalidOption`.
        parseSchemaTypes(defs)["cat.Query"]!!.properties.getValue(EI.client).valueType.options shouldBe null
    }

    // Transcribed from the skill's "Layouts: `g-layout`" section.
    "the layout builder writes the g-layout block the skill shows, and the store keeps it out of the schema" {
        val defs = schemaDefs(cxt, "lay") {
            type("Questionnaire") {
                type = SCT.kObject
                property("topic", "What this is about.")
                property("hasIssue", "Whether a problem was flagged.") { type = SCT.boolean }
                layout(fragmentFileId = "acme") {
                    field("topic", label = "Topic", description = "Pick the subject.")
                    field("hasIssue", label = "Has issue?")
                    string(LAYSTR.formErrorHint, "Fix the highlighted fields and try again.")
                }
            }
        }
        // The JSON block the skill shows beside the builder, key for key.
        defs["lay.Questionnaire"].toJsonMapOrEmpty()[SCH.layout] shouldBe mapOf(
            SL.fragmentFileId to "acme",
            SL.schemaFields to listOf(
                mapOf(SL.field to "topic", SL.label to "Topic", SL.description to "Pick the subject."),
                mapOf(SL.field to "hasIssue", SL.label to "Has issue?"),
            ),
            SL.strings to mapOf(LAYSTR.formErrorHint to "Fix the highlighted fields and try again."),
        )
        // "Never read into SchType ... stripped from the served schema ... delivered out-of-band."
        val layouts = collectLayouts(defs)
        layouts["lay.Questionnaire"]!!.fieldNames shouldBe listOf("topic", "hasIssue")
        withoutLayouts(defs)["lay.Questionnaire"].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
        deliveredLayouts(layouts, listOf("lay.Questionnaire")).keys shouldBe setOf("lay.Questionnaire")
    }
    // Transcribed from the skill's "Standard constraints" section (issue #823).
    "the standard constraints example validates as the skill says" {
        val defs = schemaDefs(cxt, "abc.people") {
            type("Order") {
                type = SCT.kObject
                property("postcode", "A five-digit postal code.") {
                    pattern = "^[0-9]{5}$"
                    errors { patternMismatch("A postal code is five digits.") }
                }
                property("price", "Price, above zero.") { type = SCT.number; exclusiveMinimum = 0 }
                property("tags", "Distinct tags.") { type = SCT.array; uniqueItems = true; items { type = SCT.string } }
            }
        }
        val order = parseSchemaTypes(defs).getValue("abc.people.Order")
        val failures = validate(order, mapOf("postcode" to "1234", "price" to 0, "tags" to listOf("a", "b", "a")))
        failures.map { it.code } shouldContainExactlyInAnyOrder
            listOf(SchFailCode.patternMismatch, SchFailCode.belowMinimum, SchFailCode.duplicateItem)
        // The built-in message quotes the pattern; the schema's own wording rides beside it.
        val mismatch = failures.single { it.code == SchFailCode.patternMismatch }
        mismatch.message shouldBe "This must match the pattern '^[0-9]{5}$'."
        mismatch.userMessage shouldBe "A postal code is five digits."
        failures.single { it.code == SchFailCode.belowMinimum }.message shouldBe "This must be more than 0."
    }

    // Transcribed from the skill's "Maps: free keys, typed values" section (issue #1055).
    "the maps example validates each entry and names its key" {
        val defs = schemaDefs(cxt, "abc.shop") {
            type("Line") {
                type = SCT.kObject
                property("item", "What was bought.", required = true)
                property("count", "How many.") { type = SCT.integer }
            }
            type("Order") {
                type = SCT.kObject
                property("labels", "Free labels, each some text.") { mapOfValues { type = SCT.string } }
                property("lines", "The lines, by a key the buyer chose.") { mapOfValues { ref("Line") } }
                property("copy", "Wording, by namespace and then by key.") { mapOfValues { mapOfValues { type = SCT.string } } }
            }
        }
        val order = parseSchemaTypes(defs).getValue("abc.shop.Order")
        order.properties.getValue("labels").valueType.additionalValueType?.jsonType shouldBe SCT.string
        val failures = validate(
            order,
            mapOf(
                "labels" to mapOf("gift" to "yes", "_rush" to 1),
                "lines" to mapOf("gift" to mapOf("item" to "Pen", "count" to "many")),
                "copy" to mapOf("home" to mapOf("title" to "Welcome", "lede" to "")),
            ),
        )
        // A failure under the entry's key; `_rush` is an entry like any other; an empty text is a value.
        failures.map { it.path } shouldContainExactlyInAnyOrder listOf("labels._rush", "lines.gift.count")
    }

    // Transcribed from the skill's "The schema for schema" section.
    "the schema for schema: the walk and the document report a body's faults of shape by path" {
        val body = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("cost" to mapOf(SCH.type to "strng")),
            SCH.required to "cost",
        )
        val faults = SchMetaSchema.structureFailures(body).map { it.path }
        val byDocument = validate(SchMetaSchema.nodeType, body).map { it.path }
        faults shouldBe listOf("properties.cost.type", "required")
        byDocument.toSet() shouldBe faults.toSet()
    }
})
