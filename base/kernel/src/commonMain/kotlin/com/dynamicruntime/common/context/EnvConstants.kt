package com.dynamicruntime.common.context

// Environment constants, in the kernel beside the client constants (ClientConstants.kt) and moved here for
// the same reason (issue #78): shared definitions that name an environment have to reach them from KMP code.
// `ClientDef.enabledEnvironments` is the case that forced the move -- a client says which environments it is
// enabled in, and the client definition lives in the kernel because `GedraConfig` does.
//
// The package is unchanged, so nothing that referenced `ENV` had to be edited: this file and `CxtConstants.kt`
// contribute to one package from two modules, exactly as `ClientConstants.kt` already does.

/** Environment names and environment types. */
@Suppress("ConstPropertyName", "unused")
object ENV {
    // Environment names: what kind of run this is.
    const val local = "local"
    const val dev = "dev"
    const val prod = "prod"
    const val integration = "integration"
    const val unit = "unit"

    // Environment types: what kind of system is running the instance.
    const val liveSource = "liveSource"
    const val deployed = "deployed"

    /**
     * Every environment that exists, in rough order of how far a run is from a developer's keyboard.
     *
     * A list rather than an enum for the reason stated at the top of `CxtConstants.kt`: a runtime meant to be
     * modified dynamically does not want its choice values enforced at compile time. `staging` is anticipated
     * and deliberately absent -- an environment nothing can be deployed to is a name, not an environment.
     */
    val names: List<String> = listOf(unit, local, dev, integration, prod)
}
