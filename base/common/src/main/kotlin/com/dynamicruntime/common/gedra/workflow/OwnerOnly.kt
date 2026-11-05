package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraGuardedWrite
import com.dynamicruntime.common.gedra.GedraWriteGuard
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Forms kept to their owner (issue #1433): while a form carries the framework singleton [WSC.ownerOnly], an **editor**
 * (#1432) may change it only as its owner or as a reviewer of an engaged workflow's current task. A client puts the
 * fact on a form the way it does `kdr:needsReview` -- a workflow maps one of its own cfacts onto it with a singleton
 * rule -- so it lands in the form's stored `kdr:cfacts`, and nothing new is stored.
 *
 * It asks nothing of anyone but an editor ([AdminRules.editsClient]): an administrator changes any form it reaches,
 * and an ordinary user reaches only their own forms anyway. The system's own work is no editor's, so a recompute or a
 * job passes untouched.
 *
 * ### Who still may
 *
 *  - **The owner** ([RCF.isOwner], worked out by [RowCfacts]): in production another of the owner's own users counts,
 *    on a test instance only the owning user.
 *  - **A reviewer**: for each engaged normal workflow that still calculates, whoever its current task (`ctaTask`, as
 *    stored) names as a reviewer through its `viewerCfacts` functions -- [ViewerCfacts.forTask], the very answer the
 *    approve endpoint asks. So a reviewer may edit while the form waits on them, and not once the workflow has moved
 *    past their task. Nothing about the reviewer is stored per form.
 *
 * One computation, [keepsOut], answers both the guard that refuses the write ([OwnerOnlyGuard]) and the pages that
 * draw the form read-only (the locks answer, the workflow view), so what is drawn and what is refused cannot disagree.
 */
object OwnerOnly {
    /** Whether the form whose state is [states] carries [WSC.ownerOnly] in its stored cfacts. */
    fun isOn(states: List<Map<String, Any?>>): Boolean = states.any { entry ->
        entry[GE.traitId].toOptStr() == GT.cfacts &&
            entry[GE.data].toJsonMapOrEmpty()[GT.facts].toJsonListOrEmpty().any { it.toOptStr() == WSC.ownerOnly }
    }

    /**
     * Whether the caller [cxt] acts as is kept from changing [row], whose state is [states]: an editor, on a form kept
     * to its owner, who is neither its owner nor a reviewer of its current work. The cheap checks first, so a caller
     * who is no editor, or a form without the fact, costs no user or workflow lookup.
     */
    fun keepsOut(cxt: KdrCxt, row: GedraDataRow, states: List<Map<String, Any?>>): Boolean {
        @Suppress("RedundantIf2")
        if (!AdminRules.editsClient(cxt) || !isOn(states)) {
            return false
        }
        // The rule `kdr:isOwner|kdr:reviewer`, the owner asked first: it is a comparison on a test instance, where the
        // reviewer check resolves the engaged workflows.
        return !RowCfacts.isOwner(cxt, row.userId) && !isReviewer(cxt, row.client, row.entries, states)
    }

    /** Refuses (403) a change [keepsOut] keeps the caller from. */
    fun requireMayChange(cxt: KdrCxt, row: GedraDataRow, states: List<Map<String, Any?>>) {
        if (keepsOut(cxt, row, states)) {
            throw KdrException(TraitLockCopy.keptToOwner, code = EXC.notAuthorized)
        }
    }

    /**
     * Whether the caller is a reviewer of the current task of a normal workflow the form is engaged with. A workflow
     * the caller does not see, or one frozen past its relevancy (whose current task takes no action), names no one.
     * Asked of a caller who does not own the form ([keepsOut] asks the owner first), so a CTA filter is judged
     * without [RCF.isOwner].
     */
    fun isReviewer(
        cxt: KdrCxt,
        client: String,
        /** The form's entries, which the current task's CTA filter (issue #1595) is concluded from. */
        entries: List<Map<String, Any?>>,
        states: List<Map<String, Any?>>,
        /** The caller's viewer facts -- one shared by a caller asking of many forms. */
        viewer: ViewerCfacts = ViewerCfacts(cxt, client),
    ): Boolean {
        val engaged = engagedWorkflowIds(states).toSet()
        if (engaged.isEmpty()) {
            return false
        }
        val registry = WorkflowService.get(cxt).forClient(client)
        val cfacts = SchemaService.get(cxt).cfactsFor(client)
        val requestFacts by lazy { cfacts.assemble(cxt) }
        return states
            .filter { it[GE.traitId].toOptStr() == WFS.workflowState }
            .map { it[GE.data].toJsonMapOrEmpty() }
            .any { data ->
                val workflowId = data[WFD.workflowId].toOptStr()?.takeIf { it in engaged } ?: return@any false
                val def = WorkflowPhases.liveFor(cxt, registry, workflowId)?.def?.takeIf { it.entry == WfEntry.normal }
                    ?: return@any false
                if (!WorkflowPhases.of(cxt, client, def).calculates) {
                    return@any false
                }
                val task = data[WFS.ctaTask].toOptStr()?.let { def.task(it) } ?: return@any false
                // A reviewer the CTA's filter does not name (issue #1595) is not a reviewer of the current work. The filter
                // is concluded from the form's data now, as the judge and the approve endpoint conclude it: whoever may
                // act is decided by the data as it stands, and stored state only ever shows it.
                val filter = ctaAssignment(cxt, cfacts, workflowId, task, entries).filter
                WFC.reviewer in viewer.forCta(task, filter, requestFacts)
            }
    }
}

/**
 * Refuses an editor's change to a form kept to its owner (issue #1433), under the form's lock and before anything is
 * written -- every patch, workflow save and delete of an existing form, whichever surface asks. See [OwnerOnly].
 */
object OwnerOnlyGuard : GedraWriteGuard {
    override fun check(cxt: KdrCxt, write: GedraGuardedWrite): ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)? {
        // `keepsOut` asks whether the caller is an editor before it reads the (lazy) state, so no one else costs a read.
        OwnerOnly.requireMayChange(cxt, write.row, write.states)
        return null
    }
}
