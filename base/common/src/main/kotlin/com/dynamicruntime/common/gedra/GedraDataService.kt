package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlScopeUtil
import com.dynamicruntime.common.sql.SqlStatement
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

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
 * Storage for gedra **data**: creating one, reading one, listing the ones a caller may see (issue #310),
 * deleting one (#326), and patching several (#337).
 *
 * It holds no state of its own -- the identity space it works in belongs to [GedraService], which the config
 * service will share. Registered by the `common` component; found via [get].
 *
 * ### What is not here yet
 *
 * **Enforcing locked, admin-only and process-only entries.** The patch is supposed to refuse an edit naming
 * one, and cannot: those are directives on the trait wrapper, which does not exist, so there is nowhere for a
 * trait to declare itself locked. Said here rather than left as a silence, because a patch that looks as
 * though it honors them and does not is worse than one that plainly cannot yet.
 *
 * **Dry runs, and all-or-nothing across gedras.** See `gedra-patch.md`.
 *
 * ### Keyed traits (issue #487)
 *
 * A gedra holds at most one entry per trait, **unless** the trait declares a `g-primaryKey` -- then one per
 * distinct value of that key ([checkEntryKeys]). A keyed entry is addressed by `(traitId, data[<keyField>])`,
 * which an edit carries in its own data, so the same rule names an entry to add, merge, replace, or delete.
 */
@Suppress("DuplicatedCode")
class GedraDataService : ServiceInitializer {
    override val serviceName: String = GedraDataService.serviceName

    private lateinit var gedraService: GedraService

    /**
     * The in-memory `GedraData` cache, or null when the table-cache service is absent (see [GedraDataCache]).
     * Every lookup below consults it first and falls back to SQL on a miss, so its absence costs queries and
     * nothing else.
     */
    var dataCache: SqlTableCache<Map<String, Any?>>? = null

    override fun checkInit(cxt: KdrCxt) {
        gedraService = GedraService.get(cxt)
        // Registered during this pass so the cache service's own checkReady -- which runs after every
        // service's checkInit -- performs the initial load at startup rather than in a request.
        dataCache = GedraDataCache.register(cxt)
    }

    /**
     * Serves a by-id lookup from [dataCache], or null when it cannot -- which the caller turns into its SQL
     * query, so the cache only ever saves a round trip and never changes an answer.
     *
     * The scope is applied **per row** by [admitsRow], the way `UserService.queryAdministrableUser` already
     * does it, not by composing a predicate: composing one would be a second implementation of what
     * `SqlScopeUtil` exists to be the only copy of. A row the scope refuses returns null here, and the caller
     * re-asks SQL, which refuses it too -- one wasted query on a denied cross-scope probe, in exchange for the
     * cached path having no way to *widen* an answer. The refusal is tested on the raw row *before* extracting
     * it, so a denied probe does not pay for an extraction it will throw away.
     */
    private fun cachedGedra(cxt: KdrCxt, fullId: String, scope: ReadScope): GedraDataRow? {
        val cache = dataCache ?: return null
        cache.checkRefresh(cxt)
        val row = cache.snapshot.get(cache.idOf(fullId)) ?: return null
        if (!admitsRow(scope, row.value)) return null
        return GedraDataRow.extract(gedraService, row.value)
    }

    /**
     * Whether a stored gedra [row] is admitted by [scope] -- the exact set `SqlScopeUtil.scopeConditions`
     * composes into the SQL `where`, applied to one row in memory. The by-id read and the listing both go
     * through this, so a cache can no more widen a listing than a lookup, and the two cannot come to disagree
     * about what a scope means -- the same guarantee the shared `scopeConditions` gives the SQL side.
     *
     * It reads the protocol columns exactly as [GedraDataRow.extract] does (`client`/`userId` coerced, an
     * empty `org` normalized to null), so testing the raw row here and the extracted row there is the same
     * test -- which is what lets the by-id path move its check ahead of the extraction.
     */
    private fun admitsRow(scope: ReadScope, row: Map<String, Any?>): Boolean {
        if (scope.client != null && row[PF.client].toOptStr() != scope.client) return false
        if (!scope.admitsOrg(row[PF.org].toOptStr()?.ifEmpty { null })) return false
        if (scope.userId != null && row[PF.userId].toOptLong() != scope.userId) return false
        return true
    }

    /**
     * The order [listGedras] returns, made to match its SQL `order by c:createdAt desc, c:gedraId desc`: newest
     * first, with the id breaking a tie so the order is *total* (the id's base is the project's time-sortable
     * unique id). It has to be total, or two gedras created in the same millisecond would page in whatever
     * order each side happened to produce, and the cache would disagree with SQL only sometimes.
     *
     * A null `createdAt` sorts **last**, but only defensively: `SqlTopicUtil.prepDates` stamps `createdAt` on
     * every insert, so a stored gedra never has one, which is also why it does not matter that a database's own
     * null ordering under `desc` (H2 and PostgreSQL differ) is not reproduced here -- the case never arises on
     * real rows, so the two cannot be seen to differ.
     */
    private val gedraListOrder: Comparator<Map<String, Any?>> =
        compareByDescending<Map<String, Any?>> { it[PF.createdAt].toOptInstant() ?: Instant.DISTANT_PAST }
            .thenByDescending { it[GD.gedraId].toOptStr() ?: "" }

    internal fun gedraDataTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[GDT.gedraData]
        ?: throw KdrException("${GDT.gedraData} table is not registered in the schema store.")

    /**
     * The scoped single-gedra update statement every write path here shares: `update GedraData set [setSql]
     * where gedraId = :gedraId and enabled = true and <the scope's own conditions>` -- named
     * `<stmtNamePrefix><shapeKey>`, since the scope's shape changes the SQL and statements are cached by name.
     *
     * One builder rather than a copy per caller because the where clause **is the write-side authorization**:
     * the patch and the delete must always agree on what "a row this caller may write" means, and a scope
     * dimension added here reaches both at once. [bind] receives the scope's bind values; the caller adds its own.
     */
    internal fun mkScopedGedraUpdate(
        sqlCxt: SqlCxt,
        table: KdrTable,
        scope: ReadScope,
        stmtNamePrefix: String,
        setSql: String,
        bind: MutableMap<String, Any?>,
    ): SqlStatement {
        val conditions = mutableListOf(
            "c:${GD.gedraId} = :${GD.gedraId}",
            "c:${PF.enabled} = true",
        )
        conditions.addAll(SqlScopeUtil.scopeConditions(scope, table, bind))
        return SqlStmtUtil.prepareSql(
            sqlCxt, "$stmtNamePrefix${scope.shapeKey}", table.columns,
            "update t:${GDT.gedraData} set $setSql where ${conditions.joinToString(" and ")}",
        )
    }

    /**
     * The order a patch applies kinds in (issue #337): declaration order, which is arbitrary but **fixed**. What
     * matters is that the server chooses it, so a caller cannot reorder the phases by reordering their request.
     *
     * `wfData` used to be hoisted first, because the #381 workflow recorded "has this already been done?" there
     * and had to see a submit before the forms it governed. That machine was retired (issue #533) and nothing
     * now reads a `wfData` gedra during a patch, so the hoist went with it; the replacement design (issue #532)
     * keeps workflow state in a companion table rather than in a gedra's entries.
     */
    private val kindApplyOrder: List<GedraDataType> = GedraDataType.entries

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
    fun createGedra(
        cxt: KdrCxt,
        kind: GedraDataType,
        entries: List<Map<String, Any?>>,
        allowAdditionalTraits: Boolean = false,
        /**
         * The creation workflow that made this gedra, when one did (issue #535): stamped once under
         * [GD.creationWorkflowId], never rewritten. Configuration lineage, not a trait -- see [GedraDataRow].
         */
        creationWorkflowId: com.dynamicruntime.common.gedra.workflow.WfRef? = null,
    ): GedraDataRow {
        // At most one entry per trait -- or per primary-key value, for a keyed trait (issue #487) -- refused
        // before anything is minted (issue #337). This was not checked when create was written, so a caller
        // could store two entries nothing could address, which is how the patch, and the form, address them.
        checkEntryKeys(entries, pkFieldsOf(cxt, kind))
        checkTraitsSupported(cxt, kind, entries.mapNotNull { it[GE.traitId].toOptStr() }, allowAdditionalTraits)
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

        // Against the schema of the client this is being stored **in** (issue #379), which the patch path
        // already did and this one did not -- so a client's narrowing reached an edit and not a creation, and
        // the same payload was kept or refused depending on which call made it.
        //
        // After the envelope is built, not before: what is checked is the entry as it will be stored, and the
        // union requires the fields `asStoredEntry` adds.
        checkStoredEntries(cxt, kind, stored)

        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = gedraDataTable(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        // The stored `data` map: the entries, and -- when a creation workflow made this gedra -- its
        // reference beside them, the one key promoted out of `extra` on read (issue #535).
        val dataMap = LinkedHashMap<String, Any?>()
        creationWorkflowId?.let { dataMap[GD.creationWorkflowId] = it.text }
        dataMap[GD.entries] = stored
        val data = mutableMapOf<String, Any?>(
            GD.gedraId to gedraId.fullId,
            GD.gedraKind to kind.name,
            GD.data to dataMap,
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
        checkPathClient(cxt, gedraId)
        if (gedraId.dataType != kind) {
            return null
        }
        cachedGedra(cxt, gedraId.fullId, scope)?.let { return it }
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
     * Deletes the gedra [fullId] names, returning whether there was one to delete (issue #326).
     *
     * ### A soft delete, and why that is the "delete"
     *
     * The row is marked not [PF.enabled] rather than removed. That is what `enabled` is for -- the SQL layer
     * calls a row whose flag is not `true` one that is *not there*, and every application read already goes
     * through that rule, so nothing has to learn about deletion to honor it. Keeping the row is also what makes
     * the harder cases possible later: a gedra referenced by a workflow, or wanted by an audit, is worse gone
     * than disabled, and a purge that really removes bytes is a different operation with different authority.
     *
     * False is returned for a gedra that is absent, already deleted, of the wrong [kind], or outside [scope] --
     * the same four-into-one that [queryGedra] does, and for the same reason: a caller who may not see
     * something must not be able to learn it exists by trying to delete it.
     *
     * ### Why the existence check comes before the transaction
     *
     * The "write" goes through a topic transaction, because coordinating changes to one gedra is what the root
     * table is *for*, and a delete that reached a file store later would have to. But `executeTopicTran`
     * inserts the lock row when it is missing, so entering it with an id that names nothing would mint a root
     * row for a gedra that never existed -- a delete quietly creating something. Checking first means the
     * transaction is only ever entered for a gedra that is really there.
     *
     * The gap between the check and the "write" is harmless: the update is itself scoped and requires the row to
     * still be enabled, so a second concurrent delete changes no rows and simply reports nothing to do.
     */
    fun deleteGedra(cxt: KdrCxt, fullId: String, kind: GedraDataType, scope: ReadScope): Boolean {
        val row = queryGedra(cxt, fullId, kind, scope) ?: return false

        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = gedraDataTable(cxt)
        val data = mutableMapOf<String, Any?>(
            GD.gedraId to row.gedraId.fullId,
            // The audit half of a delete. `prepForStdExecute` is deliberately not used: it stamps `enabled`
            // true unconditionally, which is right for a "create" that revives a disabled row and exactly wrong
            // here -- the "write" would succeed and leave the gedra live. `UserService.updateUser` defends the
            // same field for the same reason; this states it in the SQL instead, so there is nothing to defend.
            // updatedAt is stamped under the lock below, not here, so it is strictly past the row's real value.
            PF.updatedBy to cxt.userProfile.userId,
        )
        // The shared scoped-update builder carries the enabled = true condition, which is also what makes the
        // returned count mean something here: already-deleted is not deleted again.
        val stmt = mkScopedGedraUpdate(
            sqlCxt, table, scope, "uGedraDataDelete",
            "c:${PF.enabled} = false, c:${PF.updatedAt} = :${PF.updatedAt}, c:${PF.updatedBy} = :${PF.updatedBy}",
            data,
        )
        val selectStmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        var changed = 0
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranDelete, null, mapOf(GD.gedraId to row.gedraId.fullId)) {
            // Stamp updatedAt strictly past the row's *current* value, read here under the lock. A delete that
            // did not advance it would be permanently invisible to the gedra cache: the cache skips a row at or
            // before the version it holds, and a disabled gedra never gets a later write to correct that, so it
            // would stay readable from the cache forever. The `row` read before the transaction cannot be trusted
            // for the bump -- it may have come from the cache, which is exactly what might be behind. A row
            // gone or already disabled here reads as null, and nextUpdatedAt falls back to now, which is
            // harmless: the enabled-only update then matches nothing and the delete reports nothing to do.
            val current = sqlCxt.sqlDb.queryOneEnabled(cxt, selectStmt, mapOf(GD.gedraId to row.gedraId.fullId))
            data[PF.updatedAt] = SqlTopicUtil.nextUpdatedAt(cxt, current?.get(PF.updatedAt).toOptInstant())
            changed = sqlCxt.sqlDb.executeStatement(cxt, stmt, data)
        }
        return changed > 0
    }


    /**
     * Applies a patch across several gedras and reports what became of each edit (issue #337).
     *
     * ### Two phases, and the first one is the whole security story
     *
     * **Admit everything before writing anything.** Every target is resolved, checked against the kind it was
     * grouped under, and read through [queryGedra] with [scope] -- so a caller who may not reach one of them
     * has the *whole* call refused rather than discovering it partway through, with some gedras already
     * changed. It is also why the refusal names the id but never says why: absent, disabled, wrong kind, and
     * out of scope answer alike, so a patch reveals no more than a read would.
     *
     * **Then apply, one topic transaction per target.** Atomicity is per target: each succeeds or fails alone,
     * and the answer says which. That is the arrangement rather than a first cut -- recovery from a partial
     * patch is by **replay**, which is safe because every edit is idempotent by value: a `deleteOrNoOp` of an
     * absent entry is a no-op, an `addOrMerge` refolded over its own result is unchanged, and an
     * `addOrReplace` takes the supplied entry whole (see `applyEdit`). A retry re-applies what already landed
     * and changes nothing but the audit stamps. See `gedra-patch.md`.
     *
     * Kinds are applied in a fixed order the server chooses (`kindApplyOrder`) rather than the request's, so a
     * caller cannot reorder the phases by reordering their targets.
     *
     * The row is read again inside the transaction. The admit-phase read cannot be reused: it happened outside
     * the lock, and a merge has to work from what is stored now rather than from what was stored a moment ago.
     */
    fun patchGedras(
        cxt: KdrCxt,
        targetsByKind: Map<GedraDataType, List<GedraPatchTarget>>,
        scope: ReadScope,
        allowAdditionalTraits: Boolean = false,
    ): List<GedraPatchResult> {
        val ordered = kindApplyOrder.mapNotNull { kind -> targetsByKind[kind]?.let { kind to it } }
        val patchCxt = cxt.mkSubContext("patch", oneClient(cxt, ordered))
        for ((kind, targets) in ordered) {
            for (target in targets) {
                admit(patchCxt, kind, target, scope)
                // Only what this call writes: a delete removes a trait rather than storing one, so it is
                // always allowed -- which is what lets an unsupported entry be cleaned up without the flag.
                checkTraitsSupported(
                    patchCxt,
                    kind,
                    target.edits.filterNot { it.action == GedraEditAction.deleteOrNoOp }.map { it.traitId },
                    allowAdditionalTraits,
                )
            }
        }
        return ordered.flatMap { (kind, targets) -> targets.map { applyToOne(patchCxt, kind, it, scope) } }
    }

    /**
     * Refuses a gedra outside the client the **path** named (issue #387).
     *
     * Only ever fires on a client endpoint: on the shared surface `cxt.clientFromPath` is null and this does
     * nothing, so an `allClients` holder reaches across clients there exactly as before.
     *
     * It is what makes a client endpoint's guarantee structural rather than incidental. Scope already stops
     * most cross-client reach, but scope is about who the caller is; this is about where the request was
     * addressed, and the two stop agreeing precisely for the caller whose scope is wide enough not to be
     * stopped. Refused as bad input rather than as "not found", because the request contradicts itself: the
     * path said one client and the id says another, and either half could be the mistake.
     */
    private fun checkPathClient(cxt: KdrCxt, gedraId: GedraId) {
        val confined = cxt.clientFromPath ?: return
        if (gedraId.client != confined) {
            throw KdrException.mkInput(
                "'$gedraId' belongs to the client '${gedraId.client}', and this endpoint is the one for " +
                    "'$confined'. Use that client's own endpoint, or the shared one.",
            )
        }
    }

    /**
     * The one client every target belongs to, which the whole patch is then bound to (issue #356).
     *
     * **A patch may not span clients.** Each target names its own gedra, so a caller holding `allClients`
     * could otherwise reach into several at once -- and a client's schema is a property of where the data
     * lives, so a patch spanning two would have two different sets of rules running inside one transaction.
     * Refusing is both simpler to reason about and simpler to say: split it into a patch per client.
     *
     * The answer binds a sub context, so everything downstream -- validating against the right variant, and
     * any later client-specific logic -- reads `cxt.client` and is correct without being handed the client.
     * For a caller without `allClients` this changes nothing: their scope confines them to their own client,
     * so the only client they can name is the one they were already bound to.
     *
     * Note what it does **not** do: choosing a client here never grants reach. `admit` still runs the scope
     * check and the existence check against every target, so naming a gedra in somebody else's client answers
     * 404 rather than borrowing that client's rules.
     */
    private fun oneClient(cxt: KdrCxt, ordered: List<Pair<GedraDataType, List<GedraPatchTarget>>>): String {
        ordered.forEach { (_, targets) -> targets.forEach { checkPathClient(cxt, it.gedraId) } }
        val clients = ordered.flatMap { (_, targets) -> targets.map { it.gedraId.client } }.distinct()
        if (clients.size > 1) {
            throw KdrException.mkInput(
                "A patch targets one client at a time, and this one names ${clients.joinToString(", ")}. " +
                    "A client's schema belongs to where the data lives, so a patch spanning two would apply " +
                    "two sets of rules inside one transaction. Send a patch per client.",
            )
        }
        return clients.firstOrNull() ?: cxt.client
    }

    /**
     * Refuses a target the caller may not reach, or that contradicts the kind it was filed under.
     *
     * One scoped query per target, and it stays one even for a caller who is confined by nothing. The scope is
     * a clause on a query that has to happen anyway: a patch must know the gedra **exists** before entering a
     * transaction for it, because `executeTopicTran` inserts the lock row when it is missing and would
     * otherwise mint a root row for a gedra that never was -- the same trap the delete guards.
     *
     * So the obvious saving is not one. Skipping the check for an `allClients` administrator, or reading
     * ownership out of the id's client segment for a client-scoped one, removes a `where` clause and no round
     * trip: the id says who *would* own the row, never whether there is one. What removes the round trip is
     * the table cache, and until it exists this is a database query per target, which is the right cost for a
     * surface where nearly every caller is an ordinary user confined to their own rows.
     */
    private fun admit(cxt: KdrCxt, kind: GedraDataType, target: GedraPatchTarget, scope: ReadScope) {
        if (target.gedraId.dataType != kind) {
            // The id carries its kind, so a row filed under the wrong group is a request that disagrees with
            // itself. Refused rather than believed, because either half could be the mistake.
            throw KdrException.mkInput(
                "'${target.gedraId}' is a ${target.gedraId.dataType?.name} gedra but was sent under " +
                    "'${kind.name}'. A gedra id already says what kind it is, so the two have to agree.",
            )
        }
        // One edit per entry, not per trait: two edits may name two *different* entries of one keyed trait --
        // which is the whole point of a primary key (issue #487) -- but naming one entry twice is still
        // contradictory, a "replace" written twice or a delete racing a merge, with no honest reading. The
        // address is what has to be unique among a target's edits, and for an unkeyed trait the address is the
        // trait, so this stays the one-per-trait rule there.
        val pkFieldsOf = pkFieldsOf(cxt, kind)
        val seenKeys = mutableSetOf<String>()
        for (edit in target.edits) {
            val pkFields = pkFieldsOf(edit.traitId)
            val key = entryKey(edit.traitId, pkFields.map { edit.data?.get(it) })
            if (!seenKeys.add(key)) {
                throw KdrException.mkInput(
                    if (pkFields.isEmpty()) {
                        "The target '${target.gedraId}' asks two things of trait '${edit.traitId}'. A gedra " +
                            "holds one entry per trait, so there is no way to say which of the two was meant."
                    } else {
                        "The target '${target.gedraId}' asks two things of the same '${edit.traitId}' entry " +
                            "(${pkFields.joinToString(", ")} = " +
                            "${pkFields.joinToString(", ") { canonicalKey(edit.data?.get(it)) }}). Two edits " +
                            "may name different entries of a keyed trait, but not one entry twice."
                    },
                )
            }
        }
        if (queryGedra(cxt, target.gedraId.fullId, kind, scope) == null) {
            throw KdrException("No ${kind.name} gedra '${target.gedraId}'.", code = EXC.notFound)
        }
    }

    /** Applies one target's edits inside its own topic transaction and reports each outcome. */
    private fun applyToOne(
        cxt: KdrCxt,
        kind: GedraDataType,
        target: GedraPatchTarget,
        scope: ReadScope,
    ): GedraPatchResult {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = gedraDataTable(cxt)
        // The scope rides on the "write" as well as on the admit-phase read, exactly as the delete's does. It is
        // belt and braces -- admitting already refused anything out of reach -- and it costs one clause to
        // make the "write" itself unable to touch a row the caller may not, rather than relying on an earlier
        // phase having been correct.
        val bind = mutableMapOf<String, Any?>()
        val stmt = mkScopedGedraUpdate(
            sqlCxt, table, scope, "uGedraDataPatch",
            "c:${GD.data} = :${GD.data}, c:${PF.updatedAt} = :${PF.updatedAt}, c:${PF.updatedBy} = :${PF.updatedBy}",
            bind,
        )
        val outcomes = mutableListOf<GedraEditOutcome>()
        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, tranPatch, null, mapOf(GD.gedraId to target.gedraId.fullId),
        ) {
            // Read under the lock: the admit-phase read was for permission, and a merge needs current data.
            val row = readForPatch(cxt, sqlCxt, table, target.gedraId)
            // Keyed by trait -- plus its primary-key value when the trait declares one (issue #487) -- because
            // that is how an edit names an entry, and the address is unique. Order is preserved so an unrelated
            // entry does not move when its neighbor changes.
            val pkFieldsOf = pkFieldsOf(cxt, kind)
            val byKey = keyEntries(row.entries, pkFieldsOf)
            // Strictly past the row's current updatedAt (read under this lock), not merely "now": the gedra
            // cache reloads by walking updatedAt forward and skips a row stamped at or before the version it
            // holds, so a re-edit landing in the same millisecond as the last would otherwise be invisible to
            // the cache until the gedra's next write. See SqlTopicUtil.nextUpdatedAt.
            val now = SqlTopicUtil.nextUpdatedAt(cxt, row.updatedAt)
            for (edit in target.edits) {
                outcomes.add(GedraEditOutcome(edit.traitId, applyEdit(cxt, edit, byKey, pkFieldsOf(edit.traitId), now)))
            }
            val entries = byKey.values.toList()
            checkStoredEntries(cxt, kind, entries)
            val changed = sqlCxt.sqlDb.executeStatement(
                cxt, stmt,
                bind + mapOf(
                    GD.gedraId to target.gedraId.fullId,
                    GD.data to row.storedData(entries),
                    PF.updatedAt to now,
                    PF.updatedBy to cxt.userProfile.userId,
                ),
            )
            // Zero rows means the gedra stopped being writable between admitting and applying -- deleted, or
            // moved out of reach. Silence here would report edits as applied that were not, which is the one
            // outcome the answer must never contain.
            if (changed == 0) {
                throw KdrException(
                    "The gedra '${target.gedraId}' could no longer be written when the patch reached it.",
                    code = EXC.notFound,
                )
            }
        }
        return GedraPatchResult(target.gedraId, outcomes)
    }

    /** The row as it stands inside the transaction; absent here means it went between admitting and applying. */
    internal fun readForPatch(
        cxt: KdrCxt,
        sqlCxt: SqlCxt,
        table: KdrTable,
        gedraId: GedraId,
    ): GedraDataRow {
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        val raw = sqlCxt.sqlDb.queryOneEnabled(cxt, stmt, mapOf(GD.gedraId to gedraId.fullId))
            ?: throw KdrException("No gedra '$gedraId'.", code = EXC.notFound)
        return GedraDataRow.extract(gedraService, raw)
    }

    /**
     * Applies one edit to the entries held by trait, returning whether anything changed.
     *
     * The entry an edit names is its `(traitId, primary-key values)` -- the trait alone for a single-instance
     * trait, or the trait plus the key values carried in the edit's own data for a keyed one (issue #487). A
     * supplied [GedraEdit.entryId] never chooses the entry; it is a staleness check on the entry that address
     * resolves to. Treating a mismatch as an error costs nothing and tells a caller working from an older copy
     * so, rather than letting it overwrite whatever replaced the entry it was written against.
     */
    private fun applyEdit(
        cxt: KdrCxt,
        edit: GedraEdit,
        byKey: MutableMap<String, Map<String, Any?>>,
        pkFields: List<String>,
        now: Instant,
    ): Boolean {
        // The entry an edit names: its trait, plus its primary-key values when the trait declares a key (issue
        // #487). The key rides in the edit's own data -- a delete of a keyed trait carries a minimal `{key:
        // value}` -- so one addressing rule covers every action. An edit that omits a key field names no entry.
        val pkValues = pkFields.map { field ->
            edit.data?.get(field) ?: throw KdrException.mkInput(
                "The edit to '${edit.traitId}' targets a trait keyed by '$field', but supplies no value for it, " +
                    "so it names no entry.",
            )
        }
        val key = entryKey(edit.traitId, pkValues)
        val existing = byKey[key]
        val existingId = existing?.get(GE.entryId).toOptStr()
        if (edit.entryId != null && edit.entryId != existingId) {
            if (existing == null && edit.action == GedraEditAction.deleteOrNoOp) {
                return false // asked to remove a named entry that is not there; that is the "no op" half
            }
            throw KdrException.mkInput(
                "The edit to '${edit.traitId}' names entry '${edit.entryId}', but the gedra holds " +
                    (existingId?.let { "'$it'" } ?: "no matching entry") +
                    ". The copy this edit was written against is out of date.",
            )
        }
        if (edit.action == GedraEditAction.deleteOrNoOp) {
            if (existing == null) {
                return false
            }
            byKey.remove(key)
            return true
        }
        val supplied = edit.data
            ?: throw KdrException.mkInput(
                "The ${edit.action.name} of '${edit.traitId}' carries no data. Only a " +
                    "${GedraEditAction.deleteOrNoOp.name} may leave it out.",
            )
        // A merge folds the supplied keys over what is stored; a "replace" takes the supplied data whole. Keys
        // rather than a deep merge, which is what the questionnaire case wants: a page owns the answers it
        // shows and says nothing about the rest.
        val data = if (edit.action == GedraEditAction.addOrMerge) {
            existing?.get(GE.data).toJsonMapOrEmpty() + supplied
        } else {
            supplied
        }
        byKey[key] = mkStoredEntry(cxt, edit.traitId, data, existing, now)
        return true
    }

    /** The entry as it will be stored: a new envelope, or the existing one with its `updated` half moved on. */
    private fun mkStoredEntry(
        cxt: KdrCxt,
        traitId: String,
        data: Map<String, Any?>,
        existing: Map<String, Any?>?,
        now: Instant,
    ): Map<String, Any?> {
        val actor = cxt.userProfile.userId
        val base = linkedMapOf<String, Any?>(GE.traitId to traitId, GE.data to data)
        if (existing == null) {
            return base.asStoredEntry(cxt.mkUniqueId(), GSRC.user, now, actor)
        }
        // An entry that already exists keeps who made it and when; only the `updated` half moves, which is the
        // whole reason the envelope carries both pairs.
        return base.asStoredEntry(
            entryId = existing[GE.entryId].toOptStr() ?: cxt.mkUniqueId(),
            source = GSRC.user,
            createdAt = existing[GE.createdAt].toOptInstant() ?: now,
            createdBy = existing[GE.createdBy].toOptLong() ?: actor,
            updatedAt = now,
            updatedBy = actor,
        )
    }

    /**
     * Refuses trait ids this client does not support, unless the caller asked to write outside its schema
     * (issue #379).
     *
     * Which traits are supported is read off the client's own union rather than recomputed: every branch
     * carries its trait id as a `const`, so `SchVariants.isKnown` is the same answer the validator selects
     * with. Nothing new has to be carried to the request for this.
     *
     * Checked against **what this call writes**, never the whole gedra. A document holding an entry written
     * before -- or under the flag -- stays editable without it, which it would not if the merged set were
     * checked: one legacy entry would make the document permanently unpatchable.
     */
    private fun checkTraitsSupported(
        cxt: KdrCxt,
        kind: GedraDataType,
        traitIds: List<String>,
        allowAdditional: Boolean,
    ) {
        if (allowAdditional || traitIds.isEmpty()) {
            return
        }
        val union = clientUnion(cxt, kind)?.variants ?: return
        val unsupported = traitIds.filterNot { union.isKnown(it) }.distinct()
        if (unsupported.isNotEmpty()) {
            throw KdrException.mkInput(
                "The client '${cxt.client}' does not support " +
                    "${unsupported.joinToString(", ") { "'$it'" }}. Either the trait is misspelled or it " +
                    "belongs to another client; to store it anyway, send " +
                    "'${GDF.allowAdditionalTraits}'.",
            )
        }
    }

    /**
     * Per trait id, the ordered fields of its primary key (issue #487), for [cxt]'s client and [kind] -- empty
     * when the trait is single-instance or is not one this client carries for this kind. Read off the same
     * [GedraTrait]s the unions were built from, so an entry keys the way the schema that validated it declares.
     */
    private fun pkFieldsOf(cxt: KdrCxt, kind: GedraDataType): (String) -> List<String> {
        val keyed = SchemaService.get(cxt).gedraTraitsFor(cxt.client)
            .filter { kind in it.appliesTo && it.primaryKey.isNotEmpty() }
            .associate { it.traitId to it.primaryKey }
        return { traitId -> keyed[traitId].orEmpty() }
    }

    /** Folds [entries] into a map addressed by [entryKey] -- trait id, plus primary-key values for a keyed trait. */
    private fun keyEntries(
        entries: List<Map<String, Any?>>,
        pkFieldsOf: (String) -> List<String>,
    ): LinkedHashMap<String, Map<String, Any?>> {
        val byKey = LinkedHashMap<String, Map<String, Any?>>()
        for (entry in entries) {
            val traitId = entry[GE.traitId].toOptStr() ?: continue
            // These are entries read back from storage, so a missing key is the migration case (a trait keyed
            // after this entry was written), not a caller mistake -- `stored` picks the message that says so.
            byKey[entryKey(traitId, entryKeyValues(entry, traitId, pkFieldsOf(traitId), stored = true))] = entry
        }
        return byKey
    }

    /** The entry union as [cxt]'s client sees it, or null on a node with no compiled schema for it. */
    private fun clientUnion(cxt: KdrCxt, kind: GedraDataType) =
        SchemaService.get(cxt).storeFor(cxt.client)
            .types["${GCFG.globalNamespace}.${GU.unionName(kind)}"]

    /**
     * Checks what is about to be stored against the schema of the client it is being stored **in** (issue
     * #356) -- `cxt.client`, which the caller has bound to the data's own client. Against the entry union, so
     * what is checked is the **stored** shape rather than the "sent" one.
     *
     * This is the strict half of case (a). An endpoint's *published* input type stays global, so the form
     * shows global traits and `RequestService`'s path-keyed type caches stay sound; a client's own trait
     * arrives on the union's default branch as plain JSON and would pass unexamined. Here it is checked
     * properly, against the definitions its own client declared. Permissive at the edge, strict where it is
     * stored.
     *
     * The variant is the **data's**, never the caller's, and the distinction only shows for a caller holding
     * `allClients`: a client narrows a type so that data living there is valid for that client's users, which
     * is a fact about the destination rather than about who did the writing. For everybody else the two are
     * the same client, since their scope confines them to it.
     *
     * The edit was already validated on the way in, so why again? Two reasons, and this is now the **only**
     * place either is caught. A **merge** produces something neither half was: the stored data and the supplied
     * keys each satisfied the trait alone, and their union may not -- a stored `approved: true` beside a newly
     * merged `rejectionReason` is two valid halves making one invalid entry. And **completeness** is settled
     * here at all because an edit's `data` is a fragment on the way in (`g-optionalContents`, issue #487):
     * its fields are type-checked, but its `required` is not, so an edit may carry only what it changes -- a
     * delete its key, a merge its page's fields. So the assembled result is validated with `required` on, which
     * catches an incomplete add or a merge that leaves a required field unfilled; a delete leaves no entry to
     * check. This is what lets a keyed trait with a required non-key field be deleted by its key alone, which
     * the old on-the-way-in `required` check refused.
     */
    private fun checkStoredEntries(cxt: KdrCxt, kind: GedraDataType, entries: List<Map<String, Any?>>) {
        checkEntryKeys(entries, pkFieldsOf(cxt, kind))
        val union = clientUnion(cxt, kind) ?: return
        val failures = entries.flatMap { validate(union, it) }
        if (failures.isNotEmpty()) {
            throw KdrException.mkInput(
                "The patch would leave ${failures.size} problem(s) in the stored entries: " +
                    failures.joinToString("; ") { "${it.path}: ${it.message}" },
            )
        }
    }

    /**
     * Serves [listGedras] from [dataCache], or returns null when it cannot -- the same fall-back-to-SQL
     * contract [cachedGedra] has, so the cache only ever saves work and never changes an answer.
     *
     * **It can only serve a scope that names a client.** The `clientKind` index is keyed by client (issue
     * #363: cache by client, and by kind within a client), so a scope with no client -- an `allClients`
     * administrator reading every client, or an ordinary user whose scope carries only a `userId` -- has no
     * key to look up and falls back to SQL. That is not a gap the index should close: a user's *own* gedras
     * are reached by building their derived id and hitting the primary-key map, which is why there is no
     * `userId` index; the listing shape the index exists for is the client-wide one an administrator runs.
     *
     * The result **equals the SQL path** for the shapes it serves, step for step. `rowsForClientKind` is the
     * cache's copy of the SQL `where` for a client-scoped listing (both are client+kind, enabled only --
     * disabled rows are tombstones the index omits); [admitsRow] narrows an organization scope the way the
     * SQL `org` predicate would; the sort is the SQL `order by`; and [limit] caps the ordered, scoped list
     * last -- exactly where `listGedras` applies it, since its SQL carries no `limit` either. Extraction comes
     * after the cap, so only the rows actually returned are built, which the SQL path (extracting its whole
     * matched set) does not bother to do.
     */
    private fun cachedListGedras(
        cxt: KdrCxt,
        kind: GedraDataType,
        scope: ReadScope,
        limit: Int,
        offset: Int,
    ): GedraListPage? {
        val cache = dataCache ?: return null
        val client = scope.client ?: return null
        cache.checkRefresh(cxt)
        val matched = GedraDataCache.rowsForClientKind(cache, client, kind.name).asSequence()
            .map { it.value }
            .filter { admitsRow(scope, it) }
            .sortedWith(gedraListOrder)
            .toList()
        // Reported before the cap, so `rowsMatched` means the same thing the SQL path's does: how many rows
        // the scope admitted, not how many the page returned.
        explainScope(cxt, scope, "cache:${GDX.clientKind}", matched.size)
        // Extraction after the page window, so only the rows actually returned are built -- and the page is the
        // same slice the SQL path returns because both order identically. `numAvailable` is the whole matched
        // set, so the caller can say "of N" and know whether more remain past this page.
        val page = matched.asSequence().drop(offset).take(limit).map { GedraDataRow.extract(gedraService, it) }.toList()
        return GedraListPage(page, matched.size)
    }

    /**
     * Every gedra of [kind] within [scope], newest first, capped at [limit].
     *
     * Scope and the enabled flag are both SQL, so the rows that come back are already the rows the caller may
     * see; [limit] is applied afterward, which makes it a cap on what is returned rather than an exact page.
     * That is the arrangement #310 asked for on the way to holding this data in memory, and it is `listUsers`'
     * arrangement too.
     *
     * A client-scoped listing is served from [dataCache] first ([cachedListGedras]); the SQL below is the
     * fall-back for that and the whole answer for a scope the cache cannot key on.
     *
     * The prepared statement is named for the kind **and** the scope's shape, because both change the SQL.
     * Statements are cached by name, so two shapes sharing one name would serve whichever query ran first --
     * and the failure would be a listing quietly scoped to somebody else's width.
     */
    fun listGedras(cxt: KdrCxt, kind: GedraDataType, scope: ReadScope, limit: Int, offset: Int = 0): GedraListPage {
        cachedListGedras(cxt, kind, scope, limit, offset)?.let { return it }
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
        // `rows` is the whole scoped, ordered set (the SQL carries no limit); the page window is applied here,
        // the same place `limit` always was, now with an offset. Extraction is after the window, so only the
        // returned rows are built. `numAvailable` is the whole set, matching the cache path.
        val page = rows.asSequence()
            .drop(offset)
            .take(limit)
            .map { GedraDataRow.extract(gedraService, it) }
            .toList()
        return GedraListPage(page, rows.size)
    }

    /** One page of a gedra listing: the [rows] returned, and [numAvailable] -- how many the scope admits in all. */
    class GedraListPage(val rows: List<GedraDataRow>, val numAvailable: Int)

    /**
     * Reports, under `_meta`, which scope the listing actually ran with -- the fact the response cannot show,
     * since a correctly and an incorrectly scoped listing differ only in rows the caller never sees.
     *
     * The same argument as `_debug=explainAccess`: without it, a scoping bug surfaces as a count one lower (or
     * higher) than expected, which is not something anybody notices.
     */
    private fun explainScope(cxt: KdrCxt, scope: ReadScope, statementName: String, rowCount: Int) {
        // Fenced to a test node or ENV DEBUG (issue #517): it names the SQL statement and the scope shape, and
        // shares the one diagnostic gate its `explain*` siblings use so the vocabulary cannot drift.
        if (!cxt.hasDebugDiagnostic(GDBG.explainScope)) {
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

        /** Name of the "delete" transaction; it prefixes the generated transaction id. */
        const val tranDelete = "deleteGedra"

        /** Name of the "patch" transaction; it prefixes the generated transaction id. */
        const val tranPatch = "patchGedra"

        /** The service; throws naming it on a node that does not run it. */
        fun get(cxt: KdrCxt): GedraDataService = cxt.instanceConfig.get(serviceName) as? GedraDataService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
