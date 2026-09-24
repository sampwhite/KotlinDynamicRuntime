package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt

/**
 * What a [GedraWriteGuard] is handed about a patch it may refuse (issue #857): the gedra's [row] and its [states] as
 * they stand **under the write's lock**, before any edit is applied; the trait ids the patch edits ([editedTraits] --
 * every action, deletes included); and the [overrideReason] the writer gave to override a guard, or null when they
 * asked for no override. [states] is read on first use, so a guard with nothing to check costs no state read.
 */
class GedraGuardedWrite(
    val row: GedraDataRow,
    val editedTraits: Set<String>,
    val overrideReason: String?,
    statesProvider: () -> List<Map<String, Any?>>,
) {
    val states: List<Map<String, Any?>> by lazy(statesProvider)
}

/**
 * A component-registered check on an edit of an **existing** gedra's data (issue #857), run inside the patch's own
 * transaction, **under the gedra's lock and before any edit is applied** -- so what it decides from the gedra's
 * state holds at the moment of the write, and a refusal (a throw) rolls back with nothing written. Every edit of an
 * existing gedra goes through the patch -- the raw editor, the patch endpoint, the survey's and a workflow's saves --
 * so one guard covers them all; a create or an import makes a new gedra, which has no state to guard it by.
 *
 * A guard may also return a **state change** to apply with the write -- the trail a permitted override leaves, say.
 * It is applied after the edits and before the post-write hooks, whose recompute preserves it (it is asserted
 * state). Null for none.
 *
 * Registered through `SchemaCollector.addWriteGuard`, the counterpart of `addWriteHook` on the other side of the
 * write; the data layer never names the workflow code that registers one.
 */
fun interface GedraWriteGuard {
    fun check(cxt: KdrCxt, write: GedraGuardedWrite): ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)?
}
