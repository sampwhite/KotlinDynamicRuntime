package com.dynamicruntime.common.sql

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.mkUniqueId

/**
 * Implements the standard topic transaction: take a lock on a single lock row by updating its [PF.touchedAt]
 * date, read the row back, run the caller's work, then write the row back (recording the transaction id).
 * The initial lock-row insert is done outside the transaction (see [SqlTranUtil]) because some databases
 * handle inserts with uniqueness constraints poorly inside a transaction. Protocol columns (audit dates,
 * owner, actor) are populated via [SqlTopicUtil].
 *
 * Rewritten from the prior-art `SqlTopicTranProvider`; the caller's work is a plain lambda rather than a
 * `SqlFunction`, and protocol population reads the owner/actor split off the [com.dynamicruntime.common.context.KdrCxt].
 */
class SqlTopicTranProvider(
    val sqlCxt: SqlCxt,
    val tranName: String,
    tranId: String?,
    /** Which lock table to take; null means the topic's only one (see [SqlTopic.tranFor]). */
    tranTableName: String?,
    val tranExecute: () -> Unit,
) : SqlTranExecProvider {
    val cxt = sqlCxt.cxt
    val sqlDb = sqlCxt.sqlDb
    val sqlTopic = sqlCxt.sqlTopic
        ?: throw KdrException("SqlCxt must provide a SqlTopic to run topic transaction $tranName.")

    // SqlTopic.init must have built these; resolving the set as a unit is what keeps a topic with several
    // transactional tables from mixing one table's statements with another's (issue #435).
    private val tran = sqlTopic.tranFor(tranTableName, tranName)
    val tranTable = tran.table

    val tranId: String = tranId ?: (tranName + cxt.mkUniqueId())

    override fun insert() {
        if (tran.insertLock.returnGeneratedKeys) {
            throw KdrException(
                "Cannot insert in transaction logic because the insert auto-increments a column for " +
                    "transaction $tranName.",
            )
        }
        SqlTopicUtil.prepForStdExecute(cxt, tranTable, sqlCxt.tranData)
        SqlTopicUtil.prepForTranInsert(cxt, sqlCxt.tranData)
        sqlDb.executeStatement(cxt, tran.insertLock, sqlCxt.tranData)
    }

    override fun lock(): Boolean {
        sqlCxt.tranAlreadyDone = false
        sqlCxt.tranData[PF.touchedAt] = cxt.instanceNow() // a persisted queuing date (issue #160)
        return sqlDb.executeStatement(cxt, tran.takeLock, sqlCxt.tranData) > 0
    }

    override fun execute() {
        val curRow = sqlDb.queryOneStatement(cxt, tran.queryLock, sqlCxt.tranData)
            ?: throw KdrException("Data not present for ${tran.queryLock.name} after initiating transaction.")

        // Absorb the persisted row into tranData, but keep our own touchedAt/lastTranId bookkeeping.
        for ((k, v) in curRow) {
            if (k != PF.lastTranId && k != PF.touchedAt) {
                sqlCxt.tranData[k] = v
            }
        }

        tranExecute()

        if (!sqlCxt.tranAlreadyDone) {
            sqlCxt.tranData[PF.lastTranId] = tranId
            SqlTopicUtil.prepForStdExecute(cxt, tranTable, sqlCxt.tranData)
            sqlDb.executeStatement(cxt, tran.updateLock, sqlCxt.tranData)
        }
    }

    companion object {
        /**
         * Runs [tranExecute] as a topic transaction named [tranName]. [tranData] is cloned onto the context
         * (so the caller's map is not mutated); the resulting row can be read afterward from
         * [SqlCxt.tranData]. A null [tranId] is generated from the transaction name plus a unique id.
         */
        fun executeTopicTran(
            sqlCxt: SqlCxt,
            tranName: String,
            tranId: String?,
            tranData: Map<String, Any?>,
            /**
             * Which lock table to take. Null means the topic's only transactional table, so every
             * single-table topic calls this exactly as before; a topic with several refuses rather than
             * picking one (issue #435).
             */
            tranTableName: String? = null,
            tranExecute: () -> Unit,
        ) {
            sqlCxt.tranData = LinkedHashMap(tranData)
            val provider = SqlTopicTranProvider(sqlCxt, tranName, tranId, tranTableName, tranExecute)
            SqlTranUtil.doTran(sqlCxt, tranName, provider)
        }
    }
}
