package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.overlay.OverlayMergeError
import com.dynamicruntime.common.startup.DefRepairContext
import com.dynamicruntime.common.startup.repairTypeDef
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.ProblemCode
import com.dynamicruntime.common.util.ProblemLocation
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

/**
 * How schema types are put together (issue #1738 gathered these): extensions, map-valued objects, discriminated
 * unions, a client's overlay of `$defs`, narrowing, `$ref` resolution in the parser, and the type builder. Each part
 * below is one of the specs that used to be a file of its own, kept whole.
 */
class SchStructureTest : StringSpec({
    include(schExtendsTests)
    include(schMapValuesTests)
    include(schVariantsTests)
    include(schOverlayTests)
    include(schNarrowingTests)
    include(schParserTests)
    include(schTypeBuilderTests)
})

/**
 * Extensions (issue #990): a named type declared as another type plus a delta, resolved before parsing by #985's
 * merger -- properties merged by default, the layout by field, every other key replaced -- and refused where it cannot
 * be resolved. And the `$ref` beside schema keys that used to parse clean and do nothing, now refused at each site.
 */
private val schExtendsTests = stringSpec {
    fun prop(type: String = SCT.string) = mapOf<String, Any?>(SCH.type to type, SCH.description to "A field.")
    val base = mapOf<String, Any?>(
        SCH.type to SCT.kObject,
        SCH.description to "The base.",
        SCH.properties to mapOf("a" to prop(), "b" to prop(), "c" to prop(SCT.integer)),
        SCH.required to listOf("a"),
        SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "a", SL.label to "Alpha"), mapOf(SL.field to "b", SL.label to "Beta"))),
    )
    fun extension(vararg entries: Pair<String, Any?>) = mapOf(SCH.extends to "kdr.B", *entries)
    fun resolve(ext: Map<String, Any?>) = resolveExtensions(mapOf("kdr.B" to base, "client.acme.A" to ext))
    fun props(resolved: ExtensionsResolved) = (resolved.defs.getValue("client.acme.A") as Map<*, *>)[SCH.properties] as Map<*, *>

    "an extension adds, replaces and removes properties, and keeps the rest" {
        val resolved = resolve(
            extension(
                SCH.properties to mapOf<String, Any?>("d" to prop(), "b" to prop(SCT.boolean), "c" to null, "a" to emptyMap<String, Any?>()),
            ),
        )
        resolved.refused shouldBe emptyMap()
        props(resolved).keys shouldBe setOf("a", "b", "d")
        (props(resolved)["b"] as Map<*, *>)[SCH.type] shouldBe SCT.boolean
        (props(resolved)["a"] as Map<*, *>)[SCH.type] shouldBe SCT.string
        // The rest of the base comes through; the directive does not.
        val a = resolved.defs.getValue("client.acme.A") as Map<*, *>
        a[SCH.required] shouldBe listOf("a")
        a.containsKey(SCH.extends) shouldBe false
        // And it compiles as an ordinary named type.
        parseSchemaTypes(resolved.defs).getValue("client.acme.A").properties.keys shouldBe setOf("a", "b", "d")
    }

    "a property the base gains later comes through, since the extension names only what it changes" {
        val grown = base + (SCH.properties to (base[SCH.properties] as Map<*, *>) + ("e" to prop()))
        val resolved = resolveExtensions(mapOf("kdr.B" to grown, "client.acme.A" to extension(SCH.properties to mapOf("d" to prop()))))
        props(resolved).keys shouldBe setOf("a", "b", "c", "e", "d")
    }

    "g-merge restates the set instead, and is not left in the type" {
        val resolved = resolve(
            extension(SCH.merge to mapOf(SCH.properties to MCH.restate), SCH.properties to mapOf<String, Any?>("a" to emptyMap<String, Any?>(), "d" to prop())),
        )
        props(resolved).keys shouldBe setOf("a", "d")
        (resolved.defs.getValue("client.acme.A") as Map<*, *>).containsKey(SCH.merge) shouldBe false
    }

    "the base's layout merges into the extension by field, and an entry for a field the extension removes goes" {
        val resolved = resolve(
            extension(
                SCH.properties to mapOf<String, Any?>("c" to null, "b" to null, "d" to prop()),
                SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "a", SL.label to "First"), mapOf(SL.field to "d", SL.label to "Delta"))),
            ),
        )
        val fields = ((resolved.defs.getValue("client.acme.A") as Map<*, *>)[SCH.layout] as Map<*, *>)[SL.schemaFields] as List<*>
        fields.map { (it as Map<*, *>)[SL.field] to it[SL.label] } shouldBe listOf("a" to "First", "d" to "Delta")
    }

    "an extension that cannot be resolved is left out and named" {
        fun refusal(defs: Map<String, Any?>, name: String = "client.acme.A") = resolveExtensions(defs).let {
            it.defs.containsKey(name) shouldBe false
            it.refused.getValue(name).message
        }
        refusal(mapOf("client.acme.A" to extension())) shouldContain "not a type here"
        refusal(mapOf("kdr.B" to base, "kdr.C" to extension(), "client.acme.A" to mapOf(SCH.extends to "kdr.C"))) shouldContain "itself extends"
        refusal(mapOf("kdr.B" to mapOf(SCH.type to SCT.string), "client.acme.A" to extension())) shouldContain "not an object type"
        refusal(mapOf("kdr.B" to base, "client.acme.A" to extension(SCH.merge to mapOf(SCH.properties to "sideways")))) shouldContain "merge"
        refusal(mapOf("client.acme.A" to mapOf(SCH.extends to 5))) shouldContain "as text"
    }

    "a document with no extension comes back as it was" {
        val defs = mapOf<String, Any?>("kdr.B" to base)
        resolveExtensions(defs).defs shouldBeSameInstanceAs defs
    }

    "g-extends below the top of a named type is refused by the parser" {
        val nested = mapOf<String, Any?>(
            "kdr.B" to base,
            "client.acme.A" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("x" to mapOf(SCH.type to SCT.kObject, SCH.extends to "kdr.B", SCH.description to "Nested.")),
            ),
        )
        val message = shouldThrow<KdrException> { parseSchemaTypes(nested) }.message.orEmpty()
        message shouldContain SCH.extends
        // Not an alteration, so the message does not send its reader to declare a new type: they are declaring one.
        message shouldNotContain "alteration"
        message shouldContain "a named type of its own"
    }

    // --- a `$ref` takes its type whole (issue #990) ---

    val refT = typeRefPath("kdr.T", "kdr")
    val target = mapOf<String, Any?>("kdr.T" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("n" to prop())))
    fun withType(body: Map<String, Any?>) = target + ("kdr.Holder" to body)

    $$"schema keys beside a property's $ref are refused, naming the key; its use-site annotations still parse" {
        val refused = shouldThrow<KdrException> {
            parseSchemaTypes(
                withType(
                    mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("t" to mapOf(SCH.dRef to refT, SCH.properties to emptyMap<String, Any?>()))),
                ),
            )
        }.message.orEmpty()
        refused shouldContain "'${SCH.properties}'"
        refused shouldContain SCH.extends
        val annotated = mapOf(
            SCH.dRef to refT, SCH.description to "The target.", SCH.title to "Target",
            SCH.optionalContents to true, SCH.presentation to PRES.detail, SCH.dComment to "A note.",
        )
        parseSchemaTypes(withType(mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("t" to annotated))))
            .getValue("kdr.Holder").properties.getValue("t").title shouldBe "Target"
    }

    $$"schema keys beside an items $ref, or a union branch's, are refused too" {
        shouldThrow<KdrException> {
            parseSchemaTypes(withType(mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to refT, SCH.maxLength to 3))))
        }.message.orEmpty() shouldContain "'${SCH.maxLength}'"
        val union = mapOf(
            SCH.oneOf to listOf(mapOf(SCH.dRef to refT, SCH.type to SCT.kObject)),
            SCH.discriminator to mapOf(SCH.propertyName to "n"),
        )
        shouldThrow<KdrException> { parseSchemaTypes(withType(union)) }.message.orEmpty() shouldContain "'${SCH.type}'"
    }
}

