package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt

/**
 * Convenience wrapper bundling the parameters common to SQL calls: the acting [cxt], the target [sqlDb], the
 * [topic] (a logical grouping of tables), and — when created from a [SqlTopic] — the resolved [sqlTopic] plus
 * the mutable transaction state used by the topic transaction machinery.
 */
class SqlCxt(
    val cxt: KdrCxt,
    val sqlDb: SqlDatabase,
    val topic: String,
    /** The resolved topic, when this context was created for a full topic; null for a bare database context. */
    val sqlTopic: SqlTopic? = null,
) {
    /** Creates a context bound to a full [sqlTopic] (its database and name are taken from the topic). */
    constructor(cxt: KdrCxt, sqlTopic: SqlTopic) : this(cxt, sqlTopic.sqlDb, sqlTopic.name, sqlTopic)

    /**
     * The row data for the current topic transaction. Set before a transaction starts (to help create the
     * lock row), then updated during it with values that should be persisted in the lock row; when the row
     * is read back mid-transaction, it is replaced with that row's data. Owned by the transaction machinery.
     */
    var tranData: MutableMap<String, Any?> = LinkedHashMap()

    /** Set true if the current transaction performed an insert to create the lock row. */
    var didInsert: Boolean = false

    /**
     * Set true by transaction-implementing code that detects the work was already done (transactions should
     * be idempotent); suppresses the write-back of the lock row.
     */
    var tranAlreadyDone: Boolean = false
}
