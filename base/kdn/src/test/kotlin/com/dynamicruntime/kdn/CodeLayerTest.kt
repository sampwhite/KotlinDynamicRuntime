package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientStoredEdit
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CodeSide
import com.dynamicruntime.common.gedra.ConfigCodeLayer
import com.dynamicruntime.common.gedra.ConfigKeys
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignOrigin
import com.dynamicruntime.common.gedra.DesignRefusal
import com.dynamicruntime.common.gedra.DesignSharedEdit
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.OverSide
import com.dynamicruntime.common.gedra.SourceCut
import com.dynamicruntime.common.gedra.SourceCuts
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.reassembleGedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.gedra.workflow.workflowDefStamp
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.ConcurrentHashMap

/**
 * A client's stored configuration over its code (issue #1739 gathered these): an entry marked to stand over the code's
 * (issue #1634), the bundle editor's code layer and what replaces what (issues #1616, #1646), and an overlay holding only
 * what it changes of a code workflow or type (issue #1657). Each part below is one of the specs that used to be a file
 * of its own, kept whole.
 */
class CodeLayerTest : StringSpec({
    include(codeOverridesTests())
    include(configCodeLayerTests())
    include(codeOverlaysTests())
})

/**
 * A client's stored configuration standing over its code (issue #1634): an entry marked `"overrides": "code"` stands over
 * the code layer's entry of the same key -- its template's, its own code's, and in its sandbox -- which is cut out of the
 * code; an unmarked match is refused at write, saying what it would replace, and loads as before with an issue; a marked
 * entry with nothing under it is refused. The patch's dry run is the code-layer part's, below, and Acme's own code the
 * sample's `AcmeCodeOverrideTest`. A client per case, on the shared extension-template instance.
 */
