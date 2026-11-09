package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CtaFilterByValueBuilder
import com.dynamicruntime.common.gedra.workflow.OwnerOnly
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.TraitLockCopy
import com.dynamicruntime.common.gedra.workflow.ViewerCfacts
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.ctaFilterByValue
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A workflow's call to action carries **what kind** of action it is -- a search keyword -- and **who** may act on it --
 * a CTA filter concluded from the form's data (issue #1594). Both are stored with the CTA in the workflow's state and
 * recomputed with it. Over a client of its own per case: a site review whose `describe` task declares `review`, whose
 * approval task carries it by default and chooses its reviewer by the site's region, and whose last task carries
 * nothing.
 *
 * Also, since issue #1739, the tests that were `CtaFilterActsTest`, each kept whole below.
 */
class CtaFilterTest : StringSpec({
    include(ctaFilterTests())
    include(ctaFilterActsTests())
})

/** This file's own tests, as the class describes them. */
private fun ctaFilterTests() = stringSpec {
    val cxt = TestInstances.default("ctaFilter1594")
    val svc = GedraConfigService.get(cxt)

    fun CtaFilterByValueBuilder.regions(northwest: String, path: String = "region") {
        trait = "site"
        valuePath = path
        case("northwest", northwest, "Waiting on a Northwest reviewer")
        case("south", "reviewerSouth", "Waiting on a South reviewer")
        otherwise("reviewerNorthwest,reviewerSouth", "Waiting on a reviewer of both regions")
    }

    /**
     * Writes [client]'s configuration with the site review, its approval's filter from [filters], and anything [more]
     * adds, and reloads it.
     */
    fun store(
        client: String,
        filters: CtaFilterByValueBuilder.() -> Unit = { regions("reviewerNorthwest") },
        more: GedraConfigBuilder.() -> Unit = {},
    ) {
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
            cfact("flagged", "Review", "A fact about the form, not the person.")
            trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "The site visited.") {
                property("region", "Its region.")
                property("areas", "The regions it spans.") { type = SCT.array; items { type = SCT.string } }
            }
            trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "What was seen.") { property("text", "The notes.") }
            trait("FollowUpEntry", "followUp", setOf(GedraDataType.formDoc), "What happens next.") { property("plan", "The plan.") }
            siteReview(filters)
            more()
        }
        svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 15940L }, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun site(region: String, areas: List<String> = emptyList()) =
        mapOf(GE.traitId to "site", GE.data to mapOf("region" to region, "areas" to areas))

    class Form(val owner: TestUser, val gid: String)

    /** A form of a new owner's in [client], at [region], engaged with the site review. */
    fun engagedForm(client: String, region: String, areas: List<String> = emptyList()): Form {
        val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(site(region, areas))))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "siteReview"))
        return Form(owner, gid)
    }

    /** The site review's stored state entry for [form], as the forms listing returns it. */
    fun reviewState(form: Form): Map<String, Any?> =
        form.owner.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).single { it[GDF.gedraId] == form.gid }[GDF.states]
            .toJsonListOfMaps().map { it[GE.data].toJsonMapOrEmpty() to it[GE.traitId] }
            .single { (data, traitId) -> traitId == WFS.workflowState && data[WFD.workflowId] == "siteReview" }.first

    fun saveNotes(form: Form) = form.owner.postData(
        GEP.workflowSave,
        mapOf(
            WFD.workflowId to "siteReview", GDF.taskId to "describe", GDF.saveId to "saveNotes", GDF.gedraId to form.gid,
            GDF.entries to listOf(mapOf(GE.traitId to "notes", GE.data to mapOf("text" to "Seen."))),
        ),
    )

    "a declared keyword, then an approval's, rides the CTA, and goes when the CTA reaches a task carrying none" {
        val client = "ctakeyword1594"
        store(client)
        val form = engagedForm(client, "northwest")
        // `describe` declares `review`, and sets no filter.
        reviewState(form).let {
            it[WFS.ctaTask] shouldBe "describe"
            it[WFS.ctaKeyword] shouldBe CTAK.review
            (WFS.ctaFilterRule in it) shouldBe false
        }
        // The approval carries `review` without saying so, and its filter names the region's reviewers.
        saveNotes(form)
        reviewState(form).let {
            it[WFS.ctaTask] shouldBe "approve"
            it[WFS.ctaKeyword] shouldBe CTAK.review
            it[WFS.ctaFilterRule] shouldBe "reviewerNorthwest"
            it[WFS.ctaFilterLabel] shouldBe "Waiting on a Northwest reviewer"
        }
        // Approved, the CTA moves to the follow-up, which carries nothing.
        val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
        val reviewer = TestUser.create(cxt, "reviewer@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf("reviewer", "northwest")))
        reviewer.postData(GEP.workflowApprove, mapOf(GDF.gedraId to form.gid, GDF.workflowId to "siteReview", GDF.taskId to "approve"))
        reviewState(form).let {
            it[WFS.ctaTask] shouldBe "followUp"
            (WFS.ctaKeyword in it) shouldBe false
            (WFS.ctaFilterRule in it) shouldBe false
            (WFS.ctaFilterLabel in it) shouldBe false
        }
    }

    "the filter follows the form's data: a change of region changes who the approval waits on" {
        val client = "ctaregion1594"
        store(client)
        val form = engagedForm(client, "northwest")
        saveNotes(form)
        reviewState(form)[WFS.ctaFilterRule] shouldBe "reviewerNorthwest"
        form.owner.postData(
            GEP.patch,
            mapOf(
                GPF.targets to mapOf(
                    GedraDataType.formDoc.name to listOf(
                        mapOf(
                            GDF.gedraId to form.gid,
                            GPF.edits to listOf(mapOf(GED.action to GedraEditAction.addOrReplace.name) + site("south")),
                        ),
                    ),
                ),
            ),
        )
        reviewState(form).let {
            it[WFS.ctaFilterRule] shouldBe "reviewerSouth"
            it[WFS.ctaFilterLabel] shouldBe "Waiting on a South reviewer"
        }
    }

    "a changed definition reaches a form's stored filter when its state is recomputed" {
        val client = "ctarecompute1594"
        store(client)
        val form = engagedForm(client, "northwest")
        saveNotes(form)
        reviewState(form)[WFS.ctaFilterRule] shouldBe "reviewerNorthwest"
        // Northwest's sites go to the South's reviewers now; the stored filter stands until the state is recomputed --
        // by the form's next write, the batch job, or (here) the recompute the job runs, one form at a time.
        store(client, filters = { regions("reviewerSouth") })
        reviewState(form)[WFS.ctaFilterRule] shouldBe "reviewerNorthwest"
        form.owner.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to form.gid))
        reviewState(form)[WFS.ctaFilterRule] shouldBe "reviewerSouth"
    }

    "a value no case lists falls to otherwise, so a gap in the cases never opens the approval to every reviewer" {
        val client = "ctaotherwise1594"
        store(client)
        val form = engagedForm(client, "east")
        saveNotes(form)
        reviewState(form).let {
            it[WFS.ctaFilterRule] shouldBe "reviewerNorthwest,reviewerSouth"
            it[WFS.ctaFilterLabel] shouldBe "Waiting on a reviewer of both regions"
        }
    }

    "a list value chooses the first case any of its values matches" {
        val client = "ctalist1594"
        store(client, filters = { regions("reviewerNorthwest", path = "areas") })
        val form = engagedForm(client, "", areas = listOf("east", "south"))
        saveNotes(form)
        reviewState(form)[WFS.ctaFilterRule] shouldBe "reviewerSouth"
    }

    "a function reading a trait the client does not have, or without otherwise, is reported" {
        // A misspelled trait would never match, routing every form the same way; refused rather than quietly wrong.
        shouldThrow<KdrException> { store("ctabadtrait1594", filters = { regions("reviewerNorthwest"); trait = "sites" }) }
            .message.orEmpty() shouldContain "reads trait(s) [sites]"
        // A function with no otherwise would leave a gap in its cases open to everyone saveWhen admits.
        shouldThrow<KdrException> {
            store("ctanootherwise1594", filters = {
                trait = "site"
                valuePath = "region"
                case("northwest", "reviewerNorthwest")
            })
        }.message.orEmpty() shouldContain "otherwise"
    }

    "a ctaFilter function on a core details workflow's task is reported: only a normal workflow has a call to action" {
        shouldThrow<KdrException> {
            store("ctacoredetails1594", more = {
                workflow("siteDetails", WfEntry.coreDetails) {
                    task("site", "The site") {
                        trait("site")
                        save("saveSite", "Save", WfSaveKind.edit)
                        function(ctaFilterByValue { regions("reviewerNorthwest") })
                    }
                }
            })
        }.message.orEmpty() shouldContain "only a normal workflow's task has"
    }

    "a filter naming a fact that is not about the person is reported, and never stored" {
        val client = "ctabadfilter1594"
        // `flagged` is a fact about the form: a listing could not evaluate it without the form. In a unit test, where
        // configuration is strict, the report refuses the reload.
        shouldThrow<KdrException> { store(client, filters = { regions("flagged") }) }.message.orEmpty().let {
            it shouldContain "ctaFilterByValue"
            it shouldContain "'flagged'"
            it shouldContain "not facts about the person"
        }
    }
}

