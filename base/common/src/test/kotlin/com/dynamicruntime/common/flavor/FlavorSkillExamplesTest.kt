package com.dynamicruntime.common.flavor

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.startup.ComponentDefinition
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Stands in for an existing variable, as the skill's `sampleLoadEnvVar` does. */
private val sampleLoadEnvVar = EnvVarDef(
    "KDR_FLAVOR_SKILL_EXAMPLE", group = ENVGRP.application, defaultDoc = "unset",
    description = "The kdr-deploy-flavors skill's example variable.",
)

// Transcribed from the skill's "Declaring and contributing" example.
private class ReferenceComponent : ComponentDefinition {
    override fun flavorDefs(cxt: KdrCxt) = listOf(FlavorDef("reference", "A public reference deployment."))

    override fun flavors(cxt: KdrCxt) = listOf(
        flavor("reference") {
            set("confDemoDocLinks", true)
            default(sampleLoadEnvVar, "true")
        },
    )
}

/** The `kdr-deploy-flavors` skill's examples, compiled and run, so a signature change breaks the build. */
class FlavorSkillExamplesTest : StringSpec({

    "the skill's component declares a flavor and sets its values when it is active" {
        val config = KdrInstanceConfig("flavorSkill", ENV.unit, ENV.liveSource)
        config.put(FLV.deployFlavorsEnvVar.name, "reference")
        DeployFlavors.resolve(KdrCxt.mkSimpleCxt("flavorSkill", config), listOf(ReferenceComponent()))
        config.get("confDemoDocLinks") shouldBe true
        config.getEnvVar(sampleLoadEnvVar) shouldBe "true"
    }
})
