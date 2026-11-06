package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
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
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.ctaFilterByValue
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
 * What waits on whom (issue #1637): the forms listing's `needsMe`, by the rule the save and approve gates apply, judged
 * from stored state. Over a client of its own: a site review whose `describe` task its owner saves (naming nobody), whose
 * approval waits on a reviewer of the site's region, and whose follow-up is the owner's again.
 */
class WaitingStepsTest : StringSpec({
    val cxt = TestInstances.default("waiting1637")
    val client = "waiting1637"

    gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer", "northwest", "south"),
            ),
        )
        cfact("reviewerNorthwest", "Review", "The viewer reviews the Northwest region's sites.")
        cfact("reviewerSouth", "Review", "The viewer reviews the South region's sites.")
        cfact("visitApproved", "Review", "A reviewer approved the visit.")
        trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "The site visited.") { property("region", "Its region.") }
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "What was seen.") { property("text", "The notes.") }
        trait("FollowUpEntry", "followUp", setOf(GedraDataType.formDoc), "What happens next.") { property("plan", "The plan.") }
        workflow("siteReview", WfEntry.normal) {
            label = "Site review"
            task("describe", "Describe the visit") {
                trait("notes")
                save("saveNotes", "Save", WfSaveKind.edit)
            }
            task("approve", "Approve the visit") {
                approval("visitApproved", "Approve the visit?", "Approve")
                function(userHasLabel { label = "reviewer" })
                function(userHasLabel { label = "northwest"; cfact = "reviewerNorthwest" })
                function(userHasLabel { label = "south"; cfact = "reviewerSouth" })
                function(
                    ctaFilterByValue {
                        trait = "site"
                        valuePath = "region"
                        case("northwest", "reviewerNorthwest", "Waiting on a Northwest reviewer")
                        case("south", "reviewerSouth", "Waiting on a South reviewer")
                        otherwise("reviewerNorthwest,reviewerSouth", "Waiting on a reviewer of both regions")
                    },
                )
            }
            task("followUp", "Plan the follow-up") {
                trait("followUp")
                save("savePlan", "Save", WfSaveKind.edit)
            }
        }
    }.also { config ->
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 16370L }, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)

    /** An editor of the client carrying [labels]: one who sees every form of it, and reviews by label. */
    fun reviewer(name: String, vararg labels: String): TestUser =
        TestUser.create(cxt, "$name@$client.test", capabilities = listOf(ROLE.editor), userClient = client).also {
            admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to it.userId, ADF.labels to labels.toList()))
        }

    val northwest = reviewer("northwest", "reviewer", "northwest")
    val south = reviewer("south", "reviewer", "south")

    /** The forms listed as waiting on [user], each its id to the steps it waits on them for (task to action text). */
    fun waitingFor(user: TestUser): Map<String, List<Pair<Any?, Any?>>> =
        user.getItems(GEP.formDocs, mapOf(GDF.needsMe to true)).associate { row ->
            row[GDF.gedraId].toOptStr()!! to row[GDF.waitingSteps].toJsonListOfMaps().map { it[WFS.ctaTask] to it[SWF.actionText] }
        }

    /** A new owner's form at the northwest site, engaged with the site review. */
    fun engagedForm(owner: TestUser): String {
        val gid = owner.postItem(
            GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "site", GE.data to mapOf("region" to "northwest")))),
        )[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "siteReview"))
        return gid
    }

    "a step waits on the person it is for: the owner's own save, then the region's reviewer, then the owner again" {
        val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
        val gid = engagedForm(owner)

        // Describing the visit names nobody, so it waits on the form's owner -- not on the administrator, whose gate
        // would admit them, nor on a reviewer.
        waitingFor(owner)[gid] shouldBe listOf("describe" to "Describe the visit")
        waitingFor(admin).containsKey(gid) shouldBe false
        waitingFor(northwest).containsKey(gid) shouldBe false

        owner.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to "siteReview", GDF.taskId to "describe", GDF.saveId to "saveNotes", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "notes", GE.data to mapOf("text" to "Seen."))),
            ),
        )
        // The approval waits on a Northwest reviewer: not the South one, whom the region's filter leaves out, nor the
        // owner, nor an administrator who is no reviewer.
        waitingFor(northwest)[gid] shouldBe listOf("approve" to "Approve")
        waitingFor(south).containsKey(gid) shouldBe false
        waitingFor(owner).containsKey(gid) shouldBe false
        waitingFor(admin).containsKey(gid) shouldBe false

        northwest.postData(GEP.workflowApprove, mapOf(GDF.gedraId to gid, GDF.workflowId to "siteReview", GDF.taskId to "approve"))
        // The follow-up is the owner's again, and nothing waits on the reviewer.
        waitingFor(owner)[gid] shouldBe listOf("followUp" to "Plan the follow-up")
        waitingFor(northwest).containsKey(gid) shouldBe false
    }

    "a form in no workflow waits on nobody, and the count is of the forms waiting" {
        val owner = TestUser.create(cxt, "plain@$client.test", userClient = client)
        val idle = owner.postItem(
            GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "site", GE.data to mapOf("region" to "south")))),
        )[GDF.gedraId].toOptStr()!!
        val engaged = engagedForm(owner)
        waitingFor(owner).keys shouldBe setOf(engaged)
        waitingFor(owner).containsKey(idle) shouldBe false
        // The listing's count is of what matched, so Home can say how many wait.
        val resp = owner.client.sendJsonGetRequest(GEP.formDocs, mapOf(GDF.needsMe to true, EP.limit to 1))
        (resp[EP.numAvailable] as? Number)?.toInt() shouldBe 1
    }
})