/**
 * A CTA filter decides **who may act** (issue #1595): it narrows the task's `saveWhen`, so a person both must pass, on
 * a save and on an approval, and the view says who the task waits on when the filter is what stands in the way.
 *
 * Over a client of its own. Its `siteReview` waits, after the visit's notes, on an approval by the site's region's
 * reviewer; its `siteCheck` has a data task saved by that reviewer (`saveWhen: kdr:reviewer`, its trait locked to the
 * task). The `northwest` and `south` user labels make a reviewer of each region; every reviewer also carries `reviewer`.
 */
private fun ctaFilterActsTests() = stringSpec {
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
            // Kept to its owner but for the reviewer of the current work, while engaged; waiting on a review while the
            // approval is its call to action, since an approval carries the `review` keyword (issue #1596).
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
}

// Declared with what was `CtaFilterTest`.
/** The site review (issue #1594): describe the site, a reviewer approves the visit, then a follow-up. */
private fun GedraConfigBuilder.siteReview(filters: CtaFilterByValueBuilder.() -> Unit) {
    workflow("siteReview", WfEntry.normal) {
        task("describe", "Describe the visit") {
            trait("notes")
            save("saveNotes", "Save", WfSaveKind.edit)
            keyword(CTAK.review)
        }
        task("approve", "Approve the visit") {
            approval("visitApproved", "Approve the visit?", "Approve")
            function(userHasLabel { label = "reviewer" })
            function(userHasLabel { label = "northwest"; cfact = "reviewerNorthwest" })
            function(userHasLabel { label = "south"; cfact = "reviewerSouth" })
            function(ctaFilterByValue(filters))
        }
        task("followUp", "Plan the follow-up") {
            trait("followUp")
            save("savePlan", "Save", WfSaveKind.edit)
        }
    }
}
