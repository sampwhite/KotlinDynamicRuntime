package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The workflow pages (issue #792): the aggregate counts each workflow's eligible, engaged and finished forms over
 * everything the caller may see; the forms listing's drill-down opens exactly the forms a count counted; and the
 * menu item is a client's to turn on.
 */
class WorkflowPagesTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfPages792", "wfPages792Test", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val list = clientPath(GEP.formDocs, SC.acme)

    fun newForm(user: TestUser, surveyDone: Boolean): String {
        val audit = listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")))
        val survey = listOf(
            mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
            mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
        )
        return user.postItem(create, mapOf(GDF.entries to audit + if (surveyDone) survey else emptyList()))[GDF.gedraId].toOptStr()!!
    }

    fun aggregate(user: TestUser) = user.getItems(GEP.workflowAggregate).associateBy { it[WFD.workflowId].toOptStr()!! }
    fun counts(entry: Map<String, Any?>?) = listOf(entry?.get(WAGG.eligible), entry?.get(WAGG.engaged), entry?.get(WAGG.finished))
    fun drill(user: TestUser, workflowId: String, state: WfColumnCategory? = null) =
        user.getItems(list, mapOf(WAGG.workflowId to workflowId) + (state?.let { mapOf(WAGG.workflowState to it.name) } ?: emptyMap()))
            .map { it[GDF.gedraId].toOptStr()!! }

    "a caller with no forms still sees the workflows being calculated, with nothing counted" {
        val user = TestUser.create(cxt, "pages-empty@acme.test", userClient = SC.acme)
        val agg = aggregate(user)
        agg.keys shouldContainExactlyInAnyOrder listOf(SW.auditReview, SW.siteFollowUp)
        counts(agg[SW.auditReview]) shouldBe listOf(0L, 0L, 0L)
        agg.getValue(SW.auditReview)[WCOL.client] shouldBe SC.acme
        agg.getValue(SW.auditReview)[WFD.label] shouldBe "Audit review"
        agg.getValue(SW.auditReview)[WCOL.phase] shouldBe WfPhase.engageable.name
    }

    "each count is the forms in that state, and its drill-down opens exactly those forms" {
        val user = TestUser.create(cxt, "pages-counts@acme.test", userClient = SC.acme)
        val ready = newForm(user, surveyDone = true)
        val unready = newForm(user, surveyDone = false)
        val engaged = newForm(user, surveyDone = true)
        user.postData(engage, mapOf(GDF.gedraId to engaged, GDF.workflowId to SW.auditReview))

        val agg = aggregate(user)
        // The unready form is not eligible for the audit, so it counts nowhere; the follow-up takes all three.
        counts(agg[SW.auditReview]) shouldBe listOf(1L, 1L, 0L)
        counts(agg[SW.siteFollowUp]) shouldBe listOf(3L, 0L, 0L)

        drill(user, SW.auditReview, WfColumnCategory.eligible) shouldBe listOf(ready)
        drill(user, SW.auditReview, WfColumnCategory.engaged) shouldBe listOf(engaged)
        drill(user, SW.auditReview, WfColumnCategory.finished) shouldBe emptyList()
        drill(user, SW.siteFollowUp, WfColumnCategory.eligible) shouldContainExactlyInAnyOrder listOf(ready, unready, engaged)
        // No state: every form whose column shows the workflow -- the ineligible one included.
        drill(user, SW.auditReview) shouldContainExactlyInAnyOrder listOf(ready, unready, engaged)
        // A workflow the client does not have matches nothing, as an unknown search value does.
        drill(user, "noSuchWorkflow") shouldBe emptyList()
    }

    "the drill-down composes with the status filter" {
        val user = TestUser.create(cxt, "pages-compose@acme.test", userClient = SC.acme)
        newForm(user, surveyDone = true)
        val unready = newForm(user, surveyDone = false)
        user.getItems(list, mapOf(WAGG.workflowId to SW.siteFollowUp, "surveyStatus" to "needsInfo"))
            .map { it[GDF.gedraId] } shouldBe listOf(unready)
    }

    "the menu item is off by default, and acme turns it on" {
        fun menuIds(user: TestUser) =
            user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps().map { it[HFLD.id] }
        menuIds(TestUser.create(cxt, "pages-menu@acme.test", userClient = SC.acme)) shouldContain HMENU.workflows
        menuIds(TestUser.create(cxt, "pages-menu@globex.test", userClient = SC.globex)) shouldNotContain HMENU.workflows
    }
})
