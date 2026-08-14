package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlScopeUtil
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.mkUniqueId

/** The `_debug` tag the gedra listing answers to, and the `_meta` keys it writes under. */
@Suppress("ConstPropertyName")
object GDBG {
    /** `_debug` tag: report the scope the listing ran with. */
    const val explainScope = "explainScope"

    /** `_meta` key the tag writes under. */
    const val scopeExplained = "scopeExplained"

    /** Under [scopeExplained]: the scope, spelled out. */
    const val scope = "scope"

    /** Under [scopeExplained]: the scope's shape, which is what chose the statement. */
    const val shapeKey = "shapeKey"

    /** Under [scopeExplained]: the prepared statement that ran. */
    const val statement = "statement"

    /** Under [scopeExplained]: rows the query matched, before `limit` capped them. */
    const val rowsMatched = "rowsMatched"
}

/**
 * Storage for gedra **data** (issue #310): creating one, reading one, and listing the ones a caller may see.
 *
 * It holds no state of its own -- the identity space it works in belongs to [GedraService], which the config
 * service will share. Registered by the `common` component; found via [get].
 *
 * ### What is not here yet
 *
 * **Updating.** Creation assigns entries, and that is all it does; a patch is its own issue and its own set of
 * problems (locked and process-owned entries, merging, entry primary keys, deletion, and a batch across
 * several documents at once). Nothing here should be extended into an update path by adding a flag to
 * [createGedra] -- the reason those are hard is that they are decisions, not plumbing.
 */
@Suppress("DuplicatedCode")
class GedraDataService : ServiceInitializer {
    override val serviceName: String = GedraDataService.serviceName

    private lateinit var gedraService: GedraService

    override fun checkInit(cxt: KdrCxt) {
        gedraService = GedraService.get(cxt)
            ?: throw KdrException("GedraService required for GedraDataService.")
    }

    private fun gedraDataTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[GDT.gedraData]
        ?: throw KdrException("${GDT.gedraData} table is not registered in the schema store.")

    /**
     * Creates a gedra of [kind] owned by the calling context, carrying [entries], and returns it as stored.
     *
     * The two tiers are written inside one topic transaction: the root row is what the lock is taken on, and
     * the content row is inserted under it. The root never exists beforehand -- the id was minted a line
     * earlier -- so the first lock attempt always fails and the insert-then-retry path in `SqlTranUtil` is the
     * ordinary case here rather than the exception. That is the shape the layer was built for.
     *
     * Entries arrive carrying a `traitId` and their own `data`, already validated against the manufactured
     * union by the endpoint that took them. What is added here is the stored envelope: an id, a source, and
     * the timestamps. A caller supplies none of it, which is what `g-derived` on those fields says.
     */
    fun createGedra(cxt: KdrCxt, kind: GedraDataType, entries: List<Map<String, Any?>>): GedraDataRow {
        // Interned as it is minted, so every later reader of this gedra shares one instance. The cache does
        // not yet hold every extant id, so this buys identity and cheap keys and not existence -- see
        // GedraService.gedraIds.
        val gedraId = gedraService.intern(cxt.mkGedraId(kind, cxt.client, GedraIdContext.ui))
        val now = cxt.instanceNow()
        val stored = entries.map {
            it.asStoredEntry(
                // Long, and deliberately: a per-gedra counter would be shorter and would bring a high-water
                // mark to maintain so that a deleted entry's number is never handed out again. This carries
                // its own creation time and never repeats.
                entryId = cxt.mkUniqueId(),
                // A direct call to an endpoint means a person is accountable for the value.
                source = GSRC.user,
                createdAt = now,
                // The actor rather than the owner (issue #325). They are the same person here -- a "create"
                // makes the caller the owner -- and are not once an administrator edits somebody else's
                // document, which is the case the field exists for.
                createdBy = cxt.userProfile.userId,
            )
        }

        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = gedraDataTable(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val data = mutableMapOf<String, Any?>(
            GD.gedraId to gedraId.fullId,
            GD.gedraKind to kind.name,
            GD.data to mapOf(GD.entries to stored),
        )
        // Ownership and audit come from the context, never from the caller: `client`, `org` and `userId` from
        // the bound owner, `createdBy`/`updatedBy` from the actor, and `enabled` true.
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranCreate, null, mapOf(GD.gedraId to gedraId.fullId)) {
            sqlCxt.sqlDb.executeStatement(cxt, stmt, data)
        }
        // Extracted from the very map that was written, so what the caller is handed back cannot differ from
        // what is stored. The entries' `createdAt` was taken a beat before the row's and is therefore never
        // later than it, which keeps "was this entry added after the gedra?" answerable by comparing them.
        return GedraDataRow.extract(gedraService, data)
    }

