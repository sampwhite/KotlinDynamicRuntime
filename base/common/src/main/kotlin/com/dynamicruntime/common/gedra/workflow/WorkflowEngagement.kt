package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.startup.SchemaService
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
     * Sets whether the form [row] is engaged with [workflowId], recording the transition, and recomputes the
     * form's derived state so the per-workflow entries follow. Returns the form's state entries as they stand
     * afterwards.
     *
     * One locked read-modify-write ([GedraDataService.changeState]): the engagement is merged into the state as
     * it stands *under the lock*, so two engagements at once cannot each write back a set missing the other,
     * and a state row this creates belongs to the form's owner rather than to whoever engaged it. [row] is the
     * form as the caller was admitted to it.
     *
     * **Engaging is gated on eligibility** (issue #783) when [def] -- the workflow's definition -- is given: a form
     * that fails any of its tests is refused, and the refusal carries every reason. Evaluated here, under the
     * lock, against the form's stored cfacts (every write recomputes them, so they are the form's current ones),
     * and against the *definition* rather than a stored `eligible` flag, so a test added since the last recompute
     * already binds. Disengaging is never gated: taking a form out of a workflow needs no qualification.
     */
    fun setEngaged(
        cxt: KdrCxt,
        row: GedraDataRow,
        workflowId: String,
        engaged: Boolean,
        def: WfDef?,
    ): List<Map<String, Any?>> {
        // The actor and the moment, taken before the owner binding -- they are who did it, not whose form it is.
        val at = cxt.instanceNow()
        val by = cxt.userProfile.userId
        val registry = SchemaService.get(cxt).cfactsFor(row.client)
        return GedraDataService.get(cxt).changeState(cxt, row) { current ->
            if (engaged && def != null) {
                val failures = WorkflowEligibility.failures(registry, def, WorkflowEligibility.formFacts(current))
                if (failures.isNotEmpty()) {
                    val reasons = WorkflowEligibility.explain(cxt, def, failures)
                    throw KdrException.mkInput(
                        "This form is not eligible for workflow '$workflowId': " + reasons.joinToString(" ") +
                            " (failed: ${failures.joinToString(", ")}).",
                    )
                }
            }
            withEngagement(current, workflowId, engaged, at, by)
        }
    }

    /** Whether [entries] hold an engagement entry for [workflowId], engaged or not. */
    fun hasEngagement(entries: List<Map<String, Any?>>, workflowId: String): Boolean =
        entries.any { isEngagementFor(it, workflowId) }

    /** Whether the state [entry] is the engagement entry for [workflowId]. */
    fun isEngagementFor(entry: Map<String, Any?>, workflowId: String): Boolean =
        entry[GE.traitId].toOptStr() == WFS.workflowEngagement &&
            entry[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() == workflowId

    /**
     * [current] with the [workflowId] engagement entry set to [engaged] and its transition recorded -- every
     * other entry carried through as it stands, since `writeState` replaces the whole set.
     *
     * Pure, so the merge rule is testable without a database: an entry that exists keeps what it holds and has
     * its trail extended; one that does not is created with this event as its first. Disengaging **keeps** the
     * entry and records the transition rather than removing it -- that a form was once in a workflow is part of
     * its history.
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
        val existing = current.firstOrNull { isEngagementFor(it, workflowId) }?.get(GE.data).toJsonMapOrEmpty()
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
        val others = current.filterNot { isEngagementFor(it, workflowId) }
            .map { mapOf(GE.traitId to it[GE.traitId], GE.data to it[GE.data].toJsonMapOrEmpty()) }
        return others + listOf(mapOf(GE.traitId to WFS.workflowEngagement, GE.data to updated))
    }
}
