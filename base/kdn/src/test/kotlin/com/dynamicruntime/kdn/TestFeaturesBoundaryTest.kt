package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.ComponentDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * The `testFeatures` storage boundary (issue #696): the field round-trips through stored client configuration so
 * it can be authored over the API, but it is honored only on a test instance -- neutralized on the way in on any
 * other node, and refused at the write. The three faces:
 *
 * 1. **Round-trip + honored** on a test instance -- a client written purely over the API (no boot fixture) comes
 *    back carrying its `testFeatures`. This is what lets a test drive a test-feature client entirely from
 *    outside, which #678's strict-cfact test now does instead of a boot fixture.
 * 2. **Neutralized** on a non-test instance -- a boot-defined client's `testFeatures` is stripped from the
 *    present definition, so a stored value cloned onto a real node is simply not there.
 * 3. **Refused** at the write on a non-test instance -- an explicit write that carries `testFeatures` throws
 *    rather than silently dropping the field.
 */
class TestFeaturesBoundaryTest : StringSpec({

    fun asClient(cxt: KdrCxt, client: String): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

    fun clientWithTestFeatures(cxt: KdrCxt, client: String): GedraConfig =
        gedraConfig(cxt, "${client}cfg", "${client}config", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    testFeatures = setOf(BoundaryFixture.feature),
                ),
            )
        }

    "on a test instance, a client written over the API round-trips its testFeatures and they are honored" {
        val cxt = Startup.mkTestBootCxt("tf696api", "tf696api")
        val client = "tf696api"
        GedraConfigService.get(cxt).writeConfig(asClient(cxt, client), clientWithTestFeatures(cxt, client))
        GedraConfigReload.reloadClient(cxt, client)

        ClientService.get(cxt).present(client).shouldNotBeNull().testFeatures
            .shouldContainExactly(setOf(BoundaryFixture.feature))
    }

    "on a test instance, a boot-defined client keeps its testFeatures" {
        val cxt = Startup.mkTestBootCxt(
            "tf696bootTest", "tf696bootTest",
            mapOf(BoundaryFixture.loadFlag.name to "true"),
            additionalComponents = listOf(BoundaryFixture()),
        )
        ClientService.get(cxt).present(BoundaryFixture.client).shouldNotBeNull().testFeatures
            .shouldContainExactly(setOf(BoundaryFixture.feature))
    }

    "on a non-test instance, the same boot-defined client's testFeatures are neutralized" {
        val cxt = Startup.mkTestBootCxt(
            "tf696bootProd", "tf696bootProd",
            mapOf(ACFG.isTestInstance to false, BoundaryFixture.loadFlag.name to "true"),
            additionalComponents = listOf(BoundaryFixture()),
        )
        ClientService.get(cxt).present(BoundaryFixture.client).shouldNotBeNull().testFeatures.shouldBeEmpty()
    }

    "on a non-test instance, an explicit write carrying testFeatures is refused" {
        val cxt = Startup.mkTestBootCxt("tf696writeProd", "tf696writeProd", mapOf(ACFG.isTestInstance to false))
        val client = "tf696writeprod"
        shouldThrow<KdrException> {
            GedraConfigService.get(cxt).writeConfig(asClient(cxt, client), clientWithTestFeatures(cxt, client))
        }
    }
})

/** A boot fixture: one client that lists a test feature, for the honored-vs-neutralized halves. */
private class BoundaryFixture : ComponentDefinition {
    override val providerName: String = "testFeaturesBoundaryFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "${client}cfg", "${client}config", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit),
                    testFeatures = setOf(feature),
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_TESTFEATURES_BOUNDARY_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the testFeatures boundary fixture client.",
        )
        const val client = "tf696boot"
        const val feature = "demoFeature"
    }
}
