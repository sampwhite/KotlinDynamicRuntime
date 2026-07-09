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
    val tranExecute: () -> Unit,
) : SqlTranExecProvider {
    val cxt = sqlCxt.cxt
    val sqlDb = sqlCxt.sqlDb
    val sqlTopic = sqlCxt.sqlTopic
        ?: throw KdrException("SqlCxt must provide a SqlTopic to run topic transaction $tranName.")
    val tranTable = sqlTopic.tranTable
        ?: throw KdrException("Topic ${sqlTopic.name} has no transactional table for transaction $tranName.")

    // SqlTopic.init must have built the lock queries.
    private val iTranLockQuery = requireQuery(sqlTopic.iTranLockQuery, "insert")
    private val qTranLockQuery = requireQuery(sqlTopic.qTranLockQuery, "select")
    private val uTranLockQuery = requireQuery(sqlTopic.uTranLockQuery, "update")
    private val uTakeLockQuery = requireQuery(sqlTopic.uTakeLockQuery, "take-lock")

    val tranId: String = tranId ?: (tranName + cxt.mkUniqueId())

    private fun requireQuery(stmt: SqlStatement?, which: String): SqlStatement =
        stmt ?: throw KdrException("Topic ${sqlTopic.name} has no $which lock query; was it initialized?")

    override fun insert() {
        if (iTranLockQuery.returnGeneratedKeys) {
            throw KdrException(
                "Cannot insert in transaction logic because the insert auto-increments a column for " +
                    "transaction $tranName.",
            )
        }
        SqlTopicUtil.prepForStdExecute(cxt, tranTable, sqlCxt.tranData)
        SqlTopicUtil.prepForTranInsert(cxt, sqlCxt.tranData)
        sqlDb.executeStatement(cxt, iTranLockQuery, sqlCxt.tranData)
    }

    override fun lock(): Boolean {
        sqlCxt.tranAlreadyDone = false
        sqlCxt.tranData[PF.touchedAt] = cxt.now()
        return sqlDb.executeStatement(cxt, uTakeLockQuery, sqlCxt.tranData) > 0
    }

    override fun execute() {
        val curRow = sqlDb.queryOneStatement(cxt, qTranLockQuery, sqlCxt.tranData)
            ?: throw KdrException("Data not present for ${qTranLockQuery.name} after initiating transaction.")

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
            sqlDb.executeStatement(cxt, uTranLockQuery, sqlCxt.tranData)
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
            tranExecute: () -> Unit,
        ) {
            sqlCxt.tranData = LinkedHashMap(tranData)
            val provider = SqlTopicTranProvider(sqlCxt, tranName, tranId, tranExecute)
            SqlTranUtil.doTran(sqlCxt, tranName, provider)
        }
    }
}
