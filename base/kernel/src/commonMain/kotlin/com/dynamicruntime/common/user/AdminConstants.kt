package com.dynamicruntime.common.user

// Admin (user-management) constants shared with the *frontend*, alongside the auth constants in
// AuthConstants.kt and for the same reason: an admin console is a Kotlin/JS widget-group waiting to be written,
// and it should build its calls from the same strings the backend serves them under. Per the code guide these
// are lowerCamelCase `const val`s in short upper-case acronym objects, always referenced qualified.

/**
 * Admin endpoint paths (before the API context root is prepended). Every one sits under the `admin` *section*,
 * which `RequestService.adminSections` gates on [com.dynamicruntime.common.http.request.ROLE.admin] -- the path
 * prefix is the access control, so an endpoint added here is admin-only by construction.
 */
@Suppress("ConstPropertyName")
object ADEP {
    const val users = "/admin/users"
    const val userCreate = "/admin/user/create"
    const val userSetRoles = "/admin/user/setRoles"
    const val userSetEnabled = "/admin/user/setEnabled"
}

/** Admin request/response field (JSON key) names. */
@Suppress("ConstPropertyName")
object ADF {
    const val userId = "userId"
    const val primaryId = "primaryId"
    const val username = "username"
    const val roles = "roles"
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
