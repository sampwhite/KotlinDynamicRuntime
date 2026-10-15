package com.dynamicruntime.common.flavor

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EVSRC
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.ComponentDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** A variable no process environment sets, for a flavor to default. */
private val flavorTestVar = EnvVarDef(
    "KDR_FLAVOR_TEST_1218", group = ENVGRP.application, defaultDoc = "unset",
    description = "A variable DeployFlavorsTest defaults through a flavor (issue #1218).",
)

/** A component declaring [defs] and contributing [contributions], under [name]. */
private class FlavorComponent(
    override val providerName: String,
    private val defs: List<FlavorDef> = emptyList(),
    private val contributions: List<FlavorContribution> = emptyList(),
) : ComponentDefinition {
    override fun flavorDefs(cxt: KdrCxt) = defs
    override fun flavors(cxt: KdrCxt) = contributions
}

/**
 * Resolving deployment flavors (issue #1218): which are active, the refusals, and what each value does against the
 * deployment's own settings. Resolution is called directly on a hand-built config -- it runs before any service, so
 * nothing else is needed to exercise it; `DeployFlavorsBootTest` covers a flavor's value deciding what loads.
 */
class DeployFlavorsTest : StringSpec({

    val owner = FlavorComponent(
        "owner",
        defs = listOf(FlavorDef("alpha", "The first test flavor."), FlavorDef("beta", "The second test flavor.")),
        contributions = listOf(
            flavor("alpha") {
                set("shade", "alphaShade")
                set("alphaOnly", true)
                default(flavorTestVar, "fromAlpha")
            },
            flavor("beta") { set("shade", "betaShade") },
        ),
    )

    /** Resolves [components] on a fresh config carrying [settings], returning the config and what was decided. */
    fun resolve(
        settings: Map<String, Any?>,
        components: List<ComponentDefinition> = listOf(owner),
    ): Pair<KdrInstanceConfig, DeployFlavors> {
        val config = KdrInstanceConfig("flavors1218", ENV.unit, ENV.liveSource)
        config.putAll(settings)
        return config to DeployFlavors.resolve(KdrCxt.mkSimpleCxt("flavors", config), components)
    }

    fun refusal(settings: Map<String, Any?>, components: List<ComponentDefinition> = listOf(owner)): String =
        shouldThrow<KdrException> { resolve(settings, components) }.message.orEmpty()

    val flavors = FLV.deployFlavorsEnvVar.name

    "with no flavors named, nothing is active and nothing is set" {
        val (config, result) = resolve(emptyMap())
        result.active.shouldBeEmpty()
        result.declared.map { it.name } shouldContainExactly listOf("alpha", "beta")
        config.get("shade") shouldBe null
        config.resolveEnvVar(flavorTestVar).source shouldBe EVSRC.unset
        resolve(mapOf(flavors to FLV.none)).second.active.shouldBeEmpty()
    }

    "an active flavor sets its config keys and defaults its variables" {
        val (config, result) = resolve(mapOf(flavors to " alpha "))
        result.active shouldContainExactly listOf("alpha")
        config.get("shade") shouldBe "alphaShade"
        config.get("alphaOnly") shouldBe true
        val resolution = config.resolveEnvVar(flavorTestVar)
        resolution.value shouldBe "fromAlpha"
        resolution.source shouldBe EVSRC.flavor
        result.values.all { it.applied && it.component == "owner" } shouldBe true
    }

    "an explicit setting beats the flavor, and the report says what did" {
        val (config, result) = resolve(mapOf(flavors to "alpha", "shade" to "mine", flavorTestVar.name to "explicit"))
        config.get("shade") shouldBe "mine"
        config.resolveEnvVar(flavorTestVar).let { it.value to it.source } shouldBe ("explicit" to EVSRC.config)
        val byName = result.values.associateBy { it.name }
        byName.getValue("shade").overriddenBy shouldBe "instance config"
        byName.getValue(flavorTestVar.name).applied shouldBe false
        byName.getValue("alphaOnly").applied shouldBe true
    }

    "the later flavor in the variable wins where two set one key" {
        resolve(mapOf(flavors to "alpha,beta")).let { (config, result) ->
            config.get("shade") shouldBe "betaShade"
            result.values.single { it.flavor == "alpha" && it.name == "shade" }.overriddenBy shouldBe "flavor 'beta'"
            // What only the earlier flavor sets still applies.
            config.get("alphaOnly") shouldBe true
        }
        resolve(mapOf(flavors to "beta,alpha")).first.get("shade") shouldBe "alphaShade"
    }

    "the variable must name declared flavors, each once, and 'none' alone" {
        refusal(mapOf(flavors to "alpha,gamma")) shouldContain "[gamma] that no component declares"
        refusal(mapOf(flavors to "alpha,alpha")) shouldContain "more than once"
        refusal(mapOf(flavors to "none,alpha")) shouldContain "beside other flavors"
    }

    "a flavor is declared once, and contributed to only once declared" {
        refusal(emptyMap(), listOf(owner, FlavorComponent("again", defs = listOf(FlavorDef("alpha", "Again.")))))
            .shouldContain("declared twice")
        refusal(emptyMap(), listOf(owner, FlavorComponent("stray", contributions = listOf(flavor("gamma") { set("k", 1) }))))
            .shouldContain("'stray' contributes to the deployment flavor 'gamma'")
    }

    "two contributions to one flavor may not set the same value, active or not" {
        // Checked with no flavor active: a mistake in a flavor this node is not running still fails.
        val clash = FlavorComponent("clash", contributions = listOf(flavor("beta") { set("shade", "other") }))
        refusal(emptyMap(), listOf(owner, clash)) shouldContain "sets 'shade' twice: from 'owner' and from 'clash'"
        val envClash = FlavorComponent("envClash", contributions = listOf(flavor("alpha") { default(flavorTestVar, "x") }))
        refusal(emptyMap(), listOf(owner, envClash)) shouldContain "sets '${flavorTestVar.name}' twice"
    }

    "a flavor may not default a variable read before flavors resolve" {
        val early = FlavorComponent("early", contributions = listOf(flavor("alpha") { default(FLV.deployFlavorsEnvVar, "beta") }))
        refusal(emptyMap(), listOf(owner, early)) shouldContain "read before flavors are resolved"
    }

    "the operator report lists what was declared, what is active, and every value" {
        val report = resolve(mapOf(flavors to "alpha,beta")).second.toReport()
        report[FLV.active] shouldBe listOf("alpha", "beta")
        (report[FLV.values] as List<*>).size shouldBe 4
    }
})
