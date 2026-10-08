package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.job.JOB
import com.dynamicruntime.common.job.JOBEP
import com.dynamicruntime.common.job.JOBX
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobExceptionRows
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.job.JobTraceEvent
import com.dynamicruntime.common.job.JobTraceRows
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.minutes

/**
 * The job-exceptions table (issue #871): per gedra, the jobs whose tasks failed on it -- recorded, updated,
 * cleared, and read under a caller's scope -- and the runner recording and clearing entries as its tasks fail and
 * succeed.
 */
class JobExceptionsTest : StringSpec({
    // Its own instance (issue #1075): it runs the job machinery, which is instance-wide, with ExceptionFixture's jobs,
    // and freezes the clock.
    val cxt = Startup.mkTestBootCxt("jobExceptions", "jobExceptionsTest", additionalComponents = listOf(ExceptionFixture()))
    cxt.instanceConfig.clock.freeze()

    val client = "jxclient"
    val ownerId = 90701L

    /** A new form in [inClient], owned by [owner]: something for a failure to be recorded against. */
    fun form(inClient: String = client, owner: Long = ownerId): String =
        GedraDataService.get(cxt).createGedra(
            cxt.mkSubContext("jx", inClient).also { it.userId = owner }, GedraDataType.formDoc,
            listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "A form"))),
        ).gedraId.fullId

    fun entries(gedraId: String, scope: ReadScope = ReadScope.unrestricted) = JobExceptionRows.read(cxt, gedraId, scope)

    fun dataOf(entry: Map<String, Any?>) = entry[GE.data].toJsonMapOrEmpty()

    "a first failure records an entry of the jobException trait" {
        val gid = form()
        JobExceptionRows.record(cxt, "jxAlpha", gid, "testBroke", "It broke.", mapOf("formName" to "A form"), "run1") shouldBe true
        val entry = entries(gid).single()
        entry[GE.traitId] shouldBe JOBX.traitId
        entry[GE.createdAt] shouldBe entry[GE.updatedAt]
        val data = dataOf(entry)
        data[JOB.jobType] shouldBe "jxAlpha"
        data[JOBX.scenario] shouldBe "testBroke"
        data[JOBX.message] shouldBe "It broke."
        data[JOBX.count] shouldBe 1L
        data[JOBX.details].toJsonMapOrEmpty()["formName"] shouldBe "A form"
    }

    "a repeat bumps the count, keeps when it first failed, and moves the last failure into its history" {
        val gid = form()
        JobExceptionRows.record(cxt, "jxAlpha", gid, "testBroke", "It broke.")
        val created = entries(gid).single()[GE.createdAt]
        cxt.instanceConfig.clock.advanceBy(5.minutes)
        JobExceptionRows.record(cxt, "jxAlpha", gid, "testBrokeAgain", "It broke again.")

        val entry = entries(gid).single()
        entry[GE.createdAt] shouldBe created
        entry[GE.updatedAt] shouldNotBe created
        val data = dataOf(entry)
        data[JOBX.count] shouldBe 2L
        data[JOBX.message] shouldBe "It broke again."
        data[JOBX.history].toJsonListOfMaps().single()[JOBX.message] shouldBe "It broke."
    }

    "each job keeps its own entry, and a success clears only that job's" {
        val gid = form()
        JobExceptionRows.record(cxt, "jxAlpha", gid, "testBroke", "Alpha broke.")
        JobExceptionRows.record(cxt, "jxBeta", gid, "testBroke", "Beta broke.")
        entries(gid).map { dataOf(it)[JOB.jobType] } shouldContainExactly listOf("jxAlpha", "jxBeta")

        JobExceptionRows.clear(cxt, "jxAlpha", gid) shouldBe true
        entries(gid).map { dataOf(it)[JOB.jobType] } shouldContainExactly listOf("jxBeta")
        JobExceptionRows.clear(cxt, "jxAlpha", gid) shouldBe false
    }

    "a read applies the caller's scope, as a gedra read does" {
        val gid = form()
        JobExceptionRows.record(cxt, "jxAlpha", gid, "testBroke", "It broke.")
        entries(gid, ReadScope.ofClient(client)).size shouldBe 1
        entries(gid, ReadScope.ofUser(ownerId)).size shouldBe 1
        entries(gid, ReadScope.ofClient("jxotherclient")).shouldBeEmpty()
        entries(gid, ReadScope.ofUser(ownerId + 1)).shouldBeEmpty()
    }

    "only a gedra that exists can have a failure recorded against it" {
        JobExceptionRows.record(cxt, "jxAlpha", "not-a-gedra-id", "testBroke", "It broke.") shouldBe false
        val gone = form()
        GedraDataService.get(cxt).deleteGedra(cxt, gone, GedraDataType.formDoc, ReadScope.unrestricted) shouldBe true
        JobExceptionRows.record(cxt, "jxAlpha", gone, "testBroke", "It broke.") shouldBe false
        entries(gone).shouldBeEmpty()
    }

    "a run records its failed tasks against their gedras, and a later success clears them" {
        val broken = form(CL.hub, 90702L)
        val fine = form(CL.hub, 90702L)
        var failing = true
        JobScripts.set("jxRun", mapOf(CL.hub to listOf(broken, fine))) { _, key ->
            // No resource named on the failure: the job's resourceOf supplies it from the task key.
            if (key == broken && failing) throw KdrException.mkJob("Cannot recompute.", JobHandling.skipTask, "testRecompute")
            JobTaskResult.done
        }
        fun launch(name: String, dryRun: Boolean = false) =
            JobService.get(cxt).launch(cxt, "jxRun", name, mode = JobRunMode.sync, clients = listOf(CL.hub), dryRun = dryRun)

        launch("run1").outcome shouldBe JobLaunchOutcome.completed
        val recorded = entries(broken).single()
        dataOf(recorded)[JOBX.scenario] shouldBe "testRecompute"
        dataOf(recorded)[JOB.launchName] shouldBe "run1"
        entries(fine).shouldBeEmpty()
        JobTraceRows.read(cxt, "jxRun", JobLaunchKind.endpoint, "run1").single { it.event == JobTraceEvent.taskFailed }
            .data[JOBX.recorded] shouldBe true

        // A dry run neither records nor clears.
        failing = false
        launch("dry1", dryRun = true)
        entries(broken).size shouldBe 1

        launch("run2").outcome shouldBe JobLaunchOutcome.completed
        entries(broken).shouldBeEmpty()
    }

    "a resourceOf that throws is treated as naming no gedra, and the run goes on" {
        JobScripts.set("jxBadMap", mapOf(CL.hub to listOf("bad", "ok"))) { _, key ->
            if (key == "bad") throw KdrException.mkJob("It broke.", JobHandling.skipTask, "testBroke")
            JobTaskResult.done
        }
        val result = JobService.get(cxt).launch(cxt, "jxBadMap", "run1", mode = JobRunMode.sync, clients = listOf(CL.hub))
        result.outcome shouldBe JobLaunchOutcome.completed
        result.counts?.failed shouldBe 1L
        result.counts?.completed shouldBe 1L
        val failed = JobTraceRows.read(cxt, "jxBadMap", JobLaunchKind.endpoint, "run1").single { it.event == JobTraceEvent.taskFailed }
        failed.data[JOBX.recorded] shouldBe false
    }

    "the operator surface reads a gedra's recorded failures within the caller's reach" {
        val gid = form()
        JobExceptionRows.record(cxt, "jxAlpha", gid, "testBroke", "It broke.")
        // A full administrator reaches every client's data, so sees the gedra's failures.
        val ada = TestUser.createFullAdmin(cxt, "jx-ada@example.com")
        ada.getItems(JOBEP.exceptions, mapOf(JOBX.gedraId to gid))
            .map { it[GE.data].toJsonMapOrEmpty()[JOB.jobType] } shouldContainExactly listOf("jxAlpha")
        // A deployment operator reaches the section, but is not an administrator of anyone's data: the read is
        // scoped as every read is, and finds nothing of theirs.
        val opal = TestUser.createOperator(cxt, "jx-opal@example.com")
        opal.getItems(JOBEP.exceptions, mapOf(JOBX.gedraId to gid)).shouldBeEmpty()
    }
})

