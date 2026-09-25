package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.gedra.GedraConfigTrial
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * A trial reload (issue #843) leaves the running node exactly as it found it. The case worth pinning is a client with
 * **source-code** bundles -- sample `acme` -- because the trial's scratch collector shares those bundle objects with
 * the node: resolving their workflow functions there once reassigned each definition's `resolvedFunctions`, so a
 * refused write could still change what the node's workflows run.
 */
class ConfigTrialIsolationTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "trialIso", "configTrialIsolationTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    "a trial does not touch the resolved functions of a client's source-code workflows" {
        val bundles = SchemaCollector.get(cxt)!!.gedraConfigs.configs
            .filter { it.gedraId.client == SC.acme && !it.isStored }
        val defs = bundles.flatMap { it.workflows.values }
        val before = defs.associateWith { def -> def.resolvedFunctions to def.tasks.map { it.resolvedFunctions } }
        (defs.flatMap { d -> d.tasks.flatMap { it.resolvedFunctions } }.isNotEmpty()) shouldBe true

        // acme has no stored config here, so the trial judges its source bundles alone -- which is all it takes to
        // walk their workflows.
        GedraConfigTrial.trial(cxt, SC.acme).shouldBeEmpty()

        for (def in defs) {
            val (defFns, taskFns) = before.getValue(def)
            (def.resolvedFunctions === defFns) shouldBe true
            def.tasks.map { it.resolvedFunctions }.zip(taskFns).all { (now, was) -> now === was } shouldBe true
        }
    }
})
