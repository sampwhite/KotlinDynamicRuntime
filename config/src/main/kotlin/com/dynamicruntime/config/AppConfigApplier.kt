package com.dynamicruntime.config

import com.dynamicruntime.common.startup.KdrProvider

/**
 * Implemented by a deployment-supplied configuration object that is discovered at startup and contributes
 * additional application configuration. The single method takes the [AppConfigBuilder] as its *receiver*, so an
 * implementation's body reads like a Kotlin builder DSL -- an implicit `apply` -- e.g.
 * `env = "prod"; inMemoryOnly = false`.
 *
 * Extends [KdrProvider] so it is discovered by the shared ServiceLoader mechanism (issue #171): the launcher
 * enumerates providers, selects the applier whose [KdrProvider.providerName] matches `KDR_CUSTOM_CONFIG`, and
 * invokes it through this interface. Discovery locates the class, but the call itself is type-checked (per the
 * code guide: discover by the service mechanism, but call a known interface).
 */
interface AppConfigApplier : KdrProvider {
    fun AppConfigBuilder.applyAppConfig()
}
