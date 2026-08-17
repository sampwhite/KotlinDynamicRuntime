package com.dynamicruntime.common.sql

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import java.sql.SQLTransientException

/**
 * Builds and translates SQL statements. The centerpiece is [prepareSql], which turns a query written with
 * `t:`/`c:`/`:` entity markers into a JDBC-ready parameterized query plus its bind-field list. Rewritten
 * from the prior-art `SqlStmtUtil`, with the shard dimension dropped.
 */
object SqlStmtUtil {
    /** Renders a comma-separated list of column names for the given field declarations. */
    fun createColumnList(sqlCxt: SqlCxt, fieldDeclarations: List<String>): String {
        val aliases = sqlCxt.sqlDb.getAliases(sqlCxt.topic)
        return fieldDeclarations.joinToString(", ") { aliasComposite(aliases, it) }
    }

    /** Aliases the field-name portion of a declaration (which may carry a trailing sort qualifier). */
    fun aliasComposite(aliases: SqlColumnAliases, str: String): String {
        val s = str.trim()
        val index = s.indexOf(' ')
        return if (index < 0) {
            aliases.getColumnName(s)
        } else {
            aliases.getColumnName(s.substring(0, index)) + s.substring(index)
        }
    }

    /** Method looks up a column by field name, falling back to a reserved field or, finally, a simple string column. */
    fun getColumn(sqlStmt: SqlStatement, aliases: SqlColumnAliases, fldName: String): KdrColumn =
        sqlStmt.columns[fldName]
            ?: aliases.reservedFields[fldName]
            ?: simpleStringColumn(fldName)

    /** A minimal string column, used when a query references a field with no known definition. */
    fun simpleStringColumn(name: String): KdrColumn =
        KdrColumn(name, linkedMapOf(SCH.type to SCT.string), StoreType.string, isList = false,
            required = false, autoIncrement = false
        )

    /**
     * Parses a query for convertible elements and turns it into a JDBC prepared statement. Word elements
     * starting with `:` are parameters, with `t:` are table definition names, and with `c:` are field names
     * to map to column names.
     *
     * Example: `select * from t:myTable where c:myField = :myValue` becomes
     * `select * from MY_TABLE where my_field = ?;` with `bindFields = ["myValue"]`.
     */
    fun prepareSql(sqlCxt: SqlCxt, name: String, columns: List<KdrColumn>, query: String): SqlStatement {
        val aliases = sqlCxt.sqlDb.getAliases(sqlCxt.topic)
        val trimmed = query.trim()
        val sb = StringBuilder(trimmed.length + 20)
        val bindFields = mutableListOf<String>()
        // Captured as the `t:` markers are translated, so every statement records what it touches for free --
        // see SqlStatement.tableNames and the cache announcement it drives.
        val tableNames = mutableListOf<String>()

        var inWord = false
        var inQuote = false
        var startWordIndex = -1
        val len = trimmed.length
        var curIndex = 0
        var i = 0
        while (i <= len) {
            val ch = if (i < len) trimmed[i] else '\u0000' // Sentinel for the final word at end of string.
            val allowedWordStart = (ch in 'a'..'z') || (ch in 'A'..'Z') || ch == '_' || ch == ':'
            if (!inWord && !inQuote) {
                if (allowedWordStart) {
                    sb.append(trimmed, curIndex, i)
                    inWord = true
                    startWordIndex = i
                } else if (ch == '\'') {
                    inQuote = true
                }
            } else if (inWord) {
                val isLegalWordChar = allowedWordStart || (ch in '0'..'9')
                if (!isLegalWordChar) {
                    val ch1 = trimmed[startWordIndex]
                    val ch2 = trimmed[startWordIndex + 1]
                    val wordLen = i - startWordIndex
                    if ((ch1 == ':' && wordLen > 1) || (ch2 == ':' && wordLen > 2)) {
                        val word = trimmed.substring(startWordIndex, i)
                        when (ch1) {
                            ':' -> {
                                bindFields.add(word.substring(1))
                                sb.append('?')
                            }
                            'c' -> sb.append(aliases.getColumnName(word.substring(2)))
                            't' -> {
                                val tableName = word.substring(2)
                                if (tableName !in tableNames) {
                                    tableNames.add(tableName)
                                }
                                sb.append(sqlCxt.sqlDb.mkSqlTableName(tableName))
                            }
                            else -> sb.append(word) // A strange expression, likely to fail at execution.
                        }
                    } else {
                        sb.append(trimmed, startWordIndex, i)
                    }
                    inWord = false
                    curIndex = i
                }
            } else { // inQuote
                if (ch == '\'') {
                    if (i < len - 1 && trimmed[i + 1] == '\'') {
                        i++ // A doubled quote; skip over the escape.
                    } else {
                        inQuote = false
                    }
                }
            }
            i++
        }
        sb.append(trimmed, curIndex, len)

        // Semicolon forgiveness: useful for complex query construction where the builder may not know it is
        // producing the final part of a query.
        if (len > 0 && trimmed[len - 1] != ';') {
            sb.append(';')
        }
        val columnsByName = columns.associateBy { it.name }
        return SqlStatement(sqlCxt.topic, name, trimmed, sb.toString(), columnsByName, bindFields, tableNames)
    }

