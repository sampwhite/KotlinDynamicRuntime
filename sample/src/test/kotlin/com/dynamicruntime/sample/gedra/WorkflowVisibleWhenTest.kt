package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A creation-workflow form honors `g-visibleWhen` (issue #569): the resolved workflow view carries the caller's
 * frontend-delivered cfacts, so the page hides an admin-only field from an ordinary caller exactly as the
 * endpoint form does. The backend never drops the field -- it keeps the `g-visibleWhen` keyword for everyone and
 * lets the page decide -- so this asserts both halves: the cfact present-set differs by caller, and the gated
 * property is served to both.
 *
 * `acme`'s creation workflow collects `expenseReport`, whose data schema carries an admin-only `reviewerNote`
 * (`SampleTraits`), so acme's workflow view is where the two callers diverge.
 */
class WorkflowVisibleWhenTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfVisibleWhen", "wfVisibleWhenTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.create(cxt, "wf-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
    val ordinary = TestUser.create(cxt, "wf-user@acme.test", userClient = SC.acme)

    fun view(user: TestUser): Map<String, Any?> = user.getData(clientPath(GEP.workflowView, SC.acme))

    fun cfacts(view: Map<String, Any?>): Map<String, Any?> = view[WVF.cfacts].toJsonMapOrEmpty()

    // The `g-visibleWhen` gate on the admin-only field, read from whichever carried type declares `reviewerNote`
    // in the view's own `$defs` -- present for both callers, since the backend never drops a gated field.
    fun reviewerNoteGate(view: Map<String, Any?>): Any? {
        val type = view[SCH.dDefs].toJsonMapOrEmpty().values.map { it.toJsonMapOrEmpty() }
            .first { it[SCH.properties].toJsonMapOrEmpty().containsKey(ST.reviewerNote) }
        return type[SCH.properties].toJsonMapOrEmpty()[ST.reviewerNote].toJsonMapOrEmpty()[SCH.visibleWhen]
    }

    "the workflow view carries hasAdminLevel present for an admin and absent for an ordinary caller" {
        cfacts(view(admin))[CFACTS.hasAdminLevel] shouldBe true
        cfacts(view(ordinary))[CFACTS.hasAdminLevel] shouldBe false
    }

    "the admin-only field is served to both callers -- only the delivered cfacts differ" {
        // The field and its gate ride in the view's $defs for both; it is the page, against the cfacts above,
        // that shows it to the admin and hides it from the ordinary caller.
        reviewerNoteGate(view(admin)) shouldBe CFACTS.hasAdminLevel
        reviewerNoteGate(view(ordinary)) shouldBe CFACTS.hasAdminLevel
    }
})
