package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.util.toLowerCaseIdentifier
import com.dynamicruntime.common.util.toOptInstant
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/**
 * Access to a traditional SQL database, including a fixed pool of [SqlSession] connections. Work is normally
 * done inside [withSession] (which assigns a pooled connection to the acting context) or [withTran] (which
 * additionally wraps it in a transaction).
 *
 * Rewritten from the prior-art `SqlDatabase`, with the shard dimension removed (issue #33): table names and
 * connections no longer vary by shard.
 */
class SqlDatabase(
    /** Unique name of the database within a running instance. */
    val dbName: String,
    val driver: Driver,
    val connectionUrl: String,
    val connectionProperties: Properties,
    /** Fields reserved for common query activities (e.g., paging), keyed by field name. */
    val reservedFields: Map<String, KdrColumn>,
    val options: SqlDbOptions,
    maxConnections: Int,
) {
    /** Table names (as stored in the database) this process has already created/verified. */
    val createdTables: MutableSet<String> = HashSet()

    private val topicAliases: MutableMap<String, SqlColumnAliases> = HashMap()

    val connections: ArrayBlockingQueue<SqlSession> = ArrayBlockingQueue(maxConnections, true)

    var isDebug: Boolean = false

    /** Seconds before a query times out. One minute has proven a good, stable value over the years. */
    var queryTimeout: Int = 60

    /** Seconds to wait for a pooled connection. Kept short: a starved pool is usually already failing. */
    var pollWaitTime: Int = 2

    init {
        repeat(maxConnections) { connections.add(SqlSession(this)) }
    }

    fun createConnection(): Connection {
        try {
            return driver.connect(connectionUrl, connectionProperties)
        } catch (e: SQLException) {
            throw KdrException(
                "Could not create a connection to database $dbName.",
                e, EXC.internalError, SRC.database, ACT.connection,
            )
        }
    }

    // --- column aliasing ----------------------------------------------------

    private fun convertUpperToLower(): Boolean =
        !options.storesLowerCaseIdentifiersInSchema && !options.identifiersCaseSensitive

    fun addAliases(topic: String, fieldToColumnNames: Map<String, String>) {
        synchronized(topicAliases) {
            val aliases = topicAliases.getOrPut(topic) {
                SqlColumnAliases(convertUpperToLower(), reservedFields, HashMap(), HashMap())
            }
            aliases.newFieldNameToColNameFeeder.putAll(fieldToColumnNames)
        }
    }

    fun getAliases(topic: String): SqlColumnAliases {
        synchronized(topicAliases) {
            var aliases = topicAliases.getOrPut(topic) {
                SqlColumnAliases(convertUpperToLower(), reservedFields, HashMap(), HashMap())
            }
            if (aliases.newFieldNameToColNameFeeder.isNotEmpty()) {
                aliases = aliases.getUpdated()
                topicAliases[topic] = aliases
            }
            return aliases
        }
    }

    /** Registers default camelCase -> lower_snake_case aliases for a table's columns (case-folding databases). */
    fun addDefaultAliases(topic: String, columns: List<KdrColumn>) {
        if (options.identifiersCaseSensitive) {
            return
        }
        val fldToCol = HashMap<String, String>()
        for (col in columns) {
            fldToCol[col.name] = col.name.toLowerCaseIdentifier()
        }
        addAliases(topic, fldToCol)
    }

    /** The database table name for a logical [tableName], applying identifier case rules for this database. */
    fun mkSqlTableName(tableName: String): String {
        return if (!options.identifiersCaseSensitive) {
            val lower = tableName.toLowerCaseIdentifier()
            if (!options.storesLowerCaseIdentifiersInSchema) lower.uppercase() else lower
        } else {
            tableName.replaceFirstChar { it.uppercaseChar() }
        }
    }

    // --- session & transaction management -----------------------------------

    fun withSession(cxt: KdrCxt, block: () -> Unit) {
        val existing = SqlSession.get(cxt)
        val session: SqlSession
        val assignedIt: Boolean
        if (existing != null) {
            session = existing
            assignedIt = false
        } else {
            val polled = try {
                connections.poll(pollWaitTime.toLong(), TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                throw KdrException(
                    "Interrupted while waiting for SQL connection in database $dbName.",
                    e, EXC.internalError, SRC.database, ACT.interrupted,
                )
            } ?: throw KdrException(
                "Unable to get database connection for database $dbName after waiting $pollWaitTime seconds.",
                null, EXC.internalError, SRC.database, ACT.connection,
            )
            session = polled
            assignedIt = true
            cxt.session[SqlSession.sessionKey] = session
            session.isBeingUsed = true
        }
        try {
            if (assignedIt) {
                // Make sure the connection is initialized (and give it a chance to throw).
                session.getSessionStartConnection()
            }
            block()
        } catch (e: KdrException) {
            if (e.source == SRC.database && (e.activity == ACT.io || e.activity == ACT.connection)) {
                session.setInvalid()
            }
            throw e
        } finally {
            if (assignedIt) {
                cxt.session.remove(SqlSession.sessionKey)
                session.isBeingUsed = false
                connections.offer(session)
            }
        }
    }

    fun withTran(cxt: KdrCxt, block: () -> Unit) {
        val session = SqlSession.get(cxt)
        if (session != null) {
            withTranAndSession(cxt, session, block)
        } else {
            withSession(cxt) { withTranAndSession(cxt, getMustExist(cxt), block) }
        }
    }

    fun withTranAndSession(cxt: KdrCxt, session: SqlSession, block: () -> Unit) {
        if (session.inTran) {
            block()
            return
        }
        // Recorded for the transaction's duration (issue #1266), after the listeners have pinned what they hold for
        // it -- read while the connection is still in auto-commit, so outside the transaction.
        val tran = SqlThreadTrans.OpenTran(this)
        tranOpening(cxt, tran)
        SqlThreadTrans.withOpen(tran) { runTran(session, block) }
    }

    /** Tells the [SqlTranListener]s a transaction on this database is about to open; a listener cannot refuse it. */
    private fun tranOpening(cxt: KdrCxt, tran: SqlThreadTrans.OpenTran) {
        val listeners = (cxt.instanceConfig.get(SqlTopicService.serviceName) as? SqlTopicService)?.tranListeners ?: return
        for (listener in listeners) {
            try {
                listener.onTranOpening(cxt, tran)
            } catch (e: Exception) {
                LogSql.error(cxt, "Transaction listener failed on database $dbName; the transaction goes ahead.", e)
            }
        }
    }

    private fun runTran(session: SqlSession, block: () -> Unit) {
        val conn = session.conn
            ?: throw KdrException("No connection available for transaction on database $dbName.")
        var committedIt = false
        try {
            conn.autoCommit = false
            session.inTran = true
            block()
            conn.commit()
            // Committed from here on, whatever restoring auto-commit does next: the writes must be announced.
            committedIt = true
            conn.autoCommit = true
        } catch (e: SQLException) {
            throw SqlStmtUtil.mkException("Could not execute transaction.", e)
        } finally {
            session.inTran = false
            // The transaction's writes are announced now that they are committed (issue #1256), and forgotten when
            // they were not: a write rolled back changed nothing anyone needs to hear about.
            val pending = session.pendingWrites.values.toList()
            session.pendingWrites.clear()
            if (committedIt) {
                for (write in pending) announceWrite(write.cxt, listOf(write.tableName), write.changedSince)
            }
            if (!committedIt) {
                // Auto-commit may be off still if the commit itself was what failed; turning it on below is safe
                // either way, and a rollback after a commit that succeeded is a no-op.
                // Roll back **before** restoring auto-commit: under JDBC, switching auto-commit on while a
                // transaction is open commits it -- so the old order (auto-commit first) committed the partial work
                // of every transaction that threw, and nothing was ever rolled back (found by issue #843, whose
                // refused writes stayed stored).
                try {
                    conn.rollback()
                } catch (_: Exception) {
                }
                try {
                    conn.autoCommit = true
                } catch (_: Exception) {
                }
            }
        }
    }

    // --- raw schema-change execution ----------------------------------------

    // The SQL is raw (a schema-change statement built by our own table code), which trips IntelliJ's
    // tainted-string inspection. It is safe because it is never end-user input, and the guard below ensures
    // only the system user can reach it.
    @Suppress("SqlSourceToSinkFlow")
    fun executeSchemaChangeSql(cxt: KdrCxt, sql: String) {
        // Schema changes (DDL) run as raw SQL, so they must never execute on behalf of an end user: only
        // system-level startup code (acting as the system user) creates or alters tables.
        if (cxt.userProfile.userId != CL.systemUserId.toLong()) {
            throw KdrException(
                "Schema-change SQL may only be executed by the system user, but the acting user is " +
                    "${cxt.userProfile.userId}.",
                null, EXC.notAuthorized, SRC.database, ACT.code,
            )
        }
        try {
            getStatement(cxt).use { stmt ->
                LogSql.debug(cxt) { "SQL Schema change: $sql" }
                stmt.execute(sql)
            }
        } catch (e: SQLException) {
            throw SqlStmtUtil.mkException("Failed to change schema on $dbName with $sql.", e)
        }
    }

    /** A JDBC statement for raw queries, with the standard query timeout applied. */
    fun getStatement(cxt: KdrCxt): Statement {
        val conn = getMustExist(cxt).getConnection()
            ?: throw KdrException("No connection available on database $dbName.")
        try {
            val stmt = conn.createStatement()
            stmt.queryTimeout = queryTimeout
            return stmt
        } catch (e: SQLException) {
            throw KdrException(
                "Cannot get connection to database $dbName.",
                e, EXC.internalError, SRC.database, ACT.connection,
            )
        }
    }

    // --- prepared-statement execution ---------------------------------------

    /** Executes a simple insert or update, returning the affected row count. */
    fun executeStatement(cxt: KdrCxt, stmt: SqlStatement, data: Map<String, Any?>): Int {
        val boundStmt = getMustExist(cxt).checkAndGetStatement(stmt)
        val pStmt = getAndBindPreparedStatement(cxt, boundStmt, data)
        try {
            val result = pStmt.executeUpdate()
            publishWrite(cxt, stmt, result, data)
            return result
        } catch (e: SQLException) {
            throw SqlStmtUtil.mkException("Could not execute query ${stmt.name}.", e)
        }
    }

    /**
     * Publishes a completed write to the registered [SqlWriteListener]s, with the tables the statement
     * touched.
     *
     * **This is here, at the one place every write passes through, on purpose.** The alternative is for each
     * service to announce its own writes, which puts the correctness of every listener in the hands of
     * whoever next adds a write path -- and a forgotten announcement fails *quietly*. A statement already
     * knows its tables ([SqlStatement.tableNames], captured from its `t:` markers), so nothing has to be
     * remembered.
     *
     * What it deliberately does **not** do is name a listener. The data layer publishes; who subscribes (the
     * table caches, today) is not its concern -- see [SqlWriteListener] for why that is worth an interface.
     *
     * Only for writes: reads go through [queryStatement], and a statement that matched no row
     * ([rowsAffected] of zero) changed nothing.
     *
     * **Announced once committed** (issue #1256). A statement outside a transaction is committed by running, so it
     * is announced straight away ([announceWrite]); one inside a transaction is held on the session and announced
     * when the transaction commits, or dropped if it rolls back. Announcing at the statement let a cache on another
     * thread refresh inside the writer's transaction, miss the uncommitted row, and count itself current.
     */
    @KdrPrivate
    fun publishWrite(cxt: KdrCxt, stmt: SqlStatement, rowsAffected: Int, data: Map<String, Any?> = emptyMap()) {
        if (rowsAffected <= 0 || stmt.tableNames.isEmpty()) {
            return
        }
        // The row's own stamp where the statement set one -- what a cache's incremental query compares against --
        // or the moment the statement ran.
        val now = cxt.instanceNow()
        val changedSince = data[PF.updatedAt].toOptInstant()?.let { if (it < now) it else now } ?: now
        val session = SqlSession.get(cxt)
        if (session != null && session.inTran) {
            session.notePendingWrite(cxt, stmt.tableNames, changedSince)
        } else {
            announceWrite(cxt, stmt.tableNames, changedSince)
        }
    }

    /**
     * Tells the [SqlWriteListener]s about a committed write (issue #1256): straight after a statement outside a
     * transaction, or for each of a transaction's writes once it commits.
     *
     * **A listener cannot fail the write.** It has already happened, so a listener exception here would make a
     * write that *succeeded* look failed to the caller -- inviting a retry that double-writes. The listener
     * contract says implementations must not throw; this is where that contract is enforced rather than hoped for.
     */
    @KdrPrivate
    fun announceWrite(cxt: KdrCxt, tableNames: List<String>, changedSince: Instant) {
        // Read optionally, not through the throwing get(): write-listener notification is best-effort, and the
        // data layer runs below (and in tests without) the topic service -- a bare SqlDatabase must still write.
        val listeners = (cxt.instanceConfig.get(SqlTopicService.serviceName) as? SqlTopicService)?.writeListeners ?: return
        for (listener in listeners) {
            try {
                listener.onWrite(cxt, tableNames, changedSince)
            } catch (e: Exception) {
                LogSql.error(cxt, "Write listener failed for tables $tableNames; the write itself succeeded.", e)
            }
        }
    }

    /** Executes an insert and reads back generated counter (auto-increment) values into [counterValue]. */
    fun executeStatementGetCounterBack(
        cxt: KdrCxt,
        stmt: SqlStatement,
        data: Map<String, Any?>,
        counterValue: LongArray?,
    ): Int {
        val boundStmt = getMustExist(cxt).checkAndGetStatement(stmt)
        val pStmt = getAndBindPreparedStatement(cxt, boundStmt, data)
        try {
            val result = pStmt.executeUpdate()
            publishWrite(cxt, stmt, result, data)
            if (result > 0 && counterValue != null) {
                pStmt.generatedKeys.use { generatedKeys ->
                    if (generatedKeys.next()) {
                        val colCount = generatedKeys.metaData.columnCount
                        var i = 0
                        while (i < colCount && i < counterValue.size) {
                            counterValue[i] = generatedKeys.getLong(i + 1)
                            i++
                        }
                    }
                }
            }
            return result
        } catch (e: SQLException) {
            throw SqlStmtUtil.mkException("Could not execute query ${stmt.name}.", e)
        }
    }

    /** Queries for rows, decoding each into a field-name -> value map (column order preserved). */
    fun queryStatement(cxt: KdrCxt, stmt: SqlStatement, data: Map<String, Any?>): List<Map<String, Any?>> {
        val boundStmt = getMustExist(cxt).checkAndGetStatement(stmt)
        val aliases = boundStmt.aliases
        val pStmt = getAndBindPreparedStatement(cxt, boundStmt, data)
        try {
            pStmt.executeQuery().use { rs ->
                val retVal = mutableListOf<Map<String, Any?>>()
                val md = rs.metaData
                val fields = ArrayList<KdrColumn>()
                for (i in 0 until md.columnCount) {
                    val colName = md.getColumnName(i + 1)
                    val fldName = aliases.getFieldName(colName)
                    fields.add(SqlStmtUtil.getColumn(stmt, boundStmt.aliases, fldName))
                }
                var count = 0
                while (rs.next() && count++ < 100_000) {
                    val row = LinkedHashMap<String, Any?>()
                    for (i in fields.indices) {
                        val fld = fields[i]
                        val obj = SqlTypeUtil.convertDbObject(cxt, fld, rs.getObject(i + 1))
                        if (obj != null) {
                            row[fld.name] = obj
                        }
                    }
                    retVal.add(row)
                }
                return retVal
            }
        } catch (e: SQLException) {
            throw SqlStmtUtil.mkException("Failure querying for result set from statement ${stmt.name}.", e)
        }
    }

    /** Queries and returns only the first row (best for existence tests and unique-index lookups), or null. */
    fun queryOneStatement(cxt: KdrCxt, stmt: SqlStatement, data: Map<String, Any?>): Map<String, Any?>? =
        queryStatement(cxt, stmt, data).firstOrNull()

    /**
     * Queries a single row but returns it only when it is enabled (issue #48): a [PF.enabled] value that is
     * not boolean `true` -- including a null or an absent column -- counts as disabled, and the row is
     * returned as null, i.e., as if it were not there. This is the standard read for application data. Works
     * well outside transactions and against a unique index; inside a transaction the row is locked and read
     * with [queryOneStatement] instead. Ported from the prior-art `queryOneEnabled`.
     */
    fun queryOneEnabled(cxt: KdrCxt, stmt: SqlStatement, data: Map<String, Any?>): Map<String, Any?>? {
        val row = queryOneStatement(cxt, stmt, data) ?: return null
        return if (row[PF.enabled] == true) row else null
    }

    fun getAndBindPreparedStatement(
        cxt: KdrCxt,
        boundStmt: SqlBoundStatement,
        data: Map<String, Any?>,
    ): PreparedStatement {
        val sqlStmt = boundStmt.sqlStmt
        val pStmt = boundStmt.stmt
        // Every statement passes through here on its way to run, which is what lets one word log them all (issue #1334).
        if (cxt.hasDebugDiagnostic(SQLDBG.logSql)) {
            LogSql.debug(cxt) { SQLDBG.statementLine(sqlStmt.name, sqlStmt.sql, sqlStmt.bindFields.associateWith { data[it] }) }
        }
        for ((i, bindParam) in sqlStmt.bindFields.withIndex()) {
            val obj = data[bindParam]
            val fld = SqlStmtUtil.getColumn(sqlStmt, boundStmt.aliases, bindParam)
            SqlTypeUtil.setStmtParameter(cxt, i + 1, sqlStmt.name, pStmt, fld, obj)
        }
        return pStmt
    }

    fun getMustExist(cxt: KdrCxt): SqlSession =
        SqlSession.get(cxt)
            ?: throw KdrException("Database activity can only be executed inside a session on database $dbName.")

    // --- table creation bookkeeping -----------------------------------------

    fun hasCreatedTable(tableName: String): Boolean {
        val dbTableName = mkSqlTableName(tableName)
        synchronized(createdTables) {
            return createdTables.contains(dbTableName)
        }
    }

    fun registerHasCreatedSqlTable(dbTableName: String) {
        synchronized(createdTables) {
            createdTables.add(dbTableName)
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val h2InMemoryUrlPrefix = "jdbc:h2:mem:"

        /**
         * Creates an in-memory H2 database for the given [dbName]. `DB_CLOSE_DELAY=-1` keeps the database
         * alive for the life of the JVM even between pooled connections. Intended for tests and the
         * in-memory application mode; the config-driven builder (file H2 / Postgres) arrives in a later pass.
         */
        fun mkInMemoryH2(dbName: String, maxConnections: Int = 4): SqlDatabase {
            val connStr = "$h2InMemoryUrlPrefix$dbName;DB_CLOSE_DELAY=-1"
            val driver = try {
                DriverManager.getDriver(connStr)
            } catch (e: SQLException) {
                throw KdrException(
                    "Cannot get H2 driver for database $dbName.",
                    e, EXC.notSupported, SRC.database, ACT.connection,
                )
            }
            return SqlDatabase(dbName, driver, connStr, Properties(), emptyMap(), SqlDbOptions(), maxConnections)
        }
    }
}
