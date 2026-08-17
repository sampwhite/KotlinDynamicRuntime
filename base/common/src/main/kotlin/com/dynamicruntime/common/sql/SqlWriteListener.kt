package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt

/**
 * Notified when a statement writes rows, with the tables it touched
 * ([SqlStatement.tableNames]). Registered with [SqlTopicService.addWriteListener].
 *
 * This exists so the data layer can *publish* what it wrote without knowing who
 * cares. "A write happened, to these tables" is a fact the SQL layer owns and is
 * the only place that reliably knows; what anybody does about it -- invalidate a
 * cache, audit, wake a projection -- is not its business.
 *
 * The alternative is for the bottom of the data layer to name the thing sitting
 * on top of it, which makes a package cycle (`sql` -> `sql.cache` -> `sql`) and
 * leaves the cache impossible to reason about without also reading the
 * connection pool. One interface is a cheap price for keeping the dependency
 * pointing one way.
 */
fun interface SqlWriteListener {
    /**
     * Called after a statement affected at least one row. [tableNames] is nearly always a single table.
     *
     * Called **inside** the writing session, and inside its transaction when there is one, so an
     * implementation should be cheap and must not assume the write is committed -- a rolled-back transaction
     * still reports here. In particular it must not *read* the written data from inside the notification: on
     * the writer's own connection that read sees uncommitted rows (the table caches defer their reload past
     * the transaction for exactly this reason). It must not throw -- and [SqlDatabase.publishWrite] enforces
     * that by catching and logging, because by the time listeners run the write has already happened and a
     * listener's failure must not make it look otherwise.
     */
    fun onWrite(cxt: KdrCxt, tableNames: List<String>)
}
