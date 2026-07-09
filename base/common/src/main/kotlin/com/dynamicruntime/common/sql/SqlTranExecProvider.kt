package com.dynamicruntime.common.sql

/**
 * The three phases of a standard topic transaction, supplied to [SqlTranUtil.doTran]. [lock] attempts to
 * take the transaction lock and reports whether it succeeded; [insert] creates the lock row (done outside a
 * transaction, only after a first lock attempt failed); [execute] runs the actual work under the lock.
 */
interface SqlTranExecProvider {
    /** Inserts the row on which the lock will be taken. */
    fun insert()

    /** Attempts to take the lock; returns true if it was acquired. */
    fun lock(): Boolean

    /** Performs the transaction's work under the acquired lock. */
    fun execute()
}
