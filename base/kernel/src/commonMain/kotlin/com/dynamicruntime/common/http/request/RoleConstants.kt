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
 *
 * Three of them form the built-in privilege ladder -- see [RoleLadder], which is what gives "or lower" a
 * meaning.
 */
@Suppress("ConstPropertyName")
object ROLE {
    const val user = "user"
    const val operator = "operator"
    const val admin = "admin"
}

/**
 * The built-in privilege ladder: [ROLE.user] < [ROLE.operator] < [ROLE.admin]. It answers one question --
 * does a caller's role set satisfy the role a section requires? -- and it is the only place that ordering is
 * written down.
 *
 * **Why an ordering at all.** Before this, holding a role was a flat set membership test, and "an admin can
 * also do what a user can" was true only because provisioning granted *both* roles ([ROLE.user] plus
 * [ROLE.admin]). That works until a third level appears: an operator marked section would then have to be
 * granted explicitly to every admin, and every future level multiplies the grants. Ranking the ladder once
 * means a higher privilege satisfies a lower one by construction, with nothing to backfill on existing rows.
 *
 * **Why only three roles are on it.** A deployment adds its own roles, and those are *capabilities*, not
 * privilege levels -- a `billing` role is not "above" or "below" an operator, it is beside them. So a role
 * that is not on the ladder never satisfies a ladder role, and a *required* role that is not on the ladder
 * falls back to exact membership ([satisfies]). That keeps the dynamic-roles design intact: adding a
 * deployment role can never accidentally confer a privilege level.
 *
 * This is the enforcement rule the dispatcher applies. `AdminRules.canManageUsers` remains the separate seam
 * for *shaping the UI*, and is deliberately not expressed in terms of rank.
 */
object RoleLadder {
    /** The ladder, least privileged first. Membership here is what makes a role a *level* rather than a capability. */
    val ordered: List<String> = listOf(ROLE.user, ROLE.operator, ROLE.admin)

    /** [role]'s position on the ladder, or null when it is not a ladder role (a deployment's own capability). */
    fun rankOf(role: String): Int? = ordered.indexOf(role).takeIf { it >= 0 }

    /**
     * The highest ladder role in [heldRoles] -- a role set's *level*, as opposed to the capabilities it also
     * carries -- or null when it holds none. Roles off the ladder are ignored, since they are not levels.
     *
     * This is what lets a surface describe someone as "an operator" from a role list: the ladder is an
     * ordering, so the top rung held is the only one that says anything, and every rung below it is implied
     * by [satisfies] rather than needing to be listed.
     */
    fun highestHeld(heldRoles: Collection<String>): String? = ordered.lastOrNull { it in heldRoles }

    /**
     * Whether [heldRoles] is enough to act where [requiredRole] is demanded.
     *
     * On the ladder, any held role that ranks at or above the requirement passes -- so an admin satisfies an
     * operator section without holding `operator`. Off the ladder (either side), the test is exact
     * membership, which is the pre-ladder behavior and what a deployment's own role should get.
     */
    fun satisfies(heldRoles: Set<String>, requiredRole: String): Boolean {
        val required = rankOf(requiredRole) ?: return requiredRole in heldRoles
        return heldRoles.any { held -> (rankOf(held) ?: -1) >= required }
    }
}
