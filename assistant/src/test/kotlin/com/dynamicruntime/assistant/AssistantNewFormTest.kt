package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The assistant and a client's New form (issue #1569): the model is told what the creation workflow is, and what kind
 * of client this is -- configured, built on a code-defined base, or defined in code -- and a scripted turn changes what
 * a self-serve client's starter New form asks for, as a draft its sandbox runs. An instance of its own, with a
 * code-defined base to build on.
 */
class AssistantNewFormTest : StringSpec({
    val model = ScriptedModel(emptyList())
    val cxt: KdrCxt = Startup.mkTestBootCxt(
        "assistantNewForm1569", "assistantNewForm1569",
        mapOf(AssistantService.modelConfigKey to model, AENV.selfServe.name to "true"),
        additionalComponents = listOf(AssistantComponent(), NewFormBaseComponent()),
    )
    val service = AssistantService.get(cxt)
    beforeEach { model.clear() }

    /** A person's own client, the assistant switched on for it, and the person: its administrator there. */
    fun ownClient(email: String, base: String? = null): Pair<String, TestUser> {
        val person = TestUser.create(cxt, email)
        val body = buildMap {
            put(CLD.name, "Shop")
            base?.let { put(CLD.extendsFromClientId, it) }
        }
        val client = person.postData(AEP.createOwnClient, body)[CLD.client].toOptStr().shouldNotBeNull()
        service.setClient(cxt, client, enabled = true, monthlyBudget = null, useDefaultBudget = false, acknowledgedNotice = true)
        return client to person
    }

    "the model is told what kind of client it works for, and where its New form comes from" {
        // Configured: a person's own blank client, whose starter New form is its own.
        val (configured, _) = ownClient("blank@asst1569.test")
        AssistantPrompt.clientLine(cxt, configured).let {
            it shouldContain "is configured"
            it shouldContain "Its creation workflow is its own"
        }
        // Built on a code-defined base with a New form: it inherits that one.
        val (built, _) = ownClient("built@asst1569.test", base = NewFormBaseComponent.base)
        AssistantPrompt.clientLine(cxt, built).let {
            it shouldContain "is built on '${NewFormBaseComponent.base}'"
            it shouldContain "Its creation workflow is inherited from '${NewFormBaseComponent.base}'"
        }
        // Built on one with none: nothing is inherited, and a template takes no starter, so there is none to change.
        val (bare, _) = ownClient("bare@asst1569.test", base = NewFormBaseComponent.bareBase)
        AssistantPrompt.clientLine(cxt, bare).let {
            it shouldContain "is built on '${NewFormBaseComponent.bareBase}'"
            it shouldContain "has no creation workflow yet"
            it shouldNotContain "inherited from"
        }
        // Defined in code.
        AssistantPrompt.clientLine(cxt, CL.hub) shouldContain "is defined in code"
        // Each in the system prompt, beside what the New form is; and a changed line is a version of its own, so a
        // conversation begun under one kind is not continued under another.
        val text = AssistantPrompt.system(cxt, configured).single()[MSG.text].toString()
        text shouldContain "is configured"
        text shouldContain "The New form page asks for exactly the traits of the client's creation workflow"
        // Long text (issue #1592): a trait it writes marks its free text, so a form draws a box of several lines.
        text shouldContain "Give it \"g-longText\": true in the trait's dataSchema"
        // And the message the administrator writes is long text too, so the endpoint catalog draws a box for it.
        SchemaService.get(cxt).schemaStore.endpoints.values.single { it.path == ACNV.messagePath }.inputFields.orEmpty()
            .single { it.name == ACNV.text }.schema[SCH.longText] shouldBe true
        AssistantPrompt.versionFor(configured, "one line") shouldNotBe AssistantPrompt.versionFor(configured, "another line")
        AssistantPrompt.versionOf(cxt, configured) shouldBe
            AssistantPrompt.versionFor(configured, AssistantPrompt.clientLine(cxt, configured))
    }

    "a scripted turn changes what a starter New form asks for, as a draft the sandbox runs" {
        val (client, admin) = ownClient("starter@asst1569.test")
        val starter = CWF.configPrefix + "newForm"
        val namespace = clientNamespace(client)
        val trait = mapOf(
            CCT.traitId to "rsvpNote", CCT.typeName to "$namespace.RsvpNote", CCT.appliesTo to listOf(GedraDataType.formDoc.name),
            CCT.dataSchema to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("note" to mapOf(SCH.type to SCT.string))),
        )
        val overlay = mapOf(
            WFD.workflowId to "newForm", WFD.fromTemplate to WFT.createForm, WFD.label to "New form",
            WFD.tasks to listOf(
                mapOf(
                    WFD.id to "identify", WFD.label to "Name the form",
                    WFD.traits to listOf(
                        mapOf(WFD.traitId to GT.name, WFD.required to true), mapOf(WFD.traitId to "rsvpNote", WFD.required to false),
                    ),
                ),
            ),
        )
        fun edit(slot: String, data: Map<String, Any?>) =
            mapOf(CFEP.slot to slot, GED.action to GedraEditAction.addOrReplace.name, GE.data to data)
        val configs = listOf(
            mapOf(
                CFEP.name to starter,
                CFEP.edits to listOf(
                    edit(CCT.traitDef, trait),
                    edit(CCT.workflowDef, mapOf(CCT.workflowId to "newForm", CCT.definition to overlay)),
                ),
            ),
        )
        model.add(
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to true)) },
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to false)) },
            { ScriptedModel.toolResponse(ATOOL.viewWorkflow to emptyMap()) },
            { ScriptedModel.textResponse("Your New form now asks for a note too.") },
        )
        val started = admin.postData(ACNV.messagePath, mapOf(ACNV.clientMessageId to "nf1", ACNV.text to "Ask for a note on New form."))
        service.awaitTurn(started[ACNV.turnId].toString())
        val results = admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to started[ACNV.conversationId]))[ACNV.messages]
            .toJsonListOfMaps().filter { it[ACNV.role] == MSG.user }.drop(1)
            .map { it[ACNV.content].toJsonListOfMaps().single { b -> b[MSG.type] == TOOL.toolResult } }
        results.forEach { it[TOOL.isError].shouldBeNull() }
        // The creation view the model read is the draft's.
        results[2][TOOL.content].toString() shouldContain "rsvpNote"

        fun asks(scope: String) =
            WorkflowService.get(cxt).forClient(scope).creation.shouldNotBeNull().def.tasks.single().traits.map { it.traitId }
        // The sandbox's New form asks for the note; the client's own, until it is published, does not.
        asks(sandboxOf(client)) shouldBe listOf(GT.name, "rsvpNote")
        asks(client) shouldBe listOf(GT.name)
    }
})

/** Code-defined clients a self-serve client may be built on: [base] with a New form of its own, [bareBase] with none. */
class NewFormBaseComponent : ComponentDefinition {
    override val providerName: String = "assistantNewFormBase"
    override val ownerRoot: String = "nfbase"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "base", "client.$base", base) {
            defineClient(
                ClientDef(
                    clientId = base, name = "New form base", usageType = ClientUsageType.template,
                    audience = ClientAudience.customer, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    includedTraits = listOf(GT.name), publicTemplate = true,
                ),
            )
            workflow("baseCreate", WfEntry.creation) { task("n", "Name") { trait(GT.name); save("c", "Create", WfSaveKind.create) } }
        },
        gedraConfig(cxt, "base", "client.$bareBase", bareBase) {
            defineClient(
                ClientDef(
                    clientId = bareBase, name = "Bare base", usageType = ClientUsageType.template,
                    audience = ClientAudience.customer, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    includedTraits = listOf(GT.name), publicTemplate = true,
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val base = "nfbase"
        const val bareBase = "nfbare"
    }
}
