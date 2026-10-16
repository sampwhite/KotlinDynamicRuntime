package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import kotlin.time.Instant

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
     * Called once a write is **committed** (issue #1256), with the tables it touched -- [tableNames] is nearly
     * always a single table -- and [changedSince], the earliest `updatedAt` the write stamped on a row (the moment
     * the statement ran, when it stamped none).
     *
     * After the commit, and not when the statement runs, because a listener on another thread acts on the
     * notification at once: a table cache told while the writer's transaction was still open would refresh, miss
     * the uncommitted row, and consider itself current. A write outside a transaction is committed by its statement
     * and is reported straight after it; one inside a transaction is held on its session ([SqlSession.pendingWrites])
     * and reported when the transaction commits, or never, if it rolls back. A statement nested in an outer
     * transaction waits for the outer commit.
     *
     * [changedSince] is what lets a cache look back far enough. A transaction can run long after it stamped a row,
     * and an unrelated refresh in between may have moved the cache's query start past that stamp.
     *
     * It must not throw -- [SqlDatabase.announceWrite] enforces that by catching and logging, because by the time
     * listeners run the write has already happened and a listener's failure must not make it look otherwise.
     */
    fun onWrite(cxt: KdrCxt, tableNames: List<String>, changedSince: Instant)
}
