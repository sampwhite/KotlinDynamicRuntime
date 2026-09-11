package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfEventType
import com.dynamicruntime.common.gedra.workflow.WfFunction
import com.dynamicruntime.common.gedra.workflow.WfFunctionCreation
import com.dynamicruntime.common.gedra.workflow.WfFunctionUsage
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The workflow-function **second pass** end to end (issue #677): a component registers a function's creation
 * object, a client's workflow declares a usage of it as data, and the boot pass in `WorkflowService.build`
 * resolves the usage into a runnable `WfFunction` in place on the definition. Booted in **warn** mode so every
 * boot check can be observed as a recorded issue rather than a refused boot -- one boot exercises the good path
 * and all four ways a usage is dropped (unknown fn, bad init data, an undeclared literal cfact, wrong scope).
 *
 * The functions here are test fixtures with no execution: A ships only the framework, and the per-event
 * execution interfaces and real functions arrive with issues #678/#679.
 */
class WfFunctionResolutionTest : StringSpec({

    fun boot(): KdrCxt = Startup.mkTestBootCxt(
        "wfFn", "wfFnResolutionTest",
        mapOf(WfFnFixture.loadFlag.name to "true", GCFG.checkEnvVar.name to "warn"),
        additionalComponents = listOf(WfFnFixture()),
    )

    "a declared function usage is resolved onto the definition, in place" {
        val def = WorkflowService.get(boot()).forClient(WfFnFixture.goodClient).workflow("createForm")
            .shouldNotBeNull().def
        def.resolvedFunctions.map { it.fn } shouldContainExactly listOf("testPing")
        def.resolvedFunctions.single().event shouldBe WfEventType.cfactCalc
        def.resolvedFunctions.single().priority shouldBe 5
    }

    "each way a usage is unresolvable is dropped and reported" {
        val issues = WorkflowService.get(boot()).issues.map { it.message }
        fun reported(vararg needles: String) = issues.any { m -> needles.all { m.contains(it) } }
        reported("noSuchFn", "not a registered") shouldBe true
        reported("needsField", "invalid initialization data") shouldBe true
        reported("emitBadCfact", "does not declare") shouldBe true
        reported("testPing", "does not belong on a task") shouldBe true
    }
})

/** A resolved test function -- identity only; A ships no execution (issues #678/#679 add the event interfaces). */
private class TestFn(override val fn: String, override val priority: Int) : WfFunction {
    override val event = WfEventType.cfactCalc
}

/** Builds a [TestFn] for any usage. */
private object TestPingCreation : WfFunctionCreation {
    override val fn = "testPing"
    override val event = WfEventType.cfactCalc
    override fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction = TestFn(fn, usage.priority)
}

/** Validates its own initialization data on create -- a `required` field must be present. */
private object TestNeedsFieldCreation : WfFunctionCreation {
    override val fn = "needsField"
    override val event = WfEventType.cfactCalc
    override fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction {
        if (usage.initData["required"] == null) {
            throw KdrException.mkConv("'$fn' needs a 'required' field in its initialization data.")
        }
        return TestFn(fn, usage.priority)
    }
}

/** Emits a literal cfact the test client never declares -- for the declared-cfact check. */
private object TestBadCfactCreation : WfFunctionCreation {
    override val fn = "emitBadCfact"
    override val event = WfEventType.cfactCalc
    override fun emittedCfacts(usage: WfFunctionUsage): Set<String> = setOf("undeclaredCfact")
    override fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction = TestFn(fn, usage.priority)
}

/** A fixture component: registers the creations, and declares one good and one all-bad client workflow. */
private class WfFnFixture : ComponentDefinition {
    override val providerName: String = "wfFnFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addWorkflowFunction(TestPingCreation)
        collector.addWorkflowFunction(TestNeedsFieldCreation)
        collector.addWorkflowFunction(TestBadCfactCreation)
    }

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(good(cxt), bad(cxt))

    private fun clientDef(id: String, name: String) = ClientDef(
        clientId = id, name = name, description = "Workflow-function resolution fixture.",
        usageType = ClientUsageType.dev, audience = ClientAudience.customer,
        enabledEnvironments = setOf(ENV.unit), includedTraits = listOf(CLD.allGlobal),
    )

    /** One good, workflow-global `cfactCalc` usage that resolves. */
    private fun good(cxt: KdrCxt): GedraConfig =
        gedraConfig(cxt, "wfFnGood", "wffngoodconfig", goodClient) {
            defineClient(clientDef(goodClient, "WfFnGood"))
            workflow("createForm", WfEntry.creation) {
                function(mapOf(WFD.fn to "testPing", WFD.priority to 5))
                task("identify", "Name it") { trait(GT.name); save("create", "Create") }
            }
        }

    /** Every way a usage fails: unknown fn, bad init data, an undeclared cfact (all global), and a global fn on a task. */
    private fun bad(cxt: KdrCxt): GedraConfig =
        gedraConfig(cxt, "wfFnBad", "wffnbadconfig", badClient) {
            defineClient(clientDef(badClient, "WfFnBad"))
            workflow("createForm", WfEntry.creation) {
                function(mapOf(WFD.fn to "noSuchFn"))
                function(mapOf(WFD.fn to "needsField"))
                function(mapOf(WFD.fn to "emitBadCfact"))
                task("identify", "Name it") {
                    trait(GT.name)
                    save("create", "Create")
                    function(mapOf(WFD.fn to "testPing"))
                }
            }
        }

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_WF_FN_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the workflow-function resolution fixture regardless of environment.",
        )
        const val goodClient = "wffngood"
        const val badClient = "wffnbad"
    }
}
