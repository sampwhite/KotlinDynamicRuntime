package com.dynamicruntime.config

/**
 * A minimal [AppConfigApplier] used only by [KdrProviderDiscoveryTest]. It is registered for ServiceLoader
 * discovery through `config/src/test/resources/META-INF/services/com.dynamicruntime.common.startup.KdrProvider`,
 * so the test proves the real discovery path (issue #171) in CI without depending on the non-versioned
 * `customConfig` project. A class with a public no-arg constructor, as ServiceLoader requires.
 */
@Suppress("unused")
class FixtureConfigApplier : AppConfigApplier {
    override fun AppConfigBuilder.applyAppConfig() {
        data["fixtureApplied"] = true
    }
}
