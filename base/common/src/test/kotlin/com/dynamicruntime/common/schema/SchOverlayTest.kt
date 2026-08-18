package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

/**
 * Overlaying a client's definitions onto the global `$defs` document (issue #356).
 *
 * The two properties worth the most care are the ones the rest of the variant machinery rests on: that a
 * `$ref` to an altered type resolves to the **altered** form once re-parsed -- the case namespacing cannot
 * fake -- and that nothing is ever written into the global document, since every client shares it.
 */
class SchOverlayTest : StringSpec({

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

    // Rule three, and the one that shapes how schema gets authored: once an overlay defines a property, that
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

    // `client-definition.md`: a variant may create new nodes and point at old ones; it must not write into old
    // ones. Asserted rather than trusted, because every client shares this document and a write here would
    // corrupt all of them at once -- silently, and at boot.
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
                    "title" to linkedMapOf(SCH.dRef to "#/\$defs/core.Name"),
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
})
