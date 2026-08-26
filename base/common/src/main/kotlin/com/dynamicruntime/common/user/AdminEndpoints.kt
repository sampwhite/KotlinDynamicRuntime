package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.defaultListLimit
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.clientAttribute
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.isEmailAddress
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * The administrator's user-management endpoints: list users, create one, and edit an existing one's roles or
 * enabled state.
 *
 * Access control is the path itself. Every path here sits under the `admin` **section**, which
 * `RequestService.adminSections` gates on [ROLE.admin] before dispatch -- so these handlers can assume an
 * authenticated administrator and none of them repeats the check. Granting that role in the first place is
 * [AdminRules]' job (an auto-admin email domain) or the `GrantRole` command-line script's; from there,
 * [ADEP.userSetRoles] grants it to anyone else.
 *
 * Two self-inflicted foot-guns are refused outright: an admin may not strip their **own** admin role or disable
 * their **own** account. Either would let the last administrator lock the deployment out of its own admin
 * surface, recoverable only by going back to the command-line script. Editing *another* admin is allowed --
 * that is co-equal administration, not a lock-out.
 *
 * Registered by the `common` component.
 */
/** The paths one user-administration surface is served under. */
class UserAdminPaths(
    val users: String,
    val userSearch: String,
    val userCreate: String,
    val userSetRoles: String,
    val userSetEnabled: String,
    val userSetOrg: String,
    val userSetName: String,
    val userDelete: String,
)

/** The **full-scope** surface: the `admin` section, which requires [ROLE.allClients]. */
fun adminSchema(cxt: KdrCxt): SchModule = userAdminModule(
    cxt, "admin",
    UserAdminPaths(ADEP.users, ADEP.userSearch, ADEP.userCreate, ADEP.userSetRoles, ADEP.userSetEnabled, ADEP.userSetOrg, ADEP.userSetName, ADEP.userDelete),
)

/**
 * The **scoped** surface: the `userAdmin` section, which requires only [ROLE.admin] and confines every read to
 * `ReadScopeRules.forCaller` (issue #225).
 *
 * The same module built twice rather than a second set of handlers, because the difference between the two
 * surfaces is *who may enter*, not what they do once inside: the scope is derived from the caller's roles, so
 * one handler serves a client-scoped administrator and a full-scope one correctly. Copying the handlers would
 * mean two implementations of the same rules, and the copy is the one that would miss a fix.
 */
fun scopedUserAdminSchema(cxt: KdrCxt): SchModule = userAdminModule(
    cxt, "userAdmin",
    UserAdminPaths(UADEP.users, UADEP.userSearch, UADEP.userCreate, UADEP.userSetRoles, UADEP.userSetEnabled, UADEP.userSetOrg, UADEP.userSetName, UADEP.userDelete),
)