/**
 * An object that is a **map** (issue #1055): free keys, each value of a declared shape -- JSON Schema's
 * `additionalProperties` given as a schema. The third way to describe an object, beside the closed record and the
 * open one, and until now the one this layer could not state: a map of author-chosen names was an untyped object.
 */
private val schMapValuesTests = stringSpec {
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

    "a fault in a value schema or an item schema is said to be there, not on the type that holds it" {
        // The type itself is well formed -- `type: object` -- so "Type 'm.Bad' sets 'type' to 'strng'" would send
        // its author to the wrong line.
        fun refusal(holder: Map<String, Any?>) = shouldThrow<KdrException> { parseSchemaTypes(mapOf("m.Bad" to holder)) }.message.orEmpty()
        val misspelt = mapOf(SCH.type to "strng")
        refusal(mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to misspelt)) shouldContain
            "Type 'm.Bad' (in its value schema) sets 'type' to 'strng'"
        refusal(mapOf(SCH.type to SCT.array, SCH.items to misspelt)) shouldContain
            "Type 'm.Bad' (in its item schema) sets 'type' to 'strng'"
        refusal(
            mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("by" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to misspelt)),
            ),
        ) shouldContain "Property 'by' (in its value schema) sets 'type' to 'strng'"
        // The repair of a stored definition reports it in the same words.
        fun repair(holder: Map<String, Any?>) =
            repairTypeDef("Type 'client.x.Tally'", holder, DefRepairContext(emptySet()) { null }).second.single().message
        repair(mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to misspelt)) shouldContain
            "Type 'client.x.Tally' (in its value schema) sets 'type' to 'strng'"
        repair(mapOf(SCH.type to SCT.array, SCH.items to misspelt)) shouldContain
            "Type 'client.x.Tally' (in its item schema) sets 'type' to 'strng'"
    }

    "a type is declared a map or given the switch, not both" {
        // Both write `additionalProperties`, so the later used to win in silence: a map quietly closed, or a
        // closed record quietly made a map.
        shouldThrow<KdrException> {
            schemaDefs(cxt, "m") {
                type("Both") {
                    mapOfValues { type = SCT.string }
                    additionalProperties = false
                }
            }
        }.message.orEmpty() shouldContain "not both"
        shouldThrow<KdrException> {
            schemaDefs(cxt, "m") {
                type("Both") {
                    additionalProperties = true
                    mapOfValues { type = SCT.string }
                }
            }
        }.message.orEmpty() shouldContain "not both"
        // Each alone reads back as it was set, and the switch reads as unset on a map.
        schemaDefs(cxt, "m") {
            type("Open") {
                type = SCT.kObject
                additionalProperties = true
                additionalProperties shouldBe true
            }
            type("Map") {
                mapOfValues { type = SCT.string }
                additionalProperties shouldBe null
            }
        }.getValue("m.Open").toJsonMapOrEmpty()[SCH.additionalProperties] shouldBe true
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

    "a gated field inside a map's entry is left out of what a caller who cannot see it is shown" {
        val value = mapOf(
            "title" to "T",
            "lines" to mapOf("a" to mapOf("text" to "one", "cost" to 5L), "b" to mapOf("text" to "two")),
        )
        // The whole object, as one control over it would show it: `cost` is gone from the entry that held it.
        hideGatedFields(order, value, failsAdmin) shouldBe
            mapOf("title" to "T", "lines" to mapOf("a" to mapOf("text" to "one"), "b" to mapOf("text" to "two")))
        // And the map alone, which is what its own control is handed.
        hideGatedFields(order.properties.getValue("lines").valueType, value["lines"], failsAdmin) shouldBe
            mapOf("a" to mapOf("text" to "one"), "b" to mapOf("text" to "two"))
        // What was shown, written back, keeps what was hidden: the round trip the view is for.
        val shown = hideGatedFields(order, value, failsAdmin).toJsonMapOrEmpty()
        val written = keepGatedFields(order, value, shown, failsAdmin)
        written.refused.shouldBeEmpty()
        written.data shouldBe value
        // Nothing hidden is nothing copied: a caller who passes the gate, a value holding no gated field, and
        // text part way through being typed are each handed back as they are.
        (hideGatedFields(order, value, { true }) === value) shouldBe true
        val ungated = mapOf("lines" to mapOf("b" to mapOf("text" to "two")))
        (hideGatedFields(order, ungated, failsAdmin) === ungated) shouldBe true
        hideGatedFields(order, "{ \"lines\": ", failsAdmin) shouldBe "{ \"lines\": "
        // In a list's elements too, and a value nested without end is refused rather than followed.
        val listed = parseSchemaTypes(
            mapOf("g.Batch" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "#/\$defs/g.Line"))),
            existingTypes = gated,
        ).getValue("g.Batch")
        hideGatedFields(listed, listOf(mapOf("text" to "one", "cost" to 5L), mapOf("text" to "two")), failsAdmin) shouldBe
            listOf(mapOf("text" to "one"), mapOf("text" to "two"))
        val folder = types.getValue("m.Folder")
        var deep: Map<String, Any?> = mapOf("note" to "leaf")
        repeat(SGATE.maxDepth) { deep = mapOf("children" to mapOf("in" to deep)) }
        shouldThrow<KdrException> { hideGatedFields(folder, deep, failsAdmin) }.message.orEmpty() shouldContain "nests deeper"
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
}

