package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RMETA
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.RWF
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfApprovalStep
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * An administrator reverses an approval (issue #1787). Over a client of its own, whose `review` workflow records notes,
 * is approved at once (`a`), records more, then is approved in two steps (`b`: a look, then a sign-off), all by a
 * reviewer. Reversing an approval takes it and every later step and approval away, records each on the trail with the
 * reason, and puts the call to action back on it; the form's data is untouched. Only an administrator of the form's
 * client may, and only an approval given now.
 */
class ApprovalReversalTest : StringSpec({
    val cxt = TestInstances.default("approvalReversal1787")
    val client = "reverse1787"
    val workflowId = "review"
    val svc = GedraConfigService.get(cxt)

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer"),
            ),
        )
        cfact("aApproved", "Review", "The first approval was given.")
        cfact("bLooked", "Review", "The second approval's first look was given.")
        cfact("bApproved", "Review", "The second approval was given.")
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "Notes.") { property("text", "The notes.") }
        trait("MoreEntry", "more", setOf(GedraDataType.formDoc), "More notes.") { property("text", "The notes.") }
        workflow(workflowId, WfEntry.normal) {
            task("record", "Record the notes") { trait("notes") }
            task("a", "Approve the notes") {
                approval("aApproved", "Approve the notes.", "Approve")
                function(userHasLabel { label = "reviewer" })
            }
            task("more", "Record more") { trait("more") }
            task("b", "Sign it off") {
                approvalSteps(
                    "bApproved",
                    listOf(
                        WfApprovalStep("look", "First look", "Look it over.", "Looked", null, null, "bLooked"),
                        WfApprovalStep("sign", "Sign-off", "Sign it off.", "Sign off", null, null, null),
                    ),
                )
                function(userHasLabel { label = "reviewer" })
            }
            singleton(WSC.finished, "bApproved")
        }
    }
    svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 17871L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    // A reviewer reads every form of the client as an editor, as a reviewer is set up in ApprovalStepsTest.
    val reviewer = TestUser.create(cxt, "reviewer@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
    val editor = TestUser.create(cxt, "editor@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
    admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf("reviewer")))

    /** A form with both notes recorded, engaged, and every approval given: `a`, then `b`'s two steps. */
    fun approvedForm(): String {
        fun entry(traitId: String) = mapOf(GE.traitId to traitId, GE.data to mapOf("text" to "Ready."))
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry("notes"), entry("more"))))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))
        reviewer.postData(GEP.workflowApprove, mapOf(GDF.gedraId to gid, GDF.workflowId to workflowId, GDF.taskId to "a"))
        for (step in listOf("look", "sign")) {
            reviewer.postData(
                GEP.workflowApprove, mapOf(GDF.gedraId to gid, GDF.workflowId to workflowId, GDF.taskId to "b", GDF.stepId to step),
            )
        }
        return gid
    }

    fun reverse(gid: String, taskId: String, stepId: String? = null, reason: String? = "The figures were wrong.") = buildMap {
        put(GDF.gedraId, gid)
        put(GDF.workflowId, workflowId)
        put(GDF.taskId, taskId)
        stepId?.let { put(GDF.stepId, it) }
        reason?.let { put(GDF.reason, it) }
    }

    /** The form's whole state, as an administrator of every client reads it, the asserted entries included. */
    val fullAdmin = TestUser.createFullAdmin(cxt, "full@$client.test")
    fun states(gid: String): List<Map<String, Any?>> =
        fullAdmin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()

    fun dataOf(gid: String, traitId: String): List<Map<String, Any?>> =
        states(gid).filter { it[GE.traitId] == traitId }.map { it[GE.data].toJsonMapOrEmpty() }

    fun workflowState(gid: String) = dataOf(gid, WFS.workflowState).single { it[WFD.workflowId] == workflowId }
    fun approvalKeys(gid: String) = dataOf(gid, WFS.workflowApproval).map { a ->
        val step = a[WFS.stepId].toOptStr()
        if (step == null || step == a[WFS.taskId]) a[WFS.taskId] else "${a[WFS.taskId]}.$step"
    }
    fun events(gid: String) = dataOf(gid, WFS.workflowEngagement).single()[WFS.events].toJsonListOfMaps()
    fun facts(gid: String) = dataOf(gid, GT.cfacts).flatMap { it[GT.facts].toJsonListOrEmpty() }

    fun task(viewer: TestUser, gid: String, taskId: String): Map<String, Any?> =
        viewer.getData(GEP.workflowView, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == taskId }

    fun steps(viewer: TestUser, gid: String, taskId: String) =
        task(viewer, gid, taskId)[WVF.approval].toJsonMapOrEmpty()[WVF.steps].toJsonListOfMaps()

    "reversing an approval takes it and every later one away, records each with the reason, and the workflow goes back to it" {
        val gid = approvedForm()
        approvalKeys(gid).toSet() shouldBe setOf("a", "b.look", "b.sign")
        facts(gid) shouldContain WSC.finished
        // Only the administrator is offered the reversal, on each step given.
        steps(admin, gid, "b").map { it[WVF.canReverse] } shouldBe listOf(true, true)
        steps(reviewer, gid, "b").map { it[WVF.canReverse] } shouldBe listOf(null, null)
        steps(owner, gid, "a").single()[WVF.canReverse] shouldBe null

        admin.postData(GEP.workflowReverseApproval, reverse(gid, "a", reason = "  The figures were wrong.  "))

        approvalKeys(gid) shouldBe emptyList()
        workflowState(gid).let {
            it[WFS.ctaTask] shouldBe "a"
            it[WFS.tasksDone] shouldBe false
        }
        facts(gid) shouldNotContain WSC.finished
        // Each approval taken away is on the trail, with who, why, and which one the administrator chose.
        events(gid).filter { it[WFS.kind] == WFS.approvalReversedEvent }.map {
            listOf(it[WFS.note], it[WFS.reversedFrom], it[WFS.reason], it[WFS.by].toString())
        } shouldBe listOf("a", "b.look", "b.sign").map { listOf(it, "a", "The figures were wrong.", admin.userId.toString()) }
        // The data the tasks hold is untouched.
        task(owner, gid, "record")[WVF.status].toJsonMapOrEmpty()[CDF.complete] shouldBe true
        task(owner, gid, "more")[WVF.status].toJsonMapOrEmpty()[CDF.complete] shouldBe true
        // Each task says so, to the owner too: the one chosen, and the later ones that went with it.
        steps(owner, gid, "a").single().let {
            it[WVF.approved] shouldBe false
            it[WVF.reversedReason] shouldBe "The figures were wrong."
            it[WVF.reversedByName] shouldBe "admin@$client.test"
            it[WVF.reversedWith] shouldBe null
        }
        steps(owner, gid, "b").map { it[WVF.reversedWith] } shouldBe listOf("Approve the notes", "Approve the notes")
        // The reviewer approves it again, and the workflow moves on; the reversal no longer describes it.
        task(reviewer, gid, "a")[WVF.canApprove] shouldBe true
        reviewer.postData(GEP.workflowApprove, mapOf(GDF.gedraId to gid, GDF.workflowId to workflowId, GDF.taskId to "a"))
        workflowState(gid)[WFS.ctaTask] shouldBe "b"
        steps(owner, gid, "a").single()[WVF.reversedReason] shouldBe null
        steps(owner, gid, "b").first()[WVF.reversedReason] shouldBe "The figures were wrong."
    }

    "reversing a later step leaves the approvals before it" {
        val gid = approvedForm()
        admin.postData(GEP.workflowReverseApproval, reverse(gid, "b", "sign", "Signed too soon."))
        approvalKeys(gid).toSet() shouldBe setOf("a", "b.look")
        workflowState(gid).let {
            it[WFS.ctaTask] shouldBe "b"
            it[WFS.ctaStep] shouldBe "sign"
        }
        facts(gid) shouldNotContain WSC.finished
        steps(owner, gid, "b").map { it[WVF.approved] to it[WVF.reversedReason] } shouldBe
            listOf(true to null, false to "Signed too soon.")
    }

    "only an administrator of the form's client may reverse, for a reason, an approval given now" {
        val gid = approvedForm()
        for (who in listOf(reviewer, editor, owner)) {
            who.expectError(EXC.notAuthorized, GEP.workflowReverseApproval, reverse(gid, "a")).toString() shouldContain
                "takes an administrator of client '$client'"
        }
        admin.expectError(EXC.badInput, GEP.workflowReverseApproval, reverse(gid, "a", reason = null)).toString() shouldContain
            GDF.reason
        admin.expectError(EXC.badInput, GEP.workflowReverseApproval, reverse(gid, "a", reason = "   ")).toString() shouldContain
            GDF.reason
        admin.expectError(EXC.badInput, GEP.workflowReverseApproval, reverse(gid, "a", reason = "x".repeat(GDF.maxReasonLength + 1)))
        admin.expectError(EXC.badInput, GEP.workflowReverseApproval, reverse(gid, "record")).toString() shouldContain
            "not an approval task"
        // An approval in steps names the step it reverses.
        admin.expectError(EXC.badInput, GEP.workflowReverseApproval, reverse(gid, "b")).toString() shouldContain "approved in steps"
        admin.expectError(EXC.badInput, GEP.workflowReverseApproval, reverse(gid, "b", "nope"))
        approvalKeys(gid).toSet() shouldBe setOf("a", "b.look", "b.sign")
        // Once reversed, there is nothing left to reverse: a stale page, or two administrators at once.
        admin.postData(GEP.workflowReverseApproval, reverse(gid, "b", "look"))
        admin.expectError(EXC.conflict, GEP.workflowReverseApproval, reverse(gid, "b", "sign")).toString() shouldContain
            "nothing to reverse"
        // An administrator of every client may too, through the client's own copy of the endpoint.
        fullAdmin.postData(clientPath(GEP.workflowReverseApproval, client), reverse(gid, "a"))
        approvalKeys(gid) shouldBe emptyList()
    }

    "a report reads a standing reversal and the workflow's last activity from the stored trail" {
        // Read back through the database, where the trail's events are open maps of parsed JSON -- not the kernel test's
        // hand-built values.
        val gid = approvedForm()
        admin.postData(GEP.workflowReverseApproval, reverse(gid, "b", "sign", "Signed too soon."))
        val reversal = events(gid).last()
        reversal[WFS.kind] shouldBe WFS.approvalReversedEvent
        val approvalB = "workflow.$workflowId.${RWF.approval}[b]"
        fun column(label: String, path: String) = mapOf(RDEF.label to label, RDEF.path to path)
        admin.postData(
            UADEP.reportCreate,
            mapOf(
                RDEF.label to "Reversals",
                RDEF.columns to listOf(
                    column("Form", "meta.${RMETA.gedraId}"),
                    column("Reversed", "$approvalB.${RWF.reversed}"),
                    column("Reversed on", "$approvalB.${RWF.reversedAt}"),
                    column("Reversed by", "$approvalB.${RWF.reversedBy}"),
                    column("Reason", "$approvalB.${RWF.reversedReason}"),
                    column("First reversed", "workflow.$workflowId.${RWF.approval}[a].${RWF.reversed}"),
                    column("Last activity", "workflow.$workflowId.${RWF.lastActivityAt}"),
                ),
            ),
        )
        val rows = admin.getItems(UADEP.reportRun, mapOf(RRUN.reportId to "reversals")).map { it[RRUN.values].toJsonMapOrEmpty() }
        val row = rows.single { it["form"] == gid }
        row["reversed"] shouldBe true
        row["reversedOn"] shouldBe reversal[WFS.at]
        row["reversedBy"] shouldBe admin.userId
        row["reason"] shouldBe "Signed too soon."
        // The first approval still stands, and the reversal is the last thing anyone did there.
        row["firstReversed"] shouldBe false
        row["lastActivity"] shouldBe reversal[WFS.at]
    }

    "an administrator of another client cannot reach the form" {
        val otherClient = "other1787"
        svc.writeConfig(
            cxt.mkSubContext("setup", otherClient).also { it.userId = 17872L },
            gedraConfig(cxt, "${otherClient}cfg", clientNamespace(otherClient), otherClient) {
                defineClient(
                    ClientDef(
                        clientId = otherClient, name = otherClient, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        GedraConfigReload.reloadClient(cxt, otherClient)
        val gid = approvedForm()
        val other = TestUser.create(cxt, "admin@$otherClient.test", level = ROLE.admin, userClient = otherClient)
        other.expectError(EXC.notFound, GEP.workflowReverseApproval, reverse(gid, "a"))
        approvalKeys(gid).toSet() shouldBe setOf("a", "b.look", "b.sign")
    }
})
