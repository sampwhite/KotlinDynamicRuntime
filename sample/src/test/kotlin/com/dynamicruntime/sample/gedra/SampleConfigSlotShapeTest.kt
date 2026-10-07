package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.gedra.configSlotFailures
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Every configuration the sample declares -- clients with workflows, reports, usage rules, overlays and types of
 * their own -- passes the config slot gate (issue #1052) in the form the serializer stores it in. The gate judges a
 * whole configuration on a patch, so anything the serializer can write must be something the gate accepts: a
 * configuration promoted from source, or read and written back, is never refused for its own canonical form.
 */
class SampleConfigSlotShapeTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "sampleSlotShape", "sampleConfigSlotShapeTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    "the stored form of every source configuration is one the gate accepts" {
        val configs = SchemaCollector.get(cxt).shouldNotBeNull().gedraConfigs.configs
            // What a client's configuration may hold: the serializer refuses a config declaring config or state traits.
            .filter { it.configTraits.isEmpty() && it.stateTraits.isEmpty() }
        // The sample's clients are among them, not only the core's.
        configs.any { it.gedraId.client == SC.acme && it.workflows.isNotEmpty() } shouldBe true
        configs.any { it.reports.isNotEmpty() } shouldBe true
        configs.any { it.usages.isNotEmpty() } shouldBe true
        for (config in configs) {
            configSlotFailures(cxt, gedraConfigToEntries(config)).map { "${config.gedraId}: ${it.path}: ${it.message}" }.shouldBeEmpty()
        }
    }
})
