package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
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
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A workflow's call to action carries **what kind** of action it is -- a search keyword -- and **who** may act on it --
 * a CTA filter concluded from the form's data (issue #1594). Both are stored with the CTA in the workflow's state and
 * recomputed with it. Over a client of its own per case: a site review whose `describe` task declares `review`, whose
 * approval task carries it by default and chooses its reviewer by the site's region, and whose last task carries
 * nothing.
 */
class CtaFilterTest : StringSpec({
    val cxt = TestInstances.default("ctaFilter1594")
    val svc = GedraConfigService.get(cxt)

    fun CtaFilterByValueBuilder.regions(northwest: String) {
        trait = "site"
        valuePath = "region"
        case("northwest", northwest, "Waiting on a Northwest reviewer")
        case("south", "reviewerSouth", "Waiting on a South reviewer")
    }

    /** Writes [client]'s configuration with the site review, its approval's filter from [filters], and reloads it. */
    fun store(client: String, filters: CtaFilterByValueBuilder.() -> Unit = { regions("reviewerNorthwest") }) {
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
            trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "The site visited.") { property("region", "Its region.") }
            trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "What was seen.") { property("text", "The notes.") }
            trait("FollowUpEntry", "followUp", setOf(GedraDataType.formDoc), "What happens next.") { property("plan", "The plan.") }
            siteReview(filters)
        }
        svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 15940L }, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun site(region: String) = mapOf(GE.traitId to "site", GE.data to mapOf("region" to region))

    class Form(val owner: TestUser, val gid: String)

    /** A form of a new owner's in [client], at [region], engaged with the site review. */
    fun engagedForm(client: String, region: String): Form {
        val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(site(region))))[GDF.gedraId].toOptStr()!!
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
            (WFS.ctaFilter in it) shouldBe false
        }
        // The approval carries `review` without saying so, and its filter names the region's reviewers.
        saveNotes(form)
        reviewState(form).let {
            it[WFS.ctaTask] shouldBe "approve"
            it[WFS.ctaKeyword] shouldBe CTAK.review
            it[WFS.ctaFilter] shouldBe "reviewerNorthwest"
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
            (WFS.ctaFilter in it) shouldBe false
            (WFS.ctaFilterLabel in it) shouldBe false
        }
    }

    "the filter follows the form's data: a change of region changes who the approval waits on" {
        val client = "ctaregion1594"
        store(client)
        val form = engagedForm(client, "northwest")
        saveNotes(form)
        reviewState(form)[WFS.ctaFilter] shouldBe "reviewerNorthwest"
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
            it[WFS.ctaFilter] shouldBe "reviewerSouth"
            it[WFS.ctaFilterLabel] shouldBe "Waiting on a South reviewer"
        }
    }

    "a changed definition reaches a form's stored filter when its state is recomputed" {
        val client = "ctarecompute1594"
        store(client)
        val form = engagedForm(client, "northwest")
        saveNotes(form)
        reviewState(form)[WFS.ctaFilter] shouldBe "reviewerNorthwest"
        // Northwest's sites go to the South's reviewers now; the stored filter stands until the state is recomputed --
        // by the form's next write, the batch job, or (here) the recompute the job runs, one form at a time.
        store(client) { regions("reviewerSouth") }
        reviewState(form)[WFS.ctaFilter] shouldBe "reviewerNorthwest"
        form.owner.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to form.gid))
        reviewState(form)[WFS.ctaFilter] shouldBe "reviewerSouth"
    }

    "a filter naming a fact that is not about the person is reported, and never stored" {
        val client = "ctabadfilter1594"
        // `flagged` is a fact about the form: a listing could not evaluate it without the form. In a unit test, where
        // configuration is strict, the report refuses the reload.
        shouldThrow<KdrException> { store(client) { regions("flagged") } }.message.orEmpty().let {
            it shouldContain "ctaFilterByValue"
            it shouldContain "'flagged'"
            it shouldContain "not facts about the person"
        }
    }
})

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
