package com.dynamicruntime.common.overlay

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.schema.overlayTypeOutcome
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.startup.DefRepairContext
import com.dynamicruntime.common.startup.repairTypeDef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The declared overlay merger (issue #985): each strategy on its own, then the two specs built on it -- a schema
 * type's body and its field layout. Pure maps in and out, so every rule is a case here.
 */
class OverlayMergeTest : StringSpec({

    fun spec(vararg rules: Pair<String, MergeRule>) = MergeSpec(mapOf(*rules))

    "an unmentioned key is the base's, shared; a mentioned one replaces; a new one is added" {
        val shared = mapOf("deep" to 1)
        val out = mergeOverlay(spec(), mapOf("a" to shared, "b" to 2), mapOf("b" to 3, "c" to 4)).value
        out shouldBe mapOf("a" to shared, "b" to 3, "c" to 4)
        out["a"] shouldBeSameInstanceAs shared
    }

    "null in an overlay removes, under any rule but append" {
        val out = mergeOverlay(spec("m" to MergeRule.Merge), mapOf("m" to mapOf("x" to 1), "r" to 1), mapOf("m" to null, "r" to null))
        out.value shouldBe mapOf("m" to null, "r" to null)
    }

    "append adds the overlay's elements after the base's, and can take none away (issue #1501)" {
        val base = mapOf("f" to listOf(mapOf("fn" to "a")))
        mergeOverlay(spec("f" to MergeRule.Append), base, mapOf("f" to listOf(mapOf("fn" to "b")))).value["f"] shouldBe
            listOf(mapOf("fn" to "a"), mapOf("fn" to "b"))
        // Where the base has none, the overlay's list stands as written.
        mergeOverlay(spec("f" to MergeRule.Append), emptyMap(), mapOf("f" to listOf(mapOf("fn" to "b")))).value["f"] shouldBe
            listOf(mapOf("fn" to "b"))
        // A null removes nothing, and says so.
        val removal = mergeOverlay(spec("f" to MergeRule.Append), base, mapOf("f" to null))
        removal.value["f"] shouldBe base["f"]
        removal.problems.map { it.code } shouldBe listOf(OverlayMergeError.appendOnly)
    }

    "a diff of an appended list states only what was added, and refuses any other change" {
        val base = mapOf("f" to listOf("a"))
        val added = diffOverlay(spec("f" to MergeRule.Append), base, mapOf("f" to listOf("a", "b")))
        added.overlay shouldBe mapOf("f" to listOf("b"))
        added.problems.shouldBeEmpty()
        diffOverlay(spec("f" to MergeRule.Append), base, mapOf("f" to listOf("b"))).problems.map { it.code } shouldBe
            listOf(OverlayMergeError.cannotStateChange)
        // Removing the list, by a null or by leaving the key out, is refused where the edit is judged; nothing is stated.
        for (edited in listOf(mapOf("f" to null), emptyMap())) {
            val removed = diffOverlay(spec("f" to MergeRule.Append), base, edited)
            removed.overlay shouldBe emptyMap()
            removed.problems.map { it.code } shouldBe listOf(OverlayMergeError.cannotStateChange)
        }
    }

    "a type's g-onWrite merges by appending: an alteration's functions follow the type's own" {
        val base = mapOf(SCH.onWrite to listOf(mapOf("fn" to "computeValue", "target" to "total")))
        val overlay = mapOf(SCH.onWrite to listOf(mapOf("fn" to "computeValue", "target" to "other")))
        overlayTypeOutcome("t", base, overlay).value[SCH.onWrite] shouldBe
            listOf(mapOf("fn" to "computeValue", "target" to "total"), mapOf("fn" to "computeValue", "target" to "other"))
    }

    "merge folds a map's entries one by one" {
        val out = mergeOverlay(
            spec("m" to MergeRule.Merge),
            mapOf("m" to mapOf("x" to 1, "y" to 2)),
            mapOf("m" to mapOf("y" to 20, "z" to 30)),
        ).value
        out["m"] shouldBe mapOf("x" to 1, "y" to 20, "z" to 30)
    }

    "restate keeps only what the overlay mentions, in its order, and an empty entry inherits" {
        val out = mergeOverlay(
            spec("p" to MergeRule.Restate),
            mapOf("p" to mapOf("a" to mapOf("t" to "A"), "b" to mapOf("t" to "B"), "c" to mapOf("t" to "C"))),
            mapOf("p" to mapOf("c" to emptyMap<String, Any?>(), "a" to mapOf("t" to "A2"))),
        ).value
        out["p"] shouldBe mapOf("c" to mapOf("t" to "C"), "a" to mapOf("t" to "A2"))
    }

    "a keyed element replaces its match whole, keeping the base's order" {
        val base = mapOf("l" to listOf(mapOf("id" to "a", "x" to 1, "y" to 1), mapOf("id" to "b", "x" to 2)))
        val out = mergeOverlay(spec("l" to MergeRule.Keyed("id")), base, mapOf("l" to listOf(mapOf("id" to "a", "x" to 9))))
        out.value["l"] shouldBe listOf(mapOf("id" to "a", "x" to 9), mapOf("id" to "b", "x" to 2))
        out.problems.shouldBeEmpty()
    }

    "a keyed element can instead be folded into its match" {
        val base = mapOf("l" to listOf(mapOf("id" to "a", "x" to 1, "y" to 1)))
        val out = mergeOverlay(
            spec("l" to MergeRule.Keyed("id", KeyedElement.merge)), base, mapOf("l" to listOf(mapOf("id" to "a", "x" to 9))),
        ).value
        out["l"] shouldBe listOf(mapOf("id" to "a", "x" to 9, "y" to 1))
    }

    "a new keyed element is appended where order means nothing, refused where it does -- with a located problem" {
        val base = mapOf("l" to listOf(mapOf("id" to "a")))
        val over = mapOf("l" to listOf(mapOf("id" to "z")))
        mergeOverlay(spec("l" to MergeRule.Keyed("id", onNew = OnNew.append)), base, over).value["l"] shouldBe
            listOf(mapOf("id" to "a"), mapOf("id" to "z"))
        val refused = mergeOverlay(spec("l" to MergeRule.Keyed("id")), base, over, "T")
        refused.value["l"] shouldBe listOf(mapOf("id" to "a"))
        refused.problems.single().code shouldBe OverlayMergeError.unmatchedElement
        refused.problems.single().location?.path shouldBe "T.l[z]"
    }

    "a keyed element with no key is refused, whatever the list does with new ones" {
        val out = mergeOverlay(
            spec("l" to MergeRule.Keyed("id", onNew = OnNew.append)), mapOf("l" to emptyList<Any?>()), mapOf("l" to listOf(mapOf("x" to 1))),
        )
        out.value["l"] shouldBe emptyList<Any?>()
        out.problems.single().code shouldBe OverlayMergeError.missingKey
    }

    "a resource merges by its own spec, and stands as written where the base has none" {
        val inner = MergeRule.Resource { _, _ -> spec("m" to MergeRule.Merge) }
        mergeOverlay(spec("r" to inner), mapOf("r" to mapOf("m" to mapOf("x" to 1))), mapOf("r" to mapOf("m" to mapOf("y" to 2))))
            .value["r"] shouldBe mapOf("m" to mapOf("x" to 1, "y" to 2))
        mergeOverlay(spec("r" to inner), emptyMap(), mapOf("r" to mapOf("m" to mapOf("y" to 2))))
            .value["r"] shouldBe mapOf("m" to mapOf("y" to 2))
    }

    // --- the schema type and its layout ---

    fun layout(mode: String?, vararg fields: Pair<String, String>, strings: Map<String, String> = emptyMap()): Map<String, Any?> =
        buildMap {
            mode?.let { put(SL.mode, it) }
            put(SL.schemaFields, fields.map { (f, label) -> mapOf(SL.field to f, SL.label to label) })
            if (strings.isNotEmpty()) put(SL.strings, strings)
        }

    fun type(layout: Map<String, Any?>?): Map<String, Any?> = buildMap {
        put(SCH.type, SCT.kObject)
        put(SCH.properties, mapOf("a" to mapOf(SCH.type to SCT.string), "b" to mapOf(SCH.type to SCT.string), "c" to mapOf(SCH.type to SCT.string)))
        layout?.let { put(SCH.layout, it) }
    }

    "an alteration states only the layout entries it changes; the rest, and the strings, are inherited" {
        val base = type(layout(null, "a" to "A", "b" to "B", strings = mapOf("s" to "S", "t" to "T")))
        val out = overlayTypeOutcome("T", base, mapOf(SCH.layout to layout(null, "b" to "Bee", strings = mapOf("t" to "Tee"))))
        out.problems.shouldBeEmpty()
        out.value[SCH.layout] shouldBe layout(null, "a" to "A", "b" to "Bee", strings = mapOf("s" to "S", "t" to "Tee"))
        // The properties are untouched by an alteration that does not mention them.
        out.value[SCH.properties] shouldBeSameInstanceAs base[SCH.properties]
    }

    "a layout entry for a field the base layout does not list is appended under overlay, refused under reorder" {
        val over = mapOf(SCH.layout to layout(null, "c" to "Cee"))
        overlayTypeOutcome("T", type(layout(null, "a" to "A")), over).value[SCH.layout] shouldBe layout(null, "a" to "A", "c" to "Cee")
        val refused = overlayTypeOutcome("T", type(layout(SLM.reorder, "a" to "A")), over)
        refused.value[SCH.layout] shouldBe layout(SLM.reorder, "a" to "A")
        refused.problems.single().location?.path shouldBe "T.g-layout.schemaFields[c]"
        // An alteration setting its own mode restates the list rather than adding to it: under authoritative, that is
        // the membership too.
        overlayTypeOutcome("T", type(layout(null, "a" to "A")), mapOf(SCH.layout to layout(SLM.authoritative, "c" to "Cee")))
            .value[SCH.layout] shouldBe layout(SLM.authoritative, "c" to "Cee")
    }

    "with no base layout the alteration's stands as written, and null drops an inherited one" {
        val written = layout(SLM.reorder, "c" to "Cee")
        overlayTypeOutcome("T", type(null), mapOf(SCH.layout to written)).value[SCH.layout] shouldBe written
        overlayTypeOutcome("T", type(layout(null, "a" to "A")), mapOf(SCH.layout to null)).value[SCH.layout] shouldBe null
    }

    // --- choosing a rule, and restating keyed lists ---

    "merge removes an entry with null and keeps one named with an empty body" {
        val out = mergeOverlay(
            spec("m" to MergeRule.Merge),
            mapOf("m" to mapOf("x" to mapOf("t" to 1), "y" to mapOf("t" to 2), "z" to mapOf("t" to 3))),
            mapOf("m" to mapOf("x" to null, "y" to emptyMap<String, Any?>(), "w" to mapOf("t" to 4))),
        ).value
        out["m"] shouldBe mapOf("y" to mapOf("t" to 2), "z" to mapOf("t" to 3), "w" to mapOf("t" to 4))
    }

    "a restated keyed list is the overlay's, in its order, and an entry naming only its key inherits" {
        val base = mapOf("l" to listOf(mapOf("id" to "a", "x" to 1), mapOf("id" to "b", "x" to 2), mapOf("id" to "c", "x" to 3)))
        val out = mergeOverlay(
            spec("l" to MergeRule.RestateKeyed("id")), base,
            mapOf("l" to listOf(mapOf("id" to "c"), mapOf("id" to "a", "x" to 9))),
        )
        out.value["l"] shouldBe listOf(mapOf("id" to "c", "x" to 3), mapOf("id" to "a", "x" to 9))
        out.problems.shouldBeEmpty()
    }

    "an overlay chooses among the rules a resource offers, and the directive is no part of the result" {
        val choosing = MergeSpec(
            mapOf("m" to MergeRule.Restate), directiveKey = "how",
            choices = mapOf("m" to mapOf(MCH.restate to MergeRule.Restate, MCH.merge to MergeRule.Merge)),
        )
        val base = mapOf("m" to mapOf("x" to 1, "y" to 2))
        mergeOverlay(choosing, base, mapOf("m" to mapOf("y" to 3))).value["m"] shouldBe mapOf("y" to 3)
        val merged = mergeOverlay(choosing, base, mapOf("how" to mapOf("m" to MCH.merge), "m" to mapOf("y" to 3)))
        merged.value shouldBe mapOf("m" to mapOf("x" to 1, "y" to 3))
        merged.problems.shouldBeEmpty()
    }

    "a choice the resource does not offer is refused, located, and the default applies" {
        val choosing = MergeSpec(
            mapOf("m" to MergeRule.Restate), directiveKey = "how", choices = mapOf("m" to mapOf(MCH.merge to MergeRule.Merge)),
        )
        val out = mergeOverlay(choosing, mapOf("m" to mapOf("x" to 1), "n" to 1), mapOf("how" to mapOf("m" to "shuffle", "n" to MCH.merge)), "T")
        out.problems.map { it.location?.path } shouldBe listOf("T.how.m", "T.how.n")
        out.problems.map { it.code }.toSet() shouldBe setOf(OverlayMergeError.unknownChoice)
    }

    "a schema alteration may merge its properties: name only what changes, null removes" {
        val base = type(null)
        val out = overlayTypeOutcome(
            "T", base,
            mapOf(SCH.merge to mapOf(SCH.properties to MCH.merge), SCH.properties to mapOf("a" to mapOf(SCH.type to SCT.integer), "c" to null)),
        )
        out.problems.shouldBeEmpty()
        out.value[SCH.properties] shouldBe mapOf("a" to mapOf(SCH.type to SCT.integer), "b" to mapOf(SCH.type to SCT.string))
        out.value.containsKey(SCH.merge) shouldBe false
    }

    "a layout alteration that sets reorder or authoritative restates the list; a copy change cannot add there" {
        val base = type(layout(null, "a" to "A", "b" to "B"))
        val reordered = overlayTypeOutcome(
            "T", base,
            mapOf(SCH.layout to mapOf(SL.mode to SLM.reorder, SL.schemaFields to listOf(mapOf(SL.field to "b"), mapOf(SL.field to "a", SL.label to "Ay")))),
        )
        reordered.problems.shouldBeEmpty()
        reordered.value[SCH.layout] shouldBe layout(SLM.reorder, "b" to "B", "a" to "Ay")
        // Against a base whose order matters, a copy change for an unlisted field is refused, saying what to do.
        val refused = overlayTypeOutcome("T", type(layout(SLM.authoritative, "a" to "A")), mapOf(SCH.layout to layout(null, "c" to "Cee")))
        refused.problems.single().message.contains("restate the list") shouldBe true
    }

    // --- resources below the second level, and the diff (issue #1120) ---

    val tagged = MergeSpec(mapOf("tags" to MergeRule.RestateKeyed("t")))

    "a keyed list's elements may each merge by a spec of their own" {
        val rule = MergeRule.Keyed("id", KeyedElement.merge, elementSpec = { _, _ -> tagged })
        val base = mapOf("l" to listOf(mapOf("id" to "a", "label" to "A", "tags" to listOf(mapOf("t" to "x", "n" to 1), mapOf("t" to "y")))))
        // The overlay restates the element's tags, keeping x's body by naming it alone, and relabels it.
        val over = mapOf("l" to listOf(mapOf("id" to "a", "label" to "A2", "tags" to listOf(mapOf("t" to "x"), mapOf("t" to "z")))))
        val out = mergeOverlay(spec("l" to rule), base, over)
        out.problems.shouldBeEmpty()
        out.value["l"] shouldBe listOf(mapOf("id" to "a", "label" to "A2", "tags" to listOf(mapOf("t" to "x", "n" to 1), mapOf("t" to "z"))))
    }

    "a map of resources merges each entry by its spec; one the base lacks stands, and null removes" {
        val rule = MergeRule.Resources { _, _ -> MergeSpec(mapOf("m" to MergeRule.Merge)) }
        val base = mapOf("r" to mapOf("a" to mapOf("m" to mapOf("x" to 1, "y" to 2)), "b" to mapOf("m" to mapOf("x" to 1))))
        val over = mapOf("r" to mapOf("a" to mapOf("m" to mapOf("y" to 20)), "b" to null, "c" to mapOf("m" to mapOf("z" to 3))))
        mergeOverlay(spec("r" to rule), base, over).value["r"] shouldBe
            mapOf("a" to mapOf("m" to mapOf("x" to 1, "y" to 20)), "c" to mapOf("m" to mapOf("z" to 3)))
    }

    val diffSpec = MergeSpec(
        mapOf(
            "m" to MergeRule.Merge,
            "l" to MergeRule.Keyed("id", KeyedElement.merge, OnNew.refuse, elementSpec = { _, _ -> tagged }),
            "r" to MergeRule.Resources { _, _ -> MergeSpec(mapOf("m" to MergeRule.Merge)) },
        ),
    )
    val diffBase: Map<String, Any?> = mapOf(
        "title" to "T",
        "kind" to "k",
        "m" to mapOf("x" to 1, "y" to 2),
        "l" to listOf(
            mapOf("id" to "a", "label" to "A", "tags" to listOf(mapOf("t" to "x", "n" to 1))),
            mapOf("id" to "b", "label" to "B", "tags" to listOf(mapOf("t" to "y"))),
        ),
        "r" to mapOf("one" to mapOf("m" to mapOf("x" to 1, "y" to 2))),
    )

    "a diff states only what changed, by each part's rule, and merges back to the edited resource" {
        val edited = mapOf(
            "title" to "T2",
            "kind" to "k",
            "m" to mapOf("x" to 1, "y" to 3, "z" to 4),
            "l" to listOf(
                mapOf("id" to "a", "label" to "A", "tags" to listOf(mapOf("t" to "x", "n" to 1), mapOf("t" to "w"))),
                mapOf("id" to "b", "label" to "B", "tags" to listOf(mapOf("t" to "y"))),
            ),
            "r" to mapOf("one" to mapOf("m" to mapOf("x" to 1, "y" to 5)), "two" to mapOf("m" to mapOf("q" to 1))),
        )
        val diff = diffOverlay(diffSpec, diffBase, edited)
        diff.problems.shouldBeEmpty()
        diff.overlay shouldBe mapOf(
            "title" to "T2",
            "m" to mapOf("y" to 3, "z" to 4),
            // Only the element that changed, by its id; its tags restated, the unchanged one named alone.
            "l" to listOf(mapOf("id" to "a", "tags" to listOf(mapOf("t" to "x"), mapOf("t" to "w")))),
            "r" to mapOf("one" to mapOf("m" to mapOf("y" to 5)), "two" to mapOf("m" to mapOf("q" to 1))),
        )
        mergeOverlay(diffSpec, diffBase, diff.overlay).value shouldBe edited
        // Nothing changed is nothing to say.
        diffOverlay(diffSpec, diffBase, diffBase).overlay shouldBe emptyMap()
    }

    "a diff refuses what no rule can state: an element removed, moved, or new where the list takes none" {
        val l = diffBase.getValue("l") as List<*>
        fun problemOf(list: List<Any?>) = diffOverlay(diffSpec, diffBase, diffBase + ("l" to list)).problems.single()
        problemOf(l.take(1)).message.contains("'b' is gone") shouldBe true
        problemOf(l.reversed()).message.contains("another order") shouldBe true
        problemOf(l + mapOf("id" to "c", "label" to "C")).message.contains("'c' is new") shouldBe true
        problemOf(l.take(1)).code shouldBe OverlayMergeError.cannotStateChange
    }

    // --- where g-merge may stand ---

    val repairContext = DefRepairContext(emptySet()) { null }
    val directive = mapOf(SCH.properties to MCH.merge)

    "g-merge stands at the top of a client's alteration of a global type, and is refused anywhere else" {
        val body = mapOf(SCH.merge to directive, SCH.properties to emptyMap<String, Any?>())
        repairTypeDef("Type 'kdr.B'", body, repairContext, altersGlobal = true).second.shouldBeEmpty()
        // A type that alters nothing -- a global one, or a client's own.
        val (repaired, repairs) = repairTypeDef("Type 'client.acme.A'", body, repairContext, altersGlobal = false)
        repaired.containsKey(SCH.merge) shouldBe false
        repairs.single().message.contains("would do nothing") shouldBe true
        // Nested, even inside an alteration.
        val nested = mapOf(SCH.properties to mapOf("x" to mapOf(SCH.type to SCT.kObject, SCH.merge to directive)))
        repairTypeDef("Type 'kdr.B'", nested, repairContext, altersGlobal = true).second.single().message
            .contains("'g-merge'") shouldBe true
    }

    "the parser refuses a g-merge that reached it, since a merge would have consumed it" {
        val error = shouldThrow<KdrException> {
            parseSchemaTypes(mapOf("kdr.B" to mapOf(SCH.type to SCT.kObject, SCH.merge to directive)))
        }
        error.fullMessage().contains("would do nothing") shouldBe true
    }
})
