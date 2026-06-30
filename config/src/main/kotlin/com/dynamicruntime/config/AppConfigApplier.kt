package com.dynamicruntime.config

/**
 * Implemented by a deployment-supplied configuration object that is discovered by
 * reflection and contributes additional application configuration. The single
 * method takes the [AppConfigBuilder] as its *receiver*, so an implementation's
 * body reads like a Kotlin builder DSL -- an implicit `apply` -- e.g.
 * `env = "prod"; inMemoryOnly = false`.
 *
 * The runtime locates the implementing object reflectively, but invokes it
 * through this interface, so the call itself is type-checked (per the code guide:
 * discover the class by reflection, but call a known interface).
 */
interface AppConfigApplier {
    fun AppConfigBuilder.applyAppConfig()
}
