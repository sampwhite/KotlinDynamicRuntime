package com.dynamicruntime.common.user

// Admin (user-management) constants shared with the *frontend*, alongside the auth constants in
// AuthConstants.kt and for the same reason: an admin console is a Kotlin/JS widget-group waiting to be written,
// and it should build its calls from the same strings the backend serves them under. Per the code guide these
// are lowerCamelCase `const val`s in short upper-case acronym objects, always referenced qualified.

/**
 * **Full-scope** admin endpoint paths (before the API context root is prepended). Every one sits under the
 * `admin` *section*, which `RequestService.adminSections` gates on
 * [com.dynamicruntime.common.http.request.ROLE.allClients] -- the path prefix is the access control, so an
 * endpoint added here reaches every client by construction (issue #225).
 *
 * The same endpoints exist under [UADEP] for an administrator confined to their own client. A caller who holds
 * the capability satisfies both, and gets identical answers either way, since their scope is unrestricted.
 */
@Suppress("ConstPropertyName")
object ADEP {
    const val users = "/admin/users"
    const val userCreate = "/admin/user/create"
    const val userSetRoles = "/admin/user/setRoles"
    const val userSetEnabled = "/admin/user/setEnabled"
    const val userSetOrg = "/admin/user/setOrg"
}

/**
 * **Scoped** user-administration paths: the same operations as [ADEP], reachable by any
 * [com.dynamicruntime.common.http.request.ROLE.admin] and confined to what their `ReadScope` allows (issue
 * #225). Its `userAdmin` section is what a client-scoped administrator has instead of a narrowed view of the
 * full-scope surface.
 *
 * Named for the job rather than for the client level on purpose: an administrator limited to a primary
 * *organization* within a client will use this same surface, so a name like `clientAdmin` would be wrong on
 * arrival.
 *
 * **This is the surface a frontend should call.** It serves both kinds of administrator correctly -- a caller
 * with `allClients` is simply unconfined -- so a console built on it needs no branch on who is asking.
 */
@Suppress("ConstPropertyName")
object UADEP {
    const val users = "/userAdmin/users"
    const val userCreate = "/userAdmin/user/create"
    const val userSetRoles = "/userAdmin/user/setRoles"
    const val userSetEnabled = "/userAdmin/user/setEnabled"
    const val userSetOrg = "/userAdmin/user/setOrg"
}

/** Admin request/response field (JSON key) names. */
@Suppress("ConstPropertyName")
object ADF {
    const val userId = "userId"
    const val primaryId = "primaryId"
    const val username = "username"
    const val roles = "roles"
    const val org = "org"
    const val enabled = "enabled"
    const val hasPassword = "hasPassword"

    /** Case-insensitive substring filter applied to `primaryId` and `username` by the list endpoint. */
    const val search = "search"
}

/** Admin schema type names. */
@Suppress("ConstPropertyName")
object ADTY {
    const val adminUser = "AdminUser"
}
