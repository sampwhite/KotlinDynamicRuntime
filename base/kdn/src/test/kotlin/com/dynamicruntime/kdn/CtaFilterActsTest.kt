package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CtaFilterByValueBuilder
import com.dynamicruntime.common.gedra.workflow.OwnerOnly
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.TraitLockCopy
import com.dynamicruntime.common.gedra.workflow.ViewerCfacts
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.ctaFilterByValue
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A CTA filter decides **who may act** (issue #1595): it narrows the task's `saveWhen`, so a person both must pass, on
 * a save and on an approval, and the view says who the task waits on when the filter is what stands in the way.
 *
 * Over a client of its own. Its `siteReview` waits, after the visit's notes, on an approval by the site's region's
 * reviewer; its `siteCheck` has a data task saved by that reviewer (`saveWhen: kdr:reviewer`, its trait locked to the
 * task). The `northwest` and `south` user labels make a reviewer of each region; every reviewer also carries `reviewer`.
 */
class CtaFilterActsTest : StringSpec({
    val cxt = TestInstances.default("ctaFilterActs1595")
    val client = "ctaacts1595"

    fun CtaFilterByValueBuilder.regions() {
        trait = "site"
        valuePath = "region"
        case("northwest", "reviewerNorthwest", "Waiting on a Northwest reviewer")
        case("south", "reviewerSouth", "Waiting on a South reviewer")
        otherwise("reviewerNorthwest,reviewerSouth", "Waiting on a reviewer of both regions")
    }

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
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
        trait("CheckEntry", "check", setOf(GedraDataType.formDoc), "The reviewer's check.") { property("verdict", "The verdict.") }
        workflow("siteReview", WfEntry.normal) {
            // Waiting on a review, and kept to its owner but for the reviewer of the current work, while engaged.
            singleton(WSC.needsReview, "#always")
            singleton(WSC.ownerOnly, "#always")
            task("describe", "Describe the visit") { trait("notes"); save("saveNotes", "Save", WfSaveKind.edit) }
            task("approve", "Approve the visit") {
                approval("visitApproved", "Approve the visit?", "Approve")
                function(userHasLabel { label = "reviewer" })
                function(userHasLabel { label = "northwest"; cfact = "reviewerNorthwest" })
                function(userHasLabel { label = "south"; cfact = "reviewerSouth" })
                function(ctaFilterByValue { regions() })
            }
        }
        workflow("siteCheck", WfEntry.normal) {
            task("check", "Check the site") {
                trait("check")
                save("saveCheck", "Save", WfSaveKind.edit)
                saveWhen(WFC.reviewer)
                function(userHasLabel { label = "reviewer" })
                function(userHasLabel { label = "northwest"; cfact = "reviewerNorthwest" })
                function(userHasLabel { label = "south"; cfact = "reviewerSouth" })
                function(ctaFilterByValue { regions() })
            }
            lock("check", writableVia = "check")
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 15950L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    fun reviewer(region: String): TestUser =
        TestUser.create(cxt, "$region@$client.test", capabilities = listOf(ROLE.editor), userClient = client).also {
            admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to it.userId, ADF.labels to listOf("reviewer", region)))
        }
    val northwest = reviewer("northwest")
    val south = reviewer("south")

    /** A form of the owner's at [region], engaged with [workflowId]; for `siteReview`, its notes saved, so it waits on the approval. */
    fun engagedForm(region: String, workflowId: String): String {
        val site = mapOf(GE.traitId to "site", GE.data to mapOf("region" to region))
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(site)))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))
        if (workflowId == "siteReview") {
            owner.postData(
                GEP.workflowSave,
                mapOf(
                    WFD.workflowId to workflowId, GDF.taskId to "describe", GDF.saveId to "saveNotes", GDF.gedraId to gid,
                    GDF.entries to listOf(mapOf(GE.traitId to "notes", GE.data to mapOf("text" to "Seen."))),
                ),
            )
        }
        return gid
    }

    /** Task [taskId] of [workflowId]'s view of form [gid], as [viewer] sees it. */
    fun taskView(viewer: TestUser, gid: String, workflowId: String, taskId: String): Map<String, Any?> =
        viewer.getData(GEP.workflowView, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == taskId }

    fun approve(gid: String) =
        mapOf(GDF.gedraId to gid, GDF.workflowId to "siteReview", GDF.taskId to "approve")

    fun checkSave(gid: String) = mapOf(
        WFD.workflowId to "siteCheck", GDF.taskId to "check", GDF.saveId to "saveCheck", GDF.gedraId to gid,
        GDF.entries to listOf(mapOf(GE.traitId to "check", GE.data to mapOf("verdict" to "Fine."))),
    )

    "a reviewer the approval's filter does not name may not approve it: the view says who it waits on, and approving is refused" {
        val gid = engagedForm("northwest", "siteReview")
        taskView(south, gid, "siteReview", "approve").let {
            // Still a reviewer, as the labels say; the filter's answer is a fact of its own, and it is no.
            it[WVF.facts].toJsonListOfStrings() shouldContain WFC.reviewer
            it[WVF.facts].toJsonListOfStrings() shouldNotContain WFC.passesCtaFilter
            it[WVF.canApprove] shouldBe false
            it[WVF.waitingOn] shouldBe "Waiting on a Northwest reviewer"
        }
        south.expectError(EXC.notAuthorized, GEP.workflowApprove, approve(gid)).toString() shouldContain
            "Waiting on a Northwest reviewer"
        // The reviewer it names sees the approval as theirs, and approves.
        taskView(northwest, gid, "siteReview", "approve").let {
            it[WVF.facts].toJsonListOfStrings() shouldContain WFC.passesCtaFilter
            it[WVF.canApprove] shouldBe true
            (WVF.waitingOn in it) shouldBe false
        }
        northwest.postData(GEP.workflowApprove, approve(gid))
    }

    "a person passing saveWhen but failing the filter may not save the task, and is told who it waits on" {
        val gid = engagedForm("south", "siteCheck")
        taskView(northwest, gid, "siteCheck", "check").let {
            it[WVF.canSave] shouldBe false
            it[WVF.waitingOn] shouldBe "Waiting on a South reviewer"
        }
        northwest.expectError(EXC.notAuthorized, GEP.workflowSave, checkSave(gid)).toString() shouldContain
            "Waiting on a South reviewer"
        // The South's reviewer passes both, and saves.
        taskView(south, gid, "siteCheck", "check")[WVF.canSave] shouldBe true
        south.postData(GEP.workflowSave, checkSave(gid))
    }

    "a CTA with no filter is judged by saveWhen alone, as before" {
        val gid = engagedForm("northwest", "siteReview")
        // `describe` carries no filter: the owner, whom no saveWhen restricts, saved it in engagedForm; nobody is told
        // the task waits on someone.
        val describe = taskView(owner, gid, "siteReview", "describe")
        describe[WVF.canSave] shouldBe true
        (WVF.waitingOn in describe) shouldBe false
    }

    "the Needs Review popover and the owner-only rule hear the filter: only the reviewer it names reviews the current work" {
        val gid = engagedForm("northwest", "siteReview")
        fun isReviewer(viewer: TestUser): Any? =
            viewer.getData(GEP.formDocSingletonWorkflows, mapOf(GDF.gedraId to gid, WFD.cfact to WSC.needsReview))[SWF.workflows]
                .toJsonListOfMaps().single { it[WFD.workflowId] == "siteReview" }[SWF.isReviewer]
        isReviewer(south) shouldBe false
        isReviewer(northwest) shouldBe true
        // Kept to its owner: an editor may change it only as the reviewer of the current work, which the South's is not.
        val patch = mapOf(
            GPF.targets to mapOf(
                GedraDataType.formDoc.name to listOf(
                    mapOf(
                        GDF.gedraId to gid,
                        GPF.edits to listOf(
                            mapOf(
                                GED.action to GedraEditAction.addOrReplace.name, GE.traitId to "notes",
                                GE.data to mapOf("text" to "Seen again."),
                            ),
                        ),
                    ),
                ),
            ),
        )
        south.expectError(EXC.notAuthorized, GEP.patch, patch)["errorMessage"].toOptStr() shouldBe TraitLockCopy.keptToOwner
        northwest.postData(GEP.patch, patch)
    }

    "one viewer-facts object works out a task's facts once, however many forms it is asked about" {
        val forms = (1..3).map { engagedForm("northwest", "siteReview") }
        val rows = owner.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).filter { it[GDF.gedraId] in forms }
        rows.size shouldBe 3
        // A caller judging many forms makes one and passes it to each form's check.
        val request = cxt.mkSubContext("listing", client)
        val viewer = ViewerCfacts(request, client)
        val approve = WorkflowService.get(cxt).forClient(client).workflow("siteReview")!!.def.task("approve")!!
        val first = viewer.forTask(approve)
        rows.forEach {
            OwnerOnly.isReviewer(request, client, it[GDF.entries].toJsonListOfMaps(), it[GDF.states].toJsonListOfMaps(), viewer)
        }
        (viewer.forTask(approve) === first) shouldBe true
    }
})