    /**
     * The gedra [fullId] names, or null when the caller may not see it -- because it does not exist, because
     * it is disabled, because it is not of [kind], or because it lies outside [scope].
     *
     * **Out of scope reads as absent**, and the caller turns that into a 404. A 403 would confirm that the id
     * belongs to a real gedra in somebody else's client, which is exactly what an id-guessing caller is
     * fishing for. `UserService.queryAdministrableUser` makes the same choice for the same reason.
     *
     * A *malformed* id is different and does fault, as a 400: it is a broken request rather than a thing the
     * caller cannot have. The kind is checked before the query rather than in it, because the id carries the
     * kind -- there is no reason to ask the database about a form-document id that plainly names a user.
     */
    fun queryGedra(cxt: KdrCxt, fullId: String, kind: GedraDataType, scope: ReadScope): GedraDataRow? {
        val gedraId = gedraService.readId(fullId)
        if (gedraId.dataType != kind) {
            return null
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = gedraDataTable(cxt)
        val data = mutableMapOf<String, Any?>(GD.gedraId to gedraId.fullId)
        val conditions = mutableListOf("c:${GD.gedraId} = :${GD.gedraId}")
        // The scope half is composed rather than spelled out, so this and the listing cannot disagree about
        // what a scope means. A dimension the table cannot express throws here rather than widening the answer.
        conditions.addAll(SqlScopeUtil.scopeConditions(scope, table, data))
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraDataById${scope.shapeKey}", table.columns,
            "select * from t:${GDT.gedraData} where ${conditions.joinToString(" and ")}",
        )
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneEnabled(cxt, stmt, data)
        }
        return row?.let { GedraDataRow.extract(gedraService, it) }
    }

    /**
     * Every gedra of [kind] within [scope], newest first, capped at [limit].
     *
     * Scope and the enabled flag are both SQL, so the rows that come back are already the rows the caller may
     * see; [limit] is applied afterward, which makes it a cap on what is returned rather than an exact page.
     * That is the arrangement #310 asked for on the way to holding this data in memory, and it is `listUsers`'
     * arrangement too.
     *
     * The prepared statement is named for the kind **and** the scope's shape, because both change the SQL.
     * Statements are cached by name, so two shapes sharing one name would serve whichever query ran first --
     * and the failure would be a listing quietly scoped to somebody else's width.
     */
    fun listGedras(cxt: KdrCxt, kind: GedraDataType, scope: ReadScope, limit: Int): List<GedraDataRow> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = gedraDataTable(cxt)
        val data = mutableMapOf<String, Any?>(GD.gedraKind to kind.name)
        val conditions = mutableListOf("c:${GD.gedraKind} = :${GD.gedraKind}")
        conditions.addAll(SqlScopeUtil.scopeConditions(scope, table, data))
        // A disabled row is one that is not there. A literal rather than a bind: it is a constant of the query
        // rather than an input to it, and binding it would suggest a caller could choose.
        conditions.add("c:${PF.enabled} = true")
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraData${GU.gedraName(kind)}${scope.shapeKey}", table.columns,
            // Newest first, with the id as the tiebreak so the order is *total*. Two documents created in the
            // same millisecond share a `createdAt`, and without a second key their relative order would be
            // whatever the database felt like -- which is the sort of thing that shows up as a test that
            // usually passes. The id works as one because its base is the project's time-sortable unique id.
            "select * from t:${GDT.gedraData} where ${conditions.joinToString(" and ")} " +
                "order by c:${PF.createdAt} desc, c:${GD.gedraId} desc",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, data)
        }
        explainScope(cxt, scope, stmt.name, rows.size)
        return rows.asSequence()
            .map { GedraDataRow.extract(gedraService, it) }
            .take(limit)
            .toList()
    }

    /**
     * Reports, under `_meta`, which scope the listing actually ran with -- the fact the response cannot show,
     * since a correctly and an incorrectly scoped listing differ only in rows the caller never sees.
     *
     * The same argument as `_debug=explainAccess`: without it, a scoping bug surfaces as a count one lower (or
     * higher) than expected, which is not something anybody notices.
     */
    private fun explainScope(cxt: KdrCxt, scope: ReadScope, statementName: String, rowCount: Int) {
        if (!cxt.hasDebug(GDBG.explainScope)) {
            return
        }
        cxt.request?.responseMeta?.put(
            GDBG.scopeExplained,
            linkedMapOf<String, Any?>(
                GDBG.scope to scope.toString(),
                GDBG.shapeKey to scope.shapeKey,
                GDBG.statement to statementName,
                GDBG.rowsMatched to rowCount,
            ),
        )
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "GedraDataService"

        /** Name of the "create" transaction; it prefixes the generated transaction id. */
        const val tranCreate = "createGedra"

        fun get(cxt: KdrCxt): GedraDataService? = cxt.instanceConfig.get(serviceName) as? GedraDataService

        /** The service, or a fault naming it -- for a handler that cannot proceed without one. */
        fun require(cxt: KdrCxt): GedraDataService = get(cxt)
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
