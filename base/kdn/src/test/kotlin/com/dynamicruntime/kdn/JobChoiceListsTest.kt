package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.SRJ
import com.dynamicruntime.common.job.JOBEP
import com.dynamicruntime.common.job.JOBF
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The choice lists on the job operator surface (issue #872): every `jobType` field offers the job types this node
 * registered, and `/operator/job/launch`'s `clients` offers the clients it carries -- closed choices in the UI,
 * resolved per caller as the catalog is served. Neither list is enforced by validation; the handler refuses an
 * unknown job type or client itself.
 */
class JobChoiceListsTest : StringSpec({
    // Env-authed, as ClientOptionsTest explains: the caller must see the catalog endpoint itself.
    val cxt = Startup.mkTestBootCxt(
        "jobChoices", "jobChoiceListsTest", mapOf(ACFG.assumeEnvAuth to true),
        additionalComponents = listOf(ChoiceFixture()),
    )

    /** [field] of [path]'s input schema, as [user] is served it. */
    fun inputField(user: TestUser, path: String, field: String): Map<String, Any?> =
        user.getData("/schema/endpoints", mapOf(SS.pathRegex to path))[EI.endpoints]
            .toJsonListOfMaps().single { it[EI.path].toOptStr() == path }[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[field].toJsonMapOrEmpty()

    /** The values offered by a choice list, in the order offered. */
    fun values(choices: Any?): List<String?> = choices.toJsonListOfMaps().map { it[SCH.value].toOptStr() }

    "every jobType field offers the registered job types" {
        val opal = TestUser.createOperator(cxt, "choices-opal@example.com")
        val expected = listOf("jcAlpha", "jcBeta", SRJ.jobType).sorted()
        for (path in listOf(JOBEP.launch, JOBEP.status, JOBEP.abort, JOBEP.trace)) {
            values(inputField(opal, path, JOBF.jobType)[SCH.options]) shouldContainExactly expected
        }
    }

    "the launch's clients offer the clients this node carries" {
        val opal = TestUser.createOperator(cxt, "choices-opal2@example.com")
        val items = inputField(opal, JOBEP.launch, JOBF.clients)[SCH.items].toJsonMapOrEmpty()
        val clients = values(items[SCH.options])
        clients shouldContain CL.hub
        clients shouldContain CL.public
        // A closed choice: the form draws a multi-select of these, not a free-text tag list.
        items[SCH.openOptions] shouldBe null
    }
})

/** Two job types for the lists to offer, beside the built-in one. */
private class ChoiceFixture : ComponentDefinition {
    override val providerName: String = "jobChoiceFixture"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        for (type in listOf("jcBeta", "jcAlpha")) {
            collector.addJob(JobDef(type, "A choice-list test job.", JobProfile("jobChoices"), { _, _ -> emptyList() }, { _, _ -> JobTaskResult.done }))
        }
    }
}