/**
 * Discriminated unions: `oneOf` with a declared `discriminator` (issue #252).
 *
 * Two things are being pinned down here, and they pull in different directions. One is that selection works —
 * the right branch is chosen, and its failures come back at the payload's own paths. The other is that
 * selection is only ever about *which failures are reported*: every branch carries its own `const`, so a
 * validator that ignores the keyword entirely reaches the same verdict. The second is what keeps the emitted
 * document standard-valid, and it is the one worth guarding, because nothing about a passing selection test
 * would notice if it stopped being true.
 */
private val schVariantsTests = stringSpec {

    val cxt = KdrCxt.mkSimpleCxt("test")

    // An expense report keyed by year, and an approval carrying a free-text note -- two branches whose fields
    // differ, so a failure can be attributed to the wrong one and be seen to have been.
    fun entryDefs(defaultBranch: String? = null): Map<String, Any?> = schemaDefs(cxt, "gedra") {
        variantBranch("ExpenseReportEntry", "traitId", "expenseReport", "An expense report.") {
            property("year", "Reporting year.", required = true) { type = SCT.integer; minimum = 2000 }
            property("totalAmount", "Total claimed.") { type = SCT.number }
        }
        variantBranch("ApprovalEntry", "traitId", "managerApproval", "An approval.") {
            property("approved", "Whether it was approved.", required = true) { type = SCT.boolean }
        }
        variantDefault("OpaqueEntry", "traitId", "An entry this reader does not know.")
        variantType(
            "GedraEntry", "One stored entry.", on = "traitId",
            branches = listOf("ExpenseReportEntry", "ApprovalEntry"),
            defaultBranch = defaultBranch,
        )
    }

    fun entryType(defaultBranch: String? = null): SchType =
        parseSchemaTypes(entryDefs(defaultBranch)).getValue("gedra.GedraEntry")

    "a payload validates against the branch its discriminator names" {
        val result = coerceAndValidate(
            entryType(),
            mapOf("traitId" to "expenseReport", "year" to 2024, "totalAmount" to 4820.15),
        )
        result.failures.shouldBeEmpty()
    }

    // The point of declaring a discriminator at all: the failure is the selected branch's own, at the path the
    // payload actually has. Try-every-branch would also have to report that `approved` is missing, from a
    // shape the caller never meant to send.
    "a failure is reported against the selected branch alone" {
        val failures = validate(entryType(), mapOf("traitId" to "expenseReport", "year" to 1999))
        failures shouldHaveSize 1
        failures.first().path shouldBe "year"
        failures.first().code shouldBe SchFailCode.belowMinimum
    }

    "each branch enforces its own required fields" {
        val failures = validate(entryType(), mapOf("traitId" to "managerApproval"))
        failures shouldHaveSize 1
        failures.first().path shouldBe "approved"
        failures.first().code shouldBe SchFailCode.missingRequired
    }

    // Reported against the discriminator's own path rather than the union's, so the message sits on the field
    // someone has to fill in.
    "a missing discriminator is reported on the discriminator" {
        val failures = validate(entryType(), mapOf("year" to 2024))
        failures shouldHaveSize 1
        failures.first().path shouldBe "traitId"
        failures.first().code shouldBe SchFailCode.missingRequired
    }

    "an unrecognized discriminator names the values this reader knows" {
        val failures = validate(entryType(), mapOf("traitId" to "somethingElse"))
        failures shouldHaveSize 1
        val failure = failures.first()
        failure.path shouldBe "traitId"
        failure.code shouldBe SchFailCode.invalidOption
        failure.options.shouldNotBeNull().map { it.value } shouldContainExactly
            listOf("expenseReport", "managerApproval")
    }

    // With a defaultMapping an unknown trait is an ordinary event rather than a defect: definitions are
    // authored at runtime, so a reader meeting one it has never heard of has to stay readable.
    "an unrecognized discriminator falls to the default branch when there is one" {
        validate(entryType(defaultBranch = "OpaqueEntry"), mapOf("traitId" to "somethingElse")).shouldBeEmpty()
    }

    // The trap `variantDefault` exists to remove: a catch-all carrying a `const` rejects the very payload it
    // was added to keep readable. Pinned because the failure reads as nonsense -- "'somethingElse' is not
    // 'opaque'" -- rather than as "your default branch is wrong".
    "a default branch declared with a const would reject the unknown value it exists for" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("Known", "traitId", "known")
            variantBranch("Fallback", "traitId", "fallback") // wrong helper on purpose
            variantType(
                "Entry", "One entry.", on = "traitId",
                branches = listOf("Known"), defaultBranch = "Fallback",
            )
        }
        val type = parseSchemaTypes(defs).getValue("gedra.Entry")
        validate(type, mapOf("traitId" to "unheardOf")) shouldHaveSize 1
        // Declared the right way, the same payload passes.
        val fixed = schemaDefs(cxt, "gedra") {
            variantBranch("Known", "traitId", "known")
            variantDefault("Fallback", "traitId")
            variantType(
                "Entry", "One entry.", on = "traitId",
                branches = listOf("Known"), defaultBranch = "Fallback",
            )
        }
        validate(parseSchemaTypes(fixed).getValue("gedra.Entry"), mapOf("traitId" to "unheardOf")).shouldBeEmpty()
    }

    "a union rejects a value that is not an object at all" {
        val failures = validate(entryType(), "expenseReport")
        failures shouldHaveSize 1
        failures.first().code shouldBe SchFailCode.wrongType
    }

    // The property the whole design rests on. Selection decides which failures are reported, never whether a
    // payload is valid -- so validating the branch DIRECTLY, with no discriminator involved, has to reach the
    // same answer. If this ever stops holding, the emitted document means something different to a stock
    // validator than it does to us, and nothing else here would notice.
    "a branch alone reaches the same verdict as the union, because it carries its own const" {
        val types = parseSchemaTypes(entryDefs())
        val branch = types.getValue("gedra.ExpenseReportEntry")
        val union = types.getValue("gedra.GedraEntry")
        val wrongBranch = mapOf("traitId" to "managerApproval", "approved" to true)

        // Through the union: selected as an approval and accepted.
        validate(union, wrongBranch).shouldBeEmpty()
        // Against the expense branch alone: rejected, by the const rather than by anything of ours.
        val direct = validate(branch, wrongBranch)
        direct.map { it.path } shouldContainExactly listOf("traitId", "approved", "year")
        direct.first().code shouldBe SchFailCode.invalidOption
    }

    // The shape an endpoint actually presents: input is flat, so a union arrives as one PROPERTY of an
    // envelope rather than as the whole payload. Reproduced here after the form reported the property missing
    // for a payload the server accepted.
    "a union reached through a property validates, and survives coercion" {
        val defs = entryDefs("OpaqueEntry").toMutableMap()
        defs["gedra.Envelope"] = mapOf(
            SCH.type to SCT.kObject,
            SCH.required to listOf("entry"),
            SCH.properties to mapOf("entry" to mapOf(SCH.dRef to $$"#/$defs/gedra.GedraEntry")),
        )
        val envelope = parseSchemaTypes(defs).getValue("gedra.Envelope")
        val payload = mapOf("entry" to mapOf("traitId" to "expenseReport", "year" to 2024))

        validate(envelope, payload).shouldBeEmpty()
        val coerced = coerceAndValidate(envelope, payload)
        coerced.failures.shouldBeEmpty()
        // And the property is still there afterward -- a union coerced into nothing reads as "you did not
        // supply it", which is what the form reported.
        (coerced.value as Map<*, *>).keys shouldContainExactly listOf("entry")
    }

    "a union is usable as an array element type" {
        val defs = entryDefs().toMutableMap()
        defs["gedra.Gedra"] = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "entries" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to $$"#/$defs/gedra.GedraEntry")),
            ),
        )
        val gedra = parseSchemaTypes(defs).getValue("gedra.Gedra")
        val failures = validate(
            gedra,
            mapOf(
                "entries" to listOf(
                    mapOf("traitId" to "expenseReport", "year" to 2024),
                    mapOf("traitId" to "managerApproval", "approved" to true),
                    mapOf("traitId" to "expenseReport", "year" to 1999),
                ),
            ),
        )
        // Only the third element is wrong, and the path says which one -- mixed traits in one array validate
        // element-wise against different branches.
        failures shouldHaveSize 1
        failures.first().path shouldBe "entries[2].year"
    }

    // --- boot checks ---------------------------------------------------------

    "a branch with no const for the discriminator is refused, and named" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("Good", "traitId", "good")
            type("Bad") {
                type = SCT.kObject
                property("traitId", "Which kind.", required = true) { type = SCT.string } // no const
            }
            variantType("Entry", "One entry.", on = "traitId", branches = listOf("Good", "Bad"))
        }
        val e = shouldThrow<KdrException> { parseSchemaTypes(defs) }
        e.message.shouldNotBeNull() shouldContain "Branch 2"
        e.message.shouldNotBeNull() shouldContain "gedra.Entry"
    }

    "a branch that does not declare the discriminator at all is refused" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("Good", "traitId", "good")
            type("Bad") { type = SCT.kObject; property("other", "Something else.") }
            variantType("Entry", "One entry.", on = "traitId", branches = listOf("Good", "Bad"))
        }
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "declares no 'traitId' property"
    }

    "two branches claiming the same value are refused" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("One", "traitId", "same")
            variantBranch("Two", "traitId", "same")
            variantType("Entry", "One entry.", on = "traitId", branches = listOf("One", "Two"))
        }
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "repeats"
    }

    // Deliberate new strictness: before this issue such a document parsed and enforced nothing at all.
    // The builders only offer a string discriminator, but an imported document is not obliged to agree, and a
    // code-numbered union is an ordinary thing to meet. Both halves read the value through `toOptStr` -- the
    // parser indexing the branches, the validator selecting one -- so before issue #267 both halves saw null:
    // the union would not even load, failing at boot with "has no const". Written as raw schema because that
    // is the only way such a document arrives.
    "a numerically keyed discriminator loads and selects" {
        fun branch(constValue: Int, field: String) = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.integer, SCH.const to constValue),
                field to mapOf(SCH.type to SCT.string),
            ),
            SCH.required to listOf(field),
        )
        val types = parseSchemaTypes(
            mapOf(
                "gedra.First" to branch(1, "a"),
                "gedra.Second" to branch(2, "b"),
                "gedra.Keyed" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.oneOf to listOf(
                        mapOf(SCH.dRef to $$"#/$defs/gedra.First"),
                        mapOf(SCH.dRef to $$"#/$defs/gedra.Second"),
                    ),
                    SCH.discriminator to mapOf(SCH.propertyName to "kind"),
                ),
            ),
        )
        val keyed = types["gedra.Keyed"].shouldNotBeNull()
        keyed.variants.shouldNotBeNull().values shouldContainExactly listOf("1", "2")

        // Selection works from the number the wire actually carries...
        validate(keyed, mapOf("kind" to 2, "b" to "ok")).shouldBeEmpty()
        // ...and picks the branch that value names, not merely some branch. Sending the *other* branch's field
        // draws both complaints the selected one has: it has never heard of `a`, and it wanted `b`.
        validate(keyed, mapOf("kind" to 2, "a" to "ok")).map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("a" to SchFailCode.additionalProperty, "b" to SchFailCode.missingRequired)
        // The string form of the same number answers the same question, which is what a query string sends.
        validate(keyed, mapOf("kind" to "1", "a" to "ok")).shouldBeEmpty()
    }

    "a oneOf without a discriminator is refused rather than silently ignored" {
        val defs = mapOf(
            "gedra.Entry" to mapOf(
                SCH.oneOf to listOf(mapOf(SCH.type to SCT.kObject)),
            ),
        )
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "has no 'discriminator'"
    }

    "branch order follows the document, so a boot-check message points at the right one" {
        val types = parseSchemaTypes(entryDefs())
        val variants = types.getValue("gedra.GedraEntry").variants.shouldNotBeNull()
        variants.values shouldContainExactly listOf("expenseReport", "managerApproval")
    }
}

