package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.SqlCxt

/**
 * What a [GedraWriteHook] is handed about the write that just happened (issue #675): the open transaction it
 * runs in ([sqlCxt]) and the written [row] (its entries the post-write ones a patch produced).
 *
 * A **parameters object rather than loose arguments on purpose.** A write path builds up "the universe already
 * built" -- the row, and in time things it read or resolved on the way (a state row already read under the lock,
 * a resolved workflow) -- and a hook wants that without re-fetching it. Carrying it here lets a field be *added*
 * as more is collected, without changing [GedraWriteHook.afterWrite]'s signature and every hook with it. It is
 * also where the double state-row read (recompute reads it, then `writeState` re-reads it) would be retired: the
 * already-read row rides along rather than being fetched twice.
 */
class GedraWriteContext(val sqlCxt: SqlCxt, val row: GedraDataRow)

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
 * **Future: a `priority` for ordering.** When there is more than one hook, this interface should advertise a
 * priority the fire loop sorts on, rather than leaning on registration order (which couples ordering to
 * component load order). It is deliberately not here yet: with a single hook there is nothing to sort, so a sort
 * added now could not be exercised by a test -- and an untested sort is the kind of thing that is quietly wrong
 * until the second hook lands. So the sort waits for the second hook, which is also the first test of it.
 */
fun interface GedraWriteHook {
    /** Run after [write]'s data write, inside its transaction. */
    fun afterWrite(cxt: KdrCxt, write: GedraWriteContext)
}

/**
 * The built-in write hook (issue #675): recompute a form's **derived** state on every data write, preserving
 * its **asserted** state. This is what makes derived state (a survey's completeness/validity, a year-presence
 * projection) follow the data on *every* path -- a plain `formDoc/create`, a raw patch, an import -- not only an
 * interactive survey save. It delegates to [GedraDataService.recomputeDerivedStateUnderLock], which reads the
 * asserted entries to keep under the write's own lock rather than from the cache (the cache is not the source of
 * truth mid-transaction).
 */
object DerivedStateWriteHook : GedraWriteHook {
    override fun afterWrite(cxt: KdrCxt, write: GedraWriteContext) {
        GedraDataService.get(cxt).recomputeDerivedStateUnderLock(cxt, write.sqlCxt, write.row)
    }
}
