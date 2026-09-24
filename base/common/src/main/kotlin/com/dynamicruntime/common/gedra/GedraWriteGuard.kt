package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt

/**
 * What a [GedraWriteGuard] is handed about a write it may refuse (issue #857): the gedra's [row] and its [states] as
 * they stood **under the write's lock** before the write; the trait ids the write **changes** ([changedTraits] -- an
 * edit that leaves its entry as stored is not a change, a removal is); and the [overrideReason] the writer gave to
 * override a guard, or null when they asked for no override. [deletesGedra] marks the deletion of the whole gedra,
 * whose [changedTraits] are every trait it holds and which carries no override. [states] is read on first use, so a
 * guard with nothing to check costs no state read.
 */
class GedraGuardedWrite(
    val row: GedraDataRow,
    val changedTraits: Set<String>,
    val overrideReason: String?,
    val deletesGedra: Boolean = false,
    statesProvider: () -> List<Map<String, Any?>>,
) {
    val states: List<Map<String, Any?>> by lazy(statesProvider)
}

/**
 * A component-registered check on a write to an **existing** gedra's data (issue #857), run inside the write's own
 * transaction, **under the gedra's lock and before anything is written** -- so what it decides from the gedra's
 * state holds at the moment of the write, and a refusal (a throw) rolls back with nothing written. Every edit of an
 * existing gedra goes through the patch -- the raw editor, the patch endpoint, the survey's and a workflow's saves --
 * and its deletion through the delete, so the two cover them all; a create or an import makes a new gedra, which has
 * no state to guard it by.
 *
 * On a patch a guard may also return a **state change** to apply with the write -- the trail a permitted override
 * leaves, say. It is applied after the edits and before the post-write hooks, whose recompute preserves it (it is
 * asserted state). Null for none; a delete ignores it.
 *
 * Registered through `SchemaCollector.addWriteGuard`, the counterpart of `addWriteHook` on the other side of the
 * write; the data layer never names the workflow code that registers one.
 */
fun interface GedraWriteGuard {
    fun check(cxt: KdrCxt, write: GedraGuardedWrite): ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)?
}
