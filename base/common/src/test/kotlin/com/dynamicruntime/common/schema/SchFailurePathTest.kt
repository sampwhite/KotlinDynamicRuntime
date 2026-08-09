package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Reading a [SchFailure]'s path back as a location (issue #197). These are the pieces a form uses to put a
 * message beside the field that caused it, and they are covered here — plain data in, plain data out — rather
 * than through the browser, which is where the traversal that consumes them has to be checked.
 */
class SchFailurePathTest : StringSpec({

    fun failure(path: String) = SchFailure(path, SchFailCode.wrongType, "no")

    "a path is at or below itself" {
        isPathAtOrBelow("input.name", "input.name") shouldBe true
    }

    "a path is below its parents" {
        isPathAtOrBelow("input.name", "input") shouldBe true
        isPathAtOrBelow("input.contacts[1].handle", "input.contacts[1]") shouldBe true
        isPathAtOrBelow("input.contacts[1].handle", "input.contacts") shouldBe true
        isPathAtOrBelow("input.contacts[1].handle", "input") shouldBe true
    }

    "an array element is below its array" {
        isPathAtOrBelow("contacts[0]", "contacts") shouldBe true
    }

    // The reason nesting is decided on the separator rather than on startsWith: these all share a text prefix
    // with something they are not inside, and a naive check would clear or mark the wrong field.
    "a sibling sharing a text prefix is not below it" {
        isPathAtOrBelow("input.nameOfThing", "input.name") shouldBe false
        isPathAtOrBelow("contacts10[0]", "contacts1") shouldBe false
        isPathAtOrBelow("inputs.name", "input") shouldBe false
    }

    "a parent is not below its child" {
        isPathAtOrBelow("input", "input.name") shouldBe false
    }

    "everything is below the root" {
        isPathAtOrBelow("input.name", "") shouldBe true
        isPathAtOrBelow("", "") shouldBe true
    }

    "byPath groups every failure under its own path" {
        val fs = listOf(failure("a"), failure("b"), failure("a"))
        val grouped = fs.byPath()
        grouped.keys shouldContainExactlyInAnyOrder listOf("a", "b")
        grouped["a"]!!.size shouldBe 2
        grouped["b"]!!.size shouldBe 1
    }

    "atOrBelow selects a subtree" {
        val fs = listOf(
            failure("input.name"), failure("input.contacts"), failure("input.contacts[0].handle"),
            failure("other"),
        )
        fs.atOrBelow("input.contacts").map { it.path } shouldContainExactlyInAnyOrder
            listOf("input.contacts", "input.contacts[0].handle")
    }

    // The other half of "same line of the tree": a container's failure was computed from contents the edit
    // just changed. Supplying `input.name` is what brings `input` into existence, so the missing-`input`
    // complaint is stale the moment the child is filled in.
    "editing a child clears its ancestors" {
        val fs = listOf(failure("input"), failure("input.name"), failure("input.score"), failure("other"))
        fs.clearedAt("input.name").map { it.path } shouldContainExactlyInAnyOrder listOf("input.score", "other")
    }

    "clearing reaches ancestors through an array index" {
        val fs = listOf(failure("input.contacts"), failure("input"), failure("input.name"))
        fs.clearedAt("input.contacts[0].handle").map { it.path } shouldBe listOf("input.name")
    }

    "siblings survive in both directions" {
        val fs = listOf(failure("input.name"), failure("input.score"))
        fs.clearedAt("input.name").map { it.path } shouldBe listOf("input.score")
        fs.clearedAt("input.score").map { it.path } shouldBe listOf("input.name")
    }

    // The behavior the form depends on: editing a container drops the failures held against what is inside it,
    // because a removal re-indexes the rest and the old failure would then point at whatever moved into place.
    "editing a container clears its descendants" {
        val fs = listOf(failure("contacts[0].handle"), failure("contacts[1].handle"), failure("name"))
        fs.clearedAt("contacts").map { it.path } shouldBe listOf("name")
    }

    "editing one leaf leaves its siblings alone" {
        val fs = listOf(failure("contacts[0].handle"), failure("contacts[1].handle"))
        fs.clearedAt("contacts[0].handle").map { it.path } shouldBe listOf("contacts[1].handle")
    }

    "clearing at the root clears everything" {
        listOf(failure("a"), failure("b[0]")).clearedAt("").shouldBeEmpty()
    }

    // The point of the whole exercise: the paths a display would build by walking the schema are the same
    // strings the validator puts on its failures. This drives real validation over a nested + list schema and
    // checks the reported paths against ones composed with childPath/indexPath, so the two cannot drift apart
    // without failing here.
    "reported paths match paths composed with childPath and indexPath" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val types = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Contact") {
                    type = SCT.kObject
                    property("handle", "The handle", required = true) { type = SCT.string }
                }
                type("Address") {
                    type = SCT.kObject
                    property("street", "The street", required = true) { type = SCT.string }
                }
                type("Form") {
                    type = SCT.kObject
                    property("address", "Where") { ref("Address") }
                    property("contacts", "How to reach") { type = SCT.array; items { ref("Contact") } }
                }
            },
            existingTypes = emptyMap(),
        )
        val failures = validate(
            types["core.Form"]!!,
            mapOf(
                "address" to mapOf<String, Any?>(),
                "contacts" to listOf(mapOf<String, Any?>(), mapOf("handle" to "ok")),
            ),
        )
        val root = ""
        failures.map { it.path } shouldContainExactlyInAnyOrder listOf(
            childPath(childPath(root, "address"), "street"),
            childPath(indexPath(childPath(root, "contacts"), 0), "handle"),
        )
    }
})