    /** Builds an INSERT query for [columns], skipping any auto-increment column (and flagging it in [hasAutoIncrement]). */
    fun mkInsertQuery(tableName: String, columns: List<KdrColumn>, hasAutoIncrement: BooleanArray?): String {
        val fieldNames = columns.mapNotNull { col ->
            if (col.autoIncrement) {
                if (hasAutoIncrement != null && hasAutoIncrement.isNotEmpty()) {
                    hasAutoIncrement[0] = true
                }
                null
            } else {
                col.name
            }
        }
        return mkInsertQuery2(tableName, fieldNames)
    }

    fun mkInsertQuery2(tableName: String, fieldNames: List<String>): String {
        // Leading fields are prefixed with "c:" (translated to column names); the values use ":" prefix (named params).
        val cols = fieldNames.joinToString(", c:")
        val params = fieldNames.joinToString(", :")
        return "INSERT INTO t:$tableName (c:$cols) VALUES (:$params)"
    }

    fun mkSelectQuery(tableName: String, andFields: List<String>): String =
        "SELECT * FROM t:$tableName" + mkAndClause(andFields, true)

    fun mkUpdateQuery(tableName: String, columns: List<KdrColumn>, andFields: List<String>): String {
        val fieldNames = columns.mapNotNull { col -> if (col.name in andFields) null else col.name }
        return mkUpdateQuery2(tableName, fieldNames, andFields)
    }

    fun mkUpdateQuery2(tableName: String, fieldNames: List<String>, andFields: List<String>): String {
        val sb = StringBuilder()
        sb.append("UPDATE t:").append(tableName).append(" SET ")
        var isFirst = true
        for (fld in fieldNames) {
            if (!isFirst) sb.append(", ")
            isFirst = false
            sb.append("c:").append(fld).append(" = :").append(fld)
        }
        sb.append(mkAndClause(andFields, true))
        return sb.toString()
    }

    fun mkAndClause(andFields: List<String>, isStartOfClause: Boolean): String {
        val sb = StringBuilder()
        if (andFields.isNotEmpty()) {
            sb.append(if (isStartOfClause) " WHERE " else " AND ")
        }
        appendAndStatements(sb, andFields)
        return sb.toString()
    }

    fun appendAndStatements(sb: StringBuilder, andFields: List<String>) {
        var isFirst = true
        for (s in andFields) {
            if (!isFirst) sb.append(" AND ")
            isFirst = false
            sb.append("c:").append(s).append(" = :").append(s)
        }
    }

    /** Wraps a JDBC exception as a database [KdrException], marking transient failures retriable (IO). */
    fun mkException(msg: String, e: Exception): KdrException {
        val activity = if (e is SQLTransientException) ACT.io else ACT.general
        return KdrException(msg, e, EXC.internalError, SRC.database, activity)
    }
}
