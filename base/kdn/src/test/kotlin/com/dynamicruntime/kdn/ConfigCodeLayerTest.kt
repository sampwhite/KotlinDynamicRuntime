package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CodeSide
import com.dynamicruntime.common.gedra.ConfigCodeLayer
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.OverSide
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The bundle editor's code layer (issues #1616, #1646): a client's own code bundles and its template's copies on the
 * editor's read, as declared, each labeled by origin; which of the client's entries replace its code's, whole -- its own
 * code over its template's, and what it stores over either -- and whether each is marked; and a patch's dry run saying
 * what it would newly replace.
 */
class ConfigCodeLayerTest : StringSpec({
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
})
