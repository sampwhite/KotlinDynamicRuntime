package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.formWorkflowsOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * The forms listing's workflow column (issue #791): the listing's **summary** names the workflows the column may
 * show, over every form the caller may see -- not the page, not the search -- and each row's cell sorts its own
 * workflows by the shared kernel rule against it.
 *
 * Acme is the vehicle: it declares two normal workflows (`auditReview`, `siteFollowUp`), and a form's review
 * survey decides whether it is eligible for the audit.
 */
class FormsWorkflowColumnTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfColumn791", "wfColumn791Test", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val list = clientPath(GEP.formDocs, SC.acme)
    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)

    fun newForm(user: TestUser, surveyDone: Boolean): String {
        val audit = listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")))
        val survey = listOf(
            mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
            mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
        )
        return user.postItem(create, mapOf(GDF.entries to audit + if (surveyDone) survey else emptyList()))[GDF.gedraId].toOptStr()!!
    }

    /** The whole listing envelope -- the summary sits beside `items`, where the TestUser helpers do not look. */
    fun listing(user: TestUser, args: Map<String, Any?> = emptyMap()) =
        user.client.sendJsonGetRequest(list, mapOf(GDF.withStates to true) + args)

    fun summaryOf(env: Map<String, Any?>) = env[EP.summary].toJsonMapOrEmpty()[WCOL.workflows].toJsonListOfMaps()

    /** One row's cell, sorted by the kernel rule against the summary's phases. */
    fun cell(env: Map<String, Any?>, gedraId: String): List<Pair<String, WfColumnCategory>> {
        val phases = summaryOf(env).associate { it[WFD.workflowId].toOptStr()!! to WfPhase.valueOf(it[WCOL.phase].toOptStr()!!) }
        val row = env[EP.items].toJsonListOfMaps().first { it[GDF.gedraId] == gedraId }
        return formWorkflowsOf(row[GDF.states].toJsonListOfMaps()) { phases[it] }.map { it.workflowId to it.category }
    }

    "with states, the summary names the workflows over every visible form, whatever the page or the search" {
        val user = TestUser.create(cxt, "col-owner@acme.test", userClient = SC.acme)
        newForm(user, surveyDone = true)
        newForm(user, surveyDone = false)

        val full = summaryOf(listing(user))
        full.map { it[WFD.workflowId] } shouldContainExactly listOf(SW.auditReview, SW.siteFollowUp)
        val audit = full.first()
        audit[WCOL.client] shouldBe SC.acme
        audit[WFD.label] shouldBe "Audit review"
        audit[WCOL.phase] shouldBe WfPhase.engageable.name
        // Every test's reason, resolved -- the fragment pull included -- so the ineligible dialog needs no call.
        val reasons = audit[WCOL.explanations].toJsonMapOrEmpty()
        reasons.keys shouldContainExactly setOf(SW.surveyDone, SW.surveyClean, SW.noOpenReview)
        reasons[SW.surveyDone].toOptStr().orEmpty() shouldNotContain "%{"

        // A one-row page, and a search matching nothing, see the same summary.
        summaryOf(listing(user, mapOf(EP.limit to 1))) shouldBe full
        summaryOf(listing(user, mapOf(EI.q to "no such thing anywhere"))) shouldBe full
    }

    "without states the listing carries no summary" {
        val user = TestUser.create(cxt, "col-nostates@acme.test", userClient = SC.acme)
        newForm(user, surveyDone = true)
        user.client.sendJsonGetRequest(list, emptyMap()).containsKey(EP.summary) shouldBe false
    }

    "each row sorts its workflows by the shared rule: engaged, eligible, then ineligible" {
        val user = TestUser.create(cxt, "col-rows@acme.test", userClient = SC.acme)
        val ready = newForm(user, surveyDone = true)
        val unready = newForm(user, surveyDone = false)
        cell(listing(user), ready) shouldBe listOf(
            SW.auditReview to WfColumnCategory.eligible,
            SW.siteFollowUp to WfColumnCategory.eligible,
        )
        cell(listing(user), unready) shouldBe listOf(
            SW.siteFollowUp to WfColumnCategory.eligible,
            SW.auditReview to WfColumnCategory.ineligible,
        )
        user.postData(engage, mapOf(GDF.gedraId to ready, GDF.workflowId to SW.auditReview))
        val env = listing(user)
        cell(env, ready) shouldBe listOf(
            SW.auditReview to WfColumnCategory.engaged,
            SW.siteFollowUp to WfColumnCategory.eligible,
        )
        val row = env[EP.items].toJsonListOfMaps().first { it[GDF.gedraId] == ready }
        formWorkflowsOf(row[GDF.states].toJsonListOfMaps()) { WfPhase.engageable }.first().ctaTask.shouldNotBeNull()
    }

    "nothing visible means an empty summary, and a deleted form stops counting" {
        val user = TestUser.create(cxt, "col-empty@acme.test", userClient = SC.acme)
        summaryOf(listing(user)).shouldBeEmpty()
        val only = newForm(user, surveyDone = true)
        summaryOf(listing(user)).size shouldBe 2
        user.deleteData(clientPath(GEP.formDoc, SC.acme), mapOf(GDF.gedraId to only))
        summaryOf(listing(user)).shouldBeEmpty()
    }

    "a client administrator and a cross-client administrator see the client's workflows too" {
        val owner = TestUser.create(cxt, "col-admin-owner@acme.test", userClient = SC.acme)
        newForm(owner, surveyDone = true)
        // Served from the cache's client index...
        val clientAdmin = TestUser.create(cxt, "col-client-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
        summaryOf(listing(clientAdmin)).map { it[WFD.workflowId] } shouldContainExactly listOf(SW.auditReview, SW.siteFollowUp)
        // ...and, for a scope naming no client, from SQL -- still each workflow under its own client.
        val fullAdmin = TestUser.createFullAdmin(cxt, "col-full-admin@acme.test")
        val acmeRows = summaryOf(listing(fullAdmin)).filter { it[WCOL.client] == SC.acme }
        acmeRows.map { it[WFD.workflowId] } shouldContainExactly listOf(SW.auditReview, SW.siteFollowUp)
        acmeRows.first()[WFD.label] shouldBe "Audit review"
    }
})
