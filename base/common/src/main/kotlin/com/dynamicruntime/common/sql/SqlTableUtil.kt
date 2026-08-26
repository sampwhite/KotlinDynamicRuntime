package com.dynamicruntime.common.sql

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.mkUniqueShorterStr
import java.sql.DatabaseMetaData
import java.sql.SQLException

/**
 * Creates and updates database tables from a [KdrTable] definition, adding any missing columns and indexes.
 * Rewritten from the prior-art `SqlTableUtil`, driven by [KdrTable]/[KdrColumn] with the shard dimension
 * dropped. All calls must run inside a [SqlDatabase.withSession] block.
 */
object SqlTableUtil {
    /**
     * Ensures [tableDef] exists (creating or updating it). Returns false if this process has already
     * created/verified the table (so callers can make row provisioning idempotent), true if it acted now.
     */
    fun checkCreateTable(sqlCxt: SqlCxt, tableDef: KdrTable): Boolean {
        if (sqlCxt.sqlDb.hasCreatedTable(tableDef.tableName)) {
            return false
        }
        createTable(sqlCxt, tableDef)
        return true
    }

    /** Creates the table or adds any missing columns/indexes if it already exists. */
    fun createTable(sqlCxt: SqlCxt, tableDef: KdrTable) {
        val cxt = sqlCxt.cxt
        val sqlDb = sqlCxt.sqlDb
        val dbTableName = sqlDb.mkSqlTableName(tableDef.tableName)

        // Register column aliases so subsequent field <-> column translation works.
        sqlDb.addDefaultAliases(sqlCxt.topic, tableDef.columns)

        val conn = sqlDb.getMustExist(cxt).getConnection()
            ?: throw KdrException("No connection available on database ${sqlDb.dbName}.")
        try {
            val dbMetadata = conn.metaData
            val existing = HashMap<String, DbColumnInfo>()
            dbMetadata.getColumns(null, null, dbTableName, null).use { rs ->
                while (rs.next()) {
                    var name = rs.getString("COLUMN_NAME")
                    if (!sqlDb.options.identifiersCaseSensitive && !sqlDb.options.storesLowerCaseIdentifiersInSchema) {
                        // We store identifiers in lower case when identifiers are not case-sensitive.
                        name = name.lowercase()
                    }
                    // Nullability and the default are read here rather than in a second metadata pass: the
                    // drift check (issue #216) asks about the same rows this loop is already walking, and a
                    // driver that answers `columnNullableUnknown` is read as nullable so an ambiguous answer
                    // produces silence rather than a false alarm.
                    val nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls
                    existing[name] = DbColumnInfo(name, nullable, rs.getString("COLUMN_DEF") != null)
                }
            }

            val aliases = sqlDb.getAliases(sqlCxt.topic)
            // Applied to every table, including one being created from scratch (issue #303). A fresh table has
            // nothing to drift from, so the check finds nothing -- but it *registers*, and that is the point:
            // otherwise a node whose database happened to be new and a node where this was never wired up
            // report identically, and the second is the one worth noticing.
            SqlSchemaDrift.check(sqlCxt, tableDef, dbTableName, existing, aliases)
            if (existing.isEmpty()) {
                // Create the table from scratch.
                val sb = StringBuilder()
                sb.append("CREATE TABLE ").append(dbTableName).append(" (\n")
                var isFirst = true
                for (col in tableDef.columns) {
                    if (!isFirst) sb.append(",\n")
                    sb.append(' ')
                    appendColumnDeclaration(sb, sqlCxt, aliases, col)
                    isFirst = false
                }
                sb.append(",\n")
                val primaryKeyClause = SqlStmtUtil.createColumnList(sqlCxt, tableDef.primaryKey)
                sb.append(" PRIMARY KEY (").append(primaryKeyClause).append(")\n);")
                sqlDb.executeSchemaChangeSql(cxt, sb.toString())
            } else {
                // The drift check above ran *before* the ALTERs below, and that ordering is load-bearing
                // (issue #216): a column added by this very boot is necessarily nullable, since the table has
                // rows, and would otherwise be reported as an overdue backfill on the boot that legitimately
                // created it.
                // Add any missing columns. Different databases handle multi-column adds differently, so we do
                // one ALTER per column for simplicity; columns are added rarely enough that this is fine.
                for (col in tableDef.columns) {
                    val colName = aliases.getColumnName(col.name)
                    if (!existing.containsKey(colName)) {
                        val dbType = SqlTypeUtil.toDbType(sqlCxt, col)
                        sqlDb.executeSchemaChangeSql(
                            cxt,
                            "ALTER TABLE $dbTableName ADD COLUMN $colName $dbType",
                        )
                    }
                }
            }
        } catch (e: SQLException) {
            throw SqlStmtUtil.mkException("Exception during creation or update of table $dbTableName.", e)
        }

        addIndexes(sqlCxt, dbTableName, tableDef.indexes)
        sqlDb.registerHasCreatedSqlTable(dbTableName)
    }