private fun userAdminModule(cxt: KdrCxt, namespace: String, paths: UserAdminPaths): SchModule =
    schemaModule(cxt, namespace) {
    AuthUserRow.defineAdminType(this)

    // --- read ---------------------------------------------------------------

    listEndpoint(
        paths.users,
        "Lists users, newest first, optionally filtered by a search term over email, username, and name.",
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.search, "Case-insensitive substring to match against the email, username, or name.")
        },
    ) { c, request ->
        val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
        userService(c).listUsers(c, request[ADF.search].toOptStr(), limit, ReadScopeRules.forCaller(c))
            .map { it.toAdminInfo() }
    }

    // A richer search than the plain listing above: brute force over the user cache (issue #411), with a
    // per-field filter, a sort, and a fairly large default cap. Active users only -- the cache holds enabled
    // rows -- so the newest-first listing above stays the way to reach a disabled or deleted account.
    listEndpoint(
        paths.userSearch,
        "Searches active users over the in-memory cache -- email/name substring, client, and update-time " +
            "range -- sorted by any of those fields (default: newest first). Returns up to '${USF.defaultLimit}'.",
        outputRef = ADTY.adminUser,
        hasMore = true,
        hasNumAvailable = true,
        // The list builder's auto-appended `limit` defaults to ${defaultListLimit}; this search wants a far
        // larger default, so it is declared here and the auto one suppressed.
        noLimit = true,
        inputFields = {
            field(USF.email, "Case-insensitive substring to match against the email address.")
            field(USF.name, "Case-insensitive substring to match against the account's real-world name or its username.")
            field(USF.publicName, "Case-insensitive substring to match against the public name (username or email).")
            field(
                USF.client,
                "Exact client id to confine to. Only meaningful to an '${ROLE.allClients}' caller; anyone " +
                    "else is already confined to their own client.",
            )
            field(USF.updatedAfter, "Only users updated at or after this time (ISO-8601).") { dateTime() }
            field(USF.updatedBefore, "Only users updated at or before this time (ISO-8601).") { dateTime() }
            field(
                USF.sortBy,
                "Which attribute to sort by: ${userSearchFields.joinToString(", ") { "'${it.name}'" }}. " +
                    "Defaults to '${USF.updatedAt}'.",
            )
            field(USF.descending, "Sort descending (newest / Z-A first). Defaults to true.") {
                type = SCT.boolean
            }
            field(EP.limit, "The maximum number of users to return; defaults to '${USF.defaultLimit}'.") {
                type = SCT.integer
                default = USF.defaultLimit
                // `?limit=` (empty) reads as no limit given rather than a 400, matching the auto-appended one.
                emptyIsAbsent = true
            }
        },
    ) { c, request ->
        val criteria = parseUserSearch(request)
        val page = userService(c).searchUsers(c, criteria, ReadScopeRules.forCaller(c))
        ListPage(
            page.rows.map { it.toAdminInfo() },
            numAvailable = page.numAvailable,
            hasMore = page.rows.size < page.numAvailable,
        )
    }

    // --- create -------------------------------------------------------------

    generalEndpoint(
        paths.userCreate,
        "Creates a user directly, bypassing self-service email verification.",
        HttpMethod.POST,
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.primaryId, "The new user's primary email address.", required = true)
            field(ADF.username, "The new user's username; defaults to a placeholder they can change.")
            field(ADF.roles, "Roles to grant; defaults to just '${ROLE.user}'.") {
                type = SCT.array
                items { type = SCT.string }
            }
            field(ADF.org, "Primary organization for the new user; defaults to the creator's own.")
            field(
                ADF.client,
                "Client for the new user; defaults to the creator's own. Naming a different one requires the " +
                    "'${ROLE.allClients}' capability, and it cannot be changed afterward.",
            ) { clientAttribute() }
            field(ADF.isEntity, "Whether the new account belongs to a business rather than a person.") { type = SCT.boolean }
            field(ADF.name, "The new account's name: a person's full name, or the business's name.")
            field(ADF.enabled, "Whether the account starts active; defaults to true. False creates it disabled.") {
                type = SCT.boolean
            }
        },
    ) { c, request ->
        val primaryId = requireField(request, ADF.primaryId)
        // The address is the login identity and a real destination for verification mail -- so it is checked
        // for shape here rather than taken on faith. The self-service path proves the address by emailing a
        // code; this path skips that, which makes a syntactic check the only thing standing between a typo and
        // a permanent, unreachable account. Same validator the create form runs (base/kernel), so the two agree.
        if (!primaryId.isEmailAddress()) {
            throw KdrException.mkInput("'$primaryId' is not a valid email address.")
        }
        val username = request[ADF.username].toOptStr()
        val roles = request[ADF.roles].toJsonListOfStrings().ifEmpty { listOf(ROLE.user) }
        requireUsableRoles(c, roles)
        val service = userService(c)
        if (service.queryByPrimaryId(c, primaryId) != null) {
            throw KdrException.mkInput("A user with the email '$primaryId' already exists.")
        }
        if (username != null && service.queryByUsername(c, username) != null) {
            throw KdrException.mkInput("Username '$username' has already been taken.")
        }

        // The new user belongs to the administrator's own client. It was hardcoded to `public`, which a
        // client-scoped administrator would have found absurd: they would create a user they could not then
        // see. For a full-scope administrator this is the same value it always was.
        // Defaults to the creator's own organization for the same reason the client does: a confined
        // administrator creating a user outside their own scope would immediately lose sight of them.
        val org = request[ADF.org].toOptStr()?.trim()?.ifEmpty { null } ?: c.userProfile.org
        requireAssignableOrg(c, org)
        val data = AuthUserRow
            .mkInitialUser(primaryId, assignableClient(c, request[ADF.client].toOptStr()), roles, org)
            .toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val authUserData = data[AU.authUserData] as MutableMap<String, Any?>
        // The administrator is asserting the address, which stands in for the verification the self-service
        // path gets from the emailed code -- so the contact is recorded as validated and the user can log in by
        // code immediately. They still have no password; setting one remains their own (code-verified) act.
        authUserData[AD.validatedContacts] = listOf(primaryId)
        authUserData[AD.contacts] = listOf(mapOf(AC2.address to primaryId, AC2.type to AC2.email))
        if (username != null) {
            data[AU.username] = username
        }
        // Mirrors the registration path: the name is display copy, neither required nor checked for
        // uniqueness, and set independently of the flag -- a person has a full name just as a business does.
        if (request.getOptBool(ADF.isEntity) == true) {
            authUserData[AD.isEntity] = true
        }
        AuthUserRow.normalizeName(request[ADF.name].toOptStr())?.let { authUserData[AD.name] = it }

        val userId = service.insertUser(c, data)
        // Honor an explicit request to create the account disabled. insertUser always stamps `enabled = true`
        // (prepForStdExecute does, to revive a disabled placeholder -- issue #48), so "create disabled" cannot
        // ride the insert; it is a follow-up disable through the same durable path the Enabled toggle uses.
        // Defaults to enabled, so a caller that omits the field (every existing one) is unaffected.
        val enabled = request.getOptBool(ADF.enabled) ?: true
        if (!enabled) {
            loadUser(c, userId).let { it.enabled = false; service.updateUser(c, it) }
        }
        LogAuth.info(c) {
            "Admin ${c.userProfile.userId} created user $userId ('$primaryId') with roles $roles" +
                (if (!enabled) " (disabled)." else ".")
        }
        loadUser(c, userId).toAdminInfo()
    }

    // --- edit ---------------------------------------------------------------

    generalEndpoint(
        paths.userSetRoles,
        "Replaces a user's roles -- the call that grants or revokes administrator privileges.",
        HttpMethod.POST,
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.userId, "Id of the user to edit.", required = true) { type = SCT.integer }
            field(ADF.roles, "The complete new set of roles (replaces, not merges).", required = true) {
                type = SCT.array
                items { type = SCT.string }
            }
        },
    ) { c, request ->
        val userId = requireUserId(request)
        val roles = request[ADF.roles].toJsonListOfStrings()
        // Loaded before the role checks so a user outside the caller's scope is a 404 rather than a complaint
        // about roles -- the complaint would confirm the id belongs to somebody.
        val row = loadEditableUser(c, userId)
        requireUsableRoles(c, roles, row.roles)
        // Nobody may change their **own** standing on the admin surface, in either direction.
        //
        // Downward, it stops the last administrator locking the deployment out of its own admin surface.
        // Upward matters more as the capability narrows: today only an administrator reaches this endpoint, so
        // self-promotion is merely redundant -- but the whole point of AdminRules.canManageUsers is that it will
        // one day admit someone weaker (a client manager, say), and self-promotion is exactly how such a
        // caller would escalate to full administrator. Writing the rule symmetrically now means that widening
        // cannot open the hole later.
        //
        // It covers `allClients` as well as `admin` (issue #225), because the surface now requires the
        // capability: dropping your own is the lock-out this guard already existed to prevent, and it would
        // otherwise slip through as an ordinary "editing my other roles" edit -- a role list is a replacement,
        // so omitting the capability revokes it without ever naming it.
        //
        // A self-update that leaves both *unchanged* is fine -- editing your own other roles is not what this
        // guards.
        val selfProtected = listOf(ROLE.admin, ROLE.allClients).filter {
            roles.contains(it) != row.roles.contains(it)
        }
        if (userId == c.userProfile.userId && selfProtected.isNotEmpty()) {
            throw KdrException.mkInput(
                "You cannot change your own '${selfProtected.joinToString("', '")}' role; " +
                    "have another administrator do it.",
            )
        }
        val previous = row.roles
        row.roles = roles
        userService(c).updateUser(c, row)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} set user $userId roles: $previous -> $roles." }
        row.toAdminInfo()
    }

    generalEndpoint(
        paths.userSetEnabled,
        "Enables or disables a user's account (a disabled account cannot log in).",
        HttpMethod.POST,
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.userId, "Id of the user to edit.", required = true) { type = SCT.integer }
            field(ADF.enabled, "Whether the account is active.", required = true) { type = SCT.boolean }
        },
    ) { c, request ->
        val userId = requireUserId(request)
        val enabled = request[ADF.enabled] == true
        if (userId == c.userProfile.userId && !enabled) {
            throw KdrException.mkInput("You cannot disable your own account.")
        }
        val row = loadEditableUser(c, userId)
        row.enabled = enabled
        userService(c).updateUser(c, row)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} set user $userId enabled=$enabled." }
        row.toAdminInfo()
    }

    // A DELETE on the user resource rather than a POST to a `/delete` path (issue #335): the verb carries the
    // action, so the path does not have to say it twice. Both fields arrive as query params -- a DELETE sends
    // no body -- and are coerced from their string forms by the same input validation every endpoint runs.
    generalEndpoint(
        paths.userDelete,
        "Deletes a user: recoverable (disabled) by default, or -- when permanent -- disabled with the user's " +
            "email and identity obfuscated so the deletion cannot be undone.",
        HttpMethod.DELETE,
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.userId, "Id of the user to delete.", required = true) { type = SCT.integer }
            field(
                ADF.permanent,
                "When true, obfuscate the user's email and clear their stored identity -- not recoverable. " +
                    "Defaults to false, a recoverable disable.",
            ) {
                type = SCT.boolean
            }
        },
    ) { c, request ->
        val userId = requireUserId(request)
        val permanent = request[ADF.permanent] == true
        // The same self-protection the disable toggle has, and it matters more here: a permanent self-delete
        // would obfuscate the acting administrator out of their own account irrecoverably.
        if (userId == c.userProfile.userId) {
            throw KdrException.mkInput("You cannot delete your own account.")
        }
        val row = loadEditableUser(c, userId)
        val result = userService(c).deleteUser(c, row, permanent)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} deleted user $userId (permanent=$permanent)." }
        result.toAdminInfo()
    }

    generalEndpoint(
        paths.userSetOrg,
        "Sets or clears a user's primary organization within their client.",
        HttpMethod.POST,
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.userId, "Id of the user to edit.", required = true) { type = SCT.integer }
            field(ADF.org, "The organization to assign; omit or send empty to clear it.")
        },
    ) { c, request ->
        val userId = requireUserId(request)
        val org = request[ADF.org].toOptStr()?.trim()?.ifEmpty { null }
        requireAssignableOrg(c, org)
        val row = loadEditableUser(c, userId)
        val previous = row.org
        row.org = org
        userService(c).updateUser(c, row)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} set user $userId org: $previous -> $org." }
        row.toAdminInfo()
    }

    generalEndpoint(
        paths.userSetName,
        "Sets a user's name, and whether the account is a business rather than a person.",
        HttpMethod.POST,
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.userId, "Id of the user to edit.", required = true) { type = SCT.integer }
            field(ADF.isEntity, "Whether this account belongs to a business rather than a person.") { type = SCT.boolean }
            field(ADF.name, "The account's name; omit or send empty to leave it unnamed.")
        },
    ) { c, request ->
        val userId = requireUserId(request)
        val isEntity = request.getOptBool(ADF.isEntity) == true
        val row = loadEditableUser(c, userId)
        row.isEntity = isEntity
        // The name is kept across a change of the flag rather than cleared with it: a personal account has a
        // full name just as a business has a business name, so clearing `isEntity` reclassifies the name
        // instead of discarding it. Sending an empty name is still how a caller unsets it.
        row.name = request[ADF.name].toOptStr() // the row normalizes
        userService(c).updateUser(c, row)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} set user $userId name/isEntity=$isEntity." }
        row.toAdminInfo()
    }
}

