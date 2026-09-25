package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr

/**
 * Which revisions of a config are **current** (issue #875): the ones a read can still want, flagged in [GC.isCurrent]
 * so the reads that start from nothing -- the boot load, the config cache's first load, a client's listing -- can
 * skip the rest. Every publish leaves a revision behind for good, and only two of a config's revisions ever
 * matter: its **latest** and its **latest published** ([latestRevisionRow], [latestPublishedRow]). Anything older
 * than the latest published is history.
 *
 * Named *current* rather than *active* because an active revision already means something else here: the one a
 * config id with no revision suffix resolves to in a given context, a preview versus production.
 *
 * ### The flag narrows what is read; it never decides which revision a client runs
 *
 * The two row rules stay the authority, applied at read time as before; the flag only decides which rows are read
 * at all. So its errors are lopsided: a row wrongly left current costs one extra row read, while a row wrongly
 * marked historical hides a real revision. The flag must therefore always cover at least the rows the rules could
 * pick, which is why every change that could affect it recomputes it ([refresh]) in the same transaction.
 *
 * ### What changes it
 *
 * A new revision is written current. Only a **publish** demotes one -- the published head it supersedes -- and only
 * a delete (none exists yet) could promote one back. A demotion leaves the row's `updatedAt` alone: a published
 * revision is otherwise immutable, and `updatedAt` feeds the sync marker. So the config cache's incremental refresh
 * never re-sends a demoted row, and the cache keeps it until the node restarts -- harmless, since the read-time
 * rules ignore it. A **promotion** advances `updatedAt`, which it must: the row changes what the client runs, and a
 * cache that skipped it at load would otherwise never hear of it.
 */
object ConfigCurrentRevisions {

    /**
     * The ids of the current revisions among [rows] -- every revision of one config, enabled or not: its latest
     * enabled revision and its latest enabled published one, which may be the same row.
     */
    fun currentIds(rows: List<Map<String, Any?>>): Set<String> {
        val enabled = rows.filter { it[PF.enabled] == true }
        return listOfNotNull(latestRevisionRow(enabled), latestPublishedRow(enabled))
            .mapNotNull { it[GC.gedraId].toOptStr() }.toSet()
    }

    /**
     * Sets [GC.isCurrent] on every revision of the config [configId] names (a revision class, `gc.cd.acme.main`) to
     * what [currentIds] says, and returns how many rows changed. Reads every revision, current or not, since a
     * promotion is exactly a historical row becoming current. Run under the client's config lock by a change that
     * could move the current set, and by the boot's backfill of rows written before the flag existed.
     */
    fun refresh(cxt: KdrCxt, sqlCxt: SqlCxt, table: KdrTable, configId: String): Int {
        val rows = sqlCxt.sqlDb.queryStatement(cxt, classRowsQuery(sqlCxt, table), mapOf(GC.configId to configId))
        val current = currentIds(rows)
        var changed = 0
        for (row in rows) {
            val gedraId = row[GC.gedraId].toOptStr() ?: continue
            val should = gedraId in current
            if (row[GC.isCurrent] == should) continue
            // A demotion keeps updatedAt (see the object note), as does a first flag (the backfill); a promotion --
            // a historical row made current again -- advances it, so the cache hears about a row it skipped.
            val bind = mutableMapOf<String, Any?>(GC.gedraId to gedraId, GC.isCurrent to should)
            val stmt = if (should && row[GC.isCurrent] == false) {
                bind[PF.updatedAt] = SqlTopicUtil.nextUpdatedAt(cxt, row[PF.updatedAt].toOptInstant())
                promoteStmt(sqlCxt, table)
            } else {
                flagStmt(sqlCxt, table)
            }
            sqlCxt.sqlDb.executeStatement(cxt, stmt, bind)
            changed++
        }
        return changed
    }

    /**
     * Flags every row written before [GC.isCurrent] existed (issue #875), so a database from before needs no reset:
     * table setup adds the column, nullable, and this fills it, one config at a time. A no-op once every row has a
     * flag. Run by the boot load before it reads. Not under a config lock -- it runs before any write this node
     * serves -- and safe beside another node's write, since [refresh] can err only toward current, which the reads
     * tolerate. Returns how many configs it flagged.
     */
    fun backfill(cxt: KdrCxt, sqlCxt: SqlCxt, table: KdrTable): Int {
        val unflagged = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraConfigUnflagged", table.columns,
            "select c:${GC.configId} from t:${table.tableName} where c:${GC.isCurrent} is null",
        )
        var configIds: Set<String> = emptySet()
        sqlCxt.sqlDb.withSession(cxt) {
            configIds = sqlCxt.sqlDb.queryStatement(cxt, unflagged, emptyMap())
                .mapNotNull { it[GC.configId].toOptStr() }.toSet()
            configIds.forEach { refresh(cxt, sqlCxt, table, it) }
        }
        return configIds.size
    }

    private fun classRowsQuery(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "qGedraConfigClassRows", table.columns,
        "select * from t:${table.tableName} where c:${GC.configId} = :${GC.configId}",
    )

    private fun flagStmt(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "uGedraConfigCurrent", table.columns,
        "update t:${table.tableName} set c:${GC.isCurrent} = :${GC.isCurrent} where c:${GC.gedraId} = :${GC.gedraId}",
    )

    private fun promoteStmt(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "uGedraConfigPromote", table.columns,
        "update t:${table.tableName} set c:${GC.isCurrent} = :${GC.isCurrent}, c:${PF.updatedAt} = :${PF.updatedAt} " +
            "where c:${GC.gedraId} = :${GC.gedraId}",
    )
}
