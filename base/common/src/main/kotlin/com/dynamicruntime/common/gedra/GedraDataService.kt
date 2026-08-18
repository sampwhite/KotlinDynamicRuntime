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
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
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
 * **`g-primaryKey`.** An entry is addressed by its trait, and a gedra holds at most one entry per trait
 * ([checkOneEntryPerTrait]). Several entries of one trait, told apart by a key drawn from their data, is what
 * that keyword will allow.
 *
 * **Dry runs, and all-or-nothing across gedras.** See `gedra-patch.md`.
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
            ?: throw KdrException("GedraService required for GedraDataService.")
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
     * `SqlScopeUtil` exists to be the only copy of. A row the scope refuses returns null here and the caller
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

    private fun gedraDataTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[GDT.gedraData]
        ?: throw KdrException("${GDT.gedraData} table is not registered in the schema store.")

    /**
     * The order a patch applies kinds in (issue #337).
     *
     * Only the first entry is a decision: a **workflow is edited before the forms it governs**, because it is
     * where "has this already been done?" is recorded and so has to see a "submit" first. The rest follow in
     * declaration order, which is arbitrary but fixed -- what matters is that the server chooses, so a caller
     * cannot reorder the phases by reordering their request.
     */
    private val kindApplyOrder: List<GedraDataType> =
        listOf(GedraDataType.wfData) + GedraDataType.entries.filter { it != GedraDataType.wfData }

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
        // At most one entry per trait, refused before anything is minted (issue #337). This was not checked
        // when create was written, so a caller could store two entries of one trait and leave a gedra nothing
        // could address by trait alone -- which is how the patch, and the form, expect to address them.
        checkOneEntryPerTrait(entries)
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
            PF.updatedAt to cxt.instanceNow(),
            PF.updatedBy to cxt.userProfile.userId,
        )
        val conditions = mutableListOf(
            "c:${GD.gedraId} = :${GD.gedraId}",
            // Already-deleted is not deleted again, which is what makes the returned count mean something.
            "c:${PF.enabled} = true",
        )
        conditions.addAll(SqlScopeUtil.scopeConditions(scope, table, data))
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "uGedraDataDelete${scope.shapeKey}", table.columns,
            "update t:${GDT.gedraData} set c:${PF.enabled} = false, " +
                "c:${PF.updatedAt} = :${PF.updatedAt}, c:${PF.updatedBy} = :${PF.updatedBy} " +
                "where ${conditions.joinToString(" and ")}",
        )
        var changed = 0
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranDelete, null, mapOf(GD.gedraId to row.gedraId.fullId)) {
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
     * patch is by replay, since a workflow records what it has already done and a retry skips it. See
     * `gedra-patch.md`.
     *
     * Kinds are applied in a fixed order with `wfData` first, because a workflow is the thing that guards
     * against a double submit and has to see the edit before the forms it governs do. The server chooses that
     * order rather than honoring the request's, so a caller cannot subvert it by sending forms first.
     *
     * The row is read again inside the transaction. The admit-phase read cannot be reused: it happened outside
     * the lock, and a merge has to work from what is stored now rather than from what was stored a moment ago.
     */
    fun patchGedras(
        cxt: KdrCxt,
        targetsByKind: Map<GedraDataType, List<GedraPatchTarget>>,
        scope: ReadScope,
    ): List<GedraPatchResult> {
        val ordered = kindApplyOrder.mapNotNull { kind -> targetsByKind[kind]?.let { kind to it } }
        for ((kind, targets) in ordered) {
            for (target in targets) {
                admit(cxt, kind, target, scope)
            }
        }
        return ordered.flatMap { (kind, targets) -> targets.map { applyToOne(cxt, kind, it, scope) } }
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
        // One edit per trait, for the reason a gedra holds one entry per trait: two edits naming one trait are
        // either contradictory or a "replace" written twice, and neither has an honest reading.
        val traits = target.edits.map { it.traitId }
        traits.firstOrNull { id -> traits.count { it == id } > 1 }?.let {
            throw KdrException.mkInput(
                "The target '${target.gedraId}' asks two things of trait '$it'. A gedra holds one entry per " +
                    "trait, so there is no way to say which of the two was meant.",
            )
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
        val conditions = mutableListOf(
            "c:${GD.gedraId} = :${GD.gedraId}",
            "c:${PF.enabled} = true",
        )
        conditions.addAll(SqlScopeUtil.scopeConditions(scope, table, bind))
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "uGedraDataPatch${scope.shapeKey}", table.columns,
            "update t:${GDT.gedraData} set c:${GD.data} = :${GD.data}, " +
                "c:${PF.updatedAt} = :${PF.updatedAt}, c:${PF.updatedBy} = :${PF.updatedBy} " +
                "where ${conditions.joinToString(" and ")}",
        )
        val outcomes = mutableListOf<GedraEditOutcome>()
        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, tranPatch, null, mapOf(GD.gedraId to target.gedraId.fullId),
        ) {
            // Read under the lock: the admit-phase read was for permission, and a merge needs current data.
            val row = readForPatch(cxt, sqlCxt, table, target.gedraId)
            // Keyed by trait because that is how an edit names an entry, and the one-per-trait rule makes the
            // key unique. Order is preserved so an unrelated entry does not move when its neighbor changes.
            val byTrait = LinkedHashMap<String, Map<String, Any?>>()
            for (entry in row.entries) {
                entry[GE.traitId].toOptStr()?.let { byTrait[it] = entry }
            }
            val now = cxt.instanceNow()
            for (edit in target.edits) {
                outcomes.add(GedraEditOutcome(edit.traitId, applyEdit(cxt, edit, byTrait, now)))
            }
            val entries = byTrait.values.toList()
            checkStoredEntries(cxt, kind, entries)
            val changed = sqlCxt.sqlDb.executeStatement(
                cxt, stmt,
                bind + mapOf(
                    GD.gedraId to target.gedraId.fullId,
                    GD.data to (row.extra + linkedMapOf<String, Any?>(GD.entries to entries)),
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
    private fun readForPatch(
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
     * A supplied [GedraEdit.entryId] has to name the entry that is actually there. It is redundant today --
     * the trait already identifies the entry -- so treating a mismatch as an error costs nothing and turns it
     * into a cheap staleness check: a caller working from an older copy is told so rather than overwriting
     * whatever replaced it. When `g-primaryKey` allows several entries per trait, this is the field that will
     * choose between them, and the check becomes load-bearing rather than a bonus.
     */
    private fun applyEdit(
        cxt: KdrCxt,
        edit: GedraEdit,
        byTrait: MutableMap<String, Map<String, Any?>>,
        now: Instant,
    ): Boolean {
        val existing = byTrait[edit.traitId]
        val existingId = existing?.get(GE.entryId).toOptStr()
        if (edit.entryId != null && edit.entryId != existingId) {
            if (existing == null && edit.action == GedraEditAction.deleteOrNoOp) {
                return false // asked to remove a named entry that is not there; that is the "no op" half
            }
            throw KdrException.mkInput(
                "The edit to '${edit.traitId}' names entry '${edit.entryId}', but the gedra holds " +
                    (existingId?.let { "'$it'" } ?: "no entry of that trait") +
                    ". The copy this edit was written against is out of date.",
            )
        }
        if (edit.action == GedraEditAction.deleteOrNoOp) {
            if (existing == null) {
                return false
            }
            byTrait.remove(edit.traitId)
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
        byTrait[edit.traitId] = mkStoredEntry(cxt, edit.traitId, data, existing, now)
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
     * Checks the entries a patch is about to store against the entry union -- the stored shape, not the "sent"
     * one.
     *
     * The edit was already validated on the way in, so why again? Because a **merge** produces something
     * neither half was: the stored data and the supplied keys each satisfied the trait alone, and their union
     * may not. A conditional is the ordinary way that happens -- a stored `approved: true` beside a newly
     * merged `rejectionReason` is two valid halves making one invalid entry.
     *
     * Known limit, recorded rather than fixed: a merge fragment is validated against the trait's own schema on
     * the way in, `required` included. That is a non-issue for the traits merges are for, which mark their
     * fields optional and leave requiredness to the workflow, and it would bite a trait that mixed required
     * fields with merge usage. See the soft-validation section of `gedra-patch.md`.
     */
    private fun checkStoredEntries(cxt: KdrCxt, kind: GedraDataType, entries: List<Map<String, Any?>>) {
        checkOneEntryPerTrait(entries)
        val union = cxt.getSchema().types["${GCFG.globalNamespace}.${GU.unionName(kind)}"] ?: return
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
    ): List<GedraDataRow>? {
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
        return matched.take(limit).map { GedraDataRow.extract(gedraService, it) }
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
    fun listGedras(cxt: KdrCxt, kind: GedraDataType, scope: ReadScope, limit: Int): List<GedraDataRow> {
        cachedListGedras(cxt, kind, scope, limit)?.let { return it }
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

        /** Name of the "delete" transaction; it prefixes the generated transaction id. */
        const val tranDelete = "deleteGedra"

        /** Name of the "patch" transaction; it prefixes the generated transaction id. */
        const val tranPatch = "patchGedra"

        fun get(cxt: KdrCxt): GedraDataService? = cxt.instanceConfig.get(serviceName) as? GedraDataService

        /** The service, or a fault naming it -- for a handler that cannot proceed without one. */
        fun require(cxt: KdrCxt): GedraDataService = get(cxt)
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
