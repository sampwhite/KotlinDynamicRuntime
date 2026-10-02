package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
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
 * A global workflow under a root, driven end to end (issue #953). Core ships no workflow, so a fixture component
 * declares one under a test-only root, `wftest`: the workflow `wftest:review`, its task `wftest:draft`, and the trait
 * it collects. Every place a workflow id is an identifier then takes the rooted one -- the view's query, the
 * engagement and derived state stored under it, and the reference the derived state was computed against -- and a
 * client's own bare `review` sits beside it as a second workflow, since shadowing by id is gone.
 */
class RootedWorkflowTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "rootedWorkflow953", "rootedWorkflow953",
        mapOf(RootedWorkflowComponent.loadFlag.name to "true"),
        additionalComponents = listOf(RootedWorkflowComponent()),
    )
    val client = "rootedwf953"
    val review = RootedWorkflowComponent.workflowId
    val note = RootedWorkflowComponent.traitId

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(note),
            ),
        )
        trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
        // The client's own `review` -- bare, so its own, and no replacement for the global one.
        workflow("review", WfEntry.normal) {
            task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 9530L }, config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    fun statesOf(result: Map<String, Any?>) = result[GDF.states].toJsonListOfMaps()
    fun entriesOf(states: List<Map<String, Any?>>, traitId: String) =
        states.filter { it[GE.traitId].toOptStr() == traitId }.map { it[GE.data].toJsonMapOrEmpty() }

    "the client sees the global rooted workflow beside its own bare one of the same local name" {
        WorkflowService.get(cxt).forClient(client).workflows.keys shouldContainExactly setOf(review, "review")
    }

    "a rooted workflow is viewed, engaged, saved and stored under its rooted id" {
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!

        // The view's query names it by its rooted id, and its task by the task's.
        val view = user.getData(GEP.workflowView, mapOf(WFD.workflowId to review, GDF.gedraId to gid))
        view[WFD.workflowId] shouldBe review
        view[WFD.tasks].toJsonListOfMaps().map { it[WFD.id].toOptStr() } shouldContainExactly
            listOf(RootedWorkflowComponent.taskId)

        val engaged = statesOf(user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to review)))
        entriesOf(engaged, WFS.workflowEngagement).single()[WFD.workflowId] shouldBe review

        val saved = user.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to review, GDF.taskId to RootedWorkflowComponent.taskId, GDF.saveId to "saveNote",
                GDF.gedraId to gid, GDF.entries to listOf(mapOf(GE.traitId to note, GE.data to mapOf("text" to "first"))),
            ),
        )
        saved[WSF.saved] shouldBe true

        // The derived state is stored under the rooted id, against a reference that reads back to it.
        val states = statesOf(user.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to gid)))
        val derived = entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == review }
        val ref = WfRef.parse(derived[WFS.computedAgainstRef].toOptStr().shouldNotBeNull())
        ref.workflowId shouldBe review
        ref.bundleId.client shouldBe GID.globalClient
        // The client's own `review` keeps a state entry of its own.
        entriesOf(states, WFS.workflowState).count { it[WFD.workflowId].toOptStr() == "review" } shouldBe 1
    }
})

/** Contributes, under the test-only root `wftest`, a global trait and a global normal workflow collecting it. */
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
        const val taskId = "$root:draft"
    }
}
