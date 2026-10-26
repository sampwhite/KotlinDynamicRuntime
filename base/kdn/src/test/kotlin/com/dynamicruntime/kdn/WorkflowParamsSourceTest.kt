package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsFrom
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.gedra.workflow.WorkflowParamsService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A client declared in source code with default parameters for its normal workflow (issue #1386), and a sandbox. */
class SourceParamsComponent : ComponentDefinition {
    override val providerName: String = "sourceParamsFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Source params", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name), sandbox = true,
                ),
            )
            workflow("review", WfEntry.normal) { task("record", "Record it") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
            workflowParams("review") { lifetime(start = defaultStart, end = defaultEnd) }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val client = "srcparams1386"
        const val defaultStart = "2026-01-01T00:00:00Z"
        const val defaultEnd = "2099-01-01T00:00:00Z"
    }
}

/** A source default naming no workflow of its client: a mistake in the code, which refuses the boot. */
class BadSourceParamsComponent : ComponentDefinition {
    override val providerName: String = "badSourceParamsFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "main", clientNamespace("badparams1386"), "badparams1386") {
            defineClient(
                ClientDef(
                    clientId = "badparams1386", name = "Bad params", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            workflowParams("nosuch") { lifetime(end = "2099-01-01T00:00:00Z") }
        },
    )
}

/**
 * A workflow's parameters from source code (issue #1386): a client's source configuration declares defaults, which
 * the client runs until it sets its own, and a sandbox until it or its client does -- **each parameter on its own**, so
 * a scope follows the fallback for what it does not set, and unsetting returns to it. Only source code declares them.
 */
class WorkflowParamsSourceTest : StringSpec({
    // Its own instance (issue #1075): the client is declared by a component, in source.
    val cxt = Startup.mkTestBootCxt("wfParamsSource", "wfParamsSource1386", additionalComponents = listOf(SourceParamsComponent()))
    val client = SourceParamsComponent.client
    val sandbox = sandboxOf(client)
    val params = WorkflowParamsService.get(cxt)
    val defaults = WfWindows(
        lifetime = WfWindow(SourceParamsComponent.defaultStart.parseDate(), SourceParamsComponent.defaultEnd.parseDate()),
    )
    val now = Instant.fromEpochSeconds(cxt.instanceNow().epochSeconds)
    val full = TestUser.createFullAdmin(cxt, "full@$client.test")

    fun request(scope: String, windows: WfWindows?) = buildMap {
        put(CLD.client, scope)
        put(WFD.workflowId, "review")
        if (windows == null) put(WFP.unset, listOf(WFP.windows)) else put(WFP.params, WfParams(windows).toInfo())
    }

    fun shown(scope: String): Map<String, Any?> = full.getItem(UADEP.clientWorkflows, mapOf(CLD.client to scope))[CWF.workflows]
        .toJsonListOfMaps().single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()

    fun fromOf(scope: String): String? = shown(scope)[WFP.from].toJsonMapOrEmpty()[WFP.windows] as String?

    "with nothing set, the client and its sandbox run the source code's defaults" {
        params.windowsFor(cxt, client, "review") shouldBe defaults
        params.windowsFor(cxt, sandbox, "review") shouldBe defaults
        fromOf(client) shouldBe WfParamsFrom.source.name
        fromOf(sandbox) shouldBe WfParamsFrom.source.name
        WfParams.fromInfo(shown(client)[WFP.source]).windows shouldBe defaults
    }

    "each scope's own windows win, unsetting them returns to the fallback, and the history keeps every step" {
        val clientOwn = WfWindows(lifetime = WfWindow(end = now + 40.days))
        val sandboxOwn = WfWindows(lifetime = WfWindow(end = now + 10.days))
        // The client sets its own: the sandbox now follows the client's, not the source code's.
        full.postData(UADEP.clientWorkflowParams, request(client, clientOwn))
        params.windowsFor(cxt, client, "review") shouldBe clientOwn
        params.windowsFor(cxt, sandbox, "review") shouldBe clientOwn
        fromOf(client) shouldBe null
        fromOf(sandbox) shouldBe WfParamsFrom.parent.name
        // The sandbox sets its own, then unsets them: back to its client's.
        full.postData(UADEP.clientWorkflowParams, request(sandbox, sandboxOwn))
        params.windowsFor(cxt, sandbox, "review") shouldBe sandboxOwn
        full.postData(UADEP.clientWorkflowParams, request(sandbox, null))
        params.windowsFor(cxt, sandbox, "review") shouldBe clientOwn
        // Its own row stays, with what it set kept in the history.
        shown(sandbox)[WFP.history].toJsonListOfMaps() shouldHaveSize 1
        // No limits, set deliberately, is the client's own and hides the source code's defaults.
        full.postData(UADEP.clientWorkflowParams, request(client, WfWindows.none))
        params.windowsFor(cxt, client, "review") shouldBe WfWindows.none
        // Unset, the client runs the source code's defaults again, and so does the sandbox.
        full.postData(UADEP.clientWorkflowParams, request(client, null))
        params.windowsFor(cxt, client, "review") shouldBe defaults
        params.windowsFor(cxt, sandbox, "review") shouldBe defaults
        shown(client)[WFP.history].toJsonListOfMaps() shouldHaveSize 2
        // Unsetting what is not set is nothing to save.
        full.expectError(EXC.badInput, UADEP.clientWorkflowParams, request(client, null)).toString() shouldContain "nothing to save"
    }

    "only source code declares defaults" {
        // A stored configuration cannot hold them: what the database holds is a scope's own values.
        val written = shouldThrow<KdrException> {
            GedraConfigService.get(cxt).writeConfig(
                cxt.mkSubContext("setup", "storedparams1386").also { it.userId = 13860L },
                gedraConfig(cxt, "main", clientNamespace("storedparams1386"), "storedparams1386") {
                    workflow("review", WfEntry.normal) { task("t", "T") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
                    workflowParams("review") { lifetime(end = "2099-01-01T00:00:00Z") }
                },
            )
        }
        written.message.orEmpty() shouldContain "only source code declares"
        // Nor a global one, since a template has no windows, nor a sandbox's overlay.
        shouldThrow<KdrException> {
            gedraConfig(cxt, "templates", "kdr.templates") { workflowParams("review") { lifetime(end = "2099-01-01T00:00:00Z") } }
        }.message.orEmpty() shouldContain "global configuration"
        shouldThrow<KdrException> {
            gedraConfig(cxt, "overlay", clientNamespace(client), sandbox) { workflowParams("review") { lifetime(end = "2099-01-01T00:00:00Z") } }
        }.message.orEmpty() shouldContain "a sandbox follows its client's"
        // And a default naming no workflow of the client's source refuses the boot, as a mistake in source does.
        shouldThrow<KdrException> {
            // Its own instance (issue #1075): the boot itself is under test.
            Startup.mkTestBootCxt("badParams", "badSourceParams1386", additionalComponents = listOf(BadSourceParamsComponent()))
        }.message.orEmpty() shouldContain "name no workflow"
    }
})
