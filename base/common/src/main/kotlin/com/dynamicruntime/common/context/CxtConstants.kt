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
}

// AC (account constants) moved to the kernel (AccountConstants.kt), so shared data classes like UserProfile
// can live in the kernel too (issue #78); still referenced as `com.dynamicruntime.common.context.AC`.

/** Application configuration data keys. (A key's name matches its string value.) */
@Suppress("ConstPropertyName")
object ACFG {
    const val env = "env"
    const val inMemoryOnly = "inMemoryOnly"

    /** When true, endpoint responses are validated against their `outputSchema`. Default false; on in tests. */
    const val validateResponseSchema = "validateResponseSchema"

    /**
     * When true, an error flagged `sensitive` (e.g., one that would reveal whether an account exists) has its
     * message replaced with a generic one before it goes to the client; the real message is still logged
     * (issue #108). Set directly by tests. When unset, defaults from the `KDR_OBFUSCATE_ERRORS` env var, which
     * in turn defaults to whether the environment is `prod` -- so prod deployments obfuscate by default.
     */
    const val obfuscateSensitiveErrors = "obfuscateSensitiveErrors"

    /**
     * How often, in milliseconds, the frontend bumps its refresh generation while a tab is visible (issue
     * #146), served to it by the app UI-config endpoint. Unset by default (the endpoint applies its own
     * default); a deployment overrides it in code through the custom-config object rather than an env var,
     * since it is UI tuning, not an ops/environment concern.
     */
    const val idleBumpIntervalMs = "idleBumpIntervalMs"

    /**
     * The context root (leading path segment) under which API endpoints are served; defaults to
     * `ContextRoot.kda` when absent. Each kind of traffic binds to its own context root under its own key,
     * and a request whose leading segment matches none of them is fast-failed with a short 404.
     */
    const val apiContextRoot = "apiContextRoot"

    /** The context root under which content (HTML/static, e.g., the portal) is served; defaults to `ContextRoot.cp`. */
    const val contentContextRoot = "contentContextRoot"

    /** The context root under which the self-contained webapp is served; defaults to `ContextRoot.wa`. */
    const val appContextRoot = "appContextRoot"

    /** The context root under which immutable static content is served; defaults to `ContextRoot.st`. */
    const val staticContextRoot = "staticContextRoot"

    /**
     * The email domain whose plain (un-plus-addressed) addresses are automatically granted the admin role --
     * how the *first* admin of a deployment comes to exist. Set directly by tests; when unset it defaults from
     * the `KDR_ADMIN_EMAIL_DOMAIN` env var. Absent in both means no address is ever auto-granted. See
     * [com.dynamicruntime.common.user.AdminRules].
     */
    const val adminEmailDomain = "adminEmailDomain"
}