private fun codeOverridesTests() = stringSpec {
    val tpl = ExtensionTemplateComponent.template
    val own = ExtensionTemplateComponent.ownCode
    val cxt = TestInstances.extensionTemplate("codeOverrides1634")

    fun typeOf(on: KdrCxt, client: String, traitId: String) =
        SchemaService.get(on).gedraTraitsFor(client).single { it.traitId == traitId }.typeName
    fun firstTask(on: KdrCxt, client: String, workflowId: String) =
        WorkflowService.get(on).forClient(client).workflow(workflowId).shouldNotBeNull().def.tasks.first().label
    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("overrideWrite", client).also { it.userId = 16340L }

    /** Writes [client]'s stored config [name], defining it on [template] when given -- a code client defines itself. */
    fun store(
        on: KdrCxt,
        client: String,
        name: String = "main",
        template: String? = null,
        trial: Boolean = true,
        build: GedraConfigBuilder.() -> Unit = {},
    ) {
        val config = gedraConfig(on, name, "${clientNamespace(client)}.$name", client) {
            template?.let {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = it,
                    ),
                )
            }
            build()
        }
        GedraConfigService.get(on).writeConfig(writer(on, client), config, trial = trial)
    }

    /** Publishes [client]'s config [name]: a client with a sandbox runs only what it has published. */
    fun publish(client: String, name: String) =
        GedraConfigService.get(cxt).publish(writer(cxt, client), listOf(GedraId.of(GedraConfigType.configDoc, client, name)))

    fun GedraConfigBuilder.note(entry: String, traitId: String) =
        trait(entry, traitId, setOf(GedraDataType.formDoc), "The stored note.") { property("memo", "A memo.") }

    "on a template, a marked entry stands over the template's; an unmarked one, or one over nothing, is refused at write" {
        val client = "ovrtpl1634"
        store(cxt, client, template = tpl)
        GedraConfigReload.reloadClient(cxt, client)

        // Unmarked, the write says what it would replace, and where.
        shouldThrow<KdrException> {
            store(cxt, client, template = tpl) { note("MineNoteEntry", "tplNote") }
        }.message.shouldNotBeNull() shouldContain "holds trait 'tplNote', which template '$tpl''s 'base' also declares, without " +
            "marking it \"${CCT.overrides}\": \"${CCT.overridesCodeValue}\""
        // Marked over nothing, it says so.
        shouldThrow<KdrException> {
            store(cxt, client, template = tpl) {
                note("LoneEntry", "lone")
                overridesCode("lone")
            }
        }.message.shouldNotBeNull() shouldContain "marks trait 'lone' to stand over code"

        // Marked, the trait and the workflow stand over the template's, whole.
        store(cxt, client, template = tpl) {
            note("MineNoteEntry", "tplNote")
            workflow("tplFlow", WfEntry.normal) {
                task("mine", "The client's task") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) }
            }
            // The template's `notes` report reads the field this takes away, so it stands over that too.
            report("notes", "Memos") { column("memo", "Memo", "form.tplNote.memo") }
            overridesCode("tplNote", "tplFlow", "notes")
        }
        GedraConfigReload.reloadClient(cxt, client)
        typeOf(cxt, client, "tplNote") shouldBe "${clientNamespace(client)}.main.MineNoteEntry"
        firstTask(cxt, client, "tplFlow") shouldBe "The client's task"
        // The template's other definitions are still the client's.
        typeOf(cxt, client, "tplScore") shouldBe "client.$tpl.TplScoreEntry"
    }

    "a client's own code is stood over by a marked entry, in its sandbox too, and comes back when the entry goes" {
        // Unmarked, the write is refused, naming the code's configuration.
        shouldThrow<KdrException> {
            store(cxt, own, name = "bad") { note("BadNoteEntry", "ownNote") }
        }.message.shouldNotBeNull() shouldContain "holds trait 'ownNote', which its code's 'own' also declares"

        store(cxt, own, name = "over") {
            note("StoredNoteEntry", "ownNote")
            workflow("ownFlow", WfEntry.normal) { task("b", "The stored task") { trait("ownNote"); save("s", "Save", WfSaveKind.edit) } }
            overridesCode("ownNote", "ownFlow")
        }
        publish(own, "over")
        GedraConfigReload.reloadClient(cxt, own)
        typeOf(cxt, own, "ownNote") shouldBe "${clientNamespace(own)}.over.StoredNoteEntry"
        firstTask(cxt, own, "ownFlow") shouldBe "The stored task"
        // The sandbox runs the latest revisions over the same code.
        typeOf(cxt, sandboxOf(own), "ownNote") shouldBe "${clientNamespace(own)}.over.StoredNoteEntry"
        firstTask(cxt, sandboxOf(own), "ownFlow") shouldBe "The stored task"

        // A revision without the entries: the code's are back, as declared, in both.
        store(cxt, own, name = "over")
        publish(own, "over")
        GedraConfigReload.reloadClient(cxt, own)
        typeOf(cxt, own, "ownNote") shouldBe "client.$own.OwnNoteEntry"
        firstTask(cxt, own, "ownFlow") shouldBe "Code's task"
        typeOf(cxt, sandboxOf(own), "ownNote") shouldBe "client.$own.OwnNoteEntry"
    }

    // Issue #1646: an override lives in the stored bundle named like the code bundle it covers, which shares its id.
    "a stored bundle named like a code bundle loads beside it, and stands over it" {
        // The template's copy, by its name: a client on the template.
        val client = "ovrsame1646"
        store(cxt, client, template = tpl)
        store(cxt, client, name = "from_${tpl}_base") {
            note("SameNoteEntry", "tplNote")
            report("notes", "Memos") { column("memo", "Memo", "form.tplNote.memo") }
            overridesCode("tplNote", "notes")
        }
        GedraConfigReload.reloadClient(cxt, client)
        typeOf(cxt, client, "tplNote") shouldBe "${clientNamespace(client)}.from_${tpl}_base.SameNoteEntry"
        typeOf(cxt, client, "tplScore") shouldBe "client.$tpl.TplScoreEntry"

        // The client's own code, by its name, in the client and its sandbox.
        store(cxt, own, name = "own") {
            note("SameOwnEntry", "ownNote")
            overridesCode("ownNote")
        }
        publish(own, "own")
        GedraConfigReload.reloadClient(cxt, own)
        typeOf(cxt, own, "ownNote") shouldBe "${clientNamespace(own)}.own.SameOwnEntry"
        typeOf(cxt, sandboxOf(own), "ownNote") shouldBe "${clientNamespace(own)}.own.SameOwnEntry"
        // The code's other definitions in that bundle are still run.
        firstTask(cxt, own, "ownFlow") shouldBe "Code's task"
        // Emptied, the code's is back.
        store(cxt, own, name = "own")
        publish(own, "own")
        GedraConfigReload.reloadClient(cxt, own)
        typeOf(cxt, own, "ownNote") shouldBe "client.$own.OwnNoteEntry"
    }

    "a mark is stored as the entry's own field, and read back" {
        val built = gedraConfig(cxt, "over", "${clientNamespace(own)}.over", own) {
            note("StoredNoteEntry", "ownNote")
            overridesCode("ownNote")
        }
        val entries = gedraConfigToEntries(built)
        entries.getValue(CCT.traitDef).single()[CCT.overrides] shouldBe CCT.overridesCodeValue
        reassembleGedraConfig(cxt, "over", built.namespace, own, entries).overridesCode shouldBe built.overridesCode
    }

    "the code is cut in place and put back as declared; a cut record out of step with the collector is refused" {
        val loader = GedraConfigLoadService.get(cxt)
        // A scratch copy holds the very configs the node does, so it is cut as the node's would be, and nothing changes.
        val scratch = SchemaCollector.get(cxt).shouldNotBeNull().trialCopy()
        // The code's: a stored bundle of the same name may sit beside it (issue #1646).
        fun held() = scratch.gedraConfigs.configs.single { it.gedraId.client == own && it.name == "own" && !it.isStored }
        val declared = held()
        val position = scratch.gedraConfigs.configs.indexOf(declared)
        val cuts = loader.cutSource(scratch, own, ConfigKeys(traits = setOf("ownNote")), SourceCuts.none)
        cuts.cuts.single().original shouldBeSameInstanceAs declared
        held().traits.keys.contains("ownNote") shouldBe false
        // Cut where it stood, so "first declaration wins" reads as it did.
        scratch.gedraConfigs.configs.indexOf(held()) shouldBe position
        // Put back as declared; again, as a rollback might, is nothing.
        loader.cutSource(scratch, own, ConfigKeys.none, cuts)
        held() shouldBeSameInstanceAs declared
        loader.cutSource(scratch, own, ConfigKeys.none, cuts)
        held() shouldBeSameInstanceAs declared
        // A record naming a cut the collector never held, of a config it does not hold either, is said, not ignored.
        val stray = SourceCuts(listOf(SourceCut(declared.copy(), declared.copy())), ConfigKeys(traits = setOf("ownNote")))
        shouldThrow<KdrException> { loader.cutSource(scratch, own, ConfigKeys.none, stray) }.message.shouldNotBeNull() shouldContain
            "out of step"
    }

    "a mark over nothing is left out at load, with an issue, and the rest of the config loads" {
        val warn = TestInstances.extensionTemplateWarn("codeOverridesNothing1634")
        val client = "ovrnothing1634"
        // Stored past the write's trial, as a hand edit or an older release could leave it.
        store(warn, client, template = tpl, trial = false) {
            note("LoneEntry", "lone")
            note("KeptEntry", "kept")
            overridesCode("lone")
        }
        GedraConfigReload.reloadClient(warn, client)
        val traits = SchemaService.get(warn).gedraTraitsFor(client).map { it.traitId }
        traits.contains("lone") shouldBe false
        traits.contains("kept") shouldBe true
        ClientConfigIssues.get(warn).issuesFor(client).map { it.message }
            .any { "marks trait 'lone' to stand over code" in it } shouldBe true
    }

    "configuration stored before the mark still loads as it did, with an issue saying to mark it" {
        val warn = TestInstances.extensionTemplateWarn("codeOverridesWarn1634")
        val client = "ovrold1634"
        // Stored as an older release would have: past the write's trial.
        store(warn, client, template = tpl, trial = false) {
            note("OldNoteEntry", "tplNote")
            report("notes", "Memos") { column("memo", "Memo", "form.tplNote.memo") }
        }
        GedraConfigReload.reloadClient(warn, client)
        typeOf(warn, client, "tplNote") shouldBe "${clientNamespace(client)}.main.OldNoteEntry"
        val said = ClientConfigIssues.get(warn).issuesFor(client).map { it.message }
        said.any { "holds trait 'tplNote'" in it && "without marking it" in it } shouldBe true
    }
}

