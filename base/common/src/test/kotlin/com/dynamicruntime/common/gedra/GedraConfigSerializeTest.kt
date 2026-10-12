package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.util.toJsonListOfMaps
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
    fun sourceConfig(): GedraConfig = gedraConfig(cxt, "acmeConfig", "client.acme", "acme") {
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
            CCT.clientDef, CCT.traitDef, CCT.usageDef, CCT.workflowDef,
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

    // Issue #1135: each place that takes a `$comment` keeps it through the stored form -- the typed ones (workflow,
    // task, eligibility test, report) because their models carry it, and a layout's because the schema it sits in
    // is stored as written.
    "comments survive the stored round trip in every place that takes one" {
        val source = gedraConfig(cxt, "confConfig", "client.conf", "conf") {
            trait("ConfTalkEntry", "confTalk", setOf(GedraDataType.formDoc), "A talk.") {
                property("format", "The session format.", required = true)
                layout(comment = "Workshops get their own formats.") {
                    field("format", label = "Format", comment = "Narrowed by the logistics workflow.")
                }
            }
            workflow("review", WfEntry.normal) {
                comment = "Committee review."
                eligibility("surveyDone", "surveyComplete", "Finish the survey first.", comment = "Reviewers need the track.")
                task("score", "Score it") {
                    comment = "The committee's score."
                    trait("confTalk")
                    save("save", "Save", WfSaveKind.edit)
                }
            }
            report("byTrack", "Talks by track") {
                comment = "For the committee's weekly meeting."
                column("format", "Format", "form.confTalk.format")
            }
        }
        val entries = gedraConfigToEntries(source)
        val workflow = entries.getValue(CCT.workflowDef).single()[CCT.definition].toJsonMapOrEmpty()
        workflow[SCH.dComment] shouldBe "Committee review."
        workflow[WFD.tasks].toJsonListOfMaps().single()[SCH.dComment] shouldBe "The committee's score."
        workflow[WFD.eligibility].toJsonListOfMaps().single()[SCH.dComment] shouldBe "Reviewers need the track."
        entries.getValue(CCT.reportDef).single()[CCT.definition].toJsonMapOrEmpty()[SCH.dComment] shouldBe
            "For the committee's weekly meeting."
        val layout = entries.getValue(CCT.traitDef).single()[CCT.dataSchema].toJsonMapOrEmpty()[SCH.layout].toJsonMapOrEmpty()
        layout[SCH.dComment] shouldBe "Workshops get their own formats."
        layout[SL.schemaFields].toJsonListOfMaps().single()[SCH.dComment] shouldBe "Narrowed by the logistics workflow."

        val reassembled = reassembleGedraConfig(cxt, source.gedraId.baseId, source.namespace, source.gedraId.client, entries)
        gedraConfigToEntries(reassembled) shouldBe entries
    }

    "a trait's generated types are not stored, but are re-manufactured on reassembly" {
        val source = sourceConfig()
        val entries = gedraConfigToEntries(source)
        // Only the directly-declared type is a schemaDef; the entry types and the inline data type are not.
        entries.getValue(CCT.schemaDef).map { it[CCT.typeName] } shouldContainExactly listOf("client.acme.AcmeShared")
        val storedTypes = entries.getValue(CCT.schemaDef).map { it[CCT.typeName] }
        storedTypes shouldNotContain "client.acme.AcmeNoteEntry"
        storedTypes shouldNotContain "client.acme.AcmeNoteData"
        // Reassembly re-runs the declarations, so every generated type is present in the compiled defs again.
        val reassembled = reassembleGedraConfig(cxt, source.gedraId.baseId, source.namespace, source.gedraId.client, entries)
        reassembled.defs.keys shouldContain "client.acme.AcmeNoteEntry"
        reassembled.defs.keys shouldContain "client.acme.AcmeNoteData"
        reassembled.defs.keys shouldContain "client.acme.AcmeShared"
        // The inline trait's data really was carried as a body, not a bare ref, so its shape survives.
        reassembled.defs["client.acme.AcmeNoteData"].toString().contains("text") shouldBe true
        // The trait's description lives on its generated entry type, and survives the round trip -- it is read
        // back from there on serialize and put back by `trait(...)` on reassembly (issue #613 review).
        reassembled.defs["client.acme.AcmeNoteEntry"].toJsonMapOrEmpty()[SCH.description] shouldBe "A note on the doc."
    }

    // Config traits are hardwired, never stored -- so a config carrying them (only `coreConfigTraits` does) is
    // refused rather than silently misfiled (issue #613 review).
    "a config carrying config traits is refused, since they cannot be stored" {
        val ex = shouldThrow<KdrException> { gedraConfigToEntries(coreConfigTraits(cxt)) }
        (ex.message ?: "").contains("config traits") shouldBe true
    }

    // State is global, so a client's config has no slot for a state trait (issue #873): one carrying any is refused
    // rather than stored without it.
    "a config carrying state traits is refused, since state is global" {
        val withState = gedraConfig(cxt, "acmeState", "client.acme", "acme") {
            stateTrait("AcmeDoneEntry", "acmeDone", setOf(GedraDataType.formDoc), StateTraitClass.asserted) {
                property("by", "Who marked it done.")
            }
        }
        val ex = shouldThrow<KdrException> { gedraConfigToEntries(withState) }
        (ex.message ?: "").contains("acmeDone") shouldBe true
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
