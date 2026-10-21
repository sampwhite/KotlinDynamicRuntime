package com.dynamicruntime.common.sql

import com.dynamicruntime.common.app.DBGK
import com.dynamicruntime.common.context.DebugTagDef
import com.dynamicruntime.common.http.request.RequestArgsLog
import com.dynamicruntime.common.logging.KdrLogger

/**
 * Topic logger for the SQL/database subsystem — connection pooling, statement execution, and table
 * creation. Lives beside the code it serves because the `"sql"` topic is owned by this one subsystem (the
 * default placement rule for topic loggers). Rewritten from the prior-art `LogSql`/`AppLogger`.
 */
object LogSql : KdrLogger("sql")

/** The SQL layer's `_debug` word (issue #1334). */
object SQLDBG {
    /**
     * Logs every statement the request runs, at debug on the `sql` topic: logging turned on for one request rather
     * than for the node. On a node logging at info the lines reach no sink of the node's, but a request capturing
     * its log lines (`captureLogs`) keeps them, which is the pairing it is for.
     */
    val logSql = DebugTagDef(
        "logSql",
        "Logs each SQL statement the request runs, with its bound values redacted, at debug. Pair with captureLogs.",
        DBGK.log,
    )

    /**
     * The line [logSql] writes for one statement: its name, its SQL, and its bound values, which go through
     * [RequestArgsLog.format] -- a secret by name redacted (`encodedPassword`, at any depth), an address masked, a long
     * value cut -- since a bound value is often a request's own argument on its way to the table.
     */
    fun statementLine(name: String, sql: String, binds: Map<String, Any?>): String =
        "SQL $name: ${sql.trim()}" + if (binds.isEmpty()) "" else " with ${RequestArgsLog.format(binds)}"
}