/**
 * Guards the organization an administrator is assigning: one confined to an organization may only ever assign
 * **that** organization (issue #225).
 *
 * Both other answers would be an escalation of a kind. A *different* organization moves the user out of the
 * caller's own scope -- they would be editing somebody into invisibility. **Clearing** it is subtler and
 * worse: a row with no organization is visible to the whole client under the lenient rule, so clearing one
 * widens that user's reach beyond the caller's own. Applied to the caller themselves it is the escape hatch
 * from confinement altogether, which is why this needs no separate self-check.
 *
 * An administrator who is not confined to an organization -- most of them -- may assign anything, including
 * nothing.
 */
/**
 * The client a created user belongs to: [named] when the caller may say so, and their own otherwise (issue
 * #352).
 *
 * Two refusals, and they are different questions. Naming a client **other than your own** takes
 * [ROLE.allClients] -- a client-scoped administrator creating a user elsewhere would immediately lose sight of
 * them, which is the same reason the organization defaults the way it does. And any named client has to be one
 * this node **carries**, since a user in a client that is not present cannot get in.
 *
 * There is no set-client call to match this, and there should not be: a user's content carries their client
 * both in the `client` column and inside every `GedraId`, so moving one would strand it. Create is the only
 * point at which this is answerable, which is why the console offers the choice only there.
 */
