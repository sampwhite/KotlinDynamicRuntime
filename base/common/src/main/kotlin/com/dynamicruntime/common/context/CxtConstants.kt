package com.dynamicruntime.common.context

// Constants for the context package. Per the code guide, these live in
// upper-cased acronym objects and are always referenced qualified (e.g.
// `ENV.unit`, `CL.hub`), never wildcard-imported. Note the deliberate lack of
// enums: in a runtime whose model is meant to be modified dynamically, a
// compile-time-enforced enum of choice values runs counter to the goals.
//
// The lowerCamelCase `const val` names knowingly violate Kotlin style, so each
// object suppresses the const-naming inspection at the object level.

// CL (client constants) moved to the kernel (ClientConstants.kt), so shared data classes like UserProfile
// can live in the kernel too (issue #78); still referenced as `com.dynamicruntime.common.context.CL`.
// ENV followed it there (EnvConstants.kt, issue #343) once a client definition had to name environments.

/** Application configuration data keys. (A key's name matches its string value.) */
@Suppress("ConstPropertyName")
object ACFG {
    const val env = "env"
    const val inMemoryOnly = "inMemoryOnly"

    /**
     * Decides [KdrInstanceConfig.isTestInstance] outright when present, instead of it being inferred. Absent
     * (the normal case) leaves the inference alone.
     *
     * It exists because the inference is a chain of ORs, so nothing could previously turn it *off*: a unit
     * test runs in [ENV.unit] and in memory, and each of those alone makes the instance a test instance. That
     * left the behavior of a *real* node -- test endpoints absent, test-only debug output withheld -- with no
     * way to be exercised at all. Set this false to boot an instance that behaves like a deployed one.
     */
    const val isTestInstance = "isTestInstance"

    /**
     * Whether `Set-Cookie` carries the `Secure` attribute (issue #431). Decides it outright when present;
     * unset, it defaults from the `KDR_COOKIE_SECURE` env var, then from the environment (secure everywhere
     * but [ENV.local]/[ENV.unit], so plain-HTTP localhost dev keeps working). See `CookieRules.isSecure`.
     */
    const val cookieSecure = "cookieSecure"

    /**
     * The **boot role** this process is running as (issue #377): one of [BOOT]'s names, and unset for an
     * ordinary application node -- see [BOOT.app] for why absence and `app` are the same thing seen from two
     * directions. Set by the launcher, because the launcher *is* the role: it has to be known before any
     * application config exists, so it cannot come from one.
     *
     * Its effect is to give every environment variable a per-role override: with a role set, a lookup for
     * `KDR_PORT` tries `KDR_EDGE_PORT` first. See `KdrInstanceConfig.getEnvVar`.
     */
    const val bootRole = "bootRole"

    /**
     * The port this node binds when no environment variable names one -- how a boot role brings its own
     * default (issue #377), so an edge does not land on the application's port merely because nobody set
     * `KDR_EDGE_PORT`. Set by the launcher; unset means `NodeUtil.defaultPort`.
     */
    const val defaultPort = "defaultPort"

    /** When true, endpoint responses are validated against their `outputSchema`. Default false; on in tests. */
    const val validateResponseSchema = "validateResponseSchema"

    /**
     * When true, this node believes the `X-Kdr-Env-Email` header an edge server sets on a request it has
     * already authenticated (issue #348). It cannot verify that claim -- the real guarantee is that only the
     * edge can reach this node, a network property -- so the deployment asserts it here. Unset defaults from
     * `KDR_TRUST_ENV_AUTH_HEADER`, and failing that from [isTestInstance]. Resolved through
     * `EnvAuthRules.isTrusted`, which is the only thing that should read it.
     */
    const val trustEnvAuthHeader = "trustEnvAuthHeader"

    /**
     * When true, this node **invents** env auth for a request that arrived without a forwarded-for address --
     * the convenience that makes a developer's own box behave like one sitting behind an edge (issue #360).
     * Unset defaults from `KDR_ASSUME_ENV_AUTH`, and failing that from being a test instance in `local`
     * specifically -- not `unit`, where it would make every request in the suite env-authed. Resolved through
     * `EnvAuthRules.assumesEnvAuth`.
     */
    const val assumeEnvAuth = "assumeEnvAuth"