/** Registers the job [JobExceptionsTest] runs: its tasks are gedra ids, so its resource is its task key. */
private class ExceptionFixture : ComponentDefinition {
    override val providerName: String = "jobExceptionsFixture"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addJob(
            JobDef(
                "jxRun", "Scripted exceptions-test job.", JobProfile("jobExceptionsTest"),
                tasks = { _, c -> JobScripts.of("jxRun").tasks[c].orEmpty() },
                runTask = { run, key -> JobScripts.of("jxRun").body(run, key) },
                countTasks = { _, c -> JobScripts.of("jxRun").tasks[c].orEmpty().size },
                resourceOf = { it },
            ),
        )
        // A job whose key-to-gedra mapping throws, as one parsing every key as a gedra id would.
        collector.addJob(
            JobDef(
                "jxBadMap", "Scripted job whose resourceOf throws.", JobProfile("jobExceptionsBadMap"),
                tasks = { _, c -> JobScripts.of("jxBadMap").tasks[c].orEmpty() },
                runTask = { run, key -> JobScripts.of("jxBadMap").body(run, key) },
                countTasks = { _, c -> JobScripts.of("jxBadMap").tasks[c].orEmpty().size },
                resourceOf = { throw KdrException("'$it' is not a gedra id.") },
            ),
        )
    }
}
