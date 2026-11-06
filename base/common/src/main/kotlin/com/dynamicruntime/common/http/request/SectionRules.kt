package com.dynamicruntime.common.http.request

/**
 * The section an application path belongs to: its first segment (`admin` for `/admin/users`). The application
 * path is already stripped of the context root, so the leading `/` is all there is to skip.
 *
 * The one definition of the rule, deliberately. A request learns its section here (`RequestHandler.section`)
 * and so does anything asking about an endpoint it is *not* currently serving -- the catalog, deciding what a
 * caller may see (issue #211). Two spellings of "which section is this" is precisely how a catalog comes to
 * advertise what the dispatcher then refuses.
 *
 * Theme: #SECGT.
 */
fun sectionOf(appPath: String): String = appPath.removePrefix("/").substringBefore('/')

/**
 * Access rules for a section: the first path segment *after* the context root (e.g. `user` in
 * `/kda/user/profile`) that names a group of endpoints sharing an access policy. Ported from dn's
 * `ContextRootRules` (renamed, since "context root" now denotes the higher-level routing segment).
 *
 * The section is the unit an endpoint's privilege is declared at -- there is no per-endpoint marking, so
 * endpoints that need different privileges belong in different sections. `RequestService` builds the map
 * from its section lists; [admits] is the one answer both the dispatcher's gate and the endpoint catalog
 * ask, so what is advertised and what is served cannot drift apart.
 *
 * **A section constrains two independent things**, matching the two axes the role model already draws:
 * [requiredRole] is a *level* on `RoleLadder`, where a higher rung satisfies a lower requirement, and
 * [requiredCapability] is an off-ladder *capability* that must be held outright. Both must be met.
 *
 * [requiredRole] may also name an off-ladder role on purpose (issue #1432): `editor`, which *confers* reach of its own
 * -- the `clientDirectory` section requires it -- and which `RoleLadder.impliedBy` lets `admin` satisfy. That is
 * different from naming a capability there, which makes holding the capability sufficient on its own (the #237 trap):
 * a capability qualifies a level, while `editor` is meant to admit its holders, and an administrator, and nobody else.
 *
 * Theme: #SECGT.
 */
class SectionRules(
    val section: String,
    /**
     * Whether the section is for signed-in callers. Descriptive only: [admits] is what the dispatcher acts on, and
     * the one way into a gated section without a login is a direct read, where the section allows it
     * ([directReads]).
     */
    val needsLogin: Boolean,
    /**
     * Level required to access this section, or null when the section demands no level -- or `editor`, the one off-ladder
     * role meant to be required here (see the class note).
     */
    val requiredRole: String?,
    /**
     * A capability that must additionally be held, or null when the section demands none (issue #225).
     *
     * Separate from [requiredRole] rather than expressed as one, because a capability and a level are not
     * comparable: `RoleLadder.satisfies` falls back to exact membership for an off-ladder role, so naming a
     * capability as the *required role* makes holding it **sufficient on its own** -- it would confer the
     * section instead of qualifying it. That is exactly how a user demoted to `user` kept the full-scope
     * `admin` surface while losing the lesser scoped one, since nothing about their level was being asked.
     */
    val requiredCapability: String? = null,
    /**
     * Whether a **direct read** -- a GET that reached the node without a proxy (`KdrCxt.isDirect`, issue #1255) -- is
     * admitted without any of the above. For the operator surface, so a command run on the node over SSH can read
     * it with no session. Only GETs: a section's other verbs change something, and still need the roles.
     */
    val directReads: Boolean = false,
) {
    /** Whether this section constrains anything at all; false for an anonymous section. */
    val isGated: Boolean get() = requiredRole != null || requiredCapability != null

    /**
     * Whether [heldRoles] may reach this section: the level satisfied through `RoleLadder` (so an admin
     * passes an operator section) **and** the capability held outright -- or, where the section allows it,
     * [directRead] (a GET that reached the node without a proxy).
     */
    fun admits(heldRoles: Set<String>, directRead: Boolean = false): Boolean =
        unmetRequirement(heldRoles, directRead) == null

    /**
     * The name of the first requirement [heldRoles] does not meet, or null when the section admits them. Used
     * to say which of the two is missing, since "requires the 'admin' role" is a misleading thing to tell
     * somebody who has it and is short the capability.
     */
    fun unmetRequirement(heldRoles: Set<String>, directRead: Boolean = false): String? {
        if (directRead && directReads) return null
        val role = requiredRole
        if (role != null && !RoleLadder.satisfies(heldRoles, role)) return role
        val capability = requiredCapability
        if (capability != null && capability !in heldRoles) return capability
        return null
    }
}


