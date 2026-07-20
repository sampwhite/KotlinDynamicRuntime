package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.AC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.defaultListLimit
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.toJsonListOfStrings
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
fun adminSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "admin") {
    AuthUserRow.defineAdminType(this)

    // --- read ---------------------------------------------------------------

    listEndpoint(
        ADEP.users,
        "Lists users, newest first, optionally filtered by a search term over email and username.",
        outputRef = ADTY.adminUser,
        inputFields = {
            field(ADF.search, "Case-insensitive substring to match against the email or username.")
        },
    ) { c, request ->
        val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
        userService(c).listUsers(c, request[ADF.search].toOptStr(), limit).map { it.toAdminInfo() }
    }

    // --- create -------------------------------------------------------------

    generalEndpoint(
        ADEP.userCreate,
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
        },
    ) { c, request ->
        val primaryId = requireField(request, ADF.primaryId)
        val username = request[ADF.username].toOptStr()
        val roles = request[ADF.roles].toJsonListOfStrings().ifEmpty { listOf(ROLE.user) }
        requireUsableRoles(roles)
        val service = userService(c)
        if (service.queryByPrimaryId(c, primaryId) != null) {
            throw KdrException.mkInput("A user with the email '$primaryId' already exists.")
        }
        if (username != null && service.queryByUsername(c, username) != null) {
            throw KdrException.mkInput("Username '$username' has already been taken.")
        }

        val data = AuthUserRow.mkInitialUser(primaryId, AC.public, roles).toMutableMap()
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

        val userId = service.insertUser(c, data)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} created user $userId ('$primaryId') with roles $roles." }
        loadUser(c, userId).toAdminInfo()
    }

    // --- edit ---------------------------------------------------------------

    generalEndpoint(
        ADEP.userSetRoles,
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
        requireUsableRoles(roles)
        if (userId == c.userProfile.userId && !roles.contains(ROLE.admin)) {
            throw KdrException.mkInput(
                "You cannot remove your own '${ROLE.admin}' role; have another administrator do it.",
            )
        }
        val row = loadUser(c, userId)
        val previous = row.roles
        row.roles = roles
        userService(c).updateUser(c, row)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} set user $userId roles: $previous -> $roles." }
        row.toAdminInfo()
    }

    generalEndpoint(
        ADEP.userSetEnabled,
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
        val row = loadUser(c, userId)
        row.enabled = enabled
        userService(c).updateUser(c, row)
        LogAuth.info(c) { "Admin ${c.userProfile.userId} set user $userId enabled=$enabled." }
        row.toAdminInfo()
    }
}

/** Contact-descriptor keys inside `authUserData.contacts` (mirrors what the self-service path writes). */
@Suppress("ConstPropertyName")
object AC2 {
    const val address = "address"
    const val type = "type"
    const val email = "email"
}

/** The user service, or a hard failure -- these endpoints cannot run without it. */
private fun userService(cxt: KdrCxt): UserService =
    UserService.get(cxt) ?: throw KdrException("UserService is required by the admin endpoints.")

/** Loads a user by id, or a 404. */
private fun loadUser(cxt: KdrCxt, userId: Long): AuthUserRow =
    userService(cxt).queryByUserId(cxt, userId)
        ?: throw KdrException("No user with id $userId.", code = EXC.notFound)

/** Reads a required string field, rejecting a blank one (which validation alone would let through). */
private fun requireField(request: Map<String, Any?>, field: String): String =
    request[field].toOptStr()?.trim()?.ifEmpty { null }
        ?: throw KdrException.mkInput("A non-empty '$field' is required.")

/** Reads the required numeric user id. */
private fun requireUserId(request: Map<String, Any?>): Long =
    request[ADF.userId].toOptLong() ?: throw KdrException.mkInput("A numeric '${ADF.userId}' is required.")

/**
 * Guards a supplied role set: no blanks, and [ROLE.user] must be present. Role *names* are deliberately not
 * restricted to a known list -- roles are dynamic model values (the code guide's string-constant rule), and a
 * deployment will add its own. But a user without [ROLE.user] cannot log in at all (`requireUsableForLogin`),
 * so silently creating one is never what an administrator meant.
 */
private fun requireUsableRoles(roles: List<String>) {
    if (roles.any { it.isBlank() }) {
        throw KdrException.mkInput("Role names cannot be blank.")
    }
    if (!roles.contains(ROLE.user)) {
        throw KdrException.mkInput("The '${ROLE.user}' role is required; without it the account cannot log in.")
    }
}