/**
 * The bundle editor's code layer (issues #1616, #1646): a client's own code bundles and its template's copies on the
 * editor's read, as declared, each labeled by origin; which of the client's entries replace its code's, whole -- its own
 * code over its template's, and what it stores over either -- and whether each is marked; and a patch's dry run saying
 * what it would newly replace.
 */
private fun configCodeLayerTests() = stringSpec {
    val tpl = ExtensionTemplateComponent.template
    val kid = ExtensionTemplateComponent.child
    val cxt = TestInstances.extensionTemplate("configCodeLayer1616")

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun store(client: String, template: String = tpl, build: GedraConfigBuilder.() -> Unit = {}) {
        val config = gedraConfig(cxt, "main", "${clientNamespace(client)}.main", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = template,
                ),
            )
            build()
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("codeLayerWrite", client).also { it.userId = 16160L }, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun codeBundles(editor: Map<String, Any?>) = editor[CFEP.codeConfigs].toJsonListOfMaps()
    fun slotKeys(bundle: Map<String, Any?>, slot: String, key: String) =
        bundle[CFEP.slots].toJsonMapOrEmpty()[slot].toJsonListOfMaps().map { it[key] }
    fun replaced(editor: Map<String, Any?>) =
        editor[CFEP.replacements].toJsonListOfMaps().map { "${it[CFEP.name]}/${it[CFEP.slot]}/${it[CFEP.key]}" }

    "a code-defined client shows its own code, then its template's copies, and what its code replaces" {
        val editor = admin(kid).getItem(CFEP.editor)
        val bundles = codeBundles(editor)
        bundles.map { "${it[CFEP.origin]}:${it[CFEP.name]}" } shouldContainExactly listOf("code:kid", "template:from_${tpl}_base")
        bundles.last()[CFEP.template] shouldBe tpl
        // The copy is shown whole, as the template declares it (issue #1646): what the client's own code replaces of it
        // is said by the replacements, not cut out of it.
        val copy = bundles.last()
        slotKeys(copy, CCT.traitDef, CCT.traitId) shouldContainExactly listOf("tplNote", "tplScore")
        slotKeys(copy, CCT.reportDef, CCT.reportId) shouldContainExactly listOf("notes", "overview")
        slotKeys(copy, CCT.workflowDef, CCT.workflowId) shouldContainExactly listOf("tplFlow")
        // The template's client definition is never copied: the client defines itself.
        copy[CFEP.slots].toJsonMapOrEmpty().containsKey(CCT.clientDef) shouldBe false

        replaced(editor) shouldContainExactlyInAnyOrder listOf(
            "kid/${CCT.traitDef}/{traitId=tplScore}",
            "kid/${CCT.reportDef}/{reportId=overview}",
        )
        val score = editor[CFEP.replacements].toJsonListOfMaps().single { it[CFEP.slot] == CCT.traitDef }
        score[CFEP.template] shouldBe tpl
        score[CFEP.origin] shouldBe CFEP.originTemplate
        score[CFEP.codeName] shouldBe "from_${tpl}_base"
        score[CFEP.codeEntries].toJsonListOfMaps().single()[CCT.typeName] shouldBe "client.tplbase.TplScoreEntry"
        // The client's own code over its template's needs no mark.
        score[CFEP.marked] shouldBe true
    }

    "a client that extends nothing shows only its own code, and replaces nothing" {
        val editor = admin(ExtensionTemplateComponent.plainClient).getItem(CFEP.editor)
        codeBundles(editor).map { "${it[CFEP.origin]}:${it[CFEP.name]}" } shouldContainExactly listOf("code:plain")
        replaced(editor) shouldBe emptyList()
    }

    "a stored client's replacements are found in what it stores" {
        val client = "codelayerdata"
        store(client) {
            trait("DataNoteEntry", "tplNote", setOf(GedraDataType.formDoc), "The client's note.") { property("memo", "A memo.") }
            report("notes", "Memos") { column("memo", "Memo", "form.tplNote.memo") }
            overridesCode("tplNote", "notes")
        }
        val editor = admin(client).getItem(CFEP.editor)
        // A stored client has no code of its own: only the template's copy, whole, with what the client stores over it.
        val bundles = codeBundles(editor)
        bundles.map { "${it[CFEP.origin]}:${it[CFEP.name]}" } shouldContainExactly listOf("template:from_${tpl}_base")
        slotKeys(bundles.single(), CCT.traitDef, CCT.traitId) shouldContainExactly listOf("tplNote", "tplScore")
        editor[CFEP.replacements].toJsonListOfMaps().all { it[CFEP.marked] == true } shouldBe true
        replaced(editor) shouldContainExactlyInAnyOrder listOf(
            "main/${CCT.traitDef}/{traitId=tplNote}",
            "main/${CCT.reportDef}/{reportId=notes}",
        )
    }

    "a creation workflow of the client's replaces its template's by its kind, whatever its id" {
        val client = "codelayerkind"
        store(client, template = ExtensionTemplateComponent.publicTemplate) {
            workflow("myCreate", WfEntry.creation) { task("n", "Name it") { trait(GT.name); save("c", "Create") } }
            overridesCode("myCreate")
        }
        val found = admin(client).getItem(CFEP.editor)[CFEP.replacements].toJsonListOfMaps().single()
        "${found[CFEP.name]}/${found[CFEP.slot]}/${found[CFEP.key]}" shouldBe "main/${CCT.workflowDef}/{workflowId=myCreate}"
        found[CFEP.codeEntries].toJsonListOfMaps().single()[CCT.workflowId] shouldBe ExtensionTemplateComponent.creationWorkflow
    }

    "a dry run says what a patch would newly replace, and nothing for an entry the template does not have" {
        val client = "codelayerpatch"
        store(client)
        val chief = admin(client)
        fun dryRun(slot: String, data: Map<String, Any?>) = chief.postData(
            CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "flows",
                        CFEP.edits to listOf(mapOf(CFEP.slot to slot, GED.action to GedraEditAction.addOrReplace.name, GE.data to data)),
                    ),
                ),
                CFEP.dryRun to true,
            ),
        )[CFEP.replacements].toJsonListOfMaps()

        fun flowEntry(id: String, overCode: Boolean = false) = gedraConfigToEntries(
            gedraConfig(cxt, "flows", "${clientNamespace(client)}.flows", client) {
                workflow(id, WfEntry.normal) { task("a", "Only A") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) } }
                if (overCode) overridesCode(id)
            },
        ).getValue(CCT.workflowDef).single()

        // Unmarked, the template's workflow restated under its id is refused (issue #1634), saying where the code's is.
        val refused = chief.expectError(
            EXC.badInput, CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "flows",
                        CFEP.edits to listOf(
                            mapOf(
                                CFEP.slot to CCT.workflowDef, GED.action to GedraEditAction.addOrReplace.name,
                                GE.data to flowEntry("tplFlow"),
                            ),
                        ),
                    ),
                ),
                CFEP.dryRun to true,
            ),
        ).toString()
        refused shouldContain "holds workflow 'tplFlow', which template '$tpl''s 'base' also declares"
        refused shouldContain "\"${CCT.overrides}\": \"${CCT.overridesCodeValue}\""

        // Marked, the patch would replace the template's whole, and the dry run says so.
        val found = dryRun(CCT.workflowDef, flowEntry("tplFlow", overCode = true))
        found.map { "${it[CFEP.name]}/${it[CFEP.slot]}/${it[CFEP.key]}" } shouldContainExactly
            listOf("flows/${CCT.workflowDef}/{workflowId=tplFlow}")
        found.single()[CFEP.codeEntries].toJsonListOfMaps().single()[CCT.workflowId] shouldBe "tplFlow"

        // A workflow of the client's own, with an id the template does not use, replaces nothing.
        dryRun(CCT.workflowDef, flowEntry("ownFlow")) shouldBe emptyList()
    }

    "moving an entry that already replaces the template's to another configuration is not a new replacement" {
        val client = "codelayermove"
        // The field keeps the template's name, so the template's `notes` report still reads it.
        store(client) {
            trait("MoveNoteEntry", "tplNote", setOf(GedraDataType.formDoc), "The client's note.") { property("note", "A note.") }
            overridesCode("tplNote")
        }
        val chief = admin(client)
        val note = chief.getItem(CFEP.bundle, mapOf(CFEP.name to "main"))[CFEP.slots].toJsonMapOrEmpty()[CCT.traitDef]
            .toJsonListOfMaps().single()
        fun edit(action: GedraEditAction, data: Map<String, Any?>) =
            mapOf(CFEP.slot to CCT.traitDef, GED.action to action.name, GE.data to data)
        val moved = chief.postData(
            CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "main",
                        CFEP.edits to listOf(edit(GedraEditAction.deleteOrNoOp, mapOf(CCT.traitId to "tplNote"))),
                    ),
                    mapOf(CFEP.name to "notes", CFEP.edits to listOf(edit(GedraEditAction.addOrReplace, note))),
                ),
                CFEP.dryRun to true,
            ),
        )
        moved[CFEP.replacements].toJsonListOfMaps() shouldBe emptyList()
    }

    "the columns of the forms list replace the template's in each configuration holding some" {
        val theirs = listOf("base" to mapOf(CCT.usageDef to listOf(mapOf(CCT.traitId to "tplNote"))))
        val own = listOf(
            "one" to mapOf(CCT.usageDef to listOf(mapOf(CCT.traitId to "a"))),
            "none" to mapOf(CCT.traitDef to listOf(mapOf(CCT.traitId to "b"))),
            "two" to mapOf(CCT.usageDef to listOf(mapOf(CCT.traitId to "c"))),
        )
        val found = ConfigCodeLayer.replacements(
            theirs.map { (n, slots) -> CodeSide(n, CFEP.originTemplate, tpl, slots) },
            own.map { (n, slots) -> OverSide(n, slots, stored = true) },
            emptyMap(),
        ) { null }
        found.map { it.name } shouldContainExactly listOf("one", "two")
        // Unmarked, a stored set of columns says so.
        found.all { !it.marked } shouldBe true
        // One replacement wherever it is held, so a dry run counts it once.
        found.map { it.address }.distinct().size shouldBe 1
    }

    // Issue #1646: a client's own code that a stored entry stands over is shown whole, and the entry is a replacement of
    // it, marked -- where the load cuts it.
    "a client's own code is shown as declared, with a stored entry over it as a marked replacement" {
        val own = ExtensionTemplateComponent.ownLayer
        val writer = cxt.mkSubContext("codeLayerOwn", own).also { it.userId = 16461L }
        GedraConfigService.get(cxt).writeConfig(
            writer,
            gedraConfig(cxt, "own", "${clientNamespace(own)}.own", own) {
                trait("StoredNoteEntry", "ownNote", setOf(GedraDataType.formDoc), "The stored note.") { property("memo", "A memo.") }
                overridesCode("ownNote")
            },
            trial = true,
        )
        val editor = admin(own).getItem(CFEP.editor)
        val code = codeBundles(editor).single()
        code[CFEP.name] shouldBe "own"
        slotKeys(code, CCT.traitDef, CCT.traitId) shouldContainExactly listOf("ownNote")
        val r = editor[CFEP.replacements].toJsonListOfMaps().single()
        "${r[CFEP.name]}/${r[CFEP.codeName]}/${r[CFEP.origin]}/${r[CFEP.marked]}" shouldBe "own/own/${CFEP.originCode}/true"
        // The stored bundle shares the code bundle's name, and both are the editor's to show.
        editor[CFEP.configs].toJsonListOfMaps().map { it[CFEP.name] } shouldContain "own"
    }

    "a code configuration with state traits is shown without them, not refused" {
        val collector = SchemaCollector.get(cxt).shouldNotBeNull()
        val withState = collector.gedraConfigs.configs.first { it.stateTraits.isNotEmpty() }
        val slots = ConfigCodeLayer.slotsOf(withState)
        val stateTypes = withState.stateTraits.values.map { it.typeName }.toSet()
        slots[CCT.schemaDef].orEmpty().none { it[CCT.typeName] in stateTypes } shouldBe true
    }
}