/**
 * Every section and the access rules it enforces: the one statement of them. `RequestService` installs [rules] when it
 * is created, and refuses to start when an endpoint declares a section none of these lists names, since that section
 * would be served to anyone. A code constant, moved here from `RequestService` (issue #1610), so the roles and access
 * reference is made from the rules the gate enforces.
 *
 * Theme: #SECGT.
 */
object SectionAccess {
    // `home`/`logout` are listed here to record what they already were: each had no entry and was therefore
    // served anonymously, so naming them changes no behavior and only makes the decision visible.
    //
    // `fixture` and `demo` are the two non-application roots (issue #270), and the split is by *purpose*:
    //
    //  - `fixture` exists so a capability can be **exercised** -- by an automated test or by a developer by
    //    hand. It is never shown to a client, and every endpoint under it is gated (`forTestingOnly`, or a
    //    module that loads only in developer environments).
    //  - `demo` exists so a **person** can see a capability work, and may be deliberately enabled for a client
    //    as a showcase. That possibility is what earns it a grooming standard: honest verbs, real
    //    descriptions, coherent schema.
    //
    // Both are anonymous, which is what they already were under their old names (`test`, `file`,
    // `gedraSketch`). The old roots described the *gate* or the *feature*; these describe the purpose, which
    // is the thing a reader needs and the thing that stops a fixture squatting a name a real entity will want
    // -- `gedra` and `file` were both heading for exactly that collision.
    //
    // `portal` is a content section, not an endpoint one: the endpoint explorer page `PortalService` serves under the
    // content root. `content` and `site`, carried over from the original port, were dropped (issue #1610): nothing
    // was served under either, and a section with no rules is served to anyone anyway.
    val anonSections: List<String> = listOf(
        "health", "schema", "portal", "auth", "app",
        "fixture", "demo", "home", "logout",
    )
    // `gedra` is login-gated and nothing more: how far a caller reaches into stored gedras is a *scope*
    // question, answered per request by `ReadScopeRules.forCaller`, so one surface serves an ordinary user
    // and an administrator rather than the two needing separate sections (issue #310).
    val userSections: List<String> = listOf("user", "profile", "gedra")

