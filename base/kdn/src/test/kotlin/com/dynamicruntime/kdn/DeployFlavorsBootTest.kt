package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.flavor.FlavorDef
import com.dynamicruntime.common.flavor.flavor
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val gateFlavor = "gate1218"
private const val gateKey = "gate1218Loads"
private const val loadedMarker = "gate1218Applied"

/** Owns the test flavor, which sets [gateKey]. */
private class GateFlavorComponent : ComponentDefinition {
    override fun flavorDefs(cxt: KdrCxt) = listOf(FlavorDef(gateFlavor, "Loads the gated test component (issue #1218)."))
    override fun flavors(cxt: KdrCxt) = listOf(flavor(gateFlavor) { set(gateKey, true) })
}

/** Loads only where [gateKey] is set -- reading the value, as code does, never the flavor. Marks the config when loaded. */
private class GatedComponent : ComponentDefinition {
    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.instanceConfig.get(gateKey) == true
    override fun applyInstanceConfig(cxt: KdrCxt) = cxt.instanceConfig.put(loadedMarker, true)
}

/**
 * A deployment flavor's value deciding what loads (issue #1218): flavors resolve before the load decisions, so a
 * component's `isLoaded` can read a value a flavor set. Two private instances, with and without the flavor -- what
 * they differ in is their component set and flavor, which no shared setup carries.
 */
class DeployFlavorsBootTest : StringSpec({

    fun boot(name: String, flavors: String?): KdrCxt = Startup.mkTestBootCxt(
        name, name, flavors?.let { mapOf(FLV.deployFlavorsEnvVar.name to it) } ?: emptyMap(),
        additionalComponents = listOf(GateFlavorComponent(), GatedComponent()),
    )

    "a component loads where a flavor's value says to, and not otherwise" {
        boot("flavorGateOn1218", gateFlavor).instanceConfig.get(loadedMarker) shouldBe true
        boot("flavorGateOff1218", null).instanceConfig.get(loadedMarker) shouldBe null
    }

    "the operator view reports the flavor and the value it set" {
        val cxt = boot("flavorGateOn1218", gateFlavor)
        val report = TestUser.createOperator(cxt, "ops@flavors1218.test").getData(FLV.operatorPath)
        report[FLV.active] shouldBe listOf(gateFlavor)
        val value = report[FLV.values].toJsonListOfMaps().single()
        value[FLV.name] shouldBe gateKey
        value[FLV.applied] shouldBe true
    }

    // --- fragment layers (issue #1219) ---------------------------------------------------------------------

    "an active flavor's overlay reaches the copy, over the component's own" {
        val cxt = Startup.mkTestBootCxt(
            "flavorCopy1219", "flavorCopy1219", mapOf(FLV.deployFlavorsEnvVar.name to "copy1219"),
            additionalComponents = listOf(CopyFlavorComponent("title")),
        )
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, null)!!
            .content.getValue("home")["title"] shouldBe "Flavored welcome"
    }

    "a flavor's overlay naming a key its base lacks refuses the boot" {
        // What the boot-once-per-flavor test relies on: the fragment check runs over the active flavor's layers.
        val failure = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "flavorOrphan1219", "flavorOrphan1219", mapOf(FLV.deployFlavorsEnvVar.name to "copy1219"),
                additionalComponents = listOf(CopyFlavorComponent("noSuchKey1219")),
            )
        }
        failure.fullMessage() shouldContain "noSuchKey1219"
    }
})

/** Owns the `copy1219` flavor, whose inline overlay sets `home.<key>` of the home page's copy. */
private class CopyFlavorComponent(private val key: String) : ComponentDefinition {
    override fun flavorDefs(cxt: KdrCxt) = listOf(FlavorDef("copy1219", "Rewords the home page (issue #1219)."))
    override fun flavors(cxt: KdrCxt) = listOf(
        flavor("copy1219") {
            fragments { listOf(fragmentInline(HFRAG.home, "copy1219") { namespace("home") { key(key, "Flavored welcome") } }) }
        },
    )
}
