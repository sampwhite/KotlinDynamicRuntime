package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.util.joinAsPhrase

/** One held lock as a message names it (issue #857): the locked trait's name and the name of the workflow holding it. */
class LockNames(val trait: String, val workflow: String)

/**
 * The wording of a trait-lock refusal (issue #857), in the kernel so the backend's lock guard and the raw editor --
 * which catches a locked change before sending it -- say the same refusal the same way, whichever side catches it.
 * Names only, as the pages show them: a trait's title (or its humanized id) and a workflow's label, never an id.
 */
object TraitLockCopy {
    /**
     * A change to locked traits: "Site audit is locked by Audit review and can't be changed now." -- or, with several
     * workflows, each naming what it locks: "Audit review locks Site audit and Site follow-up locks Site follow-up,
     * so they can't be changed now."
     */
    fun changeRefused(locks: List<LockNames>): String {
        val byWorkflow = locks.groupBy { it.workflow }
        if (byWorkflow.size <= 1) {
            val traits = locks.map { it.trait }.distinct()
            val workflow = byWorkflow.keys.firstOrNull().orEmpty()
            return "${joinAsPhrase(traits)} ${if (traits.size == 1) "is" else "are"} locked by $workflow and can't be " +
                "changed now."
        }
        return joinAsPhrase(byWorkflow.map { (workflow, held) -> "$workflow locks ${traitsOf(held)}" }) +
            ", so they can't be changed now."
    }

    /** What follows [changeRefused] for someone every lock allows to override. */
    fun overrideOffer(lockCount: Int): String =
        "You can override the ${if (lockCount == 1) "lock" else "locks"} by giving a reason."

    /**
     * An override asked of locks the caller may not override: "You can't override the lock Audit review holds on Site
     * audit." -- no possessive, so a label ending in "s" reads as well as any other.
     */
    fun overrideRefused(locks: List<LockNames>): String =
        "You can't override " + joinAsPhrase(locks.map { "the lock ${it.workflow} holds on ${it.trait}" }) + "."

    /** A delete of a form holding locked traits: "The form can't be deleted while it's in Audit review, which locks its Site audit." */
    fun deleteRefused(locks: List<LockNames>): String {
        val workflows = locks.map { it.workflow }.distinct()
        return "The form can't be deleted while it's in ${joinAsPhrase(workflows)}, which " +
            "${if (workflows.size == 1) "locks" else "lock"} its ${traitsOf(locks)}."
    }

    private fun traitsOf(locks: List<LockNames>): String = joinAsPhrase(locks.map { it.trait }.distinct())
}
