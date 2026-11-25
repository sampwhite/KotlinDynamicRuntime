package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.SqlCxt

/**
 * What a [GedraWriteHook] is handed about the write that just happened (issue #675): the open transaction it
 * runs in ([sqlCxt]), the written [row] (its entries the post-write ones a patch produced), and [priorEntries], the
 * entries as they stood under the lock before the write -- empty for a create -- so a hook can tell what this write
 * changed from what it only carried along (issue #1838).
 *
 * A **parameters object rather than loose arguments on purpose.** A write path builds up "the universe already
 * built" -- the row, and in time things it read or resolved on the way (a state row already read under the lock,
 * a resolved workflow) -- and a hook wants that without re-fetching it. Carrying it here lets a field be *added*
 * as more is collected, without changing [GedraWriteHook.afterWrite]'s signature and every hook with it. It is
 * also where the double state-row read (recompute reads it, then `writeState` re-reads it) would be retired: the
 * already-read row rides along rather than being fetched twice.
 */
class GedraWriteContext(val sqlCxt: SqlCxt, val row: GedraDataRow, val priorEntries: List<Map<String, Any?>>)

/**
 * A component-registered action run after a gedra's data is written (issue #675), **inside the write's own
 * transaction** -- so whatever it does commits atomically with the data, or not at all. `GedraDataService`
 * fires the registered hooks at the end of every create, patch, and import, on the `GedraDataTran` lock the
 * write already holds; a hook that writes state (the built-in one) nests on that same lock.
 *
 * This is the seam Cedar reached for: mutating a form's data and recomputing the state that projects from it
 * are orthogonal concerns, connected by a hook rather than entangled -- and any component can add post-write
 * activity (a further state projection, an audit fact) without the write paths knowing about it. Work that must
 * run *outside* the transaction -- a genuine side effect, an external call -- does not belong here; that is the
 * future outbox on the transaction table.
 *
 * Registered through `SchemaCollector.addWriteHook`; hooks run in registration order, so a hook that reads the
 * state an earlier one wrote is registered after it. A hook that throws rolls the write back with it, so a hook
 * whose failure must not fail the write (a recomputable projection) swallows its own faults -- as the built-in
 * [DerivedStateWriteHook] does.
 *
 * **Five hooks, all registered by `CommonComponent`, in this order:** `CounterClaimHook` (issue #1849), which holds a
 * counter's field to the ids issued to the **caller**, first so a refused claim fails before the rest is done;
 * `FormTypeHook` (issue #1902), which holds a form's type to those its client defines and the **caller** may set;
 * [UniqueClaimHook] (issue #1851), which refuses a value another of the owner's forms holds;
 * `DataFileLinkHook` (issue #1838), which links a form to the data files its fields name and judges a newly linked file
 * against the **caller's** read scope; then [DerivedStateWriteHook]. The order matters: all hooks in one firing are
 * handed the **same** `cxt`, and the state hook rebinds its owner
 * ([com.dynamicruntime.common.context.KdrCxt.bindTransactionOwner]) to own its state to the gedra, which a later hook
 * would see.
 *
 * **Future: a `priority` for ordering, and a per-hook context.** All five are the common component's, so registration
 * order is one decision in one file, not an accident of component load order. When a hook comes from another
 * component, or one that must run after the state hook needs the actor's scope, this interface should advertise a
 * priority the fire loop sorts on, and each hook should get its own transaction-scoped context (a fresh
 * `mkTransactionSubContext` sharing the session) so one hook's rebind cannot bleed into the next.
 */
fun interface GedraWriteHook {
    /** Run after [write]'s data write, inside its transaction. */
    fun afterWrite(cxt: KdrCxt, write: GedraWriteContext)

    /**
     * Run after [row] is deleted (issue #1851), inside the delete's transaction ([sqlCxt]), with [row] as it stood. Nothing
     * by default: most of what a gedra has beside it is kept with the disabled row.
     */
    fun afterDelete(cxt: KdrCxt, sqlCxt: SqlCxt, row: GedraDataRow) {}
}

/**
 * The built-in write hook (issue #675): recompute a form's **derived** state on every data write, preserving its
 * **asserted** state. This is what makes derived state (a core details workflow's completeness/validity, a
 * year-presence projection) follow the data on *every* path -- a plain `formDoc/create`, a raw patch, an import -- not
 * only an interactive core details save. It delegates to [GedraDataService.recomputeDerivedStateUnderLock], which reads
 * the asserted entries to keep under the write's own lock rather than from the cache (the cache is not the source of
 * truth mid-transaction).
 */
object DerivedStateWriteHook : GedraWriteHook {
    override fun afterWrite(cxt: KdrCxt, write: GedraWriteContext) {
        GedraDataService.get(cxt).recomputeDerivedStateUnderLock(cxt, write.sqlCxt, write.row)
    }
}
