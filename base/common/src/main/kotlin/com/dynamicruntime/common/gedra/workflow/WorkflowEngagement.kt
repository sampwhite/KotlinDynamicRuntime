package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * Putting a form into a normal workflow, and taking it back out (issue #794) -- the write half of
 * [WFS.workflowEngagement].
 *
 * Engagement is an **asserted** state entry, so every recompute preserves it verbatim; what this writes is the
 * fact, and the derived [WFS.workflowState] entries follow from the recompute that runs after.
 *
 * ### Why disengaging does not delete
 *
 * Disengaging sets [WFS.engaged] false and records a [WFS.disengagedEvent] rather than removing the entry: that
 * a form was once in a workflow is part of its history, and an entry that vanished would take the reason with
 * it. The entry is declared open, so the later slices can record their own milestones (an approval, a finish)
 * against the same workflow.
 */
object WorkflowEngagement {
    /**
     * Sets whether [gedraId] is engaged with [workflowId], recording the transition, and recomputes the form's derived state so the per-workflow entries follow. Returns the form's state
     * entries as they stand afterwards.
     *
     * Two writes rather than one transaction, deliberately for this slice: the state write and the recompute are
     * each transactional on their own, and an engagement that landed without its recompute is corrected by the
     * next write or recompute rather than being wrong -- the derived entries are a projection, after all. A
     * single-transaction form belongs with the batch work (issue #793), where the recompute loop is built.
     */
    fun setEngaged(
        cxt: KdrCxt,
        gedraId: GedraId,
        workflowId: String,
        engaged: Boolean,
        scope: ReadScope,
    ): List<Map<String, Any?>> {
        val svc = GedraDataService.get(cxt)
        val current = svc.readState(cxt, gedraId, scope)
        svc.writeState(
            cxt, gedraId,
            withEngagement(current, workflowId, engaged, cxt.instanceNow(), cxt.userProfile.userId),
        )
        // The derived entries are computed against the engagement that now stands, so refresh them.
        svc.recomputeDerivedState(cxt, gedraId, scope)
        return svc.readState(cxt, gedraId, scope)
    }

    /**
     * [current] with the [workflowId] engagement entry set to [engaged] and its transition recorded -- every
     * other entry carried through as it stands, since `writeState` replaces the whole set.
     *
     * Pure, so the merge rule is testable without a database: an entry that exists keeps what it holds and has
     * its trail extended; one that does not is created with this event as its first. Disengaging **keeps** the entry and records the
     * transition rather than removing it -- that a form was once in a workflow is part of its history.
     *
     * [at] and [by] are the moment and the actor; `lastEngagedAt`/`lastEngagedBy` move only on an *engage*, so
     * "since when, and who put it here" survives a later disengage, while the event is appended either way.
     */
    fun withEngagement(
        current: List<Map<String, Any?>>,
        workflowId: String,
        engaged: Boolean,
        at: Instant,
        by: Long,
    ): List<Map<String, Any?>> {
        fun isThisEngagement(entry: Map<String, Any?>): Boolean =
            entry[GE.traitId].toOptStr() == WFS.workflowEngagement &&
                entry[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() == workflowId

        val existing = current.firstOrNull { isThisEngagement(it) }?.get(GE.data).toJsonMapOrEmpty()
        val event = linkedMapOf<String, Any?>(
            WFS.kind to if (engaged) WFS.engagedEvent else WFS.disengagedEvent,
            WFS.at to at,
            WFS.by to by,
        )
        val updated = linkedMapOf<String, Any?>(
            WFD.workflowId to workflowId,
            WFS.engaged to engaged,
            // Appended, never replaced: the trail is the evolution of this form's relationship with the workflow.
            WFS.events to existing[WFS.events].toJsonListOfMaps() + listOf(event),
        )
        if (engaged) {
            updated[WFS.lastEngagedAt] = at
            updated[WFS.lastEngagedBy] = by
        }
        // Anything a later slice wrote on the entry and this one does not know about rides through unchanged.
        for ((k, v) in existing) {
            if (k !in updated) {
                updated[k] = v
            }
        }
        val others = current.filterNot { isThisEngagement(it) }
            .map { mapOf(GE.traitId to it[GE.traitId], GE.data to it[GE.data].toJsonMapOrEmpty()) }
        return others + listOf(mapOf(GE.traitId to WFS.workflowEngagement, GE.data to updated))
    }
}
