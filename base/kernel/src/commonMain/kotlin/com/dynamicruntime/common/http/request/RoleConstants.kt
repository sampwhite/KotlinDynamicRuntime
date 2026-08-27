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

    /**
     * Reach across *every* client rather than only one's own (issue #225). A **capability**, deliberately not
     * a rung of [RoleLadder]: it does not say what may be done, only over whose rows, and the two are
     * different axes -- folding them together would need a rung per (level x scope) as soon as a scoped
     * operator exists.
     *
     * Being off the ladder also means it survives a change of level (`RoleLadder.rolesAtLevel` preserves
     * roles that are not rungs) and confers no level of its own.
     */
    const val allClients = "allClients"
}

/**
 * The **gated section names** -- the first path segment of a privileged surface, which `SectionRules` gates on.
 *
 * These exist because one section name is spelled in several places that must agree: the section list in
 * `RequestService` that assigns it rules, the path constants served under it ([com.dynamicruntime.common.user.UADEP],
 * [com.dynamicruntime.common.user.ADEP], [com.dynamicruntime.common.cfact.CFD]), and the schema-module namespace
 * that carries it. A section rename that missed one spelling would leave the surface gated as one section while
 * served under another: a *complete* miss trips the boot check (the section becomes unruled), but a slip landing
 * in another ruled section boots clean and silently moves the surface. Building every spelling from one constant
 * is what makes that impossible (issue #466).
 *
 * Only the gated sections are here -- the ones named in more than one place. The anonymous and plain-user
 * sections (`health`, `gedra`, ...) appear once, in `RequestService`, and their endpoint paths are ordinary
 * literals, so a constant would buy them nothing. The names match their values, as the code guide asks of a
 * key constant.
 */
@Suppress("ConstPropertyName")
object SECT {
    /** Full-scope administration (needs [ROLE.admin] **and** [ROLE.allClients]); the deployment-wide cell. */
    const val admin = "admin"

    /** Client-scoped administration (needs [ROLE.admin], confined by scope); renamed from `userAdmin` in #466. */
    const val clientAdmin = "clientAdmin"

    /** Node identity and stats -- full-scope, gated beside [admin]. */
    const val node = "node"

    /** Running the deployment (needs [ROLE.operator] **and** [ROLE.allClients] since #464); deployment-wide. */
    const val operator = "operator"
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
     * The role list that puts someone at [level], given the roles they hold now ([current], empty when
     * provisioning). The inverse of [highestHeld], and the only place the "set someone's level" rule is
     * written down -- the admin console composes a role list with it, and so does test provisioning.
     *
     * Three things have to hold at once, each with a silent failure mode:
     *
     *  - **The rungs are exclusive.** They are an ordering, not independent flags, so moving to a level
     *    *replaces* whatever rung was held. Leaving `admin` in place while granting `operator` would be a
     *    demotion that demotes nothing.
     *  - **[ROLE.user] is always kept.** A user without it cannot log in (the backend's `requireUsableRoles`
     *    refuses the write), so it is the floor of every level rather than one of the choices.
     *  - **Roles off the ladder survive untouched.** They are capabilities, not levels; a change of level must
     *    not silently strip someone's `billing`.
     *
     * A [level] that is not a ladder role leaves the user at the floor, so a bad value can only under-grant.
     */
    fun rolesAtLevel(current: List<String>, level: String): List<String> {
        val capabilities = current.filter { rankOf(it) == null }
        val rung = level.takeIf { rankOf(it) != null && it != ROLE.user }
        return listOfNotNull(ROLE.user, rung) + capabilities
    }

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
