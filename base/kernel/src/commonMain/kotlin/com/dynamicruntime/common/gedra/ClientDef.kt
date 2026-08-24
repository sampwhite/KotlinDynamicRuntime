package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder

/**
 * What a client is *for* -- the purpose it was created to serve (issue #343).
 *
 * Expected to grow: a new behavior that should be visible in some kinds of clients and not others conditions
 * itself on this. Only [production], in a production deployment, carries any strong promise that the client
 * will not be torn down and started over.
 *
 * An enum against the deliberate lack of them in `CxtConstants.kt`, and for the reason `GedraEditAction`
 * gives: the code exhausts it, and `SchTypeBuilder.options` builds a schema's choice list straight from the
 * entries, so the validator, the form's dropdown and any `when` over it cannot come to disagree. The argument
 * against enums is about values a *running deployment* may invent; a usage type is a thing this codebase has
 * to have an opinion about before it can ship the behavior conditioned on it.
 */
@Suppress("EnumEntryName")
enum class ClientUsageType {
    /** Actively used by people who care that the application stays where they left it. */
    production,

    /** Exists only to run tests against; may be allowed test fixture endpoints even in production. */
    test,

    /** Development work. Real people may try things; no external business depends on it. */
    dev,

    /** Exists to generate other client definitions. Its resources may be referenced by any client. */
    template,

    /** Exists to demonstrate the product, usually for sales; relaxed security and bulk-cleanup conveniences. */
    demo,
}

/**
 * Whose client this is -- ours or somebody else's (issue #343).
 *
 * Orthogonal to [ClientUsageType], which says what a client is *for*: an internal client can be `production`,
 * `dev` or `test`. Made a second axis rather than a sixth usage type deliberately, because the first internal
 * demo client would otherwise force the question "which is it?" -- the same conflation as letting the
 * `allClients` capability stand in for "is one of us".
 *
 * **It is an authority axis, not a label.** It decides what a caller is proactively shown, and it relaxes the
 * functional-group restriction (see [ClientDef.includedTraits]), so it may be set only in source code. Once a
 * client can be defined in data, a definition arriving that way may not choose its own -- otherwise a client
 * could write `internal` and take a capability with it.
 */
@Suppress("EnumEntryName")
enum class ClientAudience {
    /** Ours: staff, batch activity, the deployment's own work. */
    internal,

    /** Somebody else's. */
    customer,
}

/** Names and field keys for a client definition (issue #343). */
@Suppress("ConstPropertyName")
object CLD {
    /**
     * What marks an entry in [ClientDef.includedTraits] as a **group** rather than a trait id.
     *
     * A sigil no trait id can hold -- `GedraId`'s parts are `[A-Za-z0-9_]` -- so the two never have to be
     * told apart by looking them up.
     */
    const val groupSigil = '#'

    /**
     * The group of every trait owned by `global`, computed at load time rather than written down.
     *
     * A **functional** group: its membership is derived from what is global, so it cannot fall out of step
     * the way a hand-applied tag would. A client naming it is declaring that it *tracks a computed set*,
     * which is the only thing it could have meant.
     */
    const val allGlobal = "#allGlobal"

    // Field (JSON key) names, shared with the frontend: an admin console listing clients should build its
    // reads from the same strings the backend serves them under.
    const val clientId = "clientId"
    const val name = "name"
    const val description = "description"
    const val usageType = "usageType"
    const val audience = "audience"
    const val webResourcesId = "webResourcesId"
    const val enabledEnvironments = "enabledEnvironments"
    const val preload = "preload"
    const val staticConfig = "staticConfig"
    const val extendsFromClientId = "extendsFromClientId"
    const val domainPrefix = "domainPrefix"
    const val customDomain = "customDomain"
    const val includedTraits = "includedTraits"

    /** Schema type name for the [ClientDef.toInfo] dump. */
    const val infoTypeName = "ClientInfo"

    /**
     * The id under which the clients-a-caller-may-name choice list is registered (issue #413).
     *
     * Here rather than beside the callback because both ends need it and only one of them can own it: the
     * attribute declaring `optionsSource(CLD.clientOptions)` lives wherever a client is asked for, while the
     * callback lives with the client endpoints. A literal at either end is a rename waiting to empty a list.
     */
    const val clientOptions = "clientOptions"
}

/**
 * How a client reads in a choice list: its name with its id in brackets, or the id alone when it has no name.
 *
 * Both halves, because neither alone is enough. The **id** is what gets stored and what appears inside every
 * one of that client's gedra ids, so it is what an administrator will later recognize in a log or a URL, while
 * the **name** is what a person actually calls the client.
 *
 * In the kernel because both sides render this list: the backend when it answers a sourced choice list
 * (issue #413), and the admin console when it builds a selector from the clients endpoint. The same client
 * reading two ways in two dropdowns is the sort of difference nobody reports and everybody notices.
 */