private fun assignableClient(cxt: KdrCxt, named: String?): String {
    val own = cxt.userProfile.client
    val client = named?.trim()?.ifEmpty { null } ?: return own
    if (client == own) {
        return own
    }
    if (AdminRules.adminScope(cxt) != AdminScope.allClients) {
        throw KdrException.mkInput(
            "You may only create users in your own client ('$own'); naming another takes the " +
                "'${ROLE.allClients}' capability.",
        )
    }
    val clients = ClientService.get(cxt)
    if (!clients.isPresent(client)) {
        throw KdrException.mkInput(
            "There is no client '$client' on this node. The clients present here are " +
                "${clients.presentClients.joinToString(", ") { it.clientId }}.",
        )
    }
    return client
}

private fun requireAssignableOrg(cxt: KdrCxt, org: String?) {
    val actingOrg = cxt.userProfile.org ?: return
    if (AdminRules.adminScope(cxt) == AdminScope.allClients) return
    if (org != actingOrg) {
        throw KdrException.mkInput(
            "You can only assign the '$actingOrg' organization; you are confined to it.",
        )
    }
}

/** Contact-descriptor keys inside `authUserData.contacts` (mirrors what the self-service path writes). */
@Suppress("ConstPropertyName")
object AC2 {
    const val address = "address"
    const val type = "type"
    const val email = "email"
}