    /** Adds any of [indexes] not already present on the database table. */
    fun addIndexes(sqlCxt: SqlCxt, dbTableName: String, indexes: List<KdrIndex>) {
        val sqlDb = sqlCxt.sqlDb
        val cxt = sqlCxt.cxt
        val aliases = sqlDb.getAliases(sqlCxt.topic)
        val conn = sqlDb.getMustExist(cxt).getConnection()
            ?: throw KdrException("No connection available on database ${sqlDb.dbName}.")
        val existingIndexes = HashSet<String>()
        try {
            val dbMetadata = conn.metaData
            dbMetadata.getIndexInfo(null, null, dbTableName, false, false).use { rs ->
                var curIndexName: String? = null
                val sb = StringBuilder()
                while (rs.next()) {
                    val indexName = rs.getString("INDEX_NAME")
                    var isAddingTo = true
                    if (curIndexName == null || indexName != curIndexName) {
                        if (sb.isNotEmpty()) {
                            existingIndexes.add(sb.toString())
                            sb.setLength(0)
                        }
                        curIndexName = indexName
                        isAddingTo = false
                    }
                    var column = rs.getString("COLUMN_NAME")
                    if (aliases.toLowerCaseColumns) {
                        column = column.lowercase()
                    }
                    if (isAddingTo) sb.append(":")
                    sb.append(column)
                }
                if (sb.isNotEmpty()) {
                    existingIndexes.add(sb.toString())
                }
            }

            for (index in indexes) {
                val colNames = index.fieldNames.map { aliases.getColumnName(it) }
                val key = colNames.joinToString(":")
                if (!existingIndexes.contains(key)) {
                    val indexName = index.name ?: colNames.joinToString("_")
                    // Turn the index name into a global name, capped at 60 chars to stay under identifier limits.
                    val tbIndexName = "idx_${dbTableName}_$indexName"
                    val shortenedName = tbIndexName.mkUniqueShorterStr(60)
                    val uniqueStr = if (index.unique) " UNIQUE" else ""
                    val columnList = SqlStmtUtil.createColumnList(sqlCxt, index.fieldNames)
                    sqlDb.executeSchemaChangeSql(
                        cxt,
                        "CREATE$uniqueStr INDEX $shortenedName ON $dbTableName($columnList)",
                    )
                }
            }
        } catch (e: SQLException) {
            throw SqlStmtUtil.mkException("Exception adding indexes for table $dbTableName.", e)
        }
    }

    fun appendColumnDeclaration(sb: StringBuilder, sqlCxt: SqlCxt, aliases: SqlColumnAliases, col: KdrColumn) {
        val colName = aliases.getColumnName(col.name)
        val dbType = SqlTypeUtil.toDbType(sqlCxt, col)
        val extra = if (col.required) " not null" else ""
        sb.append(colName).append(" ").append(dbType).append(extra)
    }
}
