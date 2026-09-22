package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WorkflowEngagement
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * The per-workflow state foundation (issue #794): a `normal` workflow is admitted, a form carries one derived
 * [WFS.workflowState] entry per such workflow, and an asserted [WFS.workflowEngagement] beside it survives
 * every recompute.
 *
 * Acme declares the sample's one normal workflow (`auditReview`), so a form in acme is the vehicle. The two
 * state classes are what this pins: the derived entry is rebuilt wholesale on each recompute, while the
 * engagement -- a person's choice -- is preserved verbatim, which is what makes "an entry the recompute does
 * not emit is implicitly deleted" safe to rely on.
 */
class WorkflowStateFoundationTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfState794", "wfState794Test", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val recompute = clientPath(GEP.formDocRecomputeState, SC.acme)

    fun auditEntries() = listOf(
        mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")),
    )

    fun newForm(user: TestUser): String =
        user.postItem(create, mapOf(GDF.entries to auditEntries()))[GDF.gedraId].toOptStr()!!

    fun entriesOf(states: List<Map<String, Any?>>, traitId: String) =
        states.filter { it[GE.traitId].toOptStr() == traitId }.map { it[GE.data].toJsonMapOrEmpty() }

    fun statesOf(result: Map<String, Any?>) = result[GDF.states].toJsonListOfMaps()

    "a form carries a derived workflowState entry for each normal workflow, computed against its revision" {
        val user = TestUser.create(cxt, "wfs-create@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        // Created through the ordinary create path: the deriver runs in that write's own transaction, so the
        // entry is there without anything asking for a recompute.
        val states = statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid)))
        val wf = entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == SW.auditReview }
        wf[WFS.computedAgainstRef].toOptStr().shouldNotBeNull()
    }

    "engaging writes an asserted engagement with a trail, and the recompute preserves it" {
        val user = TestUser.create(cxt, "wfs-engage@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        val after = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        val engagement = entriesOf(after, WFS.workflowEngagement).single()
        engagement[WFS.engaged] shouldBe true
        engagement[WFS.lastEngagedBy].toString() shouldBe user.userId.toString()
        engagement[WFS.events].toJsonListOfMaps().map { it[WFS.kind].toOptStr() } shouldContainExactly
            listOf(WFS.engagedEvent)

        // A recompute rebuilds the derived entries and must leave the asserted engagement exactly as it was --
        // the whole reason engagement is its own asserted trait rather than a field inside the derived entry.
        val recomputed = statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid)))
        entriesOf(recomputed, WFS.workflowEngagement).single()[WFS.engaged] shouldBe true
        entriesOf(recomputed, WFS.workflowState).count { it[WFD.workflowId].toOptStr() == SW.auditReview } shouldBe 1
    }

    "disengaging keeps the entry and extends its trail rather than erasing the history" {
        val user = TestUser.create(cxt, "wfs-disengage@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview))
        val after = statesOf(
            user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview, WFS.engaged to false)),
        )
        val engagement = entriesOf(after, WFS.workflowEngagement).single()
        engagement[WFS.engaged] shouldBe false
        engagement[WFS.events].toJsonListOfMaps().map { it[WFS.kind].toOptStr() } shouldContainExactly
            listOf(WFS.engagedEvent, WFS.disengagedEvent)
        // `lastEngagedBy` records who put it in, and survives the disengage that followed.
        engagement[WFS.lastEngagedBy].toString() shouldBe user.userId.toString()
    }

    "engaging with something that is not a normal workflow of the client is refused" {
        val user = TestUser.create(cxt, "wfs-bad@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        // A typo, and the survey workflow -- neither is a normal workflow this form may be put into.
        user.expectError(EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to "nosuchworkflow"))
        user.expectError(EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to SW.reviewForm))
    }

    "the engagement merge creates an entry, then extends it, leaving other entries alone" {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val other = mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to "somethingElse"))

        val first = WorkflowEngagement.withEngagement(listOf(other), SW.auditReview, true, at, 7L)
        // The unrelated entry rides through, and the new engagement is created with its first event.
        first.count { it[GE.traitId].toOptStr() == WFS.workflowState } shouldBe 1
        val created = first.single { it[GE.traitId].toOptStr() == WFS.workflowEngagement }[GE.data].toJsonMapOrEmpty()
        created[WFS.engaged] shouldBe true
        created[WFS.events].toJsonListOfMaps().size shouldBe 1

        val second = WorkflowEngagement.withEngagement(first, SW.auditReview, false, at, 9L)
        val extended = second.single { it[GE.traitId].toOptStr() == WFS.workflowEngagement }[GE.data].toJsonMapOrEmpty()
        extended[WFS.engaged] shouldBe false
        extended[WFS.events].toJsonListOfMaps().size shouldBe 2
        // Disengaging does not move who engaged it, so "who put it here" survives.
        extended[WFS.lastEngagedBy].toString() shouldBe "7"
    }
})