    /**
     * Sections requiring [ROLE.operator] **and** the [ROLE.allClients] capability -- endpoints for running the
     * deployment rather than using it (see `OperatorEndpoints`), which are deployment-wide by nature: node
     * identity and JVM stats, cache coherence across every client, the env-var reference, boot checks.
     *
     * **The capability, not the level alone, is what fences these (issue #464).** The level is a rung of
     * [RoleLadder], so an `admin` satisfies it by ranking above `operator` -- but "admin" now includes a
     * *client-scoped* administrator (issue #225), who runs one client's users and has no business in the
     * deployment's operational internals. Requiring `allClients` as well ladders both such an admin and a
     * client-confined `operator` off this surface at once: the capability closes the implication route (an
     * admin reaching operator through the ladder) and the direct one (somebody holding `operator` outright by
     * exact rank) with a single rule, in the same shape [adminSections] already uses.
     *
     * The deliberate cost is that a genuine operator now needs `allClients` granted beside the role, which
     * makes `operator` a statement about *deployment* scope. A client-scoped operator is a different surface,
     * not this one with a narrowed view.
     *
     * **The bare name means deployment-wide.** The role model draws two axes -- level ([RoleLadder]:
     * `user` < `operator` < `admin`) and, for a privileged section, scope (deployment-wide vs. one client) --
     * and four sections name the cells (issue #466):
     *
     * |            | deployment-wide (`allClients`) | one client        |
     * | ---------- | ------------------------------ | ----------------- |
     * | admin      | `admin`                        | `clientAdmin`     |
     * | operator   | `operator`                     | `clientOperator`  |
     *
     * (`node` is also admin-gated, beside `admin` in [adminSections]; it predates the scheme and is not one of
     * its cells. And within a client-scoped cell the caller's own scope may be narrower still -- an org within
     * the client -- which `ReadScopeRules.forCaller` resolves per request, not the section.)
     *
     * `clientOperator` is now **built** (issue #488, first tenant: the cfacts reference) -- see
     * [clientOperatorSections]. The bare `operator`/`admin` are the unmarked -- and *more* privileged -- half of
     * each scope pair; if that reads backwards now that `clientOperator` is real, the fix is to mark both ends
     * (`deploymentOperator`/`deploymentAdmin`), deferred because those are the two paths used most.
     *
     * Theme: #SECGT.
     */
    val operatorSections: List<String> = listOf(SECT.operator)

    /**
     * Sections requiring [ROLE.operator] but **confined by the caller's scope** (issue #488) -- the operator
     * counterpart of [scopedAdminSections], one rung down. Admits an `operator` or an `admin` (the ladder ranks
     * admin above operator) **without** [ROLE.allClients], so a client's own operator reaches it and sees only
     * their own scope, while the deployment-wide [operatorSections] takes the capability too. The
     * client-scoped operator cell of the grid on [operatorSections].
     */
    val clientOperatorSections: List<String> = listOf(SECT.clientOperator)

    /**
     * Sections requiring [ROLE.admin] **and** the [ROLE.allClients] capability -- the **full-scope**
     * administration surface (issue #225). The level alone is not enough: an administrator confined to one
     * client does not get a narrowed view of these endpoints, they get a different surface
     * ([scopedAdminSections], the `clientAdmin` section). The bare name means deployment-wide; see
     * [operatorSections] for the level x scope grid the four sections fill (issue #466).
     *
     * **Both, not either.** These sections originally named the capability as their required role, which made
     * holding it sufficient on its own -- `RoleLadder.satisfies` falls back to exact membership off the
     * ladder, so it granted the surface rather than widening it. A user demoted to `user` who kept the
     * capability therefore retained cross-client user administration while being refused the *lesser* scoped
     * surface, which is the wrong way round. A capability qualifies an authority; it never confers one.
     *
     * Since #211 the same comparison has driven the endpoint catalog, so these are also *invisible* to a caller
     * who cannot call them -- "see" and "use" are one answer.
     *
     * Theme: #SECGT.
     */
    val adminSections: List<String> = listOf(SECT.node, SECT.admin)

    /**
     * Sections requiring [ROLE.admin] but **confined by the caller's scope** (issue #225) -- what a
     * client-scoped administrator has instead of a narrowed view of [adminSections]. A caller holding
     * [ROLE.allClients] satisfies this too and is simply unconfined, so one surface serves both.
     *
     * The section is `clientAdmin` (renamed from `userAdmin` in issue #466): a section names **authority** --
     * "an administrator confined to one client's scope" -- not a topic, so it reads right for everything that
     * belongs here, not only user administration. It is the client-scoped admin cell of the grid on
     * [operatorSections].
     */
    val scopedAdminSections: List<String> = listOf(SECT.clientAdmin)

    /**
     * Sections requiring [ROLE.editor] and **confined by the caller's scope** (issue #1432): the read-only user directory,
     * where an editor -- who sees every user of its client and administers none -- reads users. An administrator holds
     * the role by implication (`RoleLadder.impliedBy`), so passes too; an operator does not.
     */
    val editorSections: List<String> = listOf(SECT.clientDirectory)

