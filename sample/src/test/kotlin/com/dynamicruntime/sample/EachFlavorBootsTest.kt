package com.dynamicruntime.sample

import com.dynamicruntime.common.CommonComponent
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.flavor.DeployFlavors
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.kdn.KdnComponent
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Every declared deployment flavor boots on its own (issue #1219), with the widest component set a test reaches:
 * the base two and the sample. The fragment and overlay checks run at boot, strict in a unit test, so a flavor's
 * overlay naming a key its base dropped fails here rather than on the deployment running that flavor.
 *
 * One boot per flavor, deliberately not a matrix of combinations: flavors do not interact, so each is checked
 * alone. The flavors are enumerated from the declarations, so a flavor added later is covered with no change here.
 */
class EachFlavorBootsTest : StringSpec({

    "every declared flavor boots on its own" {
        val probe = KdrCxt.mkSimpleCxt("flavorEnum", KdrInstanceConfig("flavorEnum", ENV.unit, ENV.liveSource))
        val declared = listOf(CommonComponent(), KdnComponent(), SampleComponent()).flatMap { it.flavorDefs(probe) }
        for (def in declared) {
            val instance = "eachFlavor_${def.name}"
            val cxt = Startup.mkTestBootCxt(
                instance, instance, mapOf(FLV.deployFlavorsEnvVar.name to def.name, "KDR_LOAD_SAMPLE" to "true"),
                additionalComponents = listOf(SampleComponent()),
            )
            DeployFlavors.get(cxt).active shouldBe listOf(def.name)
        }
    }
})
