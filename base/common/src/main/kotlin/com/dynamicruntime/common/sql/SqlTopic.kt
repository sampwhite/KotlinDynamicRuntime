package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException

/**
 * The runtime object for one topic: the database it uses, the tables it owns, and — for each table that opts
 * into [TableFeature.transactions] — the four prepared statements that drive the standard topic transaction
 * (insert a lock row, take the lock, read it back, write it back).
 *
 * **A topic may have several transactional tables** (issue #435). It used to be limited to one, which meant a
 * second transactional concern needed a topic of its own — and a topic is also the unit of database
 * assignment, so that forced two unrelated decisions together. The limit surfaced when node coordination
 * wanted both the instance-config table and the cache-state table under [TOPIC.instance]. Locks on separate
 * tables are independent; taking the wrong one still *works*, it merely serializes unrelated transactions,
 * which is why the choice is explicit rather than inferred.
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
    /** The tables designated as transaction-lock tables; empty when the topic has no transactions. */
    val tranTables: List<KdrTable> = tables.filter { it.isTransactional }

    /** Lock queries per transactional table, keyed by table name. Empty until [init] runs. */
    private var trans: Map<String, SqlTopicTran> = emptyMap()

    /**
     * Creates (or updates) all the topic's tables and builds the four lock queries for each transactional
     * one. Must be called with a [sqlCxt] bound to this topic.
     */
    fun init(sqlCxt: SqlCxt) {
        val cxt = sqlCxt.cxt
        sqlDb.withSession(cxt) {
            for (table in tables) {
                SqlTableUtil.checkCreateTable(sqlCxt, table)
            }
        }
        // Query building only reads the (now-registered) column aliases; no live connection is needed.
        trans = tranTables.associate { t ->
            t.tableName to SqlTopicTran(
                table = t,
                insertLock = SqlTopicUtil.mkTableInsertStmt(sqlCxt, t),
                queryLock = SqlTopicUtil.mkTableSelectStmt(sqlCxt, t),
                updateLock = SqlTopicUtil.mkTableUpdateStmt(sqlCxt, t),
                takeLock = SqlTopicUtil.mkTableTranLockStmt(sqlCxt, t),
            )
        }
    }

    /** The lock queries for [tableName], or null when this topic has no such transactional table. */
    fun tranOrNull(tableName: String): SqlTopicTran? = trans[tableName]

    /**
     * The lock queries a transaction should use.
     *
     * [tableName] names the lock table; null means "the topic's only one", which keeps every single-table
     * topic reading exactly as it did. **Ambiguity is refused rather than guessed**: on a topic with several,
     * a transaction that names none would otherwise silently lock whichever table happened to be first, and
     * the symptom of a wrong-but-valid lock is unrelated work serializing — slow, correct, and invisible.
     * [tranName] appears only in the error, which is the whole reason it is passed.
     */
    fun tranFor(tableName: String?, tranName: String): SqlTopicTran {
        if (tableName != null) {
            return trans[tableName] ?: throw KdrException(
                "Topic $name has no transactional table '$tableName' for transaction $tranName" +
                    (if (trans.isEmpty()) "." else "; it has ${trans.keys.sorted()}."),
            )
        }
        if (trans.isEmpty()) {
            throw KdrException("Topic $name has no transactional table for transaction $tranName.")
        }
        if (trans.size > 1) {
            throw KdrException(
                "Topic $name has ${trans.size} transactional tables (${trans.keys.sorted()}), so transaction " +
                    "$tranName must name the one it locks.",
            )
        }
        return trans.values.first()
    }

    companion object {
        /** Resolves a topic through the [SqlTopicService] (which throws if absent), or null if the topic is. */
        fun get(cxt: KdrCxt, topic: String): SqlTopic? = SqlTopicService.get(cxt).getOrCreateTopic(cxt, topic)
    }
}

/**
 * The four prepared statements driving the standard topic transaction for one lock table (issue #435).
 *
 * Grouped per table rather than held as four fields on the topic, so that adding a second transactional table
 * cannot leave one of them pointing at the other's — the failure that shape of code invites, and one that
 * would read as a working transaction against the wrong row.
 */
class SqlTopicTran(
    /** The lock table these statements address. */
    val table: KdrTable,
    /** Inserts the initial lock row (done outside a transaction, on retry, after a lock attempt fails). */
    val insertLock: SqlStatement,
    /** Reads the lock row back immediately after the lock is taken. */
    val queryLock: SqlStatement,
    /** Writes the lock row back at the end of a transaction that changed state. */
    val updateLock: SqlStatement,
    /** Takes the lock by updating [PF.touchedAt] on the lock row. */
    val takeLock: SqlStatement,
)
