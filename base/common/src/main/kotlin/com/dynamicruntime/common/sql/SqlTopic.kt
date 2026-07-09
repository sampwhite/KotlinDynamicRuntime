package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException

/**
 * The runtime object for one topic: the database it uses, the tables it owns, and — if one of those tables
 * opts into [TableFeature.transactions] — the four prepared statements that drive the standard topic
 * transaction (insert a lock row, take the lock, read it back, write it back).
 *
 * Rewritten from the prior-art `SqlTopic`, with the shard dimension removed and table definitions supplied
 * from the schema store (rather than looked up ad hoc). The topic *creates* its tables in [init]; the
 * definitions themselves live in [com.dynamicruntime.common.context.KdrSchemaStore].
 */
class SqlTopic(
    /** Name of the topic. */
    val name: String,
    /** Database this topic's tables live in. */
    val sqlDb: SqlDatabase,
    /** The tables belonging to this topic (from the schema store). */
    val tables: List<KdrTable>,
) {
    /** The single table designated as the transaction-lock table, or null if the topic has no transactions. */
    val tranTable: KdrTable? = run {
        val transactional = tables.filter { it.isTransactional }
        if (transactional.size > 1) {
            throw KdrException(
                "Topic $name has more than one transactional table: ${transactional.map { it.tableName }}.",
            )
        }
        transactional.firstOrNull()
    }

    /** Inserts the initial lock row (done outside a transaction, on retry, after a lock attempt fails). */
    var iTranLockQuery: SqlStatement? = null

    /** Reads the lock row back immediately after the lock is taken. */
    var qTranLockQuery: SqlStatement? = null

    /** Writes the lock row back at the end of a transaction that changed state. */
    var uTranLockQuery: SqlStatement? = null

    /** Takes the lock by updating [PF.touchedAt] on the lock row. */
    var uTakeLockQuery: SqlStatement? = null

    /**
     * Creates (or updates) all the topic's tables and, if the topic has a transaction-lock table, builds
     * the four lock queries. Must be called with a [sqlCxt] bound to this topic.
     */
    fun init(sqlCxt: SqlCxt) {
        val cxt = sqlCxt.cxt
        sqlDb.withSession(cxt) {
            for (table in tables) {
                SqlTableUtil.checkCreateTable(sqlCxt, table)
            }
        }
        val t = tranTable ?: return
        // Query building only reads the (now-registered) column aliases; no live connection is needed.
        iTranLockQuery = SqlTopicUtil.mkTableInsertStmt(sqlCxt, t)
        qTranLockQuery = SqlTopicUtil.mkTableSelectStmt(sqlCxt, t)
        uTranLockQuery = SqlTopicUtil.mkTableUpdateStmt(sqlCxt, t)
        uTakeLockQuery = SqlTopicUtil.mkTableTranLockStmt(sqlCxt, t)
    }

    companion object {
        /** Resolves a topic through the [SqlTopicService], or null if the service or topic is absent. */
        fun get(cxt: KdrCxt, topic: String): SqlTopic? = SqlTopicService.get(cxt)?.getOrCreateTopic(cxt, topic)
    }
}
