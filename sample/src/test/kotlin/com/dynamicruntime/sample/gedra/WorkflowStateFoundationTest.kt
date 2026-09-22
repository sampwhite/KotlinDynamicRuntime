package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.mergeCfactContributions
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WorkflowEngagement
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    fun auditEntries(findings: String = "seen") = listOf(
        mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to findings)),
    )

    // What acme's review survey requires (an expense report and the owner's details), so a form carrying these
    // has the `surveyComplete` cfact -- which is what `auditReview`'s eligibility asks for (issue #783).
    fun surveyEntries() = listOf(
        mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
        mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
    )

    /**
     * A form in acme; eligible for `auditReview` unless [surveyDone] is false. [findings] of
     * [SC.findingsOpen] gives the workflow its own `acmeUnderAudit` cfact (issue #784).
     */
    fun newForm(user: TestUser, surveyDone: Boolean = true, findings: String = "seen"): String {
        val entries = auditEntries(findings) + if (surveyDone) surveyEntries() else emptyList()
        return user.postItem(create, mapOf(GDF.entries to entries))[GDF.gedraId].toOptStr()!!
    }

    fun entriesOf(states: List<Map<String, Any?>>, traitId: String) =
        states.filter { it[GE.traitId].toOptStr() == traitId }.map { it[GE.data].toJsonMapOrEmpty() }

    fun statesOf(result: Map<String, Any?>) = result[GDF.states].toJsonListOfMaps()

    fun auditState(states: List<Map<String, Any?>>) =
        entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == SW.auditReview }

    fun failureIds(entry: Map<String, Any?>) =
        entry[WFS.eligibilityFailures].toJsonListOfMaps().map { it[WFD.id].toOptStr() }

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

    "a form engaged by someone else stays its owner's, with the engager recorded as the actor" {
        val owner = TestUser.create(cxt, "wfs-owner@acme.test", userClient = SC.acme)
        val acmeAdmin = TestUser.create(cxt, "wfs-admin@acme.test", userClient = SC.acme, level = ROLE.admin)
        val gid = newForm(owner)
        acmeAdmin.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview))
        // The owner reads in their own-user scope: had the state row been re-owned by the admin, it would be
        // invisible to them here and the engagement would read as absent.
        val seen = entriesOf(statesOf(owner.postData(recompute, mapOf(GDF.gedraId to gid))), WFS.workflowEngagement)
        seen.single()[WFS.lastEngagedBy].toString() shouldBe acmeAdmin.userId.toString()
    }

    "a workflow retired from configuration keeps its engaged form's entry, and can still be disengaged" {
        val user = TestUser.create(cxt, "wfs-retired@acme.test", userClient = SC.acme)
        val admin = TestUser.createFullAdmin(cxt, "wfs-retired-admin@example.com")
        val gid = newForm(user)
        val retired = "retiredAudit"
        // Stands in for a form engaged with a workflow the client has since removed: no engage call could make
        // this (it refuses an undeclared workflow), so the state is written the way an operator would.
        admin.postItem(
            GEP.adminGedraState,
            mapOf(
                GDF.gedraId to gid,
                GDF.states to listOf(
                    mapOf(GE.traitId to WFS.workflowEngagement, GE.data to mapOf(WFD.workflowId to retired, WFS.engaged to true)),
                ),
            ),
        )
        // The deriver keeps a bare entry for it -- no revision, since there is no definition left to compute
        // against -- beside the declared workflow's.
        val before = entriesOf(statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid))), WFS.workflowState)
        before.map { it[WFD.workflowId].toOptStr() } shouldContainExactly listOf(SW.auditReview, retired)
        before.single { it[WFD.workflowId].toOptStr() == retired }[WFS.computedAgainstRef].shouldBeNull()

        // Re-engaging it is still refused -- the form cannot be put *into* a workflow that no longer exists...
        user.expectError(EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to retired))
        // ...but it can be taken out, and its derived entry then goes, while the engagement keeps its trail.
        val after = statesOf(
            user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to retired, WFS.engaged to false)),
        )
        entriesOf(after, WFS.workflowState).map { it[WFD.workflowId].toOptStr() } shouldContainExactly
            listOf(SW.auditReview)
        entriesOf(after, WFS.workflowEngagement).single()[WFS.engaged] shouldBe false
    }

    // --- eligibility (issue #783) -------------------------------------------------------------------------

    "a form's workflow state records whether it is eligible, and which tests it fails" {
        val user = TestUser.create(cxt, "wfs-elig@acme.test", userClient = SC.acme)
        val admin = TestUser.createFullAdmin(cxt, "wfs-elig-admin@example.com")
        // Read through the admin state endpoint, which reads and does not recompute: what is checked is what the
        // create itself wrote. Before derivers saw each other's output in the same pass, a create computed
        // eligibility against the cfacts as they stood *before* the write -- none -- and a complete form read
        // as ineligible until its next write.
        fun stored(gid: String) = statesOf(admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid)))

        val done = auditState(stored(newForm(user)))
        done[WFS.eligible] shouldBe true
        failureIds(done).shouldBeEmpty()

        // Not done: the survey is incomplete, so `surveyDone` fails -- by id, with no explanation text stored.
        // `surveyClean` passes, since nothing present is invalid; every test is evaluated, not the first failure.
        val notDone = auditState(stored(newForm(user, surveyDone = false)))
        notDone[WFS.eligible] shouldBe false
        failureIds(notDone) shouldContainExactly listOf(SW.surveyDone)
    }

    "engaging a form that is not eligible is refused, with the reasons" {
        val user = TestUser.create(cxt, "wfs-refused@acme.test", userClient = SC.acme)
        val gid = newForm(user, surveyDone = false)
        val err = user.expectError(
            EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview),
        )
        // The explanation is pulled from the acmeWf fragment file, so this is the evaluated text, not the template.
        err.toString() shouldContain "Finish reviewing your form first"
        err.toString() shouldContain SW.surveyDone
    }

    "the engage gate decides on freshly recomputed cfacts, not on what was last stored" {
        val user = TestUser.create(cxt, "wfs-fresh@acme.test", userClient = SC.acme)
        val admin = TestUser.createFullAdmin(cxt, "wfs-fresh-admin@example.com")
        val gid = newForm(user)
        // Clear the stored state, the stand-in for a form whose stored cfacts predate the configuration (one
        // created before its client had a survey, say). The data still completes the survey.
        admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to emptyList<Any?>()))
        // Judged on the stored state, this form has no `surveyComplete` and would be refused. Recomputed first,
        // its data says it is complete, so it is eligible and the engagement lands.
        val after = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        entriesOf(after, WFS.workflowEngagement).single()[WFS.engaged] shouldBe true
        auditState(after)[WFS.eligible] shouldBe true
    }

    // --- singleton cfacts as a contributed set (issue #784) --------------------------------------------------

    "an engaged workflow contributes its singleton to the form's one cfacts set, beside the survey's" {
        val user = TestUser.create(cxt, "wfs-single@acme.test", userClient = SC.acme)
        val gid = newForm(user, findings = SC.findingsOpen)
        fun formFacts(states: List<Map<String, Any?>>) =
            entriesOf(states, GT.cfacts).single()[GT.facts].toJsonListOrEmpty().map { it.toOptStr() }

        // Not engaged: the workflow has concluded its own cfact from the data, and keeps it on its own entry --
        // but contributes nothing, so the form's set is the survey's alone.
        val before = statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid)))
        auditState(before)[WFS.cfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldContainExactly listOf(SC.underAudit)
        auditState(before)[WFS.singletonCfacts].toJsonListOrEmpty().shouldBeEmpty()
        formFacts(before) shouldContainExactly listOf(SVY.surveyComplete, SVY.surveyValid)

        // Engaged: its rule emits `needsReview`, attributed on its entry and merged into the one form set -- after
        // the survey's facts, which the merge keeps rather than one producer clobbering the other.
        val engaged = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        auditState(engaged)[WFS.singletonCfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldContainExactly
            listOf(WSC.needsReview)
        formFacts(engaged) shouldContainExactly listOf(SVY.surveyComplete, SVY.surveyValid, WSC.needsReview)

        // Disengaged: the contribution goes with the engagement.
        val out = statesOf(
            user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview, WFS.engaged to false)),
        )
        formFacts(out) shouldContainExactly listOf(SVY.surveyComplete, SVY.surveyValid)
    }

    "a workflow's own cfacts follow the form's data, so an engaged workflow need not emit anything" {
        val user = TestUser.create(cxt, "wfs-quiet@acme.test", userClient = SC.acme)
        val gid = newForm(user)   // findings "seen": the audit is closed, so no acmeUnderAudit
        val states = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        auditState(states)[WFS.cfacts].toJsonListOrEmpty().shouldBeEmpty()
        auditState(states)[WFS.singletonCfacts].toJsonListOrEmpty().shouldBeEmpty()
        entriesOf(states, GT.cfacts).single()[GT.facts].toJsonListOrEmpty().map { it.toOptStr() } shouldNotContain
            WSC.needsReview
    }

    "cfacts contributions merge into one entry, in first-seen order, leaving other entries alone" {
        fun contribution(vararg facts: String) = mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to facts.toList()))
        val other = mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to "x"))
        val merged = mergeCfactContributions(listOf(contribution("a", "b"), other, contribution("b", "c")))
        merged.map { it[GE.traitId] } shouldContainExactly listOf(GT.cfacts, WFS.workflowState)
        merged.first()[GE.data].toJsonMapOrEmpty()[GT.facts] shouldBe listOf("a", "b", "c")
        // A lone contribution passes through untouched.
        mergeCfactContributions(listOf(contribution("a"))).single()[GE.data].toJsonMapOrEmpty()[GT.facts] shouldBe listOf("a")
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