/**
 * Overlaying a client's definitions onto the global `$defs` document (issue #356).
 *
 * The two properties worth the most care are the ones the rest of the variant machinery rests on: that a
 * `$ref` to an altered type resolves to the **altered** form once re-parsed -- the case namespacing cannot
 * fake -- and that nothing is ever written into the global document, since every client shares it.
 */
private val schOverlayTests = stringSpec {

    fun defs(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)

    /** Walks [path] down through nested schema maps -- the assertions here are all several levels in. */
    fun Map<String, Any?>.node(vararg path: String): Map<String, Any?> =
        path.fold(this) { m, key -> m[key].toJsonMapOrEmpty() }

    /** A closed object type with one string property carrying [maxLength]. */
    fun nameType(maxLength: Int, description: String = "What to call it."): Map<String, Any?> = linkedMapOf(
        SCH.type to SCT.kObject,
        SCH.properties to linkedMapOf(
            "name" to linkedMapOf(
                SCH.type to SCT.string,
                SCH.description to description,
                SCH.maxLength to maxLength,
            ),
        ),
        SCH.required to listOf("name"),
    )

    "a client that overlays nothing gets the very same document back" {
        val global = defs("core.Name" to nameType(128))
        // Identity, not equality: it is what lets a caller skip building a variant at all.
        overlayDefs(global, emptyMap()) shouldBeSameInstanceAs global
    }

    // Rule one: an overlay that says nothing about the properties is a presentation-only variant.
    "an overlay that does not mention properties leaves them untouched" {
        val global = defs("core.Name" to nameType(128))
        val out = overlayDefs(global, defs("core.Name" to mapOf(SCH.description to "Ours.")))
        out.node("core.Name")[SCH.description] shouldBe "Ours."
        out.node("core.Name", SCH.properties, "name")[SCH.maxLength] shouldBe 128
        out.node("core.Name")[SCH.required] shouldBe listOf("name")
    }

    // Rule two: an empty body means "as it already is" -- which is how a client keeps a property while
    // reducing the set around it, the common case since most alterations change one property and keep the rest.
    "a property with an empty body keeps the global definition" {
        val global = defs("core.Name" to nameType(128))
        val out = overlayDefs(global, defs("core.Name" to mapOf(SCH.properties to mapOf("name" to emptyMap<String, Any?>()))))
        val name = out.node("core.Name", SCH.properties, "name")
        name[SCH.maxLength] shouldBe 128
        name[SCH.description] shouldBe "What to call it."
    }

    // Rule three, and the one that shapes how the schema gets authored: once an overlay defines a property, that
    // definition wins entire. Nothing below it merges -- so an interior structure worth narrowing is pulled
    // out as a named type and altered in its own right, since there is no way to address part of one here.
    "a property the overlay defines is replaced entirely, not merged" {
        val global = defs("core.Name" to nameType(128))
        val out = overlayDefs(
            global,
            defs("core.Name" to mapOf(SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string, SCH.maxLength to 40)))),
        )
        val name = out.node("core.Name", SCH.properties, "name")
        name[SCH.maxLength] shouldBe 40
        // Gone, because the client's definition replaced the global one rather than being folded into it.
        name[SCH.description] shouldBe null
    }

    "mentioning some properties drops the rest" {
        val global = defs(
            "core.Pair" to linkedMapOf(
                SCH.type to SCT.kObject,
                SCH.properties to linkedMapOf(
                    "kept" to linkedMapOf(SCH.type to SCT.string),
                    "dropped" to linkedMapOf(SCH.type to SCT.string),
                ),
            ),
        )
        val out = overlayDefs(global, defs("core.Pair" to mapOf(SCH.properties to mapOf("kept" to emptyMap<String, Any?>()))))
        out.node("core.Pair", SCH.properties).keys shouldContainExactly setOf("kept")
    }

    // The rule doing the most work: `required`, `options` and `oneOf` are complete statements, and shortening
    // one is how a client narrows. Element-wise merging would make that inexpressible.
    "a list replaces rather than merging" {
        val global = defs("core.Choice" to mapOf(SCH.options to listOf("a", "b", "c")))
        val out = overlayDefs(global, defs("core.Choice" to mapOf(SCH.options to listOf("a", "b"))))
        out.node("core.Choice")[SCH.options] shouldBe listOf("a", "b")
    }

    "a type the client never mentions is the global one, by reference" {
        val global = defs("core.Name" to nameType(128), "core.Other" to nameType(64))
        val out = overlayDefs(global, defs("core.Name" to mapOf(SCH.description to "Theirs.")))
        out.getValue("core.Other") shouldBeSameInstanceAs global.getValue("core.Other")
    }

    "a name the base does not have is added whole" {
        val global = defs("core.Name" to nameType(128))
        val out = overlayDefs(global, defs("acme.Extra" to nameType(20)))
        out.keys shouldContainExactly setOf("core.Name", "acme.Extra")
    }

    // `docs/design/client-definition.md`: a variant may create new nodes and point at old ones; it must not write into
    // old ones. Asserted rather than trusted, because every client shares this document and a write here would corrupt
    // all of them at once -- silently, and at boot.
    "the global document is untouched, at every level the overlay reached" {
        val global = defs("core.Name" to nameType(128))
        val before = global.node("core.Name", SCH.properties, "name")
        val out = overlayDefs(
            global,
            defs(
                "core.Name" to mapOf(
                    SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string, SCH.maxLength to 40)),
                ),
            ),
        )
        before[SCH.maxLength] shouldBe 128
        // A fresh map all the way down the path that was merged, so a later write cannot reach the original.
        out.getValue("core.Name") shouldNotBeSameInstanceAs global.getValue("core.Name")
        out.node("core.Name")[SCH.properties] shouldNotBeSameInstanceAs global.node("core.Name")[SCH.properties]
    }

    // The case that decides the whole design: the ref names the original, and for this client it has to
    // resolve to the narrowed form. Nothing in the referring type is edited -- it does not know.
    "a ref to an altered type resolves to the altered form once re-parsed" {
        // The client states the property whole, per rule three.
        val global = defs(
            "core.Name" to nameType(128),
            "core.Doc" to linkedMapOf(
                SCH.type to SCT.kObject,
                SCH.properties to linkedMapOf(
                    "title" to linkedMapOf(SCH.dRef to $$"#/$defs/core.Name"),
                ),
            ),
        )
        val globalTypes = parseSchemaTypes(global)
        globalTypes.getValue("core.Doc").properties.getValue("title").valueType
            .properties.getValue("name").valueType.maxBound shouldBe 128.0

        val variant = parseSchemaTypes(
            overlayDefs(
                global,
                defs(
                    "core.Name" to mapOf(
                        SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string, SCH.maxLength to 40)),
                    ),
                ),
            ),
        )
        variant.getValue("core.Doc").properties.getValue("title").valueType
            .properties.getValue("name").valueType.maxBound shouldBe 40.0

        // And the global parse is unaffected, which is the half a shared document makes easy to break.
        globalTypes.getValue("core.Doc").properties.getValue("title").valueType
            .properties.getValue("name").valueType.maxBound shouldBe 128.0
    }

    // Order is meaning here, not presentation: `properties` order is the order a form shows its fields in.
    "a client's property order is the client's" {
        val global = defs(
            "core.Three" to linkedMapOf(
                SCH.type to SCT.kObject,
                SCH.properties to linkedMapOf(
                    "first" to linkedMapOf(SCH.type to SCT.string),
                    "second" to linkedMapOf(SCH.type to SCT.string),
                    "third" to linkedMapOf(SCH.type to SCT.string),
                ),
            ),
        )
        // Untouched, the global order stands.
        overlayDefs(global, emptyMap()).node("core.Three", SCH.properties).keys
            .toList() shouldBe listOf("first", "second", "third")

        // Reordered by the client, and reordering is a thing they may do while narrowing.
        val out = overlayDefs(
            global,
            defs(
                "core.Three" to mapOf(
                    SCH.properties to linkedMapOf(
                        "third" to emptyMap<String, Any?>(),
                        "first" to emptyMap(),
                    ),
                ),
            ),
        )
        out.node("core.Three", SCH.properties).keys.toList() shouldBe listOf("third", "first")
    }
}

