package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.DefRepairContext
import com.dynamicruntime.common.startup.repairTypeDef
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * An object that is a **map** (issue #1055): free keys, each value of a declared shape -- JSON Schema's
 * `additionalProperties` given as a schema. The third way to describe an object, beside the closed record and the
 * open one, and until now the one this layer could not state: a map of author-chosen names was an untyped object.
 */
class SchMapValuesTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("test")

    val defs = schemaDefs(cxt, "m") {
        type("Person") {
            type = SCT.kObject
            property("name", "Their name.", required = true)
            property("age", "Their age.") { type = SCT.integer }
        }
        type("Book") {
            type = SCT.kObject
            property("title", "Its title.", required = true)
            property("labels", "Free labels, each some text.") { mapOfValues { type = SCT.string } }
            property("scores", "A score a reviewer.") { mapOfValues { type = SCT.integer } }
            property("people", "Who worked on it, by role.") { mapOfValues { ref("Person") } }
            property("copy", "Wording, by namespace and then by key.") { mapOfValues { mapOfValues { type = SCT.string } } }
        }
        // A record that is also a map: `kind` is declared, and every other key is a number.
        type("Counts") {
            type = SCT.kObject
            property("kind", "What is counted.", required = true)
            mapOfValues { type = SCT.integer }
        }
        // A type whose map holds itself.
        type("Folder") {
            type = SCT.kObject
            property("note", "A note.")
            property("children", "The folders inside it, by name.") { mapOfValues { ref("Folder") } }
        }
    }
    val types = parseSchemaTypes(defs)
    val book = types.getValue("m.Book")

    fun codes(type: SchType, data: Map<String, Any?>) = validate(type, data).associate { it.path to it.code }

    "the builder writes the standard keyword, and the parser reads a map's value type from it" {
        val labels = defs.getValue("m.Book").toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()["labels"].toJsonMapOrEmpty()
        labels[SCH.type] shouldBe SCT.kObject
        labels[SCH.additionalProperties] shouldBe mapOf(SCH.type to SCT.string)

        val parsed = book.properties.getValue("labels").valueType
        parsed.additionalValueType.shouldNotBeNull().jsonType shouldBe SCT.string
        // A map admits the keys it does not declare: that is what it is for.
        parsed.additionalProperties shouldBe true
        // A `$ref` there is bound to the type it names, a nested map to a map, and a type's own map to itself.
        book.properties.getValue("people").valueType.additionalValueType shouldBe types.getValue("m.Person")
        book.properties.getValue("copy").valueType.additionalValueType.shouldNotBeNull().additionalValueType.shouldNotBeNull().jsonType shouldBe SCT.string
        val folder = types.getValue("m.Folder")
        (folder.properties.getValue("children").valueType.additionalValueType === folder) shouldBe true
        // An object that says nothing of them is as it was: closed with properties, open without.
        types.getValue("m.Person").additionalValueType shouldBe null
        types.getValue("m.Person").additionalProperties shouldBe false
    }

    "each value is validated against the map's value type, and a failure names its key's path" {
        codes(book, mapOf("title" to "T", "labels" to mapOf("genre" to "essay", "shelf" to "B2"))).shouldBeEmpty()
        codes(book, mapOf("title" to "T", "labels" to mapOf("genre" to "essay", "shelf" to 2L))) shouldBe
            mapOf("labels.shelf" to SchFailCode.wrongType)
        // A `$ref`'d value type: the failure is inside the value, under its key.
        codes(book, mapOf("title" to "T", "people" to mapOf("author" to mapOf("name" to "Ann"), "editor" to mapOf("age" to "old")))) shouldBe
            mapOf("people.editor.age" to SchFailCode.badValue, "people.editor.name" to SchFailCode.missingRequired)
        // A map of maps: two keys down.
        codes(book, mapOf("title" to "T", "copy" to mapOf("home" to mapOf("title" to "Welcome", "lede" to 5L), "nav" to "Back"))) shouldBe
            mapOf("copy.home.lede" to SchFailCode.wrongType, "copy.nav" to SchFailCode.wrongType)
        // Every failure is reported, across maps.
        validate(book, mapOf("labels" to mapOf("a" to 1L), "scores" to mapOf("ann" to "many"))).map { it.path }.toSet() shouldBe
            setOf("labels.a", "scores.ann", "title")
        // A type that holds itself in a map validates as deep as the data goes.
        codes(types.getValue("m.Folder"), mapOf("children" to mapOf("a" to mapOf("children" to mapOf("b" to mapOf("note" to 5L)))))) shouldBe
            mapOf("children.a.children.b.note" to SchFailCode.wrongType)
    }

    "values are coerced as any value of their type is, each under its key" {
        val result = coerceAndValidate(book, mapOf("title" to "T", "scores" to mapOf("ann" to "5", "bo" to 4L)))
        result.failures.shouldBeEmpty()
        result.value.toJsonMapOrEmpty()["scores"] shouldBe mapOf("ann" to 5L, "bo" to 4L)
        // An entry is validated as it stands, as an array's element is: an empty text is a value, not an absence.
        coerceAndValidate(book, mapOf("title" to "T", "labels" to mapOf("blank" to ""))).value.toJsonMapOrEmpty()["labels"] shouldBe
            mapOf("blank" to "")
    }

    "a map's keys are data: none is off-contract, and a declared property beside it is validated as itself" {
        // `_` and `$` exempt a key from a record's rules; in a map each is an entry like any other.
        codes(book, mapOf("title" to "T", "scores" to mapOf("_draft" to "no", "\$note" to "x"))) shouldBe
            mapOf("scores._draft" to SchFailCode.badValue, "scores.\$note" to SchFailCode.badValue)
        coerceAndValidate(book, mapOf("title" to "T", "scores" to mapOf("_draft" to "3"))).value.toJsonMapOrEmpty()["scores"] shouldBe
            mapOf("_draft" to 3L)
        // A record that is also a map: it declares a property, and still admits the keys it does not declare.
        val counts = types.getValue("m.Counts")
        counts.additionalProperties shouldBe true
        codes(counts, mapOf("kind" to "visits", "mon" to 3L, "tue" to 4L)).shouldBeEmpty()
        codes(counts, mapOf("mon" to "lots")) shouldBe mapOf("mon" to SchFailCode.badValue, "kind" to SchFailCode.missingRequired)
    }

    "true and false mean what they always did" {
        fun record(additional: Any?) = parseSchemaTypes(
            mapOf(
                "m.R" to buildMap {
                    put(SCH.type, SCT.kObject)
                    put(SCH.properties, mapOf("a" to mapOf(SCH.type to SCT.string)))
                    if (additional != null) put(SCH.additionalProperties, additional)
                },
            ),
        ).getValue("m.R")
        codes(record(null), mapOf("a" to "x", "b" to 1L)) shouldBe mapOf("b" to SchFailCode.additionalProperty)
        codes(record(false), mapOf("a" to "x", "b" to 1L)) shouldBe mapOf("b" to SchFailCode.additionalProperty)
        codes(record(true), mapOf("a" to "x", "b" to 1L, "c" to listOf(1L))).shouldBeEmpty()
        record(true).additionalValueType shouldBe null
        // Off-contract keys are still a record's to exempt.
        codes(record(false), mapOf("a" to "x", "_trace" to 1L, "\$note" to "n")).shouldBeEmpty()
    }

    "a value schema is a schema: its faults are refused where they are, and a reference to nothing is located" {
        val unknown = analyzeSchemaTypes(
            mapOf("m.Bad" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.dRef to "#/\$defs/m.Nowhere"))),
        ).problems.single()
        unknown.code shouldBe SchemaError.unknownRef
        unknown.location.shouldNotBeNull().path shouldBe "m.Bad.additionalProperties"
        val nested = analyzeSchemaTypes(
            mapOf(
                "m.Bad" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("by" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to "strng"))),
                ),
            ),
        ).problems.single()
        nested.code shouldBe SchemaError.badValue
        nested.location.shouldNotBeNull().path shouldBe "m.Bad.properties.by.additionalProperties"
        shouldThrow<KdrException> {
            parseSchemaTypes(mapOf("m.Bad" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to "no")))
        }.message.orEmpty() shouldContain "true, false or a schema object"
        // And the repair of a client's stored definition walks into it as it does into `items`.
        val (repaired, repairs) = repairTypeDef(
            "Type 'client.x.Tally'",
            mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to "strng", SCH.minLength to 2L)),
            DefRepairContext(emptySet()) { null },
        )
        repairs.single().message shouldContain "sets 'type' to 'strng'"
        repaired[SCH.additionalProperties] shouldBe mapOf(SCH.minLength to 2L)
    }

    "the served closure follows a reference in a map's value schema" {
        val closure = collectDefs(listOf(mapOf(SCH.dRef to "#/\$defs/m.Book")), defs)
        closure.keys shouldBe setOf("m.Book", "m.Person")
    }

    // A field's gate is enforced on a write wherever the field is (issue #830), and a form's requirements judged
    // wherever its type is used (issue #1022): inside a map's entries too, or a map would be where both stop.
    val gated = parseSchemaTypes(
        mapOf(
            "g.Line" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "text" to mapOf(SCH.type to SCT.string),
                    "cost" to mapOf(SCH.type to SCT.integer, SCH.visibleWhen to "admin"),
                ),
            ),
            "g.Order" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "title" to mapOf(SCH.type to SCT.string),
                    "lines" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.dRef to "#/\$defs/g.Line")),
                ),
            ),
        ),
    )
    val order = gated.getValue("g.Order")
    val failsAdmin: (String) -> Boolean = { it != "admin" }

    "a gated field inside a map's entry is kept as stored for a caller who cannot see it" {
        val stored = mapOf(
            "title" to "T",
            "lines" to mapOf("a" to mapOf("text" to "one", "cost" to 5L), "b" to mapOf("text" to "two")),
        )
        // Left out by a form that hid it, the stored value stays; a new entry without one is the caller's to add.
        val kept = keepGatedFields(
            order, stored,
            mapOf("title" to "T2", "lines" to mapOf("a" to mapOf("text" to "uno"), "b" to mapOf("text" to "dos"), "c" to mapOf("text" to "tres"))),
            failsAdmin,
        )
        kept.refused.shouldBeEmpty()
        kept.data["lines"] shouldBe mapOf(
            "a" to mapOf("text" to "uno", "cost" to 5L), "b" to mapOf("text" to "dos"), "c" to mapOf("text" to "tres"),
        )
        // Changing it, setting one on a new entry, or dropping the entry that holds one are each a change to a
        // value the caller may not change, named by the entry's key.
        keepGatedFields(
            order, stored,
            mapOf("lines" to mapOf("a" to mapOf("text" to "one", "cost" to 9L), "b" to mapOf("text" to "two", "cost" to 1L))),
            failsAdmin,
        ).refused shouldBe listOf("lines.a.cost", "lines.b.cost")
        keepGatedFields(order, stored, mapOf("title" to "T", "lines" to mapOf("b" to mapOf("text" to "two"))), failsAdmin)
            .refused shouldBe listOf("lines.a")
        keepGatedFields(order, stored, mapOf("title" to "T"), failsAdmin).refused shouldBe listOf("lines")
        // A caller who passes the gate changes what they like.
        keepGatedFields(order, stored, mapOf("lines" to mapOf("a" to mapOf("text" to "one", "cost" to 9L))), { true })
            .refused.shouldBeEmpty()
        holdsGatedValue(order, stored, failsAdmin) shouldBe true
        holdsGatedValue(order, mapOf("lines" to mapOf("b" to mapOf("text" to "two"))), failsAdmin) shouldBe false
    }

    "a form's requirement of a type is judged in every entry of a map of it" {
        val layouts = mapOf("g.Line" to parseSchLayout("Type 'g.Line'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "text", SL.required to true)))))
        val failures = formRequirementFailures(
            order, layouts, mapOf("title" to "T", "lines" to mapOf("a" to mapOf("text" to "one"), "b" to mapOf("cost" to 2L), "c" to mapOf("text" to " "))),
        )
        failures.map { it.path } shouldBe listOf("lines.b.text", "lines.c.text")
        failures.all { it.formRequirement && it.code == SchFailCode.missingRequired } shouldBe true
    }

    "a client may not change a map's value type, as it may not change an array's items" {
        val base = defs.getValue("m.Counts").toJsonMapOrEmpty()
        // Stating it as it is changes nothing.
        narrowingProblems("m.Counts", base, mapOf(SCH.additionalProperties to mapOf(SCH.type to SCT.integer))).shouldBeEmpty()
        // Widening it -- any number, any value at all -- and narrowing it alike are a change to how the type
        // validates, which is not one of the ways a client may narrow: it extends instead.
        for (changed in listOf<Any?>(mapOf(SCH.type to SCT.number), true, mapOf(SCH.type to SCT.integer, SCH.maximum to 9L))) {
            val problem = narrowingProblems("m.Counts", base, mapOf(SCH.additionalProperties to changed)).single()
            problem.code shouldBe NarrowingError.changesKeyword
            problem.message shouldContain "'${SCH.additionalProperties}'"
        }
    }
})
