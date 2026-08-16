package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** Field names for a patch's request and its answer (issue #337). Each name matches its value. */
@Suppress("ConstPropertyName")
object GPF {
    /** The request's targets, keyed by gedra kind. */
    const val targets = "targets"

    /** Within a target: the edits asked of that one gedra. */
    const val edits = "edits"

    /** In the answer: what became of each edit, keyed by the trait it named. */
    const val outcomes = "outcomes"

    /** In an outcome: whether the edit changed anything. */
    const val applied = "applied"
}

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
class GedraPatchTarget(val gedraId: GedraId, val edits: List<GedraEdit>) {
    companion object {
        /** Reads a target off the validated request map, resolving its id through [gedraService]. */
        fun extract(gedraService: GedraService, raw: Map<String, Any?>): GedraPatchTarget {
            val fullId = raw[GDF.gedraId].toOptStr()
                ?: throw KdrException.mkInput("A patch target needs a ${GDF.gedraId}.")
            val edits = raw[GPF.edits].toJsonListOrEmpty().map { GedraEdit.extract(it.toJsonMapOrEmpty()) }
            if (edits.isEmpty()) {
                throw KdrException.mkInput(
                    "The target '$fullId' asks for no edits. A target names a gedra in order to change it, " +
                        "so an empty list is a caller mistake rather than a way to say nothing.",
                )
            }
            return GedraPatchTarget(gedraService.readId(fullId), edits)
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