/**
 * What a client's alteration of an existing type may do (issue #356).
 *
 * An altered type keeps its name, so every `$ref` resolves to the client's version -- which is what makes the
 * rule load-bearing rather than tidy. The permitted set is deliberately closed at three, so most of this file
 * is refusals; each is only reachable here, since a boot that refuses never serves a request.
 */
private val schNarrowingTests = stringSpec {

    /** A closed object: two properties, one of them required, one offering a choice list. */
    val base: Map<String, Any?> = linkedMapOf(
        SCH.type to SCT.kObject,
        SCH.properties to linkedMapOf(
            "name" to linkedMapOf(SCH.type to SCT.string, SCH.maxLength to 128),
            "colour" to linkedMapOf(
                SCH.type to SCT.string,
                SCH.options to listOf("red", "green", "blue"),
            ),
            "note" to linkedMapOf(SCH.type to SCT.string),
        ),
        SCH.required to listOf("name"),
    )

    fun problems(overlay: Map<String, Any?>): List<String> =
        narrowingProblems("core.Thing", base, overlay).map { it.message }

    /**
     * An overlay's property block, with the keys this client keeps.
     *
     * Two of Sam's rules meet here, so the helper carries both. Every property to be kept has to be **named**,
     * because mentioning keys is how a client reduces the set; and a property whose body is given is
     * **replaced entire**, so an alteration states the whole body rather than the part that changed. Kept
     * properties are `{}`, which inherits.
     */
    fun props(vararg altered: Pair<String, Map<String, Any?>>): Map<String, Any?> {
        val kept = linkedMapOf<String, Any?>(
            "name" to emptyMap<String, Any?>(),
            "colour" to emptyMap<String, Any?>(),
            "note" to emptyMap<String, Any?>(),
        )
        altered.forEach { (name, body) -> kept[name] = body }
        return mapOf(SCH.properties to kept)
    }

    // --- the three ways to narrow ----------------------------------------------

    "mentioning fewer properties is narrowing" {
        problems(mapOf(SCH.properties to mapOf("name" to emptyMap<String, Any?>()))).shouldBeEmpty()
    }

    "shortening a choice list is narrowing" {
        problems(
            props("colour" to mapOf(SCH.type to SCT.string, SCH.options to listOf("red", "green"))),
        ).shouldBeEmpty()
    }

    // The second half of that rule: an attribute that offered no choices may be given some.
    "applying a choice list where there was none is narrowing" {
        problems(
            props("note" to mapOf(SCH.type to SCT.string, SCH.options to listOf("a", "b"))),
        ).shouldBeEmpty()
    }

    "requiring more is narrowing" {
        problems(mapOf(SCH.required to listOf("name", "colour"))).shouldBeEmpty()
    }

    // --- what a client may change freely ---------------------------------------

    "presentation is the client's own business" {
        problems(
            props(
                "name" to mapOf(
                    SCH.type to SCT.string,
                    SCH.maxLength to 128,
                    SCH.description to "What we call it.",
                    SCH.errors to mapOf("default" to "Nope."),
                ),
            ) + (SCH.description to "Ours."),
        ).shouldBeEmpty()
    }

    // Only the value takes part in validation, which is what lets a client relabel every choice while
    // narrowing none of them.
    "relabelling a choice is presentation, not widening" {
        problems(
            props(
                "colour" to mapOf(
                    SCH.type to SCT.string,
                    SCH.options to listOf(
                        mapOf(SCH.value to "red", SCH.label to "Crimson"),
                        mapOf(SCH.value to "green", SCH.label to "Emerald"),
                    ),
                ),
            ),
        ).shouldBeEmpty()
    }

    // Long text is how a form draws a plain string, not what it accepts (issue #1550).
    "marking a plain string long text is presentation, not widening" {
        problems(props("note" to mapOf(SCH.type to SCT.string, SCH.longText to true))).shouldBeEmpty()
    }

    // --- what is refused --------------------------------------------------------

    "adding a property is refused, with the alternative named" {
        val found = problems(mapOf(SCH.properties to mapOf("name" to emptyMap(), "extra" to emptyMap<String, Any?>())))
        found.size shouldBe 1
        found[0] shouldContain "'extra'"
        found[0] shouldContain "extending the type"
    }

    "adding a choice is refused" {
        val found = problems(
            props("colour" to mapOf(SCH.type to SCT.string, SCH.options to listOf("red", "purple"))),
        )
        found.size shouldBe 1
        found[0] shouldContain "'purple'"
    }

    "dropping a requirement is refused" {
        val found = problems(mapOf(SCH.required to emptyList<String>()))
        found.size shouldBe 1
        found[0] shouldContain "no longer requires 'name'"
    }

    // The case that reads as narrowing and is not: removing a property rejects data that carries it, and
    // accepts data that omits it -- which the global type rejected. What this client stored would then be
    // invalid to everybody else.
    "dropping a property the type requires is refused, because it widens" {
        val found = problems(mapOf(SCH.properties to mapOf("colour" to emptyMap<String, Any?>())))
        // Two problems, and both are the same mistake seen from each side: the property is gone, and the
        // `required` it left behind now names something the type does not have.
        found.size shouldBe 2
        found[0] shouldContain "drops 'name'"
        found[0] shouldContain "invalid to everybody else"
        found[1] shouldContain "requires 'name'"
    }

    "any other validating keyword is refused" {
        problems(mapOf(SCH.additionalProperties to true)).size shouldBe 1
        problems(props("name" to mapOf(SCH.type to SCT.integer, SCH.maxLength to 128))).size shouldBe 1
        problems(mapOf(SCH.default to "x")).size shouldBe 1
    }

    // Even a narrowing one, because the list is closed on purpose: a refusal is cheap to relax and a
    // wrongly-permitted widening reaches storage.
    "a tighter bound is refused too, and says what to do instead" {
        val found = problems(props("name" to mapOf(SCH.type to SCT.string, SCH.maxLength to 40)))
        found.size shouldBe 1
        found[0] shouldContain "'${SCH.maxLength}'"
        found[0] shouldContain "Extend the type instead"
    }

    "an array's elements narrow by the same rules: fewer choices, but never more (issue #1119)" {
        val tags = linkedMapOf(
            SCH.type to SCT.array,
            SCH.items to linkedMapOf(SCH.type to SCT.string, SCH.options to listOf("x", "y", "z")),
        )
        val withTags = base + (SCH.properties to (base.getValue(SCH.properties) as Map<*, *>) + ("tags" to tags))
        fun tagProblems(items: Map<String, Any?>) = narrowingProblems(
            "core.Thing", withTags,
            mapOf(SCH.merge to mapOf(SCH.properties to "merge"), SCH.properties to mapOf("tags" to (tags + (SCH.items to items)))),
        ).map { it.message }
        tagProblems(linkedMapOf(SCH.type to SCT.string, SCH.options to listOf("x", "z"))) shouldBe emptyList()
        tagProblems(linkedMapOf(SCH.type to SCT.string, SCH.options to listOf("x", "w"))).single() shouldContain "'w'"
        // Anything else about an element still counts as a change.
        tagProblems(linkedMapOf(SCH.type to SCT.integer, SCH.options to listOf("x"))).single() shouldContain "'${SCH.type}'"
    }

    "problems are reported together rather than one per boot" {
        problems(
            mapOf(
                SCH.required to emptyList<String>(),
                SCH.properties to mapOf(
                    "name" to mapOf(SCH.type to SCT.string, SCH.maxLength to 40),
                    "extra" to emptyMap<String, Any?>(),
                ),
            ),
        ).size shouldBe 3
    }

    // A key tells the stored values of a type apart (issue #1521), so an alteration may repeat it but nothing else.
    "an alteration may restate a type's key, but not change, add, or remove it" {
        val keyed = base + (SCH.primaryKey to listOf("name"))
        fun keyProblems(over: Map<String, Any?>) = narrowingProblems("core.Thing", keyed, over).map { it.message }
        keyProblems(mapOf(SCH.primaryKey to listOf("name"))).shouldBeEmpty()
        keyProblems(mapOf(SCH.primaryKey to listOf("name", "colour"))).single() shouldContain "may restate it but not change"
        keyProblems(mapOf(SCH.primaryKey to null)).single() shouldContain "'g-primaryKey'"
        problems(mapOf(SCH.primaryKey to listOf("name"))).single() shouldContain "may restate it but not change"
    }

    // Only an alteration that mentions `g-onWrite` can be refused for it: leaving it out keeps the type's functions.
    "an alteration may add to a type's functions, and one removing them is refused" {
        val withFunctions = base + (SCH.onWrite to listOf(mapOf("fn" to "computeValue", "target" to "note", "script" to "name")))
        fun fnProblems(over: Map<String, Any?>) = narrowingProblems("core.Thing", withFunctions, over)
        fnProblems(emptyMap()).shouldBeEmpty()
        // Added functions follow the type's own, which the merge rule allows; this used to be refused as a widening.
        fnProblems(mapOf(SCH.onWrite to listOf(mapOf("fn" to "computeValue", "target" to "colour", "script" to "name")))).shouldBeEmpty()
        val removed = fnProblems(mapOf(SCH.onWrite to null)).single()
        removed.code shouldBe OverlayMergeError.appendOnly
        removed.message shouldContain "Type 'core.Thing', at 'core.Thing.g-onWrite'"
    }

    "a merge problem in the layout is the layout's own; any other fails the alteration" {
        fun at(path: String, code: ProblemCode = OverlayMergeError.unmatchedElement) = Problem(code, "x", ProblemLocation(path))
        isLayoutProblem("core.Thing", at("core.Thing.g-layout.schemaFields[<notes>]")) shouldBe true
        isLayoutProblem("core.Thing", at("core.Thing.g-layout")) shouldBe true
        isLayoutProblem("core.Thing", at("core.Thing.g-onWrite", OverlayMergeError.appendOnly)) shouldBe false
        isLayoutProblem("core.Thing", at("core.Thing.g-layoutish")) shouldBe false
        // A directive the type does not offer means something its author did not say, wherever it is.
        isLayoutProblem("core.Thing", at("core.Thing.g-layout.g-merge", OverlayMergeError.unknownChoice)) shouldBe false
    }
}

