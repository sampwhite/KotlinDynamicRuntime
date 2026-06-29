package com.dynamicruntime.common.context

// Constants for the context package. Per the code guide, these live in
// upper-cased acronym objects and are always referenced qualified (e.g.
// `ENV.unit`, `AC.local`), never wildcard-imported. Note the deliberate lack of
// enums: in a runtime whose model is meant to be modified dynamically, a
// compile-time-enforced enum of choice values runs counter to the goals.
//
// The lowerCamelCase `const val` names knowingly violate Kotlin style, so each
// object suppresses the const-naming inspection at the object level.

/** Environment names and environment types. */
@Suppress("ConstPropertyName")
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
}

/** Account-related constants. */
@Suppress("ConstPropertyName")
object AC {
    // Default account names.
    const val local = "local"
    const val public = "public"

    // Placeholder id for the implicit system user.
    const val systemUserId = 0
}
