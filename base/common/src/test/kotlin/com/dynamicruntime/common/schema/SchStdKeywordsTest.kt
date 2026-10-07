package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.DefRepairContext
import com.dynamicruntime.common.startup.repairTypeDef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The standard keywords this layer reads are held to their shapes (issue #1053): a value of the wrong kind fails the
 * parse by name -- where it used to be read as though the keyword were absent, so that a typo in a value turned
 * validation off and nothing said so. A keyword the layer does not read stays a document's own business, and a
 * client's stored definition is repaired rather than refused: the keyword goes, and the fault costs only itself.
 */
class SchStdKeywordsTest : StringSpec({

    /** Parses one type whose `v` property is [property]; returns the refusal's message, or null. */
    fun refusal(property: Map<String, Any?>, vararg onType: Pair<String, Any?>): String? = try {
        parseSchemaTypes(mapOf("s.Raw" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("v" to property)) + onType))
        null
    } catch (e: KdrException) {
        e.message.orEmpty()
    }

    "a type that is no type is refused, where it used to be kept and then constrain nothing" {
        val message = refusal(mapOf(SCH.type to "strng"))
        message.shouldNotBeNull() shouldContain "Property 'v' sets 'type' to 'strng'"
        message shouldContain "one of string, number, integer, boolean, array, object, null"
        refusal(mapOf(SCH.type to 5L)).shouldNotBeNull() shouldContain "sets 'type' to 5"
        // A list of types is legal JSON Schema and not read here: said, rather than left to look like a typo. So
        // are a schema for undeclared properties, a schema per item, and true or false standing for a schema.
        refusal(mapOf(SCH.type to listOf(SCT.string, SCT.kNull))).shouldNotBeNull() shouldContain "a list of types is not supported"
        refusal(mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to SCT.string))).shouldNotBeNull()
            .let { it shouldContain "valid JSON Schema and is not supported here"; it shouldContain "true or false" }
        refusal(mapOf(SCH.type to SCT.array, SCH.items to listOf(mapOf(SCH.type to SCT.string)))).shouldNotBeNull() shouldContain
            "a schema per position is not supported"
        refusal(mapOf(SCH.type to SCT.array, SCH.items to true)).shouldNotBeNull() shouldContain "an empty object accepts anything"
        refusal(mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("any" to true))).shouldNotBeNull()
            .let { it shouldContain "declares property 'any' as true"; it shouldContain "not supported here" }
        for (type in listOf(SCT.string, SCT.number, SCT.integer, SCT.boolean, SCT.array, SCT.kObject, SCT.kNull)) {
            refusal(mapOf(SCH.type to type)) shouldBe null
        }
    }

    "each keyword read leniently before now refuses a value of the wrong shape, naming the keyword and the place" {
        // On a property: the keywords a property's own schema carries.
        val onProperty = listOf(
            mapOf(SCH.type to SCT.kObject, SCH.required to "name") to "'required'",
            mapOf(SCH.type to SCT.kObject, SCH.required to listOf("name", 5L)) to "lists 5 in 'required'",
            mapOf(SCH.type to SCT.kObject, SCH.properties to listOf("name")) to "'properties'",
            mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("name" to "text")) to "declares property 'name' as 'text'",
            mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to "no") to "'additionalProperties'",
            mapOf(SCH.type to SCT.array, SCH.items to "string") to "'items'",
            mapOf(SCH.type to SCT.string, SCH.format to 5L) to "'format'",
            mapOf(SCH.type to SCT.string, SCH.description to listOf("x")) to "'description'",
            mapOf(SCH.type to SCT.string, SCH.title to true) to "'title'",
            mapOf(SCH.dRef to mapOf("to" to "s.Other")) to "'\$ref'",
            mapOf(SCH.type to SCT.string, SCH.minLength to "three") to "'minLength'",
            mapOf(SCH.type to SCT.integer, SCH.maximum to listOf(5L)) to "'maximum'",
            mapOf(SCH.oneOf to "s.Other") to "'oneOf'",
        )
        for ((property, named) in onProperty) {
            val message = refusal(property).shouldNotBeNull()
            message shouldContain named
            message shouldContain "Property 'v'"
        }
        // Beside a `$ref`, whose target never sees the property's own keywords: its title and description are read
        // from the property itself.
        fun beside(vararg own: Pair<String, Any?>) = shouldThrow<KdrException> {
            parseSchemaTypes(
                mapOf(
                    "s.Other" to mapOf(SCH.type to SCT.string),
                    "s.Holder" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("o" to mapOf(SCH.dRef to "#/\$defs/s.Other") + own)),
                ),
            )
        }.message.orEmpty()
        beside(SCH.title to 5L) shouldContain "Property 'o' sets 'title' to 5"
        beside(SCH.description to listOf("x")) shouldContain "'description'"
        // And on a type.
        refusal(mapOf(SCH.type to SCT.string), SCH.required to "v").shouldNotBeNull() shouldContain "Type 's.Raw'"
        refusal(mapOf(SCH.type to SCT.string), SCH.additionalProperties to 0L).shouldNotBeNull() shouldContain "true or false"
    }

    "a well-shaped keyword, a null, a bound spelled as text, and a keyword of the document's own all pass" {
        refusal(mapOf(SCH.type to SCT.string, SCH.minLength to 3L, SCH.maxLength to "64")) shouldBe null
        refusal(mapOf(SCH.type to null, SCH.required to null, SCH.items to null)) shouldBe null
        // A keyword this layer does not read is the document's own: a stock validator's document still parses.
        refusal(mapOf(SCH.type to SCT.string, "examples" to "not a list", "\$comment" to 5L, "multipleOf" to "x")) shouldBe null
        refusal(mapOf(SCH.type to SCT.string, "x-note" to mapOf(SCH.type to "strng"))) shouldBe null
        // Nor is a keyword's word in a value: a default is data.
        refusal(mapOf(SCH.type to SCT.kObject, SCH.default to mapOf(SCH.type to "refund", SCH.required to "soon"))) shouldBe null
        // A null property is one not set, as an alteration merged into a global type removes one.
        parseSchemaTypes(mapOf("s.Null" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("gone" to null))))
            .getValue("s.Null").properties.keys.shouldBeEmpty()
    }

    "the fault is coded and located for a report, as any schema fault is" {
        val analysis = analyzeSchemaTypes(
            mapOf(
                "s.Deep" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("list" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to "text"))),
                ),
            ),
        )
        val problem = analysis.problems.single()
        problem.code shouldBe SchemaError.badValue
        problem.location.shouldNotBeNull().path shouldBe "s.Deep.properties.list.items"
        shouldThrow<KdrException> { parseSchemaTypes(mapOf("s.Top" to mapOf(SCH.type to "objct"))) }.message.orEmpty() shouldContain "Type 's.Top'"
    }

    val context = DefRepairContext(emptySet()) { null }

    "a client's stored definition is repaired, not refused: the keyword goes and the rest of the definition stands" {
        val body = mapOf(
            SCH.type to SCT.kObject,
            SCH.required to listOf("name", 5L, listOf("x")),
            SCH.additionalProperties to "no",
            SCH.properties to mapOf(
                "name" to mapOf(SCH.type to "strng", SCH.minLength to "three", SCH.maxLength to "64"),
                "tags" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string, SCH.format to 7L)),
                "junk" to "not a schema",
            ),
        )
        val (repaired, repairs) = repairTypeDef("Type 'client.x.Card'", body, context)
        // Each fault is said, with the place it is in, and what was done about it.
        repairs.map { it.message }.let { messages ->
            messages.size shouldBe 6
            messages.single { "'additionalProperties'" in it } shouldContain "Type 'client.x.Card' sets"
            messages.single { "in 'required'" in it } shouldContain "lists 5, a list"
            messages.single { "property 'junk'" in it } shouldContain "must be an object"
            messages.single { "'type'" in it } shouldContain "Type 'client.x.Card' property 'name'"
            messages.single { "'minLength'" in it } shouldContain "'three'"
            messages.single { "'format'" in it } shouldContain "property 'tags'"
        }
        repairs.all { it.degradedTo.startsWith("Dropping") } shouldBe true
        repairs.single { "in 'required'" in it.message }.degradedTo shouldContain "that part of 'required'"
        // What is left of `required` is exactly what the lenient reading made of it -- the names its entries spell,
        // a number read as its text -- and the bad property and the keywords at fault are gone, with everything
        // sound kept: the bound that reads as a number among it.
        repaired[SCH.required] shouldBe listOf("name", "5")
        repaired.containsKey(SCH.additionalProperties) shouldBe false
        val properties = repaired[SCH.properties] as Map<*, *>
        properties.keys shouldBe setOf("name", "tags")
        properties["name"] shouldBe mapOf(SCH.maxLength to "64")
        properties["tags"] shouldBe mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string))
        // And it now parses, to the type it was read as before.
        val card = parseSchemaTypes(mapOf("client.x.Card" to repaired)).getValue("client.x.Card")
        card.required shouldBe setOf("name", "5")
        card.additionalProperties shouldBe false
        card.properties.getValue("name").valueType.jsonType shouldBe null
        // A sound definition is handed back as it was.
        val sound = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("a" to mapOf(SCH.type to SCT.string)))
        repairTypeDef("Type 'client.x.Sound'", sound, context).let { (same, none) ->
            (same === sound) shouldBe true
            none.shouldBeEmpty()
        }
    }

    "a keyword's word in data is not a keyword: a default, a const and a keyword of the document's own are left alone" {
        val body = mapOf(
            SCH.type to SCT.kObject,
            SCH.default to mapOf(SCH.type to "refund", SCH.required to "soon", SCH.properties to listOf(1L)),
            "examples" to listOf(mapOf(SCH.type to "refund", SCH.items to "three")),
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.kObject, SCH.const to mapOf(SCH.type to "refund", SCH.additionalProperties to "no")),
                // A null is how an alteration merged into a global type removes a property: an instruction, kept.
                "gone" to null,
            ),
        )
        val (same, repairs) = repairTypeDef("Type 'client.x.Data'", body, context, altersGlobal = true)
        repairs.shouldBeEmpty()
        (same === body) shouldBe true
    }
})
