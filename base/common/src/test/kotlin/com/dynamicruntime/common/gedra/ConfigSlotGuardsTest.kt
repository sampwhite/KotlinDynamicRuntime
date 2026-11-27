package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.home.whatAClientIsMarkdown
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The places that must know every configuration slot, held to `CCT.slots` (issue #1854): a slot added there fails each
 * of these until the place is told, and the failure names the slot and the place. The bundle editor's labels are held
 * in the webapp (`BundleEditorTest`), and what the assistant is taught in the `assistant` module.
 *
 * Each runs over one configuration with an entry in every slot, so the places are judged by what they do with a slot's
 * entries, not by a second list of slots to keep beside theirs.
 *
 * Theme: #SLTKW.
 */
class ConfigSlotGuardsTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("configSlotGuards")
    val client = "acme"

    // An entry in every slot. A new slot needs one here, which the first test says.
    fun everySlot(): GedraConfig = gedraConfig(cxt, "everySlot", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = "Acme", usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        defineCustomer(CustomerDef("acme", "Acme"))
        trait("AcmeNoteEntry", "acmeNote", setOf(GedraDataType.formDoc), "A note on the form.") {
            property("text", "The note.", required = true)
        }
        type("AcmeShared") {
            type = SCT.kObject
            property("x", "A shared field.")
        }
        traitUsage("acmeNote", "Note", "text", UsageKind.string)
        workflow("acmeWf", WfEntry.coreDetails) { task("t1", "First") { trait("acmeNote"); save("s", "Save", WfSaveKind.edit) } }
        report("byNote", "Notes") { column("text", "Text", "form.acmeNote.text") }
        fragmentOverlay("help") { namespace("copy") { key("title", "Help") } }
        uiBlockOverlay("menu") { set("title", "Menu") }
        cfact("acmeReady", "acme", "When acme is set up")
        fileRef("logo", "ab".repeat(32), "The logo.")
        listing(ListingDef(WorkflowLayout.combined))
        counter("receipt", "R")
        ftype("visit", "Visit", setWhen = "acmeReady", mayLeave = true) { trait("acmeNote", required = false) }
    }

    /**
     * The slots whose rule says nothing stands over one of their entries by a key, each with why. Every other slot is
     * held to the keys the code layer and a client's template are cut by (`ConfigKeys`), and this list to the rules.
     */
    val notByKey = mapOf(
        CCT.clientDef to "A client has one definition, and a stored one stands over its code's by itself.",
        CCT.customerDef to "Only the platform declares customers, and no client's code layer holds one to stand over.",
        CCT.fragmentDef to "Copy is layered key by key, never replaced.",
        CCT.uiBlockDef to "The menu and Home's cards are layered item by item, never replaced.",
        CCT.listingDef to "A client has one entry of listing settings, and a stored one stands over its code's by itself.",
    )

    "the configuration these guards run over has an entry in every slot" {
        withClue("Add an entry of the new slot to everySlot() in this test") {
            gedraConfigToEntries(everySlot()).keys shouldContainExactlyInAnyOrder CCT.slots
        }
    }

    "each slot's rule names its shape's key, and says why when nothing stands over it by one" {
        for (rule in CCT.slotRules) {
            withClue("Slot ${rule.slot}: its rule's keyField (CCT.slotRules) is not the first of its shape's g-primaryKey") {
                rule.keyField shouldBe ConfigSlotShapes.primaryKey(cxt, rule.slot).firstOrNull()
            }
        }
        withClue("A slot whose rule stands over never is listed in notByKey, with why, and no other") {
            notByKey.keys shouldBe CCT.slotRules.filter { it.standsOver == SlotStandsOver.never }.map { it.slot }.toSet()
        }
    }

    "each slot stood over by key is in ConfigKeys, cut by it, and marked by CodeOverrides" {
        val entries = gedraConfigToEntries(everySlot())
        for (slot in CCT.slots) {
            val alone = reassembleGedraConfig(cxt, "alone", clientNamespace(client), client, mapOf(slot to entries.getValue(slot)))
            val keys = ConfigKeys.of(alone)
            if (slot in notByKey) {
                withClue("Slot $slot is listed in notByKey (${notByKey[slot]}), yet ConfigKeys holds it: take it out of that list") {
                    keys.isEmpty shouldBe true
                }
                continue
            }
            withClue("Slot $slot: ConfigKeys (base:kernel) has no field for it, or ConfigKeys.of does not read it") {
                keys.isEmpty shouldBe false
            }
            withClue("Slot $slot: GedraConfig.cutBy (ConfigKeys.kt) does not cut it") {
                gedraConfigToEntries(alone.cutBy(keys))[slot].orEmpty().shouldBeEmpty()
            }
            val marked = mapOf(slot to entries.getValue(slot).map { it + (CCT.overrides to CCT.overridesCodeValue) })
            withClue("Slot $slot: CodeOverrides.markedKeys does not read its marked entries") {
                CodeOverrides.markedKeys(alone, marked) shouldBe keys
            }
        }
    }

    "each slot stood over by key is a replacement the bundle editor shows" {
        val entries = gedraConfigToEntries(everySlot())
        val marked = entries.mapValues { (_, list) -> list.map { it + (CCT.overrides to CCT.overridesCodeValue) } }
        val keyFields = CCT.slots.associateWith { ConfigSlotShapes.primaryKey(cxt, it) }
        val found = ConfigCodeLayer.replacements(
            listOf(CodeSide("code", CFEP.originCode, null, entries)),
            listOf(OverSide("stored", marked, stored = true)),
            keyFields,
        ) { null }.map { it.slot }
        for (slot in CCT.slots - notByKey.keys) {
            withClue("Slot $slot: ConfigCodeLayer.replacements does not find a stored entry over the code's") {
                found shouldContain slot
            }
        }
    }

    "a sandbox's copy of a configuration holds every slot as it is" {
        val source = everySlot()
        val entries = gedraConfigToEntries(source)
        val copied = gedraConfigToEntries(SandboxConfigs.rebind(source))
        // The definition is derived from the parent's, and the customers are left out: the platform declares each once.
        withClue("SandboxConfigs.rebind copies customers, which it leaves out on purpose") { copied[CCT.customerDef] shouldBe null }
        for (slot in buildList {
            addAll(CCT.slots)
            remove(CCT.clientDef)
            remove(CCT.customerDef)
        }) {
            withClue("Slot $slot: SandboxConfigs.rebind drops or changes it") { copied[slot] shouldBe entries[slot] }
        }
    }

    "a sandbox overlay repeating its parent's source names each repeat by its kind and key" {
        val source = everySlot()
        val repeats = SandboxConfigs.overlayRepeats(source, listOf(source))
        withClue("SandboxConfigs.describe names a slot by its id: give the slot a rule in CCT.slotRules") {
            repeats.filter { it.contains("kdr:") }.shouldBeEmpty()
        }
        // Every entry but the definition's is reported, each by its kind, and by its key where it has one.
        val entries = gedraConfigToEntries(source)
        for (rule in CCT.slotRules.filter { it.slot != CCT.clientDef }) {
            val key = rule.keyField?.let { entries.getValue(rule.slot).first()[it] }
            val expected = if (rule.slot == CCT.fragmentDef || key == null) rule.kind else "${rule.kind} '$key'"
            withClue("Slot ${rule.slot}: a repeat of its entry is not reported as $expected") {
                repeats.any { it.startsWith(expected) } shouldBe true
            }
        }
    }

    "the client overview names every slot in its section on slots" {
        val page = whatAClientIsMarkdown()
        val section = page.substringAfter("## Its configuration").substringBefore("\n## ")
        for (slot in CCT.slots) {
            withClue("Slot $slot: name it in the table of WhatAClientIsReference.kt, or among the rarer ones under it") {
                section shouldContain "`$slot`"
            }
        }
    }
})