/**
 * A client's stored overlay of its code (issue #1657): an entry marked `"overrides": "codeOverlay"` holds only what it
 * changes of a code workflow or type, is stored as written, and is merged over the code as it is now on every read -- so
 * a later change to the code reaches the parts the client left alone. One that does not merge is refused at write and by
 * a patch's dry run; one the code has since moved out from under is forgiven at load, part by part, and reported.
 */
private fun codeOverlaysTests() = stringSpec {
    val tpl = ExtensionTemplateComponent.template
    val own = ExtensionTemplateComponent.ownCode
    val cxt = TestInstances.extensionTemplate("codeOverlays1657")

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("overlayWrite", client).also { it.userId = 16570L }

    /** An overlay of the code's workflow [id]: only [definition], which it changes. */
    fun flowOverlay(id: String, definition: Map<String, Any?>): Map<String, Any?> = mapOf(
        CCT.workflowId to id, CCT.overrides to CCT.overridesCodeOverlayValue, CCT.definition to mapOf(WFD.workflowId to id) + definition,
    )

    /** What changes task [task]'s label to [label], and nothing else. */
    fun relabel(task: String, label: String): Map<String, Any?> = mapOf(WFD.tasks to listOf(mapOf(WFD.id to task, WFD.label to label)))

    /**
     * Writes [client]'s stored config [name] holding [entries] beside what [build] declares, defined on [template] when
     * given. The overlays pass the write's reassembly as written, as they do through the bundle endpoints.
     */
    fun store(
        on: KdrCxt,
        client: String,
        entries: Map<String, List<Map<String, Any?>>>,
        name: String = "main",
        template: String? = null,
        trial: Boolean = true,
    ) {
        val namespace = "${clientNamespace(client)}.$name"
        val declared = gedraConfig(on, name, namespace, client) {
            template?.let {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = it,
                    ),
                )
            }
        }
        val slots = gedraConfigToEntries(declared).toMutableMap()
        entries.forEach { (slot, added) -> slots[slot] = slots[slot].orEmpty() + added }
        GedraConfigService.get(on).writeConfig(writer(on, client), reassembleGedraConfig(on, name, namespace, client, slots), trial = trial)
    }

    /** Publishes [client]'s config [name]: Design View saves only into a configuration with nothing unpublished. */
    fun publish(on: KdrCxt, client: String, name: String = "main") =
        GedraConfigService.get(on).publish(writer(on, client), listOf(GedraId.of(GedraConfigType.configDoc, client, name)))

    fun flow(on: KdrCxt, client: String, id: String) = WorkflowService.get(on).forClient(client).workflow(id).shouldNotBeNull().def

    fun storedEntries(on: KdrCxt, client: String, slot: String, name: String = "main") =
        GedraConfigService.get(on).currentConfigs(writer(on, client), client).single { it.configId.baseId == name }
            .entriesBySlot()[slot].orEmpty()

    "an overlay of a template's workflow changes one task's label, keeps the rest, and is stored as written" {
        val client = "ovltpl1657"
        val overlay = flowOverlay("tplFlow", relabel("a", "The client's A"))
        store(cxt, client, mapOf(CCT.workflowDef to listOf(overlay)), template = tpl)
        GedraConfigReload.reloadClient(cxt, client).issues shouldBe emptyList()

        val task = flow(cxt, client, "tplFlow").tasks.single()
        task.label shouldBe "The client's A"
        // What the overlay does not mention is the template's.
        task.traits.map { it.traitId } shouldContainExactly listOf("tplNote")
        task.saves.map { it.id } shouldContainExactly listOf("s")
        // Stored as written, never as the definition it merges into.
        storedEntries(cxt, client, CCT.workflowDef).single() shouldBe overlay
    }

    "an overlay of the client's own code's workflow and type runs in the client and its sandbox" {
        val typeName = "client.$own.OwnShared"
        // A property changed and one added; the code's other one stays.
        val typeOverlay = mapOf(
            CCT.typeName to typeName, CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.schema to mapOf(
                SCH.properties to mapOf(
                    "size" to mapOf(SCH.description to "A size, in meters.", SCH.type to "number"),
                    "color" to mapOf(SCH.description to "A color."),
                ),
            ),
        )
        val relabeled = flowOverlay("ownFlow", relabel("a", "The stored task"))
        store(cxt, own, mapOf(CCT.workflowDef to listOf(relabeled), CCT.schemaDef to listOf(typeOverlay)), name = "own")
        GedraConfigService.get(cxt).publish(writer(cxt, own), listOf(GedraId.of(GedraConfigType.configDoc, own, "own")))
        GedraConfigReload.reloadClient(cxt, own)

        for (client in listOf(own, sandboxOf(own))) {
            flow(cxt, client, "ownFlow").tasks.single().label shouldBe "The stored task"
            val properties = SchemaService.get(cxt).storeFor(client).types.getValue(typeName).properties
            properties.keys.toList() shouldContainExactly listOf("code", "size", "color")
            properties.getValue("size").description shouldBe "A size, in meters."
        }

        // Without the overlays, the code's are back.
        store(cxt, own, emptyMap(), name = "own")
        GedraConfigService.get(cxt).publish(writer(cxt, own), listOf(GedraId.of(GedraConfigType.configDoc, own, "own")))
        GedraConfigReload.reloadClient(cxt, own)
        flow(cxt, own, "ownFlow").tasks.single().label shouldBe "Code's task"
        SchemaService.get(cxt).storeFor(own).types.getValue(typeName).properties.keys.toList() shouldContainExactly listOf("code", "size")
    }

    "an overlay that does not merge over the code is refused at write, and by a patch's dry run" {
        val client = "ovlbad1657"
        store(cxt, client, emptyMap(), template = tpl)
        GedraConfigReload.reloadClient(cxt, client)
        val stray = flowOverlay("tplFlow", relabel("zz", "Nothing"))
        shouldThrow<KdrException> {
            store(cxt, client, mapOf(CCT.workflowDef to listOf(stray)), template = tpl)
        }.message.shouldNotBeNull() shouldContain "'zz' matches no entry here"
        // An overlay of a workflow the code does not declare is refused too.
        shouldThrow<KdrException> {
            store(cxt, client, mapOf(CCT.workflowDef to listOf(flowOverlay("noSuchFlow", relabel("a", "A")))), template = tpl)
        }.message.shouldNotBeNull() shouldContain "declares no workflow 'noSuchFlow'"

        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val refused = chief.expectError(
            EXC.badInput, CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "main",
                        CFEP.edits to listOf(
                            mapOf(CFEP.slot to CCT.workflowDef, GED.action to GedraEditAction.addOrReplace.name, GE.data to stray),
                        ),
                    ),
                ),
                CFEP.dryRun to true,
            ),
        ).toString()
        refused shouldContain "'zz' matches no entry here"
    }

    "the mark's overlay value is refused on a slot that only takes a whole override" {
        val client = "ovlslot1657"
        store(cxt, client, emptyMap(), template = tpl)
        GedraConfigReload.reloadClient(cxt, client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val refused = chief.expectError(
            EXC.badInput, CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "main",
                        CFEP.edits to listOf(
                            mapOf(
                                CFEP.slot to CCT.cfactDef, GED.action to GedraEditAction.addOrReplace.name,
                                GE.data to mapOf(
                                    CCT.name to "tplFlag", CCT.group to "tpl", CCT.description to "Changed.",
                                    CCT.overrides to CCT.overridesCodeOverlayValue,
                                ),
                            ),
                        ),
                    ),
                ),
                CFEP.dryRun to true,
            ),
        ).toString()
        refused shouldContain "kdr:cfactDef[tplFlag].overrides"
    }

    "an overlay of a template's type changes it for the client, and Design View keeps only the change" {
        val client = "ovltype1657"
        val typeName = "client.$tpl.Shared"
        val overlay = mapOf(
            CCT.typeName to typeName, CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.schema to mapOf(SCH.properties to mapOf("extra" to mapOf(SCH.description to "Something extra."))),
        )
        // In the stored bundle named like the template's copy, as the bundle editor files it.
        store(cxt, client, emptyMap(), template = tpl)
        store(cxt, client, mapOf(CCT.schemaDef to listOf(overlay)), name = "from_${tpl}_base")
        publish(cxt, client)
        publish(cxt, client, "from_${tpl}_base")
        GedraConfigReload.reloadClient(cxt, client).issues shouldBe emptyList()
        SchemaService.get(cxt).storeFor(client).types.getValue(typeName).properties.keys.toList() shouldContainExactly
            listOf("code", "extra")
        // The template keeps its own.
        SchemaService.get(cxt).storeFor(tpl).types.getValue(typeName).properties.keys.toList() shouldContainExactly listOf("code")

        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        // The listing says which bundle holds changes to which code, so the Publishing table can.
        val listed = chief.getItems(CFEP.bundles).associateBy { it[CFEP.name] }
        listed.getValue("main")[CFEP.changesCode] shouldBe null
        val copy = listed.getValue("from_${tpl}_base")
        copy[CFEP.changesCode] shouldBe true
        copy[CFEP.template] shouldBe tpl

        // A heading set in Design View is edited on the whole type and stored as part of the change.
        chief.postData(
            DSV.sharedHeadingEdit,
            mapOf(DSV.typeName to typeName, DSV.label to "Shared things", DSV.sharedBasedOn to DesignSharedEdit.stampOf(overlay)),
        )
        storedEntries(cxt, client, CCT.schemaDef, "from_${tpl}_base").single()[CCT.schema] shouldBe mapOf(
            SCH.properties to mapOf("extra" to mapOf(SCH.description to "Something extra.")),
            SCH.layout to mapOf(SL.label to "Shared things"),
        )
    }

    "Design View edits an overlay of a code workflow as it runs, and keeps only the change" {
        val client = "ovldesign1657"
        store(cxt, client, mapOf(CCT.workflowDef to listOf(flowOverlay("tplFlow", relabel("a", "The client's A")))), template = tpl)
        publish(cxt, client)
        GedraConfigReload.reloadClient(cxt, client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        chief.postData(
            DSV.labelEdit,
            mapOf(
                DSV.workflowId to "tplFlow", DSV.taskId to "a", DSV.saveId to "s", DSV.label to "Keep it",
                DSV.basedOn to workflowDefStamp(flow(cxt, client, "tplFlow")),
            ),
        )
        val task = flow(cxt, client, "tplFlow").tasks.single()
        task.label shouldBe "The client's A"
        task.saves.single().label shouldBe "Keep it"
        storedEntries(cxt, client, CCT.workflowDef).single()[CCT.definition] shouldBe mapOf(
            WFD.workflowId to "tplFlow",
            WFD.tasks to listOf(
                mapOf(WFD.id to "a", WFD.label to "The client's A", WFD.saves to listOf(mapOf(WFD.id to "s", WFD.label to "Keep it"))),
            ),
        )
    }

    // Issue #1670: Design View changes the client's own code by storing an overlay of it, in the bundle named like the code
    // bundle, as the bundle editor does.
    "Design View's first edit of a code workflow stores an overlay of it, and its next edit rewrites that overlay" {
        val client = "dvflow1670"
        store(cxt, client, emptyMap(), template = tpl)
        publish(cxt, client)
        GedraConfigReload.reloadClient(cxt, client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        // Before any change, the workflow reads as the template's code, though a data-defined client files its copy with a
        // stored origin.
        val before = chief.getItem(DSV.definition, mapOf(DSV.slot to CCT.workflowDef, DSV.key to "tplFlow"))
        before[DSV.origin] shouldBe DesignOrigin.source.name
        before[DSV.config] shouldBe "from_${tpl}_base"
        fun relabel(taskId: String, saveId: String?, label: String) = chief.postData(
            DSV.labelEdit,
            buildMap {
                put(DSV.workflowId, "tplFlow"); put(DSV.taskId, taskId); saveId?.let { put(DSV.saveId, it) }; put(DSV.label, label)
                put(DSV.basedOn, workflowDefStamp(flow(cxt, client, "tplFlow")))
            },
        )
        relabel("a", "s", "Keep it")
        val copy = "from_${tpl}_base"
        storedEntries(cxt, client, CCT.workflowDef, copy).single() shouldBe mapOf(
            CCT.workflowId to "tplFlow", CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.definition to mapOf(
                WFD.workflowId to "tplFlow",
                WFD.tasks to listOf(mapOf(WFD.id to "a", WFD.saves to listOf(mapOf(WFD.id to "s", WFD.label to "Keep it")))),
            ),
        )
        // The definition read says the stored entry only changes the code's.
        chief.getItem(DSV.definition, mapOf(DSV.slot to CCT.workflowDef, DSV.key to "tplFlow"))[DSV.changesCode] shouldBe true
        relabel("a", null, "A, ours")
        val task = flow(cxt, client, "tplFlow").tasks.single()
        task.label shouldBe "A, ours"
        task.saves.single().label shouldBe "Keep it"
        storedEntries(cxt, client, CCT.workflowDef, copy).single()[CCT.definition] shouldBe mapOf(
            WFD.workflowId to "tplFlow",
            WFD.tasks to listOf(
                mapOf(WFD.id to "a", WFD.label to "A, ours", WFD.saves to listOf(mapOf(WFD.id to "s", WFD.label to "Keep it"))),
            ),
        )
        // A layout change goes into the same overlay, beside the labels, and runs.
        chief.postData(
            DSV.layoutEntryEdit,
            mapOf(
                DSV.workflowId to "tplFlow", DSV.typeName to "client.$tpl.TplNoteData", DSV.field to "note",
                DSV.entry to mapOf(SL.label to "Your note"), DSV.basedOn to workflowDefStamp(flow(cxt, client, "tplFlow")),
            ),
        )
        val withLayout = storedEntries(cxt, client, CCT.workflowDef, copy).single()
        withLayout[CCT.overrides] shouldBe CCT.overridesCodeOverlayValue
        val stored = withLayout[CCT.definition].toJsonMapOrEmpty()
        stored[WFD.tasks] shouldBe listOf(
            mapOf(WFD.id to "a", WFD.label to "A, ours", WFD.saves to listOf(mapOf(WFD.id to "s", WFD.label to "Keep it"))),
        )
        stored.toString() shouldContain "Your note"
        flow(cxt, client, "tplFlow").toJsonMap().toString() shouldContain "Your note"
    }

    "Design View's first edit of a code type stores an overlay of it; a trait declared in code stays whole-only" {
        val client = "dvtype1670"
        val typeName = "client.$tpl.Shared"
        store(cxt, client, emptyMap(), template = tpl)
        publish(cxt, client)
        GedraConfigReload.reloadClient(cxt, client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val read = chief.getItem(DSV.definition, mapOf(DSV.slot to CCT.schemaDef, DSV.key to typeName))
        read[DSV.canEditShared] shouldBe true
        chief.postData(
            DSV.sharedHeadingEdit,
            mapOf(DSV.typeName to typeName, DSV.label to "Shared things", DSV.sharedBasedOn to read[DSV.sharedBasedOn]),
        )
        storedEntries(cxt, client, CCT.schemaDef, "from_${tpl}_base").single() shouldBe mapOf(
            CCT.typeName to typeName, CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.schema to mapOf(SCH.layout to mapOf(SL.label to "Shared things")),
        )
        // The code's type, with the heading, is what the client runs.
        SchemaService.get(cxt).storeFor(client).types.getValue(typeName).properties.keys.toList() shouldContainExactly listOf("code")

        // A trait's data type declared in code is not edited here: a trait changes only whole.
        val trait = chief.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to "tplNote"))
        trait[DSV.canEditShared] shouldBe false
    }

    // Its own instance (issue #1670 review), since it refuses publishing for some clients and changes the template's code.
    "Design View: a refused first save leaves no bundle behind, and a stale overlay is refused as stale" {
        val refusing: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val design = Startup.mkTestBootCxt(
            "codeOverlayDesign", "kdnCodeOverlayDesign1670",
            mapOf(
                GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name,
                ClientStoredEdit.publishCheckConfigKey to ClientStoredEdit.PublishCheck { _, client, _ ->
                    if (client in refusing) throw KdrException.mkInput("Publishing is refused for this test.")
                },
            ),
            listOf(ExtensionTemplateComponent()),
        )
        val copy = "from_${tpl}_base"
        fun bundles(client: String) =
            GedraConfigService.get(design).listConfigs(writer(design, client)).map { it.configId.baseId }

        // A first label edit whose publish is refused: the bundle it made is gone again, not left empty.
        val refused = "dvundo1670"
        store(design, refused, emptyMap(), template = tpl)
        publish(design, refused)
        GedraConfigReload.reloadClient(design, refused)
        refusing.add(refused)
        val chief = TestUser.create(design, "chief@$refused.test", level = ROLE.admin, userClient = refused)
        chief.expectError(
            EXC.badInput, DSV.labelEdit,
            mapOf(
                DSV.workflowId to "tplFlow", DSV.taskId to "a", DSV.label to "Refused",
                DSV.basedOn to workflowDefStamp(flow(design, refused, "tplFlow")),
            ),
        ).toString() shouldContain "Publishing is refused for this test."
        bundles(refused) shouldBe listOf("main")
        flow(design, refused, "tplFlow").tasks.single().label shouldBe "A"

        // A type overlay the code moves out from under: the shared editor says it is stale, by a code of its own.
        val collector = SchemaCollector.get(design).shouldNotBeNull()
        val typeName = "client.$tpl.Shared"
        fun deployShared(field: String) {
            val held = collector.gedraConfigs.configs.single { it.gedraId.client == tpl && it.name == "base" }
            val body = mapOf(
                SCH.type to "object",
                SCH.properties to mapOf(field to mapOf(SCH.description to "A code.")),
                SCH.layout to mapOf(SL.mode to SLM.authoritative, SL.schemaFields to listOf(mapOf(SL.field to field))),
            )
            collector.replaceGedraConfig(held, held.copy(defs = held.defs + (typeName to body))) shouldBe true
        }
        deployShared("code")
        val stale = "dvstale1670"
        val overlay = mapOf(
            CCT.typeName to typeName, CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.schema to mapOf(SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "code", SL.label to "Code, ours")))),
        )
        store(design, stale, emptyMap(), template = tpl)
        store(design, stale, mapOf(CCT.schemaDef to listOf(overlay)), name = copy)
        publish(design, stale)
        publish(design, stale, copy)
        GedraConfigReload.reloadClient(design, stale)
        val admin = TestUser.create(design, "chief@$stale.test", level = ROLE.admin, userClient = stale)
        fun read() = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.schemaDef, DSV.key to typeName))
        read()[DSV.canEditShared] shouldBe true
        deployShared("kode")
        GedraConfigReload.reloadClient(design, stale)
        val after = read()
        after[DSV.canEditShared] shouldBe false
        after[DSV.sharedRefusalCode] shouldBe DesignRefusal.staleOverlay.name
        after[DSV.sharedRefusal].toOptStr().shouldNotBeNull() shouldContain "Fix or remove them in the bundle editor first"
    }

    // Its own instance, since it changes the template's code as a deploy would.
    "a change to the code reaches an overlay of it; a part the code no longer has is dropped and reported" {
        val changing = Startup.mkTestBootCxt(
            "codeOverlayChange", "kdnCodeOverlayChange1657", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
            listOf(ExtensionTemplateComponent()),
        )
        val client = "ovlchange1657"
        store(changing, client, mapOf(CCT.workflowDef to listOf(flowOverlay("tplFlow", relabel("a", "The client's A")))), template = tpl)
        // Beside it, a client that detached the workflow from the code: stored whole, as it read when it detached.
        val detached = "ovldetach1657"
        store(
            changing, detached,
            mapOf(
                CCT.workflowDef to listOf(
                    gedraConfigToEntries(
                        gedraConfig(changing, "main", "${clientNamespace(detached)}.main", detached) {
                            workflow("tplFlow", WfEntry.normal) {
                                task("a", "Detached A") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) }
                            }
                            overridesCode("tplFlow")
                        },
                    ).getValue(CCT.workflowDef).single(),
                ),
            ),
            template = tpl,
        )

        val collector = SchemaCollector.get(changing).shouldNotBeNull()
        /** Deploys template code whose `tplFlow` is what [build] declares. */
        fun deploy(build: WfDefBuilder.() -> Unit) {
            val held = collector.gedraConfigs.configs.single { it.gedraId.client == tpl && it.name == "base" }
            val changed = gedraConfig(changing, "base", "client.$tpl", tpl) { workflow("tplFlow", WfEntry.normal, build) }
                .workflows.getValue("tplFlow")
            collector.replaceGedraConfig(held, held.copy(workflows = held.workflows + ("tplFlow" to changed))) shouldBe true
            GedraConfigReload.reloadClient(changing, client)
            GedraConfigReload.reloadClient(changing, detached)
        }

        // The code relabels the workflow and gives the task a second save: both reach the overlay.
        deploy {
            label = "Template flow, revised"
            task("a", "A") { trait("tplNote"); save("s", "Save", WfSaveKind.edit); save("t", "Save and close", WfSaveKind.edit) }
        }
        val revised = flow(changing, client, "tplFlow")
        revised.label shouldBe "Template flow, revised"
        revised.tasks.single().label shouldBe "The client's A"
        revised.tasks.single().saves.map { it.id } shouldContainExactly listOf("s", "t")
        ClientConfigIssues.get(changing).issuesFor(client) shouldBe emptyList()
        // The detached client stopped following the code, by choice.
        flow(changing, detached, "tplFlow").tasks.single().saves.map { it.id } shouldContainExactly listOf("s")

        // The code renames the task: the overlay's change of it has nothing to target, and is dropped and reported.
        deploy { task("b", "B") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) } }
        flow(changing, client, "tplFlow").tasks.map { it.label } shouldContainExactly listOf("B")
        val said = ClientConfigIssues.get(changing).issuesFor(client).map { it.message }
        said.single() shouldContain "changes the code's workflow 'tplFlow' with parts the code no longer has a place for"
        said.single() shouldContain "'a' matches no entry here"
        BootCheckRegistry.get(changing).results().single { it.name == BCHK.storedConfig }.findings
            .any { "'a' matches no entry here" in it } shouldBe true
        // Kept as stored, so the client's change is there to fix or remove.
        storedEntries(changing, client, CCT.workflowDef).single()[CCT.definition].toJsonMapOrEmpty()[WFD.tasks] shouldBe
            listOf(mapOf(WFD.id to "a", WFD.label to "The client's A"))
        // Design View, which edits it whole, refuses rather than drop that part unseen, and points to the bundle editor.
        publish(changing, client)
        val chief = TestUser.create(changing, "chief@$client.test", level = ROLE.admin, userClient = client)
        chief.expectError(
            EXC.badInput, DSV.labelEdit,
            mapOf(
                DSV.workflowId to "tplFlow", DSV.taskId to "b", DSV.label to "B, mine",
                DSV.basedOn to workflowDefStamp(flow(changing, client, "tplFlow")),
            ),
        ).toString() shouldContain "Fix or remove them in the bundle editor first"
    }
}