private fun userService(cxt: KdrCxt): UserService = UserService.get(cxt)

/**
 * Loads a user by id, or a 404 -- **within the caller's administration scope** (issue #225). A user in
 * another client is reported as absent rather than forbidden, so a scoped administrator cannot use this
 * endpoint to discover that an id belongs to somebody in a client they cannot see.
 */
private fun loadUser(cxt: KdrCxt, userId: Long): AuthUserRow =
    userService(cxt).queryAdministrableUser(cxt, userId, ReadScopeRules.forCaller(cxt))
        ?: throw KdrException("No user with id $userId.", code = EXC.notFound)

/**
 * Loads a user for an **edit**, refusing a permanently-deleted tombstone: its identity is obfuscated and there
 * is nothing left to administer -- re-enabling, renaming or re-deleting one would either resurrect an account
 * that was meant to be gone or write to a hollowed-out row. The single gate every mutating handler passes
 * through, so the UI's read-only treatment is a convenience over an enforced rule, not the rule itself.
 */
private fun loadEditableUser(cxt: KdrCxt, userId: Long): AuthUserRow {
    val row = loadUser(cxt, userId)
    if (row.isDeleted) {
        throw KdrException.mkInput("User $userId was permanently deleted and can no longer be edited.")
    }
    return row
}