fun clientLabel(clientId: String, name: String): String =
    if (name.isEmpty()) clientId else "$name ($clientId)"

/**
 * Marks an attribute as **naming a client**: it offers the clients this caller may name (issue #413).
 *
 * The shared half of a client field, and only the shared half. The **name** stays at each declaration --
 * `EI.client`, `ADF.client` and the rest are the key sets of different surfaces that happen to agree on a
 * spelling, and folding them into one constant would couple a SQL column to a query parameter. The
 * **description** stays too, and more deliberately: "whose surface am I looking at" and "where does this new
 * user go" are different sentences, and the mandatory-description rule exists so that nobody writes one
 * generic one for both.
 *
 * What is left is the thing that must not drift -- which list a client field draws on -- and it is declared
 * once, next to the class that defines what a client is. That placement is the point, and generalizes: an
 * attribute owned by a Kotlin class exports its schema fragment from that class's file, the way `toInfo`'s
 * shape is defined by `defineInfoType` a few lines below rather than by whoever serves it.
 *
 * It composes -- a site may make the field required, or add anything else, after calling this.
 */
fun SchTypeBuilder.clientAttribute() {
    optionsSource(CLD.clientOptions)
}

/**
 * A client: who it is, what it is for, where it runs, and which traits it supports (issue #343).
 *
 * Held as a typed field on the [GedraConfig] that declares it, whose [GedraId] already carries the client --
 * so the id *is* the binding and nothing says which client a definition belongs to twice. One bundle therefore
 * defines at most one client; every bundle sharing a client is read as one whole. See `client-definition.md`
 * for the reasoning behind each attribute.
 *
 * **Nothing here decides how a request is served.** This slice declares, validates, and finds a client; the
 * per-client schema, the absent-client gate and domain routing are later work.
 */
