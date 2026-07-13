package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.jsonArray
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.splitComma
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptBool
import com.dynamicruntime.common.util.toOptDouble
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.Instant

/**
 * Maps between column [StoreType]s and the database: deriving a store type from a column's JSON schema,
 * choosing the SQL column type, binding parameters, and decoding result values. Rewritten from the prior-art
 * `SqlTypeUtil`, driven by [KdrColumn]/[StoreType] and kd2's `Instant`-based dates rather than dn's
 * `DnField`/`coreType` and `java.util.Date`.
 */
object SqlTypeUtil {
    /** Whether a column's JSON schema is a list (`type: array`). */
    fun isListSchema(schema: Map<String, Any?>): Boolean = schema[SCH.type] == SCT.array

    /**
     * The [StoreType] for a column's JSON schema. For an array the element schema's type is used (a list is
     * stored as an encoded string of its elements). A string with a date/date-time `format` stores as a date.
     */
    fun storeTypeForSchema(schema: Map<String, Any?>): StoreType {
        return when (schema[SCH.type]) {
            SCT.integer -> StoreType.integer
            SCT.number -> StoreType.float
            SCT.boolean -> StoreType.boolean
            SCT.kObject -> StoreType.map
            SCT.array -> {
                val items = schema[SCH.items] as? Map<*, *>
                @Suppress("UNCHECKED_CAST")
                if (items != null) storeTypeForSchema(items as Map<String, Any?>) else StoreType.string
            }
            else -> {
                val fmt = schema[SCH.format]
                if (fmt == SFMT.date || fmt == SFMT.dateTime) StoreType.date else StoreType.string
            }
        }
    }

    /** The SQL column type for [col] in the database described by [sqlCxt]'s options (H2/Postgres bias). */
    fun toDbType(sqlCxt: SqlCxt, col: KdrColumn): String {
        if (col.isList) {
            return "varchar" // Lists are encoded as strings.
        }
        val options = sqlCxt.sqlDb.options
        return when (col.storeType) {
            // H2 and Postgres allow an unbounded varchar with no performance penalty.
            StoreType.string, StoreType.map -> "varchar"
            StoreType.integer ->
                if (col.autoIncrement) {
                    if (options.hasSerialType) "bigserial" else "bigint auto_increment"
                } else {
                    "bigint"
                }
            StoreType.float -> "float"
            StoreType.date -> if (options.useTimezoneWithTz) "timestamp with time zone" else "timestamp"
            StoreType.boolean -> "boolean"
            StoreType.binary -> "blob"
        }
    }

    /** Converts and binds a parameter value to a prepared statement position. */
    fun setStmtParameter(
        cxt: KdrCxt,
        index: Int,
        stmtName: String,
        pStmt: PreparedStatement,
        col: KdrColumn,
        obj: Any?,
    ) {
        try {
            if (col.isList) {
                pStmt.setString(index, encodeList(col, obj))
                return
            }
            when (col.storeType) {
                StoreType.string -> pStmt.setString(index, obj.toOptStr())
                StoreType.map -> pStmt.setString(index, encodeMap(obj))
                StoreType.boolean -> {
                    val b = toBool(obj)
                    if (b != null) pStmt.setBoolean(index, b) else pStmt.setNull(index, Types.BOOLEAN)
                }
                StoreType.date -> {
                    val d = obj.toDbInstant()
                    pStmt.setTimestamp(index, d?.let { Timestamp(it.toEpochMilliseconds()) })
                }
                StoreType.float -> {
                    val db = obj.toOptDouble()
                    if (db != null) pStmt.setFloat(index, db.toFloat()) else pStmt.setNull(index, Types.FLOAT)
                }
                StoreType.integer -> {
                    val l = obj.toOptLong()
                    if (l != null) pStmt.setLong(index, l) else pStmt.setNull(index, Types.BIGINT)
                }
                StoreType.binary ->
                    throw KdrException("Unsupported type ${col.storeType} for storing into database.")
            }
        } catch (e: Exception) {
            throw SqlStmtUtil.mkException(
                "Could not set parameter ${obj.fmt()} with value ${col.name} for statement $stmtName.",
                e,
            )
        }
    }

    /** Decodes a database value into the code-side value, using the column's [StoreType]. */
    fun convertDbObject(cxt: KdrCxt, col: KdrColumn, obj: Any?, insideList: Boolean = false): Any? {
        if (obj == null) {
            return null
        }
        // A list column comes back as an encoded string that must be parsed.
        if (col.isList && !insideList) {
            val s = obj.toOptStr()
            if (s.isNullOrBlank()) {
                return null
            }
            return if (col.listElementsCanHaveCommas()) {
                try {
                    s.jsonArray()
                } catch (e: KdrException) {
                    LogSql.error(cxt, "Suppressing failed conversion of $s into a list.", e)
                    null
                }
            } else {
                s.splitComma().map { item ->
                    if (item.isEmpty()) null else convertDbObject(cxt, col, item, insideList = true)
                }
            }
        }

        return when (col.storeType) {
            StoreType.boolean -> toBool(obj)
            StoreType.integer -> runCatching { obj.toOptLong() }.getOrNull()
            StoreType.float -> runCatching { obj.toOptDouble() }.getOrNull()
            StoreType.date -> runCatching { obj.toDbInstant() }.getOrNull()
            StoreType.map -> {
                val s = obj.toString().trim()
                if (s.startsWith("{")) {
                    try {
                        s.jsonMap()
                    } catch (e: KdrException) {
                        LogSql.error(cxt, "Suppressing failed conversion of $s into a map.", e)
                        null
                    }
                } else {
                    null
                }
            }
            else -> obj.toString().trim().ifEmpty { null }
        }
    }

    // --- list / map / boolean helpers ---------------------------------------

    private fun encodeList(col: KdrColumn, obj: Any?): String? = when (obj) {
        null -> null
        is List<*> ->
            if (col.listElementsCanHaveCommas()) {
                obj.toJsonStr()
            } else {
                obj.joinToString(",") { it?.fmt() ?: "" }
            }
        is CharSequence -> obj.toString()
        else -> throw KdrException("Supplied object was not a list.")
    }

    private fun encodeMap(obj: Any?): String? = when (obj) {
        null -> null
        is CharSequence -> obj.toString()
        is Map<*, *> -> obj.toJsonStr()
        else -> throw KdrException("Supplied object is not a Map.")
    }

    private fun toBool(obj: Any?): Boolean? = when (obj) {
        null -> null
        is Boolean -> obj
        else -> obj.toString().toOptBool()
    }

    /**
     * Coerces a value to an [Instant] for the database, additionally accepting the `java.util.Date` /
     * `java.sql.Timestamp` that JDBC hands back on reads. The shared [toOptInstant] is kept KMP-safe and
     * deliberately does not know these JVM types, so this JDBC-adapter-local helper layers them on top; every
     * other (`Instant` / `Number` / `String`) case is delegated unchanged.
     */
    private fun Any?.toDbInstant(): Instant? =
        if (this is java.util.Date) Instant.fromEpochMilliseconds(this.time) else toOptInstant()
}
