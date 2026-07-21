package com.dynamicruntime.common.http.request

/**
 * Role names used by the security check.
 *
 * In the **kernel** rather than beside `SectionRules` (which enforces them, and stays in `base:common`)
 * because the frontend needs the same strings: it reads a user's roles off a `UserInfo` payload and, on the
 * administration screens, edits them. Its package is unchanged, so nothing that referenced it moved.
 *
 * Roles are string constants, not an enum: a deployment adds its own, and the model values are dynamic (the
 * code guide reserves enums for closed operational sets).
 */
@Suppress("ConstPropertyName")
object ROLE {
    const val user = "user"
    const val admin = "admin"
}
