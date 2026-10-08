package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.DefRepairContext
import com.dynamicruntime.common.startup.repairTypeDef
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The generated schema for schema (issue #1056): one statement of each keyword's shape -- its table entry -- read
 * three ways. The **walk** reports every fault of shape in a body by path; the **document** says the same shapes
 * in our own dialect, as far as the dialect can; and the **parser** refuses whatever the walk does. These tests are
 * what holds the three to each other, which is the whole case for generating a meta-schema rather than keeping one.
 */
class SchMetaSchemaTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("test")
    val node = SchMetaSchema.nodeType

    fun obj(vararg properties: Pair<String, Any?>): Map<String, Any?> = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(*properties))
    fun walkPaths(body: Map<String, Any?>) = SchMetaSchema.structureFailures(body).map { it.path }
    fun documentPaths(body: Map<String, Any?>) = validate(node, body).map { it.path }

    /** Whether the parser refuses [body] as a type's definition: by a located problem, or outright. */
    fun parserRefuses(body: Map<String, Any?>): Boolean =
        runCatching { analyzeSchemaTypes(mapOf("t.Fixture" to body)).problems.isNotEmpty() }.getOrDefault(true)

    // One fault of shape each: where the walk reports it, and -- for a rule our dialect cannot state -- which listed
    // rule covers it, with the keyword that rule must name (none for the closed list, which names what *is* ours).
    class Fault(val what: String, val body: Map<String, Any?>, val at: String, val unstated: Pair<String, String?>? = null)
    val text = mapOf(SCH.type to SCT.string)
    val faults = listOf(
        Fault("a type that is no type", obj("a" to mapOf(SCH.type to "strng")), "properties.a.type"),
        Fault("a list of types", mapOf(SCH.type to listOf(SCT.string, SCT.kNull)), "type"),
        Fault("required as text", obj("a" to text) + (SCH.required to "a"), "required"),
        Fault("a number in required", obj("a" to text) + (SCH.required to listOf("a", 5L)), "required"),
        Fault("properties as a list", mapOf(SCH.type to SCT.kObject, SCH.properties to listOf(text)), "properties"),
        Fault("a property that is no schema", obj("a" to text, "b" to 5L), "properties"),
        Fault("items as a list", obj("a" to mapOf(SCH.type to SCT.array, SCH.items to listOf(text))), "properties.a.items"),
        Fault("a title that is not text", mapOf(SCH.type to SCT.string, SCH.title to 5L), "title"),
        Fault("a reference that is not text", obj("a" to mapOf(SCH.dRef to 5L)), "properties.a.${SCH.dRef}"),
        Fault("a bound that is no number", mapOf(SCH.type to SCT.integer, SCH.minimum to "low"), "minimum"),
        Fault("a flag as text", mapOf(SCH.type to SCT.integer, SCH.allowCoerce to "yes"), SCH.allowCoerce),
        Fault("a key list that is not one", obj("a" to text) + (SCH.primaryKey to "a"), SCH.primaryKey),
        Fault("a presentation that is none", mapOf(SCH.type to SCT.string, SCH.presentation to "sparkly"), SCH.presentation),
        Fault(
            "a fault inside an item schema",
            obj("a" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to "strng"))), "properties.a.items.type",
        ),
        Fault(
            "a fault inside a union branch",
            mapOf(SCH.type to SCT.kObject, SCH.oneOf to listOf(obj("k" to text), obj("k" to mapOf(SCH.type to 7L)))),
            "oneOf[1].properties.k.type",
        ),
        Fault("a branch that is no schema", mapOf(SCH.type to SCT.kObject, SCH.oneOf to listOf(obj("k" to text), "x")), "oneOf"),
        // What our dialect has no keyword to say: the walk enforces each, and the document lists it instead.
        Fault("additionalProperties as text", obj("a" to text) + (SCH.additionalProperties to "no"), "additionalProperties", unstated = MSCH.eitherOf to SCH.additionalProperties),
        Fault(
            "a fault inside a map's value schema",
            mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to "strng")), "additionalProperties.type", unstated = MSCH.eitherOf to SCH.additionalProperties,
        ),
        Fault("g-derived as a number", mapOf(SCH.type to SCT.string, SCH.derived to 5L), SCH.derived, unstated = MSCH.eitherOf to SCH.derived),
        Fault("a keyword of ours that is not one", mapOf(SCH.type to SCT.string, "g-requird" to true), "g-requird", unstated = MSCH.closedPrefix to null),
        Fault("a refused keyword", obj("a" to mapOf(SCH.type to SCT.string, SCH.enum to listOf("x"))), "properties.a.enum", unstated = MSCH.refusedKeyword to SCH.enum),
        Fault("a directive below the top", obj("a" to mapOf(SCH.extends to "t.Base")), "properties.a.${SCH.extends}", unstated = MSCH.placement to SCH.extends),
    )

    "the document declares every keyword of both tables, and nothing else" {
        node.properties.keys shouldBe (SchStdKeywords.entries.keys + SchGKeywords.entries.keys)
        // An open record: a keyword this layer does not read is the document's own.
        node.additionalProperties shouldBe true
        node.additionalValueType shouldBe null
    }

    "the document is a schema it describes: it passes the walk and validates against itself" {
        val body = SchMetaSchema.defs.getValue(MSCH.nodeTypeName).toJsonMapOrEmpty()
        SchMetaSchema.structureFailures(body).shouldBeEmpty()
        validate(node, body).shouldBeEmpty()
    }

    "the walk reports each fault of shape at the keyword's own path, and the parser refuses it too" {
        for (fault in faults) withClue(fault.what) {
            walkPaths(fault.body) shouldBe listOf(fault.at)
            parserRefuses(fault.body) shouldBe true
        }
    }

    "every fault in a body is reported at once" {
        val body = obj(
            "a" to mapOf(SCH.type to "strng", SCH.allowCoerce to "yes"),
            "b" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.kObject, SCH.required to "x", SCH.enum to listOf(1L))),
            "c" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.minLength to "few")),
        ) + (SCH.title to 5L)
        walkPaths(body) shouldBe listOf(
            "properties.a.type", "properties.a.${SCH.allowCoerce}",
            "properties.b.items.required", "properties.b.items.enum",
            "properties.c.additionalProperties.minLength", "title",
        )
        // Where the parser stops at the first.
        analyzeSchemaTypes(mapOf("t.Fixture" to body)).problems.size shouldBe 1
    }

    "the walk's wording is the tables' own" {
        SchMetaSchema.structureFailures(obj("a" to mapOf(SCH.type to "strng"))).single().message shouldBe
            SchStdKeywords.problem("This schema", SCH.type, "strng")!!.message
        SchMetaSchema.structureFailures(mapOf("g-requird" to true)).single().message shouldContain "check its spelling"
        SchMetaSchema.structureFailures(mapOf(SCH.enum to listOf("a"))).single().message shouldContain "is not supported"
    }

    "what the document can state it refuses at the same path, and what it cannot is listed as not stated" {
        fun keywordsOf(rule: String) = SchMetaSchema.notStated.filter { it[MSCH.rule] == rule }.flatMap { it[MSCH.keywords] as List<*> }
        for (fault in faults) withClue(fault.what) {
            val unstated = fault.unstated
            if (unstated == null) {
                // The document's failure is at the keyword, or -- a wrong entry in a list or a map -- just inside it.
                documentPaths(fault.body).filter { isPathAtOrBelow(it, fault.at) }.shouldNotBeEmpty()
            } else {
                documentPaths(fault.body).shouldBeEmpty()
                // The rule that covers it is listed, and names the keyword -- or, for the closed list, does not hold
                // the misspelling among ours.
                val (rule, keyword) = unstated
                keywordsOf(rule).shouldNotBeEmpty()
                if (keyword != null) keywordsOf(rule) shouldContain keyword else keywordsOf(rule) shouldNotContain fault.at
            }
        }
        // Every kind of rule the document leaves out has a fixture that breaks it, bar the one the walk does not enforce.
        faults.mapNotNull { it.unstated?.first }.toSet() shouldBe
            (SchMetaSchema.notStated.map { it[MSCH.rule] }.toSet() - MSCH.unsetEntry)
        // Each kind of rule is there, generated from the same tables.
        val rules = SchMetaSchema.notStated.groupBy { it[MSCH.rule] }
        rules.getValue(MSCH.closedPrefix).single()[MSCH.keywords] shouldBe SchGKeywords.keywords.sorted()
        rules.getValue(MSCH.refusedKeyword).map { (it[MSCH.keywords] as List<*>).single() } shouldBe refusedKeywords.keys.toList()
        rules.getValue(MSCH.eitherOf).map { (it[MSCH.keywords] as List<*>).single() } shouldBe listOf(SCH.additionalProperties, SCH.derived)
        rules.getValue(MSCH.placement).single()[MSCH.keywords] shouldBe SchGKeywords.directives.sorted()
        rules.getValue(MSCH.unsetEntry).single()[MSCH.keywords] shouldBe listOf(SCH.properties)
    }

    "a property set to null is the one thing the document refuses and the walk does not, and it is listed" {
        // How a client's alteration of a global type removes a property (issue #985). Our dialect cannot say "a
        // schema, or null", so the document says "a schema" and names the difference.
        val removal = obj("a" to text, "gone" to null)
        walkPaths(removal).shouldBeEmpty()
        parserRefuses(removal) shouldBe false
        documentPaths(removal) shouldBe listOf("properties.gone")
    }

    "a body the walk accepts is one the document accepts, and the parser reads" {
        val accepted = listOf(
            obj("a" to mapOf(SCH.type to SCT.integer, SCH.minimum to "5", SCH.maximum to 9L)),
            obj("a" to text) + (SCH.additionalProperties to true),
            mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to SCT.integer)),
            mapOf(SCH.type to SCT.string, SCH.derived to mapOf("from" to "elsewhere")),
            // A keyword this layer does not read stays the document's own -- a stock validator's must still pass.
            mapOf(SCH.type to SCT.string, "examples" to listOf("a"), "\$comment" to "kept", "x-vendor" to mapOf(SCH.type to 5L)),
            mapOf(SCH.type to SCT.array, SCH.items to obj("k" to text), SCH.minItems to 1L),
        )
        for (body in accepted) withClue(body.toString()) {
            walkPaths(body).shouldBeEmpty()
            documentPaths(body).shouldBeEmpty()
            parserRefuses(body) shouldBe false
        }
    }

    "a directive stands at the top of a stored body, held to its shape, and nowhere else" {
        val extension = mapOf(SCH.extends to "t.Base", SCH.properties to mapOf("more" to text))
        SchMetaSchema.structureFailures(extension, directivesStandAtTop = true).shouldBeEmpty()
        SchMetaSchema.structureFailures(mapOf(SCH.merge to mapOf("properties" to "reorder")), directivesStandAtTop = true).shouldBeEmpty()
        // Where a type is judged it is the fault the parser calls it.
        walkPaths(extension) shouldBe listOf(SCH.extends)
        // Standing is not unchecked: its value is still held to its shape, and one below the top is still refused.
        SchMetaSchema.structureFailures(mapOf(SCH.extends to 5L), directivesStandAtTop = true).single().message shouldContain "must be text"
        SchMetaSchema.structureFailures(obj("a" to extension), directivesStandAtTop = true).map { it.path } shouldBe
            listOf("properties.a.${SCH.extends}")
    }

    "failures sit below the path the body is at, and a body nested without end is refused" {
        SchMetaSchema.structureFailures(obj("a" to mapOf(SCH.type to "strng")), at = "schema").single().path shouldBe "schema.properties.a.type"
        var deep: Map<String, Any?> = text
        repeat(MSCH.maxDepth + 1) { deep = mapOf(SCH.type to SCT.array, SCH.items to deep) }
        shouldThrow<KdrException> { SchMetaSchema.structureFailures(deep) }.code shouldBe 400
    }

    "a schema-document field reports its body's faults of shape by path, and then what the parser refuses" {
        val holder = parseSchemaTypes(
            schemaDefs(cxt, "t") {
                type("Holder") {
                    type = SCT.kObject
                    property("schema", "A type's schema body.", required = true) { schemaDocument() }
                }
            },
        ).getValue("t.Holder")
        val twoFaults = mapOf("schema" to obj("a" to mapOf(SCH.type to "strng"), "b" to mapOf(SCH.type to SCT.string, SCH.title to 5L)))
        validate(holder, twoFaults).map { it.path } shouldBe listOf("schema.properties.a.type", "schema.properties.b.title")
        // The same from a caller that leaves the parse to someone else (the config slot gate).
        validate(holder, twoFaults, SchOpts(schemaDocumentsUnparsed = true)).map { it.path } shouldBe
            listOf("schema.properties.a.type", "schema.properties.b.title")
        // No fault of shape: the parse runs as it did, and its one refusal is of the whole value.
        val unknownRef = mapOf("schema" to obj("a" to mapOf(SCH.dRef to "#/\$defs/t.Nowhere")))
        validate(holder, unknownRef).single().let {
            it.path shouldBe "schema"
            it.message shouldContain "not a valid schema definition"
        }
        validate(holder, unknownRef, SchOpts(schemaDocumentsUnparsed = true)).shouldBeEmpty()
        // A stored body's directive stands for the caller that does not parse, and is the parser's fault for one that does.
        val extension = mapOf("schema" to mapOf(SCH.extends to "t.Base"))
        validate(holder, extension, SchOpts(schemaDocumentsUnparsed = true)).shouldBeEmpty()
        validate(holder, extension).single().path shouldBe "schema.${SCH.extends}"
    }

    "a union branch that is not a schema is refused by name, and a stored one is dropped alone" {
        val union = mapOf(
            SCH.type to SCT.kObject,
            SCH.discriminator to mapOf(SCH.propertyName to "kind"),
            SCH.oneOf to listOf(obj("kind" to mapOf(SCH.type to SCT.string, SCH.const to "a")), true),
        )
        shouldThrow<KdrException> { parseSchemaTypes(mapOf("t.Union" to union)) }.message.orEmpty().let {
            it shouldContain "lists true in 'oneOf'; each branch must be a schema object"
            it shouldContain "an empty object accepts anything"
        }
        // What the lenient reading made of it -- the branch skipped -- is what the repair leaves, and says.
        val (repaired, repairs) = repairTypeDef("Type 'client.x.Union'", union, DefRepairContext(emptySet()) { null })
        repairs.single().message shouldContain "lists true in 'oneOf'"
        (repaired[SCH.oneOf] as List<*>).size shouldBe 1
        // And a branch that is one is a schema to the repair, as the table says it is: a fault inside it is found.
        val inBranch = mapOf(SCH.type to SCT.kObject, SCH.oneOf to listOf(mapOf(SCH.type to "strng", SCH.title to "A branch")))
        val (kept, found) = repairTypeDef("Type 'client.x.Union'", inBranch, DefRepairContext(emptySet()) { null })
        found.single().message shouldContain "sets 'type' to 'strng'"
        kept[SCH.oneOf] shouldBe listOf(mapOf(SCH.title to "A branch"))
    }

    "a shape says where the schema nodes in a value are" {
        SchStdKeywords.nodesIn(SCH.items, text) shouldBe listOf("" to text)
        SchStdKeywords.nodesIn(SCH.properties, mapOf("a" to text, "b" to 5L, "c" to null)) shouldBe listOf(".a" to text)
        SchStdKeywords.nodesIn(SCH.oneOf, listOf(text, "x", text)) shouldBe listOf("[0]" to text, "[2]" to text)
        SchStdKeywords.nodesIn(SCH.additionalProperties, text) shouldBe listOf("" to text)
        SchStdKeywords.nodesIn(SCH.additionalProperties, true).shouldBeEmpty()
        // A keyword that holds none, and one this table does not hold.
        SchStdKeywords.nodesIn(SCH.required, listOf("a")).shouldBeEmpty()
        SchStdKeywords.nodesIn(SCH.default, text).shouldBeEmpty()
        SchGKeywords.entries.keys shouldContain SCH.layout
    }
})
