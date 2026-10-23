package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A workflow deleted from a client's configuration is a workflow past its lifetime (issue #1354): either way it is as
 * if it were not configured, and either way nothing a form stored is lost, so putting the workflow back -- or moving
 * its lifetime's end back out -- brings back everything the form had. Each case reads every surface a caller meets: the
 * forms listing's states and workflow summary, the Workflows page's counts, the workflow's own view, and the stored
 * state after a recompute.
 *
 * One booted instance; a client per case, its workflow `review` in a bundle `flows` of its own, and a form engaged in
 * `review` with its draft saved, so its state names `check` as the task to do next.
 */
class DeletedWorkflowTest : StringSpec({
    val cxt = TestInstances.default("deletedWorkflow1354")
    val svc = GedraConfigService.get(cxt)
    // To the second: a stored moment keeps milliseconds, so a finer one would not read back equal.
    val now = Instant.fromEpochSeconds(cxt.instanceNow().epochSeconds)

    fun GedraConfigBuilder.flows() {
        workflow("review", WfEntry.normal) {
            task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
            task("check", "Sign it off") { trait("signoff"); save("saveSignoff", "Save", WfSaveKind.edit) }
        }
    }

    fun write(client: String, name: String, build: GedraConfigBuilder.() -> Unit) =
        svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 13540L }, gedraConfig(cxt, name, clientNamespace(client), client, build = build))

    /** Defines [client] -- with a sandbox, and so published-only, when [sandbox] -- and returns its administrator. */
    fun defineClient(client: String, sandbox: Boolean = false): TestUser {
        write(client, "main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox, timeZone = "America/Chicago",
                ),
            )
            trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
            trait("SignoffEntry", "signoff", setOf(GedraDataType.formDoc), "Who signed it off.") { property("by", "Who.") }
        }
        write(client, "flows") { flows() }
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 13540L }
        svc.publish(setup, listOf("main", "flows").map { GedraId.of(GedraConfigType.configDoc, client, it) })
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    /** A form engaged in `review` with its draft saved. */
    fun engagedForm(client: String): Pair<TestUser, String> {
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))
        user.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to "review", GDF.taskId to "draft", GDF.saveId to "saveMemo", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "memo", GE.data to mapOf("text" to "m"))),
            ),
        )
        return user to gid
    }

    fun reviewEntry(admin: TestUser): Map<String, Any?> =
        admin.getItem(CFEP.bundle, mapOf(CFEP.name to "flows"))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps().single { it[CCT.workflowId] == "review" }

    fun patchFlows(admin: TestUser, action: GedraEditAction, data: Map<String, Any?>) = admin.postData(
        CFEP.bundlePatch,
        mapOf(
            CFEP.configs to listOf(mapOf(CFEP.name to "flows", CFEP.edits to listOf(mapOf(CFEP.slot to CCT.workflowDef, GED.action to action.name, GE.data to data)))),
            CFEP.reloadField to true,
        ),
    )

    /** The workflow ids each per-workflow state entry of [states] names. */
    fun workflowIdsIn(states: List<Map<String, Any?>>): List<String?> = states
        .filter { it[GE.traitId] in setOf(WFS.workflowEngagement, WFS.workflowState, WFS.workflowApproval) }
        .map { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() }

    class Seen(val inStates: Boolean, val inSummary: Boolean, val inAggregate: Boolean, val viewable: Boolean, val ctaTask: String?)

    /** What each surface says of `review`, for a form gid, as [admin] and the form's [user] see it. */
    fun seen(admin: TestUser, user: TestUser, gid: String): Seen {
        val listing = admin.client.sendJsonGetRequest(GEP.formDocs, mapOf(GDF.withStates to true, GDF.withWorkflowSummary to true))
        val states = listing[EP.items].toJsonListOfMaps().single()[GDF.states].toJsonListOfMaps()
        val summary = listing[EP.summary].toJsonMapOrEmpty()[CWF.workflows].toJsonListOfMaps()
        val aggregate = admin.client.sendJsonGetRequest(GEP.workflowAggregate)[EP.items].toJsonListOfMaps()
        val view = user.client.sendJsonGetRequest(GEP.workflowView, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))
        val cta = states.firstOrNull { it[GE.traitId] == WFS.workflowState }?.get(GE.data).toJsonMapOrEmpty()[WFS.ctaTask].toOptStr()
        return Seen(
            inStates = "review" in workflowIdsIn(states),
            inSummary = summary.any { it[WFD.workflowId] == "review" },
            inAggregate = aggregate.any { it[WFD.workflowId] == "review" },
            viewable = view["status"] == null,
            ctaTask = cta,
        )
    }

    /** The form's stored state entries, unfiltered, after a recompute -- what the deriver kept and what it dropped. */
    fun storedAfterRecompute(user: TestUser, gid: String): List<Map<String, Any?>> {
        user.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to gid))
        val full = TestUser.createFullAdmin(cxt, "full@deleted1354.test")
        return full.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()
    }

    fun storedKinds(stored: List<Map<String, Any?>>): Set<String?> =
        stored.filter { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId] == "review" }.map { it[GE.traitId].toOptStr() }.toSet()

    /** The form's state entries by the plain read (issue #1366), which recomputes nothing. */
    fun statesRead(user: TestUser, gid: String): List<Map<String, Any?>> =
        user.getData(GEP.formDocStates, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()

    /** The read asking for retired workflows too: `review`'s entries' trait ids, and the workflows named retired. */
    fun retiredRead(user: TestUser, gid: String): Pair<Set<String?>, List<String>> {
        val answer = user.getData(GEP.formDocStates, mapOf(GDF.gedraId to gid, GDF.includeRetired to true))
        return storedKinds(answer[GDF.states].toJsonListOfMaps()) to answer[GDF.retiredWorkflows].toJsonListOfStrings()
    }

    "a deleted workflow disappears from every surface, keeps what the form stored, and comes back when put back" {
        val client = "wfdel1354"
        val admin = defineClient(client)
        val (user, gid) = engagedForm(client)
        val review = reviewEntry(admin)
        seen(admin, user, gid).let { it.inStates shouldBe true; it.inSummary shouldBe true; it.inAggregate shouldBe true; it.viewable shouldBe true }

        patchFlows(admin, GedraEditAction.deleteOrNoOp, mapOf(CCT.workflowId to "review"))
        seen(admin, user, gid).let {
            it.inStates shouldBe false
            it.inSummary shouldBe false
            it.inAggregate shouldBe false
            it.viewable shouldBe false
        }
        // The plain read leaves it out too; asked for retired workflows, it answers both entries still stored --
        // the calculated state as well, since the read recomputes nothing -- and names the workflow retired.
        workflowIdsIn(statesRead(user, gid)) shouldNotContain "review"
        retiredRead(user, gid) shouldBe (setOf(WFS.workflowEngagement, WFS.workflowState) to listOf("review"))
        // The deriver no longer calculates it, and its engagement -- asserted -- is still stored.
        storedKinds(storedAfterRecompute(user, gid)).let {
            it shouldNotContain WFS.workflowState
            it shouldContain WFS.workflowEngagement
        }
        retiredRead(user, gid) shouldBe (setOf(WFS.workflowEngagement) to listOf("review"))
        // Withdrawing the form from it answers with the withdrawal: the workflow acted on is shown whatever it is.
        val withdrawn = user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review", WFS.engaged to false))
        withdrawn[GDF.states].toJsonListOfMaps().single { it[GE.traitId] == WFS.workflowEngagement }[GE.data]
            .toJsonMapOrEmpty()[WFS.engaged] shouldBe false

        // Put back, and the form engaged again (which needs the workflow to exist): its saved draft still counts.
        patchFlows(admin, GedraEditAction.addOrReplace, review)
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review", WFS.engaged to true))
        storedAfterRecompute(user, gid)
        seen(admin, user, gid).let {
            it.inStates shouldBe true
            it.inSummary shouldBe true
            it.inAggregate shouldBe true
            it.viewable shouldBe true
            // Where the form had got to: its draft is saved, so `check` is next.
            it.ctaTask shouldBe "check"
        }
        // Back, its entries are answered by the plain read, and the read asking for retired workflows names none.
        storedKinds(statesRead(user, gid)) shouldBe setOf(WFS.workflowEngagement, WFS.workflowState)
        retiredRead(user, gid) shouldBe (setOf(WFS.workflowEngagement, WFS.workflowState) to emptyList())
    }

    "a workflow past its lifetime's end answers exactly as a deleted one, and comes back when the end moves out" {
        val client = "wfend1354"
        val admin = defineClient(client)
        val (user, gid) = engagedForm(client)
        fun setWindows(windows: WfWindows) = admin.postData(
            UADEP.clientWorkflowWindows,
            mapOf(WFD.workflowId to "review", CWF.windows to windows.toInfo(), IMP.acknowledgeImpact to true),
        )

        setWindows(WfWindows(lifetime = WfWindow(now - 30.days, now - 1.days)))
        seen(admin, user, gid).let {
            it.inStates shouldBe false
            it.inSummary shouldBe false
            it.inAggregate shouldBe false
            it.viewable shouldBe false
        }
        workflowIdsIn(statesRead(user, gid)) shouldNotContain "review"
        retiredRead(user, gid) shouldBe (setOf(WFS.workflowEngagement, WFS.workflowState) to listOf("review"))
        storedKinds(storedAfterRecompute(user, gid)).let {
            it shouldNotContain WFS.workflowState
            it shouldContain WFS.workflowEngagement
        }

        setWindows(WfWindows())
        storedAfterRecompute(user, gid)
        seen(admin, user, gid).let {
            it.inStates shouldBe true
            it.inSummary shouldBe true
            it.inAggregate shouldBe true
            it.viewable shouldBe true
            it.ctaTask shouldBe "check"
        }
        retiredRead(user, gid) shouldBe (setOf(WFS.workflowEngagement, WFS.workflowState) to emptyList())
    }

    "the plain read of a form's states answers what the listing shows of it, and only to a caller who may read the form" {
        val client = "wfread1366"
        val admin = defineClient(client)
        val (user, gid) = engagedForm(client)
        val listed = admin.client.sendJsonGetRequest(GEP.formDocs, mapOf(GDF.withStates to true))[EP.items]
            .toJsonListOfMaps().single()[GDF.states].toJsonListOfMaps()
        statesRead(admin, gid) shouldBe listed
        statesRead(user, gid) shouldBe listed
        // Nothing is retired, so asking for retired workflows changes nothing.
        user.getData(GEP.formDocStates, mapOf(GDF.gedraId to gid, GDF.includeRetired to true)).let {
            it[GDF.states] shouldBe listed
            it[GDF.retiredWorkflows] shouldBe emptyList<Any?>()
        }

        val other = TestUser.create(cxt, "other@$client.test", userClient = client)
        other.client.sendJsonGetRequest(GEP.formDocStates, mapOf(GDF.gedraId to gid))["status"] shouldBe 404
    }

    "deleting a workflow past its lifetime is safe: the impact report finds nothing, where deleting a live one does" {
        val client = "wfsafe1354"
        val admin = defineClient(client, sandbox = true)
        engagedForm(client)
        fun impactOfDeleting(): List<Map<String, Any?>> {
            patchFlows(admin, GedraEditAction.deleteOrNoOp, mapOf(CCT.workflowId to "review"))
            return admin.getItem(CFEP.bundleImpact, mapOf(CFEP.names to "flows"))[IMP.findings].toJsonListOfMaps()
        }

        // Live: deleting it loses the form's workflow.
        val review = reviewEntry(admin)
        impactOfDeleting().map { it[IMP.kind] } shouldContain ImpactKind.workflowGone.name

        // Past its lifetime (published, so it is what the client runs), deleting it changes nothing anybody sees.
        patchFlows(admin, GedraEditAction.addOrReplace, review)
        admin.postData(
            UADEP.clientWorkflowWindows,
            mapOf(WFD.workflowId to "review", CWF.windows to WfWindows(lifetime = WfWindow(now - 30.days, now - 1.days)).toInfo(), IMP.acknowledgeImpact to true),
        )
        admin.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("flows"), IMP.acknowledgeImpact to true, CFEP.reloadField to true))
        // Dropping the task the form's state names, from a workflow past its lifetime, strands nothing anybody sees.
        val current = reviewEntry(admin)
        val definition = current[CCT.definition].toJsonMapOrEmpty()
        val withoutCheck = definition + (WFD.tasks to definition[WFD.tasks].toJsonListOfMaps().filter { it[WFD.id] != "check" })
        patchFlows(admin, GedraEditAction.addOrReplace, current + (CCT.definition to withoutCheck))
        admin.getItem(CFEP.bundleImpact, mapOf(CFEP.names to "flows"))[IMP.findings].toJsonListOfMaps().shouldBeEmpty()
        impactOfDeleting().shouldBeEmpty()
    }
})
