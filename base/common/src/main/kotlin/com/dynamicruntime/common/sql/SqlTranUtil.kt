package com.dynamicruntime.common.sql

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC

/**
 * Runs a database transaction with retries, capturing a retry pattern learned from real database behavior:
 * attempt to take a lock; if the lock row does not yet exist, insert it (outside a transaction, since some
 * databases handle inserts with uniqueness constraints poorly inside a transaction) and retry. Writing our
 * own retry loop — rather than leaning on a third-party library — lets us handle these cases deliberately.
 *
 * Rewritten from the prior-art `SqlTranUtil`.
 */
object SqlTranUtil {
    fun doTran(sqlCxt: SqlCxt, tranName: String, provider: SqlTranExecProvider) {
        val cxt = sqlCxt.cxt
        val sqlDb = sqlCxt.sqlDb
        // One session spans the whole transaction: the out-of-transaction lock-row insert and the
        // withTran(lock/execute) share a single connection. A nested call reuses an existing session.
        sqlDb.withSession(cxt) {
            val didIt = booleanArrayOf(false)
            var doInsert = false
            var didInsert = false
            sqlCxt.didInsert = false

            var lastException: KdrException? = null
            var i = 0
            while (i < 3 && !didIt[0]) {
                if (doInsert) {
                    try {
                        // Insert the row on which we wish to take a lock (outside the transaction).
                        provider.insert()
                        doInsert = false
                        didInsert = true
                        sqlCxt.didInsert = true // Advertise what we did to the code that implements *execute*.
                    } catch (e: KdrException) {
                        lastException = e
                        if (e.source != SRC.database) {
                            throw e
                        }
                        i++
                        continue
                    }
                }
                try {
                    // Attempt to do the transaction.
                    sqlDb.withTran(cxt) {
                        if (provider.lock()) {
                            provider.execute()
                            didIt[0] = true
                        }
                    }
                    // No error, but no work done means we need to insert a row to lock on.
                    if (!didIt[0]) {
                        doInsert = true
                    }
                } catch (e: KdrException) {
                    if (!e.canRetry()) {
                        throw e
                    }
                    lastException = e
                }
                if (doInsert && didInsert) {
                    // Should not happen.
                    throw KdrException(
                        "Could not take transaction lock after inserting a row for transaction $tranName.",
                        lastException,
                    )
                }
                i++
            }
            if (!didIt[0]) {
                throw KdrException(
                    "Unable to execute transaction $tranName after three attempts.",
                    lastException, EXC.internalError, SRC.database, ACT.code,
                )
            }
        }
    }
}
