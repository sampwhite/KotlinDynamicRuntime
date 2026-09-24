package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.computeCFactsFromData
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A normal workflow's time windows end to end (issue #790), walking the instance clock through every phase of one
 * workflow: before its lifetime, lifetime only, relevant, open for engagement, relevant again, past relevancy
 * (frozen), and past its lifetime. At each step it checks what the stored state says and what the engage, view,
 * save and approve endpoints allow.
 *
 * A per-test dynamic client, so the windows can be placed relative to the clock the test controls -- minutes
 * apart rather than days, so the test user's session outlives the walk.
 */
class WorkflowWindowsTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("wfWindows790", "wfWindows790")
    val client = "wfwin790"
    val clock = cxt.instanceConfig.clock
    clock.freeze()
    // Whole seconds: a stored definition keeps its instants to the millisecond, as the wire does.
    val t0 = Instant.fromEpochSeconds(cxt.instanceNow().epochSeconds)
    fun at(minutes: Int) = (t0 + minutes.minutes).toString()
    fun moveTo(minutes: Int) = clock.setAbsolute(t0 + minutes.minutes)

    fun asClient(c: String): KdrCxt = cxt.mkSubContext("setup", c).also { it.userId = 9000L }

    // Lifetime [10, 100), relevancy [20, 80), engagement [30, 50): every phase has room to be visited.
    val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                userLabels = listOf("reviewer"),
            ),
        )
        cfact("inspApproved", "Inspection", "The inspection was approved.")
        cfact("inspDone", "Inspection", "The visit's notes say it is done.")
        trait("VisitEntry", "visit", setOf(GedraDataType.formDoc), "A site visit.") {
            property("notes", "What was seen.") { type = SCT.string }
        }
        workflow("inspection", WfEntry.normal) {
            label = "Inspection"
            lifetime(at(10), at(100))
            relevancy(at(20), at(80))
            engagement(at(30), at(50))
            // Finished once the notes say "done" -- data-driven, so a frozen entry is told apart from a recalculated
            // one by changing the notes after relevancy closes.
            function(computeCFactsFromData {
                trait = "visit"
                valuePath = "notes"
                map("done", "inspDone")
            })
            singleton(WSC.finished, "inspDone")
            task("record", "Record the visit") { trait("visit"); save("saveVisit", "Save", WfSaveKind.edit) }
            task("approve", "Approve") {
                approval("inspApproved", "Read it.", "Approve")
                function(userHasLabel { label = "reviewer" })
            }
        }
        // A peer with no windows, held off while any workflow says the form is finished -- so a frozen Finished is
        // seen gating it, and a workflow past its lifetime is seen to stop.
        workflow("followUp", WfEntry.normal) {
            eligibility("notFinished", "~${WSC.finished}", "Already finished elsewhere.")
            task("note", "Note") { trait("visit"); save("saveNote", "Save", WfSaveKind.edit) }
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(client), config)
    GedraConfigReload.reloadClient(cxt, client)

    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    fun newForm(): String = user.postItem(
        GEP.formDocCreate,
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to "visit", GE.data to mapOf("notes" to "fine")))),
    )[GDF.gedraId].toOptStr()!!

    fun recompute(gid: String) = user.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()
    fun entry(states: List<Map<String, Any?>>, workflowId: String = "inspection"): Map<String, Any?>? = states
        .firstOrNull { it[GE.traitId].toOptStr() == WFS.workflowState && it[GE.data].toJsonMapOrEmpty()[WFD.workflowId] == workflowId }
        ?.get(GE.data)?.toJsonMapOrEmpty()
    // What the workflow pages say about the windowed workflow (issue #792): its phase and counts, or null when unlisted.
    fun aggregate(): Map<String, Any?>? = user.getItems(GEP.workflowAggregate).firstOrNull { it[WFD.workflowId] == "inspection" }
    fun countsOf(entry: Map<String, Any?>?) = listOf(entry?.get(WAGG.eligible), entry?.get(WAGG.engaged), entry?.get(WAGG.finished))
    // What the Finished chip's popover lists for the form (issue #789), as this caller sees it.
    fun behindFinished(gid: String) = user.getData(GEP.formDocSingletonWorkflows, mapOf(GDF.gedraId to gid, WFD.cfact to WSC.finished))[
        SWF.workflows].toJsonListOfMaps()
    fun formFacts(states: List<Map<String, Any?>>) = states.filter { it[GE.traitId].toOptStr() == GT.cfacts }
        .flatMap { it[GE.data].toJsonMapOrEmpty()[GT.facts].toJsonListOrEmpty() }
    fun engage(gid: String, engaged: Boolean = true) =
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, GDF.workflowId to "inspection", WFS.engaged to engaged))
    fun engageFails(gid: String) =
        user.expectError(EXC.badInput, GEP.workflowEngage, mapOf(GDF.gedraId to gid, GDF.workflowId to "inspection"))[
            "errorMessage"].toOptStr().orEmpty()
    fun view(gid: String) = user.getData(GEP.workflowView, mapOf(GDF.workflowId to "inspection", GDF.gedraId to gid))
    fun viewFails(gid: String) =
        user.expectError(EXC.notFound, GEP.workflowView, args = mapOf(GDF.workflowId to "inspection", GDF.gedraId to gid))
    fun saveBody(gid: String, notes: String = "done") = mapOf(
        GDF.workflowId to "inspection", GDF.taskId to "record", GDF.saveId to "saveVisit", GDF.gedraId to gid,
        GDF.entries to listOf(mapOf(GE.traitId to "visit", GE.data to mapOf("notes" to notes))),
    )
    // A raw patch, which no workflow window governs -- how the data changes underneath a frozen workflow.
    fun patchNotes(gid: String, notes: String) = user.postItems(
        GEP.patch,
        mapOf(
            GPF.targets to mapOf(
                GedraDataType.formDoc.name to listOf(
                    mapOf(
                        GDF.gedraId to gid,
                        GPF.edits to listOf(
                            mapOf(
                                GED.action to GedraEditAction.addOrReplace.name,
                                GE.traitId to "visit", GE.data to mapOf("notes" to notes),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    val engagedForm = newForm()
    val otherForm = newForm()

    "before its lifetime a workflow is not there at all" {
        entry(recompute(engagedForm)).shouldBeNull()
        viewFails(engagedForm)
        engageFails(engagedForm) shouldContain "is not a normal workflow"
        // Nor on the workflow pages (issue #792).
        aggregate().shouldBeNull()
    }

    "in its lifetime but before relevancy, nothing is calculated and nothing may engage" {
        moveTo(15)
        entry(recompute(engagedForm)).shouldBeNull()
        viewFails(engagedForm)
        engageFails(engagedForm) shouldContain "engagement opens at ${at(30)}"
        // Neither being calculated nor on any form: not on the workflow pages.
        aggregate().shouldBeNull()
    }

    "relevant: calculated, eligibility and all, but engagement has not opened" {
        moveTo(25)
        entry(recompute(engagedForm)).shouldNotBeNull()[WFS.eligible] shouldBe true
        // Being calculated, so listed -- but nothing counts as eligible while no form may engage.
        aggregate().shouldNotBeNull()[WCOL.phase] shouldBe WfPhase.relevant.name
        countsOf(aggregate()) shouldBe listOf(0L, 0L, 0L)
        viewFails(engagedForm)
        engageFails(engagedForm) shouldContain "engagement opens at ${at(30)}"
    }

    "in the engagement window a form engages, and the workflow is shown and saved" {
        moveTo(35)
        view(otherForm)[WVF.phase] shouldBe WfPhase.engageable.name
        entry(engage(engagedForm)[GDF.states].toJsonListOfMaps()).shouldNotBeNull()[WFS.singletonCfacts] shouldBe emptyList<String>()
        user.postData(GEP.workflowSave, saveBody(engagedForm))
        val states = recompute(engagedForm)
        entry(states).shouldNotBeNull()[WFS.singletonCfacts] shouldBe listOf(WSC.finished)
        formFacts(states) shouldContain WSC.finished
        // Live: the popover names the current task and what it asks.
        behindFinished(engagedForm).single()[WFS.ctaTask] shouldBe "approve"
        // Finished by its rule, and counted so on the workflow pages.
        countsOf(aggregate()) shouldBe listOf(0L, 0L, 1L)
    }

    "once engagement closes, only the engaged form still sees the workflow" {
        moveTo(60)
        view(engagedForm)[WVF.phase] shouldBe WfPhase.relevant.name
        viewFails(otherForm)
        engageFails(otherForm) shouldContain "engagement closed at ${at(50)}"
        // Still calculated, so the engaged form's work goes on.
        user.postData(GEP.workflowSave, saveBody(engagedForm))
    }

    "past relevancy an engaged form's state is frozen and read-only, and an unengaged one's is gone" {
        moveTo(60)
        val before = entry(recompute(engagedForm)).shouldNotBeNull()
        before[WFS.singletonCfacts] shouldBe listOf(WSC.finished)
        moveTo(90)
        // The notes no longer say "done": a recalculation would drop Finished, and the frozen entry must not.
        patchNotes(engagedForm, "reopened")
        val states = recompute(engagedForm)
        entry(states) shouldBe before
        // Its singleton still counts: the form still reads Finished while the workflow lives, and still holds off
        // the peer that waits on it.
        formFacts(states) shouldContain WSC.finished
        entry(states, "followUp").shouldNotBeNull()[WFS.eligible] shouldBe false
        // The popover still lists it, but with no action: nothing can be done in a frozen workflow.
        val frozenRow = behindFinished(engagedForm).single()
        frozenRow[WFD.workflowId] shouldBe "inspection"
        frozenRow[WFS.ctaTask].shouldBeNull()
        frozenRow[SWF.actionText].shouldBeNull()
        entry(recompute(otherForm)).shouldBeNull()
        view(engagedForm)[WVF.phase] shouldBe WfPhase.lifetimeOnly.name
        // Past relevancy it is no longer calculated, but it still stands behind its engaged form, so it stays listed.
        aggregate().shouldNotBeNull()[WCOL.phase] shouldBe WfPhase.lifetimeOnly.name
        countsOf(aggregate()) shouldBe listOf(0L, 0L, 1L)
        user.expectError(EXC.conflict, GEP.workflowSave, saveBody(engagedForm))["errorMessage"].toOptStr()
            .orEmpty() shouldContain "closed at ${at(80)}"
        user.expectError(
            EXC.conflict, GEP.workflowApprove,
            mapOf(GDF.gedraId to engagedForm, GDF.workflowId to "inspection", GDF.taskId to "approve"),
        )
    }

    "past its lifetime the workflow vanishes, but the engagement is kept and can still be withdrawn" {
        moveTo(110)
        // Before any recompute the stored entry still says Finished; the popover already knows better.
        behindFinished(engagedForm) shouldBe emptyList()
        val states = recompute(engagedForm)
        entry(states).shouldBeNull()
        formFacts(states) shouldNotContain WSC.finished
        entry(states, "followUp").shouldNotBeNull()[WFS.eligible] shouldBe true
        states.any { it[GE.traitId].toOptStr() == WFS.workflowEngagement } shouldBe true
        viewFails(engagedForm)
        aggregate().shouldBeNull()
        engage(engagedForm, engaged = false)
    }

    afterSpec { clock.reset() }
})
