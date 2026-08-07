package com.dynamicruntime.config

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.KdrProvider
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.ServiceLoader

/**
 * Proves the ServiceLoader-based provider discovery the launcher relies on (issue #171), using the CI-visible
 * [FixtureConfigApplier] registered under `src/test/resources/META-INF/services`. Exercises the base-type
 * discovery, the `AppConfigApplier` apply path, and `providerName` selection -- the three moving parts of
 * `Start.kt`'s config loading -- without needing the non-versioned `customConfig` project.
 */
class KdrProviderDiscoveryTest : StringSpec({

    fun discover(): List<KdrProvider> =
        ServiceLoader.load(KdrProvider::class.java, KdrProvider::class.java.classLoader).toList()

    "ServiceLoader discovers a provider through the shared KdrProvider service file" {
        discover().map { it.providerName } shouldContain "FixtureConfigApplier"
    }

    "a discovered AppConfigApplier applies its config to an AppConfigBuilder" {
        val applier = discover().filterIsInstance<AppConfigApplier>()
            .first { it.providerName == "FixtureConfigApplier" }
        val builder = AppConfigBuilder(KdrCxt.mkSimpleCxt("test"), LinkedHashMap())
        with(applier) { builder.applyAppConfig() }
        (builder.data["fixtureApplied"] as? Boolean).shouldBeTrue()
    }

    "providerName selection filters discovered appliers to the named one" {
        val selector = "FixtureConfigApplier"
        val selected = discover().filterIsInstance<AppConfigApplier>().filter { it.providerName == selector }
        selected.map { it.providerName } shouldBe listOf(selector)
    }
})