/** Reads a required string field, rejecting a blank one (which validation alone would let through). */
private fun requireField(request: Map<String, Any?>, field: String): String =
    request[field].toOptStr()?.trim()?.ifEmpty { null }
        ?: throw KdrException.mkInput("A non-empty '$field' is required.")

/** Reads the required numeric user id. */
private fun requireUserId(request: Map<String, Any?>): Long =
    request[ADF.userId].toOptLong() ?: throw KdrException.mkInput("A numeric '${ADF.userId}' is required.")

/**
 * Builds a [UserSearchCriteria] from the cache-search request (issue #411). The text terms are collected only
 * when non-blank -- a blank one is "no filter", not "match everything" -- and an unknown [USF.sortBy] is
 * rejected outright rather than silently falling back, so a typo in the sort field is a clear 400 instead of a
 * list ordered by something other than what was asked for.
 */
private fun parseUserSearch(request: Map<String, Any?>): UserSearchCriteria {
    // Only the text-searchable fields, keyed by their own names so `searchUserRows` can resolve each back to
    // its field. A field with no term is simply absent.
    val textTerms = buildMap {
        for (field in userSearchFields) {
            if (field.textsOf == null) continue
            request[field.name].toOptStr()?.trim()?.ifEmpty { null }?.let { put(field.name, it) }
        }
    }
    val sortBy = request[USF.sortBy].toOptStr()?.trim()?.ifEmpty { null }
    if (sortBy != null && !userSearchFieldsByName.containsKey(sortBy)) {
        throw KdrException.mkInput(
            "Unknown sort field '$sortBy'; expected one of ${userSearchFields.joinToString(", ") { "'${it.name}'" }}.",
        )
    }
    return UserSearchCriteria(
        textTerms = textTerms,
        updatedAfter = request[USF.updatedAfter].toOptInstant(),
        updatedBefore = request[USF.updatedBefore].toOptInstant(),
        sortBy = sortBy ?: USF.updatedAt,
        descending = request.getOptBool(USF.descending) ?: true,
        // Floored at 0: a negative limit would otherwise reach `List.take`, which throws -- surfacing as a 500
        // rather than the harmless empty page a nonsensical `?limit=-1` should get. Zero is left as a valid
        // "just tell me the count" request (the matched total still comes back in numAvailable).
        limit = ((request[EP.limit] as? Number)?.toInt() ?: USF.defaultLimit).coerceAtLeast(0),
    )
}

/**
 * Guards a supplied role set: no blanks, and [ROLE.user] must be present. Role *names* are deliberately not
 * restricted to a known list -- roles are dynamic model values (the code guide's string-constant rule), and a
 * deployment will add its own. But a user without [ROLE.user] cannot log in at all (`requireUsableForLogin`),
 * so silently creating one is never what an administrator meant.
 */
private fun requireUsableRoles(cxt: KdrCxt, roles: List<String>, current: List<String> = emptyList()) {
    // Anti-escalation: a role set may not hand out reach the granter does not have, or a client-scoped
    // administrator could promote someone in their own client to see every client -- and then act through
    // them. The general rule is "you cannot grant a capability you do not hold"; `allClients` is the only one
    // that exists so far, so it is the only one enumerated.
    //
    // It is a check on *adding*, not on the resulting set. A role list replaces rather than merges, so an
    // administrator editing somebody who already holds the capability sends it back unchanged -- and judging
    // the result alone would refuse that, meaning a scoped administrator could never touch such a user at
    // all, for a reason ("you cannot grant") that has nothing to do with what they were changing.
    // [current] is empty on a create, where every role in the list is by definition being added.
    if (ROLE.allClients in roles && ROLE.allClients !in current && ROLE.allClients !in cxt.userProfile.roles) {
        throw KdrException.mkInput("You cannot grant the '${ROLE.allClients}' capability; you do not hold it.")
    }
    if (roles.any { it.isBlank() }) {
        throw KdrException.mkInput("Role names cannot be blank.")
    }
    if (!roles.contains(ROLE.user)) {
        throw KdrException.mkInput("The '${ROLE.user}' role is required; without it the account cannot log in.")
    }
}
