package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfRef
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.WorkflowTemplates
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A workflow made from a global template, driven end to end (issues #953, #1120). A fixture component declares a
 * template under a test-only root, `wftest`: the workflow `wftest:review`, its task `draft`, and the trait it collects.
 * No client runs it as it is. The client here makes a workflow of its own from it, `noted`, which is stored as an
 * overlay of the template -- the one label it changes -- and read over the template at load; it is then viewed,
 * engaged, saved, and stored under the client's own id. A bare `review` of the client's own sits beside it.
 */
class RootedWorkflowTest : StringSpec({
    // Its own instance (issue #1075): RootedWorkflowComponent, a fixture component no shared entry has.
    val cxt = Startup.mkTestBootCxt(
        "rootedWorkflow953", "rootedWorkflow953",
        mapOf(RootedWorkflowComponent.loadFlag.name to "true"),
        additionalComponents = listOf(RootedWorkflowComponent()),
    )
    val client = "rootedwf953"
    val review = RootedWorkflowComponent.workflowId
    val note = RootedWorkflowComponent.traitId
    val noted = "noted"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(note),
            ),
        )
        trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
        // The client's own `review` -- bare, so its own; the template of that local name is not the client's.
        workflow("review", WfEntry.normal) {
            task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
        }
        // Made from the template: only what it changes.
        workflowTemplates = WorkflowTemplates.lookup(cxt)
        workflowFromMap(
            mapOf(
                WFD.workflowId to noted, WFD.fromTemplate to review,
                WFD.tasks to listOf(mapOf(WFD.id to RootedWorkflowComponent.taskId, WFD.label to "Write the note")),
            ),
        )
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 9530L }, config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    fun statesOf(result: Map<String, Any?>) = result[GDF.states].toJsonListOfMaps()
    fun entriesOf(states: List<Map<String, Any?>>, traitId: String) =
        states.filter { it[GE.traitId].toOptStr() == traitId }.map { it[GE.data].toJsonMapOrEmpty() }

    "the client runs its own workflows, one of them made from the template, and not the template itself" {
        val workflows = WorkflowService.get(cxt).forClient(client).workflows
        workflows.keys shouldContainExactly setOf("review", noted)
        val made = workflows.getValue(noted).def
        made.fromTemplate shouldBe review
        made.tasks.single().label shouldBe "Write the note"
        made.tasks.single().traits.map { it.traitId } shouldContainExactly listOf(note)
    }

    "what is stored of a workflow made from a template is its overlay" {
        val row = GedraConfigService.get(cxt).listConfigs(cxt.mkSubContext("read", client)).single { it.configId.baseId == "${client}cfg" }
        val stored = row.entriesBySlot().getValue(CCT.workflowDef).single { it[CCT.workflowId] == noted }[CCT.definition]
        stored shouldBe mapOf(
            WFD.workflowId to noted, WFD.fromTemplate to review,
            WFD.tasks to listOf(mapOf(WFD.id to RootedWorkflowComponent.taskId, WFD.label to "Write the note")),
        )
    }

    "a workflow made from a template is viewed, engaged, saved and stored under the client's own id" {
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!

        // The view's query names it by the client's id, and its task by the template's.
        val view = user.getData(GEP.workflowView, mapOf(WFD.workflowId to noted, GDF.gedraId to gid))
        view[WFD.workflowId] shouldBe noted
        view[WFD.tasks].toJsonListOfMaps().map { it[WFD.id].toOptStr() } shouldContainExactly
            listOf(RootedWorkflowComponent.taskId)

        val engaged = statesOf(user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to noted)))
        entriesOf(engaged, WFS.workflowEngagement).single()[WFD.workflowId] shouldBe noted

        val saved = user.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to noted, GDF.taskId to RootedWorkflowComponent.taskId, GDF.saveId to "saveNote",
                GDF.gedraId to gid, GDF.entries to listOf(mapOf(GE.traitId to note, GE.data to mapOf("text" to "first"))),
            ),
        )
        saved[WSF.saved] shouldBe true

        // The derived state is stored under the client's id, against the client's own configuration.
        val states = statesOf(user.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to gid)))
        val derived = entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == noted }
        val ref = WfRef.parse(derived[WFS.computedAgainstRef].toOptStr().shouldNotBeNull())
        ref.workflowId shouldBe noted
        ref.bundleId.client shouldBe client
        // The client's own `review` keeps a state entry of its own.
        entriesOf(states, WFS.workflowState).count { it[WFD.workflowId].toOptStr() == "review" } shouldBe 1
    }
})

/** Contributes, under the test-only root `wftest`, a global trait and a template -- a global normal workflow -- collecting it. */
class RootedWorkflowComponent : ComponentDefinition {
    override val providerName: String = "rootedWorkflowFixture"

    override val ownerRoot: String = root

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "rootedFlows", "$root.flows", GID.globalClient) {
            trait("NoteEntry", traitId, setOf(GedraDataType.formDoc), "A note the rooted workflow collects.") {
                property("text", "What it says.")
            }
            workflow(workflowId, WfEntry.normal) {
                task(taskId, "Draft it") { trait(traitId); save("saveNote", "Save", WfSaveKind.edit) }
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_ROOTED_WORKFLOW_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the rooted-workflow fixture component regardless of environment.",
        )
        const val root = "wftest"
        const val traitId = "$root:note"
        const val workflowId = "$root:review"
        // Bare: a template's task is the task of every workflow made from it.
        const val taskId = "draft"
    }
}
