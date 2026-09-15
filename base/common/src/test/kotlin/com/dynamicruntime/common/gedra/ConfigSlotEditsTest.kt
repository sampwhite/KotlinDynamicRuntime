package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The pure core of the config per-slot patch (issue #732): applying add/replace/merge/delete edits over a
 * config's current slots, addressed by slot + primary key. No database -- maps in, maps out.
 *
 * The primary-key map mirrors `coreConfigTraits`: a keyed slot (`usageDef` by `traitId`) and the single-instance
 * `clientDef` (empty key).
 */
class ConfigSlotEditsTest : StringSpec({
    val pk = mapOf(CCT.usageDef to listOf(CCT.traitId), CCT.clientDef to emptyList())

    fun usage(traitId: String, label: String) = mapOf(CCT.traitId to traitId, CCT.label to label)
    fun edit(slot: String, action: GedraEditAction, data: Map<String, Any?>) =
        mapOf(CFEP.slot to slot, GED.action to action.name, GE.data to data)

    "addOrReplace adds a new keyed entry and replaces an existing one wholesale" {
        val current = mapOf(CCT.usageDef to listOf(usage("a", "A")))
        // Replace 'a' wholesale, add a new 'b'.
        val out = applyConfigSlotEdits(
            current,
            listOf(
                edit(CCT.usageDef, GedraEditAction.addOrReplace, mapOf(CCT.traitId to "a", CCT.label to "A2")),
                edit(CCT.usageDef, GedraEditAction.addOrReplace, usage("b", "B")),
            ),
            pk,
        )
        out.getValue(CCT.usageDef) shouldBe listOf(mapOf(CCT.traitId to "a", CCT.label to "A2"), usage("b", "B"))
    }

    "addOrMerge merges into an existing entry, keeping its other fields" {
        val current = mapOf(CCT.usageDef to listOf(mapOf(CCT.traitId to "a", CCT.label to "A", CCT.kind to "string")))
        val out = applyConfigSlotEdits(
            current,
            listOf(edit(CCT.usageDef, GedraEditAction.addOrMerge, mapOf(CCT.traitId to "a", CCT.label to "A2"))),
            pk,
        )
        // label overwritten, kind (untouched) preserved.
        out.getValue(CCT.usageDef).single() shouldBe mapOf(CCT.traitId to "a", CCT.label to "A2", CCT.kind to "string")
    }

    "deleteOrNoOp removes a present entry, drops an emptied slot, and no-ops an absent one" {
        val current = mapOf(CCT.usageDef to listOf(usage("a", "A"), usage("b", "B")))
        // Delete 'a' (present) and 'zzz' (absent, no-op).
        val out = applyConfigSlotEdits(
            current,
            listOf(
                edit(CCT.usageDef, GedraEditAction.deleteOrNoOp, mapOf(CCT.traitId to "a")),
                edit(CCT.usageDef, GedraEditAction.deleteOrNoOp, mapOf(CCT.traitId to "zzz")),
            ),
            pk,
        )
        out.getValue(CCT.usageDef) shouldBe listOf(usage("b", "B"))

        // Deleting the last entry drops the slot entirely.
        applyConfigSlotEdits(
            mapOf(CCT.usageDef to listOf(usage("a", "A"))),
            listOf(edit(CCT.usageDef, GedraEditAction.deleteOrNoOp, mapOf(CCT.traitId to "a"))),
            pk,
        ) shouldNotContainKey CCT.usageDef
    }

    "a single-instance slot (empty key) is addressed as the one entry there is" {
        // Replace the single clientDef entry, leaving other slots untouched.
        val current = mapOf(
            CCT.clientDef to listOf(mapOf(CLD.clientId to "acme", CLD.name to "Acme")),
            CCT.usageDef to listOf(usage("a", "A")),
        )
        val out = applyConfigSlotEdits(
            current,
            listOf(edit(CCT.clientDef, GedraEditAction.addOrReplace, mapOf(CLD.clientId to "acme", CLD.name to "Acme Inc"))),
            pk,
        )
        out.getValue(CCT.clientDef).single()[CLD.name] shouldBe "Acme Inc"
        // The untouched slot is carried through unchanged.
        out.getValue(CCT.usageDef) shouldBe listOf(usage("a", "A"))
    }

    "an unknown slot is refused" {
        shouldThrow<KdrException> {
            applyConfigSlotEdits(emptyMap(), listOf(edit("nosuchslot", GedraEditAction.addOrReplace, emptyMap())), pk)
        }.message!! shouldContain "Unknown config slot"
    }

    "an edit missing its slot's primary key is refused" {
        // A keyed slot's edit must say which entry it means; without the key a delete would silently no-op and an
        // add/replace would append a keyless entry (issue #732 review).
        shouldThrow<KdrException> {
            applyConfigSlotEdits(
                mapOf(CCT.usageDef to listOf(usage("a", "A"))),
                listOf(edit(CCT.usageDef, GedraEditAction.deleteOrNoOp, emptyMap())),
                pk,
            )
        }.message!! shouldContain "primary-key"
    }

    "an unknown action is refused" {
        shouldThrow<KdrException> {
            applyConfigSlotEdits(
                emptyMap(),
                listOf(mapOf(CFEP.slot to CCT.usageDef, GED.action to "frobnicate", GE.data to usage("a", "A"))),
                pk,
            )
        }.message!! shouldContain "Unknown edit action"
    }
})
