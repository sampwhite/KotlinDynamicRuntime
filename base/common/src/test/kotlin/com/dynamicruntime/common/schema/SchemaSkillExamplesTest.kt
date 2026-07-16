package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
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
    fun example(): Map<String, Any?> = schemaDefs(cxt, "core") {
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

    "the skill's example builds the \$defs it says it does" {
        // "returns the $defs contents keyed by fully-qualified namespace.Name (here core.Count, core.Person)"
        example().keys shouldContainExactlyInAnyOrder listOf("core.Count", "core.Person")
    }

    "required is on the side, not per field" {
        val person = example()["core.Person"].toJsonMapOrEmpty()
        (person[SCH.required] as List<*>) shouldContain "name"
        // The field itself carries no required flag.
        person[SCH.properties].toJsonMapOrEmpty()["name"].toJsonMapOrEmpty().keys shouldContain SCH.description
        person[SCH.properties].toJsonMapOrEmpty()["name"].toJsonMapOrEmpty().keys.contains("required") shouldBe false
    }

    "a field defaults to string unless the block sets a type or a ref" {
        val props = example()["core.Person"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        props["nickname"].toJsonMapOrEmpty()[SCH.type] shouldBe SCT.string
        props["age"].toJsonMapOrEmpty()[SCH.type] shouldBe SCT.integer
    }

    "ref(\"Count\") points at #/\$defs/core.Count" {
        val props = example()["core.Person"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        props["count"].toJsonMapOrEmpty()[SCH.dRef] shouldBe "#/\$defs/core.Count"
    }

    "a reused property is cloned per use, so mutating one does not touch the other" {
        val props = example()["core.Person"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        // The skill's example clones `active` and overrides its description.
        props["active"].toJsonMapOrEmpty()[SCH.description] shouldBe "Currently active"
        // ... while the template it came from kept its own (built fresh here, same declaration).
        val fresh = schemaDefs(cxt, "core") {
            val active = property("active", "Active flag") { type = SCT.boolean }
            type("Other") { type = SCT.kObject; property(active) }
        }
        fresh["core.Other"].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()["active"].toJsonMapOrEmpty()[SCH.description] shouldBe
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
})
