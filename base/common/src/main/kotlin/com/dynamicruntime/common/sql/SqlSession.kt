package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

/**
 * A single pooled database connection plus the cache of prepared statements bound to it. Assigned to a
 * [KdrCxt] for the duration of a [SqlDatabase.withSession] block (stashed on [KdrCxt.session] under
 * [sessionKey]) and returned to the pool afterward.
 */
@Suppress("SqlSourceToSinkFlow")
class SqlSession(val sqlDb: SqlDatabase) {
    /** The live connection once one has been assigned; null when the session is idle or was invalidated. */
    var conn: Connection? = null

    @Volatile
    var lastAccess: Long = 0

    @Volatile
    var isBeingUsed: Boolean = false

    var inTran: Boolean = false

    val preparedStatements: MutableMap<String, SqlBoundStatement> = HashMap()

    /**
     * Called when a connection issue occurs as the session is being released (e.g., a query ran too long):
     * closes and drops the connection so a fresh one is created on next use.
     */
    fun setInvalid() {
        synchronized(this) {
            val c = conn
            if (c != null) {
                try {
                    c.close()
                } catch (_: Throwable) {
                }
            }
            conn = null
        }
    }

    fun getConnection(): Connection? = conn

    /** Ensures a live connection exists (creating one if needed) and returns it, resetting the statement cache. */
    fun getSessionStartConnection(): Connection {
        lastAccess = System.currentTimeMillis()
        synchronized(this) {
            var c = conn
            if (c == null) {
                for (bound in preparedStatements.values) {
                    try {
                        bound.stmt.close()
                    } catch (_: Exception) {
                    }
                }
                preparedStatements.clear()
                c = sqlDb.createConnection()
                conn = c
                // Any transaction-isolation or connection settings would go here.
            }
            return c
        }
    }

    /** Returns the prepared statement for [sqlStmt] on this session, preparing and caching it on first use. */
    fun checkAndGetStatement(sqlStmt: SqlStatement): SqlBoundStatement {
        synchronized(this) {
            val c = conn
                ?: throw KdrException(
                    "Getting bound statement ${sqlStmt.sessionKey} when no valid connection has been " +
                        "assigned to session.",
                )
            preparedStatements[sqlStmt.sessionKey]?.let { return it }
            try {
                val prepStmt = if (sqlStmt.returnGeneratedKeys) {
                    c.prepareStatement(sqlStmt.sql, Statement.RETURN_GENERATED_KEYS)
                } else {
                    c.prepareStatement(sqlStmt.sql)
                }
                prepStmt.queryTimeout = sqlDb.queryTimeout
                val aliases = sqlDb.getAliases(sqlStmt.topic)
                val newBound = SqlBoundStatement(sqlStmt, aliases, c, prepStmt)
                preparedStatements[sqlStmt.sessionKey] = newBound
                return newBound
            } catch (e: SQLException) {
                throw KdrException(
                    "Unable to prepare statement ${sqlStmt.name} with sql ${sqlStmt.sql}.",
                    e, EXC.internalError, SRC.database, ACT.io,
                )
            }
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Key under which the active session is stashed on [KdrCxt.session]. */
        const val sessionKey = "SqlSession"

        /** The active session on [cxt], or null if none is assigned. */
        fun get(cxt: KdrCxt): SqlSession? = cxt.session[sessionKey] as? SqlSession
    }
}
