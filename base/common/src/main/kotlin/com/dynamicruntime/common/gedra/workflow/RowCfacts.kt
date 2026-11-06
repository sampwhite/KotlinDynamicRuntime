package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.user.UserService

/**
 * Works out the facts about the **caller's relation to a form** ([RCF], issue #1595) from the form's owner and the
 * caller -- never from the form's data or state, and never stored. What a judge of one form adds to the request's facts
 * (`CFactRegistry.assemble`'s target facts), so a task's `saveWhen` and `display`, a CTA filter, and an approval's
 * `approveWhen` can say whose turn it is.
 *
 * The one computation of whether the caller owns a form: the approve endpoint's second-person rule and the
 * `kdr:ownerOnly` guard ([OwnerOnly]) ask it too.
 */
object RowCfacts {
    /**
     * The row facts for [cxt]'s caller on a form owned by [ownerUserId]; null for a form not yet made, which the caller
     * would own (a creation view). [ownerIdentity] as [isOwner] takes it: a listing passes the identities it read in one
     * batch (issue #1597).
     */
    fun of(
        cxt: KdrCxt,
        ownerUserId: Long?,
        ownerIdentity: () -> String? = { ownerUserId?.let { identityOf(cxt, it) } },
    ): Set<String> =
        if (ownerUserId == null || isOwner(cxt, ownerUserId, ownerIdentity)) setOf(RCF.isOwner) else emptySet()

    /**
     * Whether [cxt]'s caller owns a form owned by [ownerUserId], by [WorkflowApprovals.isOwnForm]'s person rule. The
     * owner's identity is asked for only when it can decide the answer -- a different user, in production, with an
     * identity of their own -- so on a test instance this is a comparison. [ownerIdentity] reads it, by default one
     * user lookup; a listing judging many forms passes the identities it read for its page in one batch.
     */
    fun isOwner(
        cxt: KdrCxt,
        ownerUserId: Long,
        ownerIdentity: () -> String? = { identityOf(cxt, ownerUserId) },
    ): Boolean {
        val profile = cxt.userProfile
        if (ownerUserId == profile.userId) {
            return true
        }
        val isTest = cxt.instanceConfig.isTestInstance
        if (isTest || profile.identityId == null) {
            return false
        }
        return WorkflowApprovals.isOwnForm(ownerUserId, ownerIdentity(), profile.userId, profile.identityId, isTest)
    }

    /** The identity of the user [userId], by one user lookup: what [of] and [isOwner] read when given no other way. */
    private fun identityOf(cxt: KdrCxt, userId: Long): String? = UserService.getOrNull(cxt)?.queryByUserId(cxt, userId)?.identityId
}
