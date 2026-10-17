package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt

/**
 * The transactions open on the current thread (issue #1266), and what was pinned for each when it opened.
 *
 * A transaction is opened, and committed or rolled back, on one thread ([SqlDatabase.withTranAndSession]), and the
 * work inside it runs there too -- including work on a sibling context that holds no transaction of its own. So the
 * thread, not the context, is what knows "a transaction is open here", which is the fact a table cache needs: inside
 * one it must not refresh, and must not fall back to a query that would either join the transaction or take a second
 * connection from the same pool while this thread holds locks.
 *
 * Held in a [ScopedValue] bound for exactly the transaction's block ([withOpen]): the binding ends when the block
 * returns or throws, so no transaction's pins can outlive it on a pooled thread, and a transaction opened inside
 * another rebinds the list with both and restores the outer one when it ends. A thread forked inside a transaction
 * with `StructuredTaskScope` inherits the binding, which is right: it is the transaction's work.
 *
 * Each open transaction is recorded with its [SqlDatabase], so a cache on another pool is not held to it: a query
 * there joins no transaction here and competes for no connection this thread holds.
 */
object SqlThreadTrans {
    /** One open transaction: the pool it is on, and what listeners pinned for it ([SqlTranListener]). */
    class OpenTran(val sqlDb: SqlDatabase) {
        /** Keyed by whatever the listener chooses -- a table cache keys by itself. Filled before the transaction opens. */
        val pins: MutableMap<Any, Any> = HashMap()
    }

    private val open: ScopedValue<List<OpenTran>> = ScopedValue.newInstance()

    /** The transactions open in the current scope, outermost first; empty outside any. */
    private fun openNow(): List<OpenTran> = open.orElse(emptyList())

    /** Runs [body] with [tran] recorded as open, beside any transaction already open here, for exactly its extent. */
    fun withOpen(tran: OpenTran, body: () -> Unit) {
        ScopedValue.where(open, openNow() + tran).run(body)
    }

    /** What the outermost open transaction in this scope pinned under [key], or null when none did. */
    fun pinFor(key: Any): Any? = openNow().firstNotNullOfOrNull { it.pins[key] }
}

/**
 * Told when a transaction is about to open (issue #1266), with the context opening it, while its connection is still
 * in auto-commit -- so whatever a listener reads here is read **outside** the transaction. A listener records what it
 * wants held for the transaction's duration in [SqlThreadTrans.OpenTran.pins]; the table caches pin their snapshots
 * this way. Registered with [SqlTopicService.addTranListener], for the same reason [SqlWriteListener] is: the data
 * layer announces, and does not name who listens.
 *
 * It must not throw: a listener's failure would refuse a transaction for a reason that has nothing to do with it.
 * [SqlDatabase.withTranAndSession] catches and logs.
 */
fun interface SqlTranListener {
    fun onTranOpening(cxt: KdrCxt, tran: SqlThreadTrans.OpenTran)
}