    /** Every section's rules, by section, in the order of the lists above. */
    val rules: Map<String, SectionRules> = linkedMapOf<String, SectionRules>().apply {
        for (s in anonSections) put(s, SectionRules(s, needsLogin = false, requiredRole = null))
        for (s in userSections) put(s, SectionRules(s, needsLogin = true, requiredRole = ROLE.user))
        // Reads, but only reads, that reached the node without a proxy need no login (issue #1255): whoever can make
        // one is already on the node or inside its network. The section's POSTs still take the roles.
        for (s in operatorSections) {
            val rules = SectionRules(
                s, needsLogin = true, requiredRole = ROLE.operator, requiredCapability = ROLE.allClients, directReads = true,
            )
            put(s, rules)
        }
        // Operator level, no capability: an operator or admin confined to their own scope (issue #488).
        for (s in clientOperatorSections) put(s, SectionRules(s, needsLogin = true, requiredRole = ROLE.operator))
        for (s in adminSections) {
            put(s, SectionRules(s, needsLogin = true, requiredRole = ROLE.admin, requiredCapability = ROLE.allClients))
        }
        for (s in scopedAdminSections) put(s, SectionRules(s, needsLogin = true, requiredRole = ROLE.admin))
        for (s in editorSections) put(s, SectionRules(s, needsLogin = true, requiredRole = ROLE.editor))
    }

    /**
     * What each section holds, in a sentence, for the roles and access reference (issue #1610), which lists it beside
     * the section's rule. `RolesReferenceTest` fails on a section without one.
     */
    val meanings: Map<String, String> = linkedMapOf(
        "health" to "The node's health and identity, for a load balancer or a monitor.",
        "schema" to "The endpoint catalog, which lists what the caller may call with each endpoint's schema, and the " +
            "schema for schema.",
        "portal" to "A page, not endpoints: under the content root (`/cp/portal`), a form for exploring and calling each " +
            "endpoint the caller may call.",
        "auth" to "Signing in and registering, invitations, who the caller is, leaving a sandbox, and exchanging an API " +
            "key for an access token.",
        "app" to "The web application's configuration, and the switch a session turns its env auth off, on, or to debug with.",
        "fixture" to "What tests, and developers trying something by hand, call: every endpoint here is test only, or " +
            "comes from a component loaded only in a developer environment.",
        "demo" to "Demonstrations of a capability for a person to see, such as uploading a file.",
        "home" to "The shell's configuration: its layout, links, and menu.",
        "logout" to "Signing out.",
        "user" to "The signed-in user's own account: their API keys, their other users and clients and switching between " +
            "them, joining or creating a client, and opening a sandbox.",
        "profile" to "The signed-in user's profile: their name and password.",
        "gedra" to "Forms and the data in them. How far a caller reaches here is their read scope, not the section's rule.",
        SECT.operator to "Running the deployment: system information, boot checks, caches, jobs, environment variables, " +
            "the file store, signing keys, and the assistant's switch for each client.",
        SECT.clientOperator to "A client's own operations, confined to the caller's client: today, the facts the client " +
            "knows.",
        SECT.node to "Node identity and statistics, beside `admin`; no endpoint is served under it today.",
        SECT.admin to "Administration across every client: any client's definition and configuration, its users, and " +
            "creating or retiring clients.",
        SECT.clientAdmin to "Administration of the caller's own client: its users, their roles and API keys, its " +
            "configuration, design, sandbox, reports, and assistant.",
        SECT.clientDirectory to "The read-only user directory: the users of the caller's client, listed, searched, and read.",
    )
}

// ROLE (the role-name constants) moved to the kernel (RoleConstants.kt), so the frontend shares them; it keeps
// this package name, so every reference to `com.dynamicruntime.common.http.request.ROLE` is unaffected.
