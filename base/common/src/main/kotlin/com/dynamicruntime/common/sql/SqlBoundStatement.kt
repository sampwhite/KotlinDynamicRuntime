package com.dynamicruntime.common.sql

import java.sql.Connection
import java.sql.PreparedStatement

/**
 * A [SqlStatement] bound to a live connection as a JDBC [PreparedStatement], together with the column
 * [aliases] in force. It does not hold the per-execution parameter values; those are supplied at execution
 * time.
 */
class SqlBoundStatement(
    val sqlStmt: SqlStatement,
    val aliases: SqlColumnAliases,
    val conn: Connection,
    val stmt: PreparedStatement,
)
