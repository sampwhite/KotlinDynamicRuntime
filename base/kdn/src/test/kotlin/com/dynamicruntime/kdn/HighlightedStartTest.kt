package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A workflow's highlighted Start (issue #1662): while a form is eligible for a workflow that highlights it and is not yet
 * in it, its first task waits on whoever could act on it -- so the classification makes it their turn and "waiting on
 * you" lists it -- and on nobody else, who may still engage. Once the form is in, it is the current task as ever.
 *
 * Over a client of its own with two workflows that differ only in that: `check`, which highlights its Start, and `plain`,
 * which does not. Each one's first task is saved by a reviewer (the `reviewer` label) alone, its trait locked to it.
 */
class HighlightedStartTest : StringSpec({
    val cxt = TestInstances.default("highlightedStart1662")
    val client = "hlstart1662"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer"),
            ),
        )
        for ((id, highlight) in listOf("check" to true, "plain" to false)) {
            // Each its own trait, locked to its task, as a task whose save is restricted must be.
            trait("${id}Entry", "${id}Verdict", setOf(GedraDataType.formDoc), "The reviewer's check.") { property("verdict", "The verdict.") }
            workflow(id, WfEntry.normal) {
                highlightStart = highlight
                task("${id}Task", "Check the form") {
                    trait("${id}Verdict")
                    save("${id}Save", "Save", WfSaveKind.edit)
                    saveWhen(WFC.reviewer)
                    function(userHasLabel { label = "reviewer" })
                }
                lock("${id}Verdict", writableVia = "${id}Task")
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 16620L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    val reviewer = TestUser.create(cxt, "reviewer@$client.test", capabilities = listOf(ROLE.editor), userClient = client).also {
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to it.userId, ADF.labels to listOf("reviewer")))
    }

    /** The steps waiting on [user] in form [gid], by workflow: its task, and whether it is a Start. */
    fun steps(user: TestUser, gid: String, vararg params: Pair<String, Any?>): Map<String, Pair<String?, Boolean>> =
        user.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(GDF.withStates to true, *params))[EP.items]
            .toJsonListOfMaps().firstOrNull { it[GDF.gedraId] == gid }?.get(GDF.waitingSteps).toJsonListOfMaps()
            .associate { it[WFD.workflowId].toOptStr()!! to (it[WFS.ctaTask].toOptStr() to (it[SWF.startsWorkflow] == true)) }

    "a highlighted Start waits on whoever could act on its first task, and is the current task once the form is in" {
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any>()))[GDF.gedraId].toOptStr()!!
        // The reviewer may save the first task, so the highlighting workflow's Start is theirs; the other's is not.
        steps(reviewer, gid) shouldBe mapOf("check" to ("checkTask" to true))
        // And "waiting on you" lists the form for them, not for the owner, who may not save it.
        steps(reviewer, gid, GDF.needsMe to true) shouldBe mapOf("check" to ("checkTask" to true))
        steps(owner, gid) shouldBe emptyMap()
        steps(owner, gid, GDF.needsMe to true) shouldBe emptyMap()
        // It decides nothing about who may engage: the owner, whose turn it is not, engages it.
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "check"))
        steps(reviewer, gid) shouldBe mapOf("check" to ("checkTask" to false))
        // A workflow that does not highlight its Start waits on the reviewer only once the form is in.
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "plain"))
        steps(reviewer, gid) shouldBe mapOf("check" to ("checkTask" to false), "plain" to ("plainTask" to false))
    }
})