/**
 * Covers reference resolution in [parseSchemaTypes] — in particular a `$ref` sitting directly on an array's
 * `items`, which is bound in the resolution pass (like a property `$ref`) rather than expanded during parsing.
 * This is the shape every list endpoint's output uses (`items { ref(...) }`), so without it array elements are
 * never validated.
 */
private val schParserTests = stringSpec {

    val cxt = KdrCxt.mkSimpleCxt("test")

    // A Holder with an array of Bar refs, plus a self-referential Node (its `children` array refs Node).
    fun types(): Map<String, SchType> = parseSchemaTypes(
        schemaDefs(cxt, "core") {
            type("Bar") {
                type = SCT.kObject
                property("n", "A number", required = true) { type = SCT.integer }
            }
            type("Holder") {
                type = SCT.kObject
                property("bars", "A list of bars") {
                    type = SCT.array
                    items { ref("Bar") }
                }
            }
            type("Node") {
                type = SCT.kObject
                property("value", "The value", required = true) { type = SCT.string }
                property("children", "Child nodes") {
                    type = SCT.array
                    items { ref("Node") } // self-reference via items
                }
            }
        },
    )

    "an array's items \$ref is resolved to the element type" {
        val holder = types()["core.Holder"].shouldNotBeNull()
        val bars = holder.properties["bars"].shouldNotBeNull().valueType
        bars.jsonType shouldBe SCT.array
        val bar = bars.itemType.shouldNotBeNull()
        bar.name shouldBe "core.Bar"
        bar.jsonType shouldBe SCT.kObject
        bar.required shouldBe setOf("n")
    }

    "array element contents are validated against the resolved element type" {
        val holder = types()["core.Holder"].shouldNotBeNull()
        // A well-formed element passes.
        validate(holder, mapOf("bars" to listOf(mapOf("n" to 1)))).shouldBeEmpty()
        // A second element missing the required field fails, at the indexed + nested path.
        validate(holder, mapOf("bars" to listOf(mapOf("n" to 1), emptyMap<String, Any?>())))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("bars[1].n" to SchFailCode.missingRequired)
    }

    "coercion reaches into array elements" {
        val holder = types()["core.Holder"].shouldNotBeNull()
        val result = coerceAndValidate(holder, mapOf("bars" to listOf(mapOf("n" to "5"))))
        result.failures.shouldBeEmpty()
        val bars = result.value.shouldNotBeNull().toJsonMap()["bars"] as List<*>
        (bars[0] as Map<*, *>)["n"] shouldBe 5L // the string "5" coerced to the integer element type
    }

    "a self-referential array (items refs its own type) resolves without expanding" {
        val node = types()["core.Node"].shouldNotBeNull()
        val children = node.properties["children"].shouldNotBeNull().valueType
        // The element type is bound back to the very same Node instance -- a safe object-graph cycle.
        children.itemType shouldBe node
        // Validation still descends into the recursive elements.
        validate(
            node,
            mapOf("value" to "root", "children" to listOf(mapOf("value" to "kid", "children" to emptyList<Any?>()))),
        ).shouldBeEmpty()
        validate(node, mapOf("value" to "root", "children" to listOf(emptyMap<String, Any?>())))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("children[0].value" to SchFailCode.missingRequired)
    }

    $$"an array items $ref to an unknown type throws KdrException" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "core") {
                    type("Holder") {
                        type = SCT.kObject
                        property("xs", "Items") {
                            type = SCT.array
                            items { ref("Nope") }
                        }
                    }
                },
            )
        }
    }
}