class ClientDef(
    /**
     * The unique key identifying this client, embedded in every [GedraId] it owns.
     *
     * Declared rather than derived from the config's id, which makes "declares one client and is filed under
     * another" a state that can be written down -- and refused. Through the builder that is a typo guard;
     * it earns its keep when a definition arrives as data, where the two halves travel separately.
     */
    val clientId: String,
    /** What to present to users as the name of this client. */
    val name: String,
    /** An internal note for humans: who, what, or why. Never shown to a client's own users. */
    val description: String? = null,
    /** What this client is for; see [ClientUsageType]. */
    val usageType: ClientUsageType,
    /** Whose client this is; see [ClientAudience]. Source-code only. */
    val audience: ClientAudience,
    /**
     * Identifies a package of web resources -- favicon, imagery, CSS. Two clients may share one. Where such a
     * package lives (source, file store, database, eventually a CDN) is designed later; this is the handle.
     */
    val webResourcesId: String? = null,
    /**
     * Where this client is enabled. Elsewhere, it may still be *referred to* and is otherwise not present.
     *
     * The **only** axis deciding which clients a deployment carries -- there is no separate node-level
     * notion -- so two deployments in one environment necessarily carry the same clients, and a deployment
     * focused on a subset would have to be its own environment.
     *
     * Naming any environment beyond `unit` and `local` requires naming both of those as well: a client in
     * active use must never be one that local development and the unit tests are locked out of.
     *
     * **Naming nothing retires the client**, everywhere, and is the only way to do so. The definition stays,
     * the content stays, and only access stops -- the same decision #326 made for a gedra one level up: keep
     * the row, flip a flag, let reads treat it as absent. Two things follow. Un-enabling **reclaims nothing**,
     * so re-enabling brings all of a retired client's content back, including data somebody may have assumed
     * was gone; deleting a client for real is a different question with the shape of a purge. And a retired
     * client is still *known* -- it can be referred to, and extended from -- which is why the registry keeps
     * "known" and "present" apart rather than collapsing them.
     */
    val enabledEnvironments: Set<String> = setOf(ENV.unit, ENV.local, ENV.dev),
    /** Whether to do this client's cache computations before the node reports itself ready. Not yet testable. */
    val preload: Boolean = false,
    /**
     * Whether this client refuses dynamic configuration reload, taking changes only through a deployment.
     * Applies in production only. Not yet testable; when true, configuration is preferably done in source code.
     */
    val staticConfig: Boolean = false,
    /**
     * Another client whose definitions are cloned in first, with this config applied over them.
     *
     * **One level, no chains**: the named client may not itself extend one. Usually a template (to deduplicate
     * clients), a preview variant of a client, or a test variant of a production one. From data, it may name
     * only a template; in either case only the *source-code* definition is pulled in, and any database overlay
     * on the extended client is ignored.
     */
    val extendsFromClientId: String? = null,
    /**
     * A prefix on a core domain that routes to this client. Decides anonymous presentation and
     * self-registration only.
     */
    val domainPrefix: String? = null,
    /** A whole hostname the client configured for itself; as [domainPrefix], and for the same two things. */
    val customDomain: String? = null,
    /**
     * The traits this client takes exactly as they stand: trait ids, and **group** names carrying
     * [CLD.groupSigil].
     *
     * A **minimum, not a total.** A trait this client alters, extends, or defines is supported without a second
     * mention, so the computed supported set is this list with its groups expanded plus everything the client
     * customized. Two names rather than one because an attribute claiming to list what a client supports,
     * which does not list all of it, is the kind of near-truth somebody eventually relies on.
     *
     * A functional group ([CLD.allGlobal]) is refused when [audience] is `customer` **and** [usageType] is
     * `production`, and both conditions do real work. What makes it dangerous is that *we* ship a global trait,
     * and it becomes editable in a client that never reviewed it -- a statement about two parties. A
     * customer's dev client tracking new global traits is how they preview what is coming; an internal
     * production client has no second party. Stated as the principle: **a client's supported set must be fully
     * determined by its own definition when somebody other than us depends on it.**
     */
    val includedTraits: List<String> = emptyList(),
) {
    /** Whether [enabledEnvironments] holds [env] -- the whole of whether this client is present on a node. */
    fun isEnabledIn(env: String): Boolean = env in enabledEnvironments

    /** The [includedTraits] entries that are groups rather than trait ids. */
    val includedGroups: List<String> get() = includedTraits.filter { it.startsWith(CLD.groupSigil) }

    /** The [includedTraits] entries that name a trait directly. */
    val includedTraitIds: List<String> get() = includedTraits.filterNot { it.startsWith(CLD.groupSigil) }

    override fun toString(): String = "$clientId ($usageType/$audience)"

    /**
     * A JSON-friendly dump of this client's attributes, for the administrative listing. Absent optionals are
     * omitted rather than sent as null; the shape is described by [defineInfoType].
     */
    fun toInfo(): Map<String, Any?> = buildMap {
        put(CLD.clientId, clientId)
        put(CLD.name, name)
        if (description != null) put(CLD.description, description)
        put(CLD.usageType, usageType.name)
        put(CLD.audience, audience.name)
        if (webResourcesId != null) put(CLD.webResourcesId, webResourcesId)
        put(CLD.enabledEnvironments, enabledEnvironments.toList())
        put(CLD.preload, preload)
        put(CLD.staticConfig, staticConfig)
        if (extendsFromClientId != null) put(CLD.extendsFromClientId, extendsFromClientId)
        if (domainPrefix != null) put(CLD.domainPrefix, domainPrefix)
        if (customDomain != null) put(CLD.customDomain, customDomain)
        put(CLD.includedTraits, includedTraits)
    }

    companion object {
        /** The shape of the [toInfo] dump. */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(CLD.infoTypeName) {
                type = SCT.kObject
                description = "A client this deployment carries, as it was declared."
                property(CLD.clientId, "The client's unique key, embedded in every gedra id it owns.", required = true)
                property(CLD.name, "The name presented to users as the name of the client.", required = true)
                property(CLD.description, "An internal note about who, what or why.")
                property(CLD.usageType, "What the client is for.", required = true) {
                    options(ClientUsageType.entries)
                }
                property(CLD.audience, "Whose client this is: ours, or somebody else's.", required = true) {
                    options(ClientAudience.entries)
                }
                property(CLD.webResourcesId, "Identifies the package of web resources the client presents.")
                property(CLD.enabledEnvironments, "The environments the client is enabled in.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(CLD.preload, "Whether the client's caches are computed before the node reports ready.") {
                    type = SCT.boolean
                }
                property(CLD.staticConfig, "Whether the client refuses dynamic configuration reload.") {
                    type = SCT.boolean
                }
                property(CLD.extendsFromClientId, "The client whose definitions this one is built on top of.")
                property(CLD.domainPrefix, "A prefix on a core domain that routes to this client.")
                property(CLD.customDomain, "A whole hostname the client configured for itself.")
                property(CLD.includedTraits, "Trait ids and group names the client takes as they stand.") {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
        }
    }
}
