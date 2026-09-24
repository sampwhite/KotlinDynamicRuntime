package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toOptStr

// `GPF` (the field-names for a patch's request and answer) now lives in `base/kernel` (GedraConstants.kt) so a
// front end can build a patch call and read its outcomes by name too (issue #393). References here resolve
// unchanged, same package.

/**
 * One thing a patch asks of one entry (issue #337).
 *
 * The verb sits beside the trait rather than inside the entry, because an action is not a property of a value;
 * see `gedra-patch.md`. [entryId] and [data] are both optional, and both absences mean something — see
 * [editEnvelopeFields], which declares the same shape in schema.
 */
class GedraEdit(
    val action: GedraEditAction,
    val traitId: String,
    val entryId: String? = null,
    val data: Map<String, Any?>? = null,
) {
    companion object {
        /** Reads an edit off the validated request map. */
        fun extract(raw: Map<String, Any?>): GedraEdit {
            val traitId = raw[GE.traitId].toOptStr()
                ?: throw KdrException.mkInput("An edit needs a ${GE.traitId}.")
            val actionName = raw[GED.action].toOptStr()
                ?: throw KdrException.mkInput("An edit to '$traitId' needs an ${GED.action}.")
            // The schema already refused an unknown verb by naming the ones that exist, so reaching here with
            // a bad one means something bypassed validation -- worth a plain fault rather than a silent default.
            val action = GedraEditAction.entries.firstOrNull { it.name == actionName }
                ?: throw KdrException.mkInput("'$actionName' is not an ${GED.action} an edit can carry.")
            return GedraEdit(
                action = action,
                traitId = traitId,
                entryId = raw[GE.entryId].toOptStr(),
                // Absent and empty are different: a delete sends no data at all, while `{}` is a caller
                // saying "these fields, none of them" -- which a merge is entitled to send.
                data = if (raw.containsKey(GE.data)) raw[GE.data].toJsonMapOrEmpty() else null,
            )
        }
    }
}

/** One gedra a patch touches, and everything it asks of that gedra. */
class GedraPatchTarget(
    val gedraId: GedraId,
    val edits: List<GedraEdit>,
    /**
     * A check run **under the gedra's lock**, before any edit is applied (issue #856): handed the row and its state
     * entries as they stand inside the transaction, it throws to refuse the patch. What lets a caller's condition on
     * the form's state -- a workflow the form must be engaged with -- hold at the moment of the write, rather than
     * at an earlier read another request could have overtaken. Null for none.
     */
    val underLock: ((row: GedraDataRow, states: List<Map<String, Any?>>) -> Unit)? = null,
    /**
     * The writer's reason for overriding a guard on this gedra (issue #857) -- a trait lock -- or null when they ask
     * for no override. Asking is explicit: a write without it is refused by a lock even when the writer could
     * override one.
     */
    val overrideReason: String? = null,
) {
    companion object {
        /**
         * Reads a target off the validated request map, resolving its id through [gedraService]. [overrideReason] is
         * the patch's own (issue #857), asked of every target it names.
         */
        fun extract(gedraService: GedraService, raw: Map<String, Any?>, overrideReason: String? = null): GedraPatchTarget {
            val fullId = raw[GDF.gedraId].toOptStr()
                ?: throw KdrException.mkInput("A patch target needs a ${GDF.gedraId}.")
            val edits = raw[GPF.edits].toJsonListOrEmpty().map { GedraEdit.extract(it.toJsonMapOrEmpty()) }
            if (edits.isEmpty()) {
                throw KdrException.mkInput(
                    "The target '$fullId' asks for no edits. A target names a gedra in order to change it, " +
                        "so an empty list is a caller mistake rather than a way to say nothing.",
                )
            }
            return GedraPatchTarget(gedraService.readId(fullId), edits, overrideReason = overrideReason)
        }
    }
}

/** What became of one edit. */
class GedraEditOutcome(val traitId: String, val applied: Boolean) {
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(GE.traitId to traitId, GPF.applied to applied)
}

/**
 * What became of one target's edits.
 *
 * Keyed by trait rather than by position in the request, which is possible because a gedra holds at most one
 * entry per trait and a target may ask one thing of each — so a trait names an edit unambiguously in both
 * directions. A positional index would be the alternative, and it breaks the moment anything reorders.
 */
class GedraPatchResult(val gedraId: GedraId, val outcomes: List<GedraEditOutcome>) {
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        GDF.gedraId to gedraId.fullId,
        GPF.outcomes to outcomes.map { it.toJsonMap() },
    )
}