/** Formerly `SchTypeBuilderTest`. */
private val schTypeBuilderTests = stringSpec {

    $$"namespaced $defs, refs, mandatory field descriptions, default string, reusable props" {
        val cxt = KdrCxt.mkSimpleCxt("test")

        // A reusable property declared once; type defaults to string.
        val nameProp = schemaProperty(cxt, "core", "name", "A name")

        val defs = schemaDefs(cxt, "core") {
            type("Count") {
                type = SCT.integer
                description = "A counting integer" // type description stays optional
            }
            type("Person") {
                type = SCT.kObject
                property(nameProp, required = true)                     // reuse as-is
                property("age", "Age in years") { type = SCT.integer }  // explicit type
                property("nickname", "Informal name")                   // defaults to string
                property("count", "How many things") { ref("Count") }   // $ref, no default type
            }
            type("Company") {
                type = SCT.kObject
                property(nameProp) { description = "Legal company name" } // reuse: clone + mutate
            }
        }

        defs.keys shouldBe setOf("core.Count", "core.Person", "core.Company")

        // shouldNotBeNull() asserts and returns the non-null value, so it chains.
        val person = defs["core.Person"].shouldNotBeNull().toJsonMap()
        person[SCH.required] shouldBe listOf("name")

        val pProps = person[SCH.properties].shouldNotBeNull().toJsonMap()
        pProps["name"] shouldBe mapOf(SCH.description to "A name", SCH.type to SCT.string)
        pProps["age"] shouldBe mapOf(SCH.description to "Age in years", SCH.type to SCT.integer)
        pProps["nickname"] shouldBe mapOf(SCH.description to "Informal name", SCH.type to SCT.string)
        pProps["count"] shouldBe mapOf(SCH.description to "How many things", SCH.dRef to "#/${SCH.dDefs}/core.Count")

        val cProps = defs["core.Company"].shouldNotBeNull().toJsonMap()[SCH.properties].shouldNotBeNull().toJsonMap()
        // Clone was mutated for this use...
        cProps["name"] shouldBe mapOf(SCH.description to "Legal company name", SCH.type to SCT.string)
        // ...without affecting Person's clone or the original reusable property.
        pProps["name"].shouldNotBeNull().toJsonMap()[SCH.description] shouldBe "A name"
        nameProp.data[SCH.description] shouldBe "A name"
    }

    "namespace is named once for both reusable properties and types (with per-entity override)" {
        val cxt = KdrCxt.mkSimpleCxt("test")

        val defs = schemaDefs(cxt, "core") {
            // Reusable properties declared in the scope's namespace -> "core" once.
            val name = property("name", "A name")
            val active = property("active", "Active flag") { type = SCT.boolean }
            // Per-entity override: this property's bare $ref resolves in "ext".
            val ext = property("ext", "External thing", namespace = "ext") { ref("Thing") }

            type("Person") {
                type = SCT.kObject
                property(name, required = true)
                property(active)
                property(ext)
            }
        }

        defs.keys shouldBe setOf("core.Person")

        // Assert not-null once; its contract smart-casts `props` for the lines below.
        val props = defs["core.Person"]?.toJsonMap()?.get(SCH.properties)?.toJsonMap()
        props.shouldNotBeNull()
        props["name"] shouldBe mapOf(SCH.description to "A name", SCH.type to SCT.string)
        props["active"] shouldBe mapOf(SCH.description to "Active flag", SCH.type to SCT.boolean)
        // The override namespace was honored for the property's ref.
        props["ext"] shouldBe mapOf(SCH.description to "External thing", SCH.dRef to "#/${SCH.dDefs}/ext.Thing")
    }
}
