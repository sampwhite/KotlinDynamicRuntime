package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.SRJ
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.job.JPF
import com.dynamicruntime.common.job.JobAttemptEnd
import com.dynamicruntime.common.job.JobClaim
import com.dynamicruntime.common.job.JobCounts
import com.dynamicruntime.common.job.JobLaunch
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobStatusRows
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/**
 * Recalculating a client's form states from its page (issue #1412): a workflow added after forms exist is missing from
 * their stored state until something recalculates it, and the client-admin endpoint does that now -- synchronously, as
 * a client launch of the nightly job, within the job profile's synchronous cap.
 *
 * Its own instance: the cap is the instance's job-profile configuration, set here to [cap] so that a client over it
 * needs only a few forms. A client per case.
 */
class FormStatesRecalcTest : StringSpec({
    val cap = 3
    val cxt = Startup.mkTestBootCxt(
        "formStatesRecalc1412", "formStatesRecalc1412",
        mapOf(JPF.jobProfiles to mapOf(SRJ.profile to mapOf(JPF.syncTaskCap to cap))),
    )
    val svc = GedraConfigService.get(cxt)

    fun write(client: String, name: String, build: GedraConfigBuilder.() -> Unit) =
        svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 14120L }, gedraConfig(cxt, name, clientNamespace(client), client, build = build))

    /** Defines [client], with a trait and no workflow yet, and returns its administrator. */
    fun defineClient(client: String): TestUser {
        write(client, "main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
        }
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    /** Adds the workflow `review` to [client]'s configuration, which no form has been recalculated against yet. */
    fun addWorkflow(client: String) {
        write(client, "flows") {
            workflow("review", WfEntry.normal) {
                task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
            }
        }
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun forms(client: String, n: Int): List<String> {
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        return (1..n).map {
            val entry = mapOf(GE.traitId to "memo", GE.data to mapOf("text" to "m$it"))
            user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))[GDF.gedraId].toOptStr()!!
        }
    }

    /** The workflows [gid]'s stored state has an entry for. */
    fun storedWorkflows(client: String, gid: String): List<String?> =
        GedraDataService.get(cxt).readState(cxt.mkSubContext("check", client), GedraId.parse(gid), ReadScope.ofClient(client))
            .filter { it[GE.traitId] == WFS.workflowState }
            .map { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() }

    fun recalc(admin: TestUser, client: String): Map<String, Any?> =
        admin.postData(UADEP.clientFormStatesRecalc, mapOf(CLD.client to client))

    "a workflow added after forms exist reaches them once their states are recalculated, and a second run finds them current" {
        val client = "fsrAdd1412"
        val admin = defineClient(client)
        val gids = forms(client, 3)
        addWorkflow(client)
        gids.forEach { storedWorkflows(client, it) shouldNotContain "review" }

        val result = recalc(admin, client)
        result[FSR.outcome] shouldBe FSR.completed
        result[FSR.updated] shouldBe 3L
        result[FSR.current] shouldBe 0L
        result[FSR.failed] shouldBe 0L
        result[FSR.total] shouldBe 3L
        gids.forEach { storedWorkflows(client, it) shouldContain "review" }

        val again = recalc(admin, client)
        again[FSR.outcome] shouldBe FSR.completed
        again[FSR.updated] shouldBe 0L
        again[FSR.current] shouldBe 3L
    }

    "the page learns the limit from the overview, and a client over it is refused before anything runs" {
        val client = "fsrOver1412"
        val admin = defineClient(client)
        val gids = forms(client, cap + 1)
        addWorkflow(client)
        val row = admin.getItems(UADEP.clientsOverview).single { it[CLD.clientId] == client }
        row[CLD.forms] shouldBe (cap + 1).toLong()
        row[FSR.count] shouldBe (cap + 1).toLong()
        row[FSR.limit] shouldBe cap.toLong()

        admin.expectError(EXC.badInput, UADEP.clientFormStatesRecalc, mapOf(CLD.client to client))[EP.errorMessage]
            .toOptStr()!! shouldContain "holds ${cap + 1} forms, more than the $cap"
        storedWorkflows(client, gids.first()) shouldNotContain "review"
    }

    "each client's recalculation has a launch row of its own, which leaves the operator's endpoint row alone" {
        val first = "fsrRowA1412"
        val second = "fsrRowB1412"
        recalc(defineClient(first), first)[FSR.outcome] shouldBe FSR.completed
        recalc(defineClient(second), second)[FSR.outcome] shouldBe FSR.completed
        val launches = JobStatusRows.readLaunches(cxt, SRJ.jobType)
        launches.filter { it.launchKind == JobLaunchKind.client }.map { it.launchClient } shouldContainAll listOf(first, second)
        JobStatusRows.readLaunch(cxt, SRJ.jobType, JobLaunchKind.endpoint) shouldBe null
    }

    "while another launch holds the client, the recalculation answers busy and does nothing" {
        val client = "fsrBusy1412"
        val admin = defineClient(client)
        val gid = forms(client, 1).single()
        addWorkflow(client)
        // Another client's launch holding this client's row stands in for the nightly run, which would hold it so.
        val holder = JobStatusRows.claimLaunch(cxt, JobLaunch(SRJ.jobType, JobLaunchKind.client, "holder", client = "fsrHolder1412"), 30.seconds)
            .shouldBeInstanceOf<JobClaim.Claimed>().lease
        val held = JobStatusRows.claimClient(cxt, holder, client, 30.seconds).shouldBeInstanceOf<JobClaim.Claimed>().lease
        try {
            val result = recalc(admin, client)
            result[FSR.outcome] shouldBe FSR.busy
            result[FSR.reason].toOptStr()!! shouldContain "Another recalculation"
            result[FSR.updated] shouldBe null
            storedWorkflows(client, gid) shouldNotContain "review"
        } finally {
            JobStatusRows.finish(cxt, held, JobAttemptEnd.complete, JobCounts.zero)
            JobStatusRows.finish(cxt, holder, JobAttemptEnd.complete, JobCounts.zero)
        }
        // Once it ends, the recalculation runs.
        recalc(admin, client)[FSR.updated] shouldBe 1L
    }

    "a client administrator recalculates only their own client" {
        val mine = "fsrMine1412"
        val theirs = "fsrTheirs1412"
        val admin = defineClient(mine)
        defineClient(theirs)
        admin.expectError(EXC.notAuthorized, UADEP.clientFormStatesRecalc, mapOf(CLD.client to theirs))
        recalc(admin, mine)[FSR.outcome] shouldBe FSR.completed
    }
})
