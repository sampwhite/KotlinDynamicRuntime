package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.parseWfDef
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
import io.kotest.matchers.string.shouldContain

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
        fileRef("logo", "ab".repeat(32), "The logo.")
        fileRef("banner", "cd".repeat(32))
        fileRef("crest", "The crest.") { local("brand", "marks/crest.svg") }
    }

    "a full config round-trips through its stored entries" {
        val source = sourceConfig()
        val entries = gedraConfigToEntries(source)
        entries.keys shouldContainExactlyInAnyOrder listOf(
            CCT.clientDef, CCT.traitDef, CCT.usageDef, CCT.workflowDef,
            CCT.schemaDef, CCT.fragmentDef, CCT.uiBlockDef, CCT.cfactDef, CCT.fileRefDef,
        )
        // A file ref's entry is what was authored, and none of the stamp that says where it came from (issue #1153).
        entries.getValue(CCT.fileRefDef) shouldBe listOf(
            mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to "ab".repeat(32), CCT.description to "The logo."),
            mapOf(CCT.refId to "banner", CCT.source to FREF.sourceStore, CCT.hash to "cd".repeat(32)),
            // A local file's (issue #1154): a root and a path, and no hash -- nobody authors one.
            mapOf(CCT.refId to "crest", CCT.source to FREF.sourceLocal, CCT.root to "brand", CCT.path to "marks/crest.svg", CCT.description to "The crest."),
        )
        // A ref that says where no file is, is a mistake in the code declaring it, and is said there.
        shouldThrow<KdrException> { gedraConfig(cxt, "bad", "client.acme", "acme") { fileRef("nowhere") {} } }.message.orEmpty().contains("names no file") shouldBe true
        val reassembled = reassembleGedraConfig(cxt, source.gedraId.baseId, source.namespace, source.gedraId.client, entries)
        // The stored form is stable: serialize -> reassemble -> serialize yields the same entries, which is the
        // faithful round trip (GedraConfig has no structural equality of its own to compare against).
        gedraConfigToEntries(reassembled) shouldBe entries
        // And the identity survives.
        reassembled.gedraId shouldBe source.gedraId
        reassembled.namespace shouldBe source.namespace
        reassembled.client?.clientId shouldBe "acme"
        reassembled.fileRefs.map { it.refId } shouldBe listOf("logo", "banner", "crest")
        reassembled.fileRefs.last().pointer shouldBe "local:brand/marks/crest.svg"
        reassembled.fileRefs.first().configName shouldBe "acmeConfig"
    }

    // Issue #1153: a file ref is restored as it was stored, good or not -- it is the load that judges one, so that
    // a ref that is no good costs only itself and not the configuration holding it -- and is stamped with where it
    // came from, which is what tells a stored ref from one declared in source.
    "a stored file ref reassembles whatever it holds, stamped as stored, and a sandbox and a template copy carry it" {
        val entries = mapOf(
            CCT.fileRefDef to listOf(
                mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to "ab".repeat(32)),
                // An id that is no name, a source a later release may add, no hash at all, and an id held twice.
                mapOf(CCT.refId to "not a name", CCT.source to "elsewhere"),
                mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to "ef".repeat(32)),
            ),
        )
        val stored = reassembleGedraConfig(cxt, "copy", "client.acme", "acme", entries)
        stored.fileRefs.map { it.refId } shouldBe listOf("logo", "not a name", "logo")
        stored.fileRefs.all { it.stored } shouldBe true
        stored.fileRefs[1].source shouldBe "elsewhere"
        stored.fileRefs[1].hash shouldBe null
        // Written back as it was read, an id once: an edit of anything else in the configuration does not rewrite a
        // ref, and a row holding an id twice is written with the first -- the one a load keeps -- rather than
        // being unwritable until one was taken out by hand.
        gedraConfigToEntries(stored).getValue(CCT.fileRefDef) shouldBe entries.getValue(CCT.fileRefDef).take(2)
        // Declared in source, the same entry is stamped as that.
        sourceConfig().fileRefs.none { it.stored } shouldBe true

        // A sandbox's copy of its parent's configuration holds the parent's refs as they are, stamp included.
        val rebound = SandboxConfigs.rebind(stored)
        rebound.gedraId.client shouldBe sandboxOf("acme")
        rebound.fileRefs.map { it.refId to it.hash } shouldBe stored.fileRefs.map { it.refId to it.hash }
        rebound.fileRefs.all { it.stored } shouldBe true
        // A client built on a template takes the template's refs, less the ones it names itself.
        val own = gedraConfig(cxt, "mine", "client.kid", "kid") { fileRef("banner", "12".repeat(32)) }
        val copy = ClientExtension.clones("kid", "acme", listOf(sourceConfig()), listOf(own), GedraConfigOrigin.source).single()
        copy.fileRefs.map { it.refId } shouldBe listOf("logo", "crest")
        ClientExtension.clones("kid", "acme", listOf(sourceConfig()), emptyList(), GedraConfigOrigin.source).single()
            .fileRefs.map { it.refId } shouldBe listOf("logo", "banner", "crest")
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

    // A workflow made from a template is stored as its overlay (issue #1120); one that carries none cannot be stored, since
    // read back, the definition it expanded to would be taken for an overlay and refused.
    "a workflow made from a template with no overlay to store is refused, not written expanded" {
        val made = parseWfDef(
            cxt,
            WfDefBuilder("made", WfEntry.normal).apply {
                task("t", "T") { trait("kdr:name"); save("s", "S", WfSaveKind.edit) }
            }.build() + (WFD.fromTemplate to "kdr:collect"),
        )
        val config = GedraConfig(GedraId.of(GedraConfigType.configDoc, "acme", "flows"), "client.acme", emptyMap(), defs = emptyMap(), workflows = mapOf("made" to made))
        shouldThrow<KdrException> { gedraConfigToEntries(config) }.message.orEmpty() shouldContain "carries no overlay"
    }
})