    /**
     * Where an edge forwards application traffic (issue #419). Unset defaults from `KDR_EDGE_UPSTREAM`, and
     * failing that from the ordinary development application.
     *
     * A single upstream, which is the shape of the first forwarding slice and not the intended end state: a
     * route table keyed on more than the context root, backed by a registry of live nodes, replaces it. The
     * key lives here beside the other edge-facing config rather than in the edge module, for the same reason
     * [bootRole] does -- common may know that edges exist, it just may not depend on the component.
     */
    const val edgeUpstream = "edgeUpstream"

    /**
     * This node's capability tags (issue #433), as a collection of strings. Unset defaults from
     * `BOOT.bootTagsEnvVar`, and failing that to none.
     *
     * Distinct from [bootRole]: the role says what kind of node this is and is singular; tags say what
     * surfaces it serves and are a set. A node carrying no tags is admitted by every declaration that names
     * none, which is all of them until something declares otherwise.
     */
    const val bootTags = "bootTags"

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

/**
 * The **boot roles** a node can run as (issues #377, #386) -- what kind of process this is, as opposed to which
 * environment it runs in ([ENV]) or which deployment it belongs to.
 *
 * The names live in `base/common` rather than with the components that implement them, because endpoints,
 * services, and schema will be *profiled* by role -- declaring which roles they load under -- and those
 * declarations are spread across every module. They could not all depend on the module that owns the role.
 *
 * **Knowing the names is not knowing the implementations.** Common learns that `edge` is a role a node may run
 * as; it never learns what a `KdrEdge` is, and nothing here may depend on that module. The same line
 * [ACFG.bootRole] already draws.
 */
@Suppress("ConstPropertyName")
object BOOT {
    /**
     * The ordinary application node -- the role a node has when it declares none.
     *
     * **Absence and `app` are the same thing seen from two directions**, and the difference matters at the
     * boundary. A *running* node's `KdrInstanceConfig.bootRole` is **null** for an application, which is what
     * keeps environment-variable prefixing off for every existing deployment (#377). A *declaration* -- an
     * endpoint saying which roles it serves -- needs a name, because null cannot sit in a list. So whatever
     * matches the two will read `bootRole ?: BOOT.app`, and that normalization belongs in one place when
     * profiling lands rather than at each comparison.
     */
    const val app = "app"

    /**
     * An edge node: the perimeter that fronts other servers, booted by `StartEdge`. Also the
     * environment-variable namespace, so a lookup for `KDR_PORT` on such a node tries `KDR_EDGE_PORT` first.
     */
    const val edge = "edge"

    /**
     * The `providerName` of the component that implements the [edge] role, so a launcher can **require** it
     * without naming its class -- see `bootInstance`'s required-components check.
     *
     * A name here rather than a literal in the launcher for the ordinary reason: the component declares the
     * same string, and two spellings of one fact drift. Common learning the name is not common learning the
     * implementation, which is the line [ACFG.bootRole] already draws.
     */
    const val edgeComponent = "KdrEdge"

    /**
     * Environment variable naming this node's capability tags, comma separated (issue #433).
     *
     * Role-prefixed like the rest, so an edge reads `KDR_EDGE_TAGS` before `KDR_TAGS`. A deployment running
     * both backend surfaces lists both tags rather than naming a combined role -- see `NodeProfile` for why
     * the combination never appears in a declaration.
     */
    val bootTagsEnvVar = EnvVarDef(
        "KDR_TAGS", group = ENVGRP.application, defaultDoc = "none",
        description = "This node's capability tags (issue #433), comma-separated -- what surfaces it serves, " +
            "as opposed to the singular boot role. Role-prefixed like the rest, so an edge reads `KDR_EDGE_TAGS` " +
            "first. A node carrying no tags is admitted by every declaration that names none.",
    )

}
