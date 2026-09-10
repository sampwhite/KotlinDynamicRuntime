package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The `GedraConfig` <-> stored-entry round trip (issue #613): a config serialized to its slot entries and
 * reassembled comes back the same, and the types a trait generates are re-manufactured rather than stored.
 */
class GedraConfigSerializeTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("gedraConfigSerialize")

    // A config exercising every slot, and both trait-data cases: an inline-data trait (`acmeNote`, whose
    // `AcmeNoteData` is generated) and a trait naming a shared, directly-declared type (`acmeTagged` -> the
    // `AcmeShared` a `schemaDef` keeps).
    fun sourceConfig(): GedraConfig = gedraConfig(cxt, "acmeConfig", "acmeconfig", "acme") {
        defineClient(
            ClientDef(
                clientId = "acme", name = "Acme",
                usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("AcmeNoteEntry", "acmeNote", setOf(GedraDataType.formDoc), "A note on the doc.") {
            property("text", "The note.", required = true)
        }
        type("AcmeShared") {
            type = SCT.kObject
            property("x", "A shared field.")
        }
        trait("AcmeTaggedEntry", "acmeTagged", setOf(GedraDataType.formDoc), dataType = "AcmeShared")
        stateTrait("AcmeDoneEntry", "acmeDone", setOf(GedraDataType.formDoc), StateTraitClass.asserted) {
            property("by", "Who marked it done.")
        }
        traitUsage("acmeNote", "Note", "text", UsageKind.string)
        workflow("acmeWf", WfEntry.survey) { task("t1", "First") { trait("acmeNote"); save("s", "Save", WfSaveKind.edit) } }
        fragmentOverlay("help") { namespace("copy") { key("title", "Help") } }
        uiBlockOverlay("menu") { set("title", "Menu") }
        cfact("acmeReady", "acme", "When acme is set up", toFrontend = true)
    }

    "a full config round-trips through its stored entries" {
        val source = sourceConfig()
        val entries = gedraConfigToEntries(source)
        entries.keys shouldContainExactlyInAnyOrder listOf(
            CCT.clientDef, CCT.traitDef, CCT.stateTraitDef, CCT.usageDef, CCT.workflowDef,
            CCT.schemaDef, CCT.fragmentDef, CCT.uiBlockDef, CCT.cfactDef,
        )
        val reassembled = reassembleGedraConfig(cxt, source.gedraId.baseId, source.namespace, source.gedraId.client, entries)
        // The stored form is stable: serialize -> reassemble -> serialize yields the same entries, which is the
        // faithful round trip (GedraConfig has no structural equality of its own to compare against).
        gedraConfigToEntries(reassembled) shouldBe entries
        // And the identity survives.
        reassembled.gedraId shouldBe source.gedraId
        reassembled.namespace shouldBe source.namespace
        reassembled.client?.clientId shouldBe "acme"
    }

    "a trait's generated types are not stored, but are re-manufactured on reassembly" {
        val source = sourceConfig()
        val entries = gedraConfigToEntries(source)
        // Only the directly-declared type is a schemaDef; the entry types and the inline data type are not.
        entries.getValue(CCT.schemaDef).map { it[CCT.typeName] } shouldContainExactly listOf("acmeconfig.AcmeShared")
        val storedTypes = entries.getValue(CCT.schemaDef).map { it[CCT.typeName] }
        storedTypes shouldNotContain "acmeconfig.AcmeNoteEntry"
        storedTypes shouldNotContain "acmeconfig.AcmeNoteData"
        // Reassembly re-runs the declarations, so every generated type is present in the compiled defs again.
        val reassembled = reassembleGedraConfig(cxt, source.gedraId.baseId, source.namespace, source.gedraId.client, entries)
        reassembled.defs.keys shouldContain "acmeconfig.AcmeNoteEntry"
        reassembled.defs.keys shouldContain "acmeconfig.AcmeNoteData"
        reassembled.defs.keys shouldContain "acmeconfig.AcmeShared"
        // The inline trait's data really was carried as a body, not a bare ref, so its shape survives.
        reassembled.defs["acmeconfig.AcmeNoteData"].toString().contains("text") shouldBe true
        // The trait's description lives on its generated entry type, and survives the round trip -- it is read
        // back from there on serialize and put back by `trait(...)` on reassembly (issue #613 review).
        reassembled.defs["acmeconfig.AcmeNoteEntry"].toJsonMapOrEmpty()[SCH.description] shouldBe "A note on the doc."
    }

    // Config traits are hardwired, never stored -- so a config carrying them (only `coreConfigTraits` does) is
    // refused rather than silently misfiled (issue #613 review).
    "a config carrying config traits is refused, since they cannot be stored" {
        val ex = shouldThrow<KdrException> { gedraConfigToEntries(coreConfigTraits(cxt)) }
        (ex.message ?: "").contains("config traits") shouldBe true
    }

    "an inline trait stores its data body; a shared-ref trait stores a ref" {
        val entries = gedraConfigToEntries(sourceConfig())
        val byId = entries.getValue(CCT.traitDef).associateBy { it[CCT.traitId] }
        // acmeNote wrote its data inline: the stored dataSchema is the object body (has properties), not a ref.
        val noteData = byId.getValue("acmeNote")[CCT.dataSchema].toString()
        noteData.contains("properties") shouldBe true
        // acmeTagged named a shared type: the stored dataSchema is a ref to it.
        byId.getValue("acmeTagged")[CCT.dataSchema].toString().contains("AcmeShared") shouldBe true
    }
})
