package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunCxt
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlSession
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import java.text.Normalizer

/** A unique field's value on one gedra (issue #1851): where it is, and the key it is compared by. */
class UniqueSlot(val traitId: String, val field: String, val value: String, val key: String) {
    val claimKind: String get() = UniqueClaims.kindOf(traitId, this.field)
}

/**
 * The claims of unique fields (issue #1851): which of an owner's gedras holds each value no two of them may share, in
 * the value claims table ([VCL]), keyed by a hash of the normalized value ([normalizeUnique]).
 *
 * **A claim is an index, not the truth.** The stored forms are. A refusal reads the gedra a claim names and stands only
 * when that gedra still holds the value, since a value may have changed, or its form been deleted, while its client did
 * not hold the field unique. A stale claim is dropped there. A claim that is missing -- a form that stood before the
 * field was made unique -- is filled in when it is ([fill]), and again nightly ([uniqueClaimsJob]).
 *
 * **Forms that shared a value before it had to be unique are kept.** The first made holds the value's own key, and each
 * other has a claim of its own beside it ([sharedKey]), so the value stays taken until the last of them lets it go.
 *
 * **Two writes racing for one value** are settled by the table's primary key, as a counter's are: one is refused.
 */
@Suppress("ConstPropertyName")
object UniqueClaims {
    /** What every unique field's claims are of, before its trait and field: `unique:kdr:name.name`. */
    const val kindPrefix = "unique:"

    /** Between a value's key and the gedra that shares it, in the claim of a form that shared it already. */
    private const val sharedMark = "~"

    private const val tranFill = "fillUniqueClaims"

    fun kindOf(traitId: String, field: String): String = "$kindPrefix$traitId.$field"

    /** The trait of the claim kind [kind] ([kindOf]): a trait id may hold a `.`, and a field may not. */
    fun traitOfKind(kind: String): String = kind.removePrefix(kindPrefix).substringBeforeLast('.')

    private fun sharedKey(key: String, gedraId: String): String = "$key$sharedMark$gedraId"

    private fun scopeOf(row: GedraDataRow): String = row.userId.toString()

    /**
     * The unique values of [entries], by claim kind, read by [union]'s types: none for a field left empty, or stored
     * before it was unique with nothing to compare. A type with a key has no unique field (the parser refuses it), so a trait is met once.
     */
    fun slotsOf(union: SchType?, entries: List<Map<String, Any?>>): Map<String, UniqueSlot> {
        val out = LinkedHashMap<String, UniqueSlot>()
        for (entry in entries) {
            val traitId = entry[GE.traitId].toOptStr() ?: continue
            val type = traitDataTypeIn(union, traitId) ?: continue
            val data = entry[GE.data].toJsonMapOrEmpty()
            for (field in uniqueFieldsOf(type).keys) {
                val value = data[field].toOptStr() ?: continue
                val key = keyOf(value) ?: continue
                val slot = UniqueSlot(traitId, field, value, key)
                out[slot.claimKind] = slot
            }
        }
        return out
    }

    /**
     * The key [value] is compared by, or null when it normalizes to nothing and so claims nothing. Accents are composed
     * first, so an é written as one character and as two is one letter.
     */
    fun keyOf(value: String): String? = normalizeUnique(Normalizer.normalize(value, Normalizer.Form.NFC))
        .ifEmpty { null }?.toByteArray(Charsets.UTF_8)?.sha256Hex()

    /** The claim kinds [union]'s traits have: every unique field of every trait it carries. */
    fun kindsOf(union: SchType?): Set<String> = union?.variants?.byValue.orEmpty().keys.flatMap { traitId ->
        traitDataTypeIn(union, traitId)?.let { uniqueFieldsOf(it).keys }.orEmpty().map { kindOf(traitId, it) }
    }.toSet()

    /** Every claim kind [client] holds unique now, over each kind of gedra that holds entries. */
    fun kindsFor(cxt: KdrCxt, client: String): Set<String> =
        GU.entryKinds.flatMap { kindsOf(GedraDataService.get(cxt).entryUnion(cxt, client, it)) }.toSet()

    // --- a write ---------------------------------------------------------------------------------------------------

    /**
     * Brings [row]'s claims in line with a write, in its transaction: a value it gave up is released, and one it took is
     * claimed, or refused when another of the owner's gedras holds it. A value unchanged but for its case or
     * punctuation is the same value, and one the form shared already stays as it is.
     */
    fun sync(cxt: KdrCxt, sqlCxt: SqlCxt, row: GedraDataRow, priorEntries: List<Map<String, Any?>>) {
        val union = GedraDataService.get(cxt).entryUnion(cxt, row.client, row.kind) ?: return
        val after = slotsOf(union, row.entries)
        val before = slotsOf(union, priorEntries)
        // In one order, so two writes of the same values take the keys in turn.
        val changed = (before.keys + after.keys).filter { before[it]?.key != after[it]?.key }.sorted()
        for (kind in changed) {
            if (kind in before) releaseKind(cxt, sqlCxt, row.gedraId.fullId, kind)
        }
        for (kind in changed) {
            val slot = after[kind] ?: continue
            holderOf(cxt, sqlCxt, row.client, scopeOf(row), slot, row.gedraId.fullId)?.let { held ->
                throw taken(cxt, row, slot, held)
            }
            claim(cxt, sqlCxt, row, slot, slot.key)
        }
    }

    /** Releases every unique value of the gedra [gedraId], deleted in this transaction. A counter's ids stay claimed. */
    fun releaseAll(cxt: KdrCxt, sqlCxt: SqlCxt, gedraId: String) {
        val table = ValueClaims.table(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "dUniqueClaimsOfGedra", table.columns,
            "delete from t:${VCL.gedraValueClaims} where c:${VCL.gedraId} = :${VCL.gedraId} and c:${VCL.claimKind} like '$kindPrefix%'",
        )
        sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(VCL.gedraId to gedraId))
    }

    /** Releases whatever [gedraId] claims of [kind]: for a write of that gedra, which holds its lock. */
    private fun releaseKind(cxt: KdrCxt, sqlCxt: SqlCxt, gedraId: String, kind: String) {
        val table = ValueClaims.table(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "dUniqueClaim", table.columns,
            "delete from t:${VCL.gedraValueClaims} where c:${VCL.gedraId} = :${VCL.gedraId} and c:${VCL.claimKind} = :${VCL.claimKind}",
        )
        sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(VCL.gedraId to gedraId, VCL.claimKind to kind))
    }

    /** Deletes the one claim [row], read from the table, if it is still the same gedra's. */
    private fun releaseRow(cxt: KdrCxt, sqlCxt: SqlCxt, row: Map<String, Any?>) {
        val table = ValueClaims.table(cxt)
        val key = listOf(PF.client, VCL.claimKind, VCL.scopeKey, VCL.valueKey, VCL.gedraId)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "dUniqueClaimRow", table.columns,
            "delete from t:${VCL.gedraValueClaims} where ${key.joinToString(" and ") { "c:$it = :$it" }}",
        )
        sqlCxt.sqlDb.executeStatement(cxt, stmt, key.associateWith { row[it] })
    }

    private fun claim(cxt: KdrCxt, sqlCxt: SqlCxt, row: GedraDataRow, slot: UniqueSlot, valueKey: String) {
        val claim = ValueClaim(
            row.client, slot.claimKind, scopeOf(row), valueKey, slot.value, row.gedraId.fullId, slot.traitId, slot.field,
        )
        ValueClaims.claim(cxt, sqlCxt, listOf(claim)) { _, _ -> takenText(cxt, row.client, row.userId, slot, null) }
    }

    /** Another gedra holding a value: its id, and the value as it holds it. */
    class Holder(val gedraId: String, val value: String)

    /**
     * The gedra of [scope]'s owner other than [except] that holds [slot]'s value, read in [sqlCxt]'s session; null when
     * none does. A claim naming one that no longer holds it is stale, and dropped when a transaction is open: that claim
     * alone, since the gedra is not locked here, and may have just claimed the value it holds now.
     */
    private fun holderOf(
        cxt: KdrCxt,
        sqlCxt: SqlCxt,
        client: String,
        scope: String,
        slot: UniqueSlot,
        except: String?,
    ): Holder? {
        val table = ValueClaims.table(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qUniqueClaims", table.columns,
            "select * from t:${VCL.gedraValueClaims} where c:${PF.client} = :${PF.client} and c:${VCL.claimKind} = :${VCL.claimKind} " +
                "and c:${VCL.scopeKey} = :${VCL.scopeKey} " +
                "and (c:${VCL.valueKey} = :${VCL.valueKey} or c:${VCL.valueKey} like :$sharedLike)",
        )
        val rows = sqlCxt.sqlDb.queryStatement(
            cxt, stmt,
            mapOf(
                PF.client to client, VCL.claimKind to slot.claimKind, VCL.scopeKey to scope, VCL.valueKey to slot.key,
                sharedLike to "${slot.key}$sharedMark%",
            ),
        )
        for (held in rows) {
            val id = held[VCL.gedraId].toOptStr()?.takeIf { it != except } ?: continue
            val value = heldValue(cxt, sqlCxt, client, scope, slot, id)
            if (value != null) return Holder(id, value)
            if (SqlSession.get(cxt)?.inTran == true) releaseRow(cxt, sqlCxt, held)
        }
        return null
    }

    /** The value the live gedra [gedraId] holds in [slot]'s field, when it is [slot]'s value and [scope]'s owner's; else null. */
    private fun heldValue(
        cxt: KdrCxt,
        sqlCxt: SqlCxt,
        client: String,
        scope: String,
        slot: UniqueSlot,
        gedraId: String,
    ): String? {
        val table = GedraDataService.get(cxt).gedraDataTable(cxt)
        val raw = sqlCxt.sqlDb.queryOneEnabled(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(GD.gedraId to gedraId))
            ?: return null
        val row = GedraDataRow.extract(GedraService.get(cxt), raw)
        if (row.client != client || scopeOf(row) != scope) return null
        val union = GedraDataService.get(cxt).entryUnion(cxt, client, row.kind)
        return slotsOf(union, row.entries)[slot.claimKind]?.takeIf { it.key == slot.key }?.value
    }

    // --- asking ----------------------------------------------------------------------------------------------------

    /**
     * Another of [userId]'s gedras in [client] that holds [slot]'s value, other than [except]; null when none
     * does. For a form asking as a value is typed, and an import asking before it writes; the write's own check decides.
     */
    fun heldElsewhere(cxt: KdrCxt, client: String, userId: Long, slot: UniqueSlot, except: String? = null): Holder? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        var out: Holder? = null
        sqlCxt.sqlDb.withSession(cxt) { out = holderOf(cxt, sqlCxt, client, userId.toString(), slot, except) }
        return out
    }

    /**
     * The unique field [field] of [traitId] in [client], holding [value]; null when the client does not hold it unique, or
     * the value claims nothing.
     */
    fun slotFor(cxt: KdrCxt, client: String, kind: GedraDataType, traitId: String, field: String, value: String): UniqueSlot? {
        val type = traitDataTypeIn(GedraDataService.get(cxt).entryUnion(cxt, client, kind), traitId) ?: return null
        if (field !in uniqueFieldsOf(type)) return null
        return keyOf(value)?.let { UniqueSlot(traitId, field, value, it) }
    }

    /** The refusal of a write storing [slot], held by [held]: a 400, the other gedra in its extra data. */
    private fun taken(cxt: KdrCxt, row: GedraDataRow, slot: UniqueSlot, held: Holder): KdrException =
        KdrException.mkInput(takenText(cxt, row.client, row.userId, slot, held.value)).also {
            it.extraData[UNQ.traitId] = slot.traitId
            it.extraData[UNQ.field] = slot.field
            it.extraData[GDF.gedraId] = held.gedraId
        }

    /**
     * That [slot]'s value is taken by another of [owner]'s forms, in words for the person who typed it; [heldAs] is it as
     * the other form holds it, if known.
     */
    fun takenText(cxt: KdrCxt, client: String, owner: Long?, slot: UniqueSlot, heldAs: String?): String {
        val label = formFieldLabel(cxt, client, slot.traitId, slot.field)
        val typed = slot.value.trim()
        val spelled = heldAs?.trim()?.takeIf { it != typed }?.let { ", as '$it'" }.orEmpty()
        val whose = if (owner == cxt.userProfile.userId) "another of your forms" else "another of their forms"
        return "$label '$typed' is already used by $whose$spelled. Choose a different one."
    }

    // --- filling in ------------------------------------------------------------------------------------------------

    /**
     * Claims the unique values of [client]'s forms that have no claim, oldest form first, and answers how many forms were
     * given one ([dryRun] only counts). A form whose claims are in order costs no write, so a run that finds nothing to
     * do is one query and a walk of the cache. Each form in a transaction of its own, on the form's lock, reading its data
     * there.
     */
    fun fill(cxt: KdrCxt, client: String, dryRun: Boolean = false): Int {
        val data = GedraDataService.get(cxt)
        var filled = 0
        for (kind in GU.entryKinds) {
            val union = data.entryUnion(cxt, client, kind) ?: continue
            if (kindsOf(union).isEmpty()) continue
            val claimed = claimedOf(cxt, client)
            val scope = ReadScope.ofClient(client)
            val lacking = data.liveGedraIds(cxt, kind, client).chunked(readBatch).flatMap { ids ->
                data.readGedras(cxt, kind, ids, scope).values.filter { row ->
                    slotsOf(union, row.entries).values.any { Triple(row.gedraId.fullId, it.claimKind, it.key) !in claimed }
                }
            }.sortedWith(compareBy({ it.createdAt }, { it.gedraId.fullId }))
            for (row in lacking) {
                if (dryRun || fillOne(cxt, kind, row.gedraId)) filled++
            }
        }
        return filled
    }

    private fun fillOne(cxt: KdrCxt, kind: GedraDataType, gedraId: GedraId): Boolean {
        // A write racing this one may take the key between the look and the insert: looked at again, once.
        repeat(2) { attempt ->
            try {
                return fillUnderLock(cxt, kind, gedraId)
            } catch (e: KdrException) {
                if (attempt == 1) {
                    LogGedra.warn(cxt) { "Could not claim the unique values of '${gedraId.fullId}': ${e.message}" }
                }
            }
        }
        return false
    }

    private fun fillUnderLock(cxt: KdrCxt, kind: GedraDataType, gedraId: GedraId): Boolean {
        var wrote = false
        val txCxt = cxt.mkTransactionSubContext(tranFill)
        val sqlCxt = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranFill, null, mapOf(GD.gedraId to gedraId.fullId)) {
            val table = GedraDataService.get(txCxt).gedraDataTable(txCxt)
            val raw = sqlCxt.sqlDb.queryOneEnabled(
                txCxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(GD.gedraId to gedraId.fullId),
            )
            if (raw != null) {
                val row = GedraDataRow.extract(GedraService.get(txCxt), raw)
                val union = GedraDataService.get(txCxt).entryUnion(txCxt, row.client, kind)
                val claimed = claimsOf(txCxt, sqlCxt, gedraId.fullId)
                for (slot in slotsOf(union, row.entries).values) {
                    // One it has claimed stays as it is, so the first made keeps the value's own key.
                    if (slot.claimKind to slot.key in claimed) continue
                    // Whatever this form claimed of the field before -- a value it changed while the field was not unique.
                    releaseKind(txCxt, sqlCxt, gedraId.fullId, slot.claimKind)
                    val shared = holderOf(txCxt, sqlCxt, row.client, scopeOf(row), slot, gedraId.fullId) != null
                    claim(txCxt, sqlCxt, row, slot, if (shared) sharedKey(slot.key, gedraId.fullId) else slot.key)
                    wrote = true
                }
            }
        }
        return wrote
    }

    /** The unique values the gedra [gedraId] has claims of, as (claim kind, key), read in [sqlCxt]'s transaction. */
    private fun claimsOf(cxt: KdrCxt, sqlCxt: SqlCxt, gedraId: String): Set<Pair<String, String>> {
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qUniqueClaimsOfGedra", ValueClaims.table(cxt).columns,
            "select * from t:${VCL.gedraValueClaims} where c:${VCL.gedraId} = :${VCL.gedraId} and c:${VCL.claimKind} like '$kindPrefix%'",
        )
        return sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(VCL.gedraId to gedraId))
            .map { it[VCL.claimKind].toOptStr().orEmpty() to it[VCL.valueKey].toOptStr().orEmpty().substringBefore(sharedMark) }.toSet()
    }

    /** The unique values [client]'s gedras have claims of, as (gedra, claim kind, key). */
    private fun claimedOf(cxt: KdrCxt, client: String): Set<Triple<String, String, String>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = ValueClaims.table(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qUniqueClaimsOfClient", table.columns,
            "select c:${VCL.gedraId}, c:${VCL.claimKind}, c:${VCL.valueKey} from t:${VCL.gedraValueClaims} " +
                "where c:${PF.client} = :${PF.client} and c:${VCL.claimKind} like '$kindPrefix%'",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client)) }
        fun Map<String, Any?>.text(name: String) = this[name].toOptStr().orEmpty()
        return rows.map { Triple(it.text(VCL.gedraId), it.text(VCL.claimKind), it.text(VCL.valueKey).substringBefore(sharedMark)) }.toSet()
    }

    /**
     * Fills in [client]'s claims after its reload, when the reload made a field unique that was not ([before]). Never
     * fails the reload, which has happened: what it could not do, the nightly run does.
     */
    fun fillNewlyUnique(cxt: KdrCxt, client: String, before: Set<String>) {
        try {
            if ((kindsFor(cxt, client) - before).isEmpty()) return
            val filled = fill(cxt, client)
            if (filled > 0) LogGedra.info(cxt) { "Claimed the unique values of $filled existing form(s) of client '$client'." }
        } catch (e: Exception) {
            LogGedra.error(cxt, "Could not claim the unique values of client '$client'; the nightly run will.", e)
        }
    }

    private const val readBatch = 500

    /** The bind name of the pattern matching the claims of forms that shared a value already. */
    private const val sharedLike = "sharedLike"
}

/**
 * Keeps a gedra's unique values claimed (issue #1851), in its write: see [UniqueClaims.sync]. On a delete it releases them,
 * so a name can be used again.
 */
object UniqueClaimHook : GedraWriteHook {
    override fun afterWrite(cxt: KdrCxt, write: GedraWriteContext) {
        UniqueClaims.sync(cxt, write.sqlCxt, write.row, write.priorEntries)
    }

    override fun afterDelete(cxt: KdrCxt, sqlCxt: SqlCxt, row: GedraDataRow) {
        UniqueClaims.releaseAll(cxt, sqlCxt, row.gedraId.fullId)
    }
}

/**
 * Where a unique field is out of place (issue #1851), as `(type name, message)` pairs for those of [names] in [types]: one
 * a trait's data reaches through a `$ref`, nested in its data or in a list, where no claim is made. The parser refuses one
 * written inline there, and a type of its own may be any trait's data, so this is judged once every type is known.
 */
fun nestedUniqueProblems(types: Map<String, SchType>, names: Set<String>): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val dataTypes = GU.entryKinds.flatMap { kind ->
        types["${GCFG.globalNamespace}.${GU.unionName(kind)}"]?.variants?.branches.orEmpty()
            .mapNotNull { it.properties[GE.data]?.valueType }
    }.toSet()
    for (dataType in dataTypes) {
        val seen = HashSet<SchType>()
        fun nestedIn(type: SchType): List<SchType> = buildList {
            type.properties.values.mapTo(this) { it.valueType }
            addAll(listOfNotNull(type.itemType, type.additionalValueType))
            addAll(type.variants?.branches.orEmpty())
        }
        fun walk(type: SchType) {
            if (!seen.add(type)) return
            uniqueFieldsOf(type).keys.forEach { field ->
                val owner = listOfNotNull(dataType.name, type.name).firstOrNull { it in names } ?: return@forEach
                out.add(
                    owner to "Type '${dataType.name}' holds type '${type.name}' inside it, whose field '$field' has " +
                        "'${SCH.unique}'. A unique field is one of a trait's own data; its values are not compared there.",
                )
            }
            nestedIn(type).forEach(::walk)
        }
        nestedIn(dataType).forEach(::walk)
    }
    return out.distinct()
}

/** What a misplaced unique field's config problem says the node does instead. */
const val misplacedUnique = "Keeping the field; its values are not held unique."

/** The names of the job that fills in unique claims (issue #1851). */
@Suppress("ConstPropertyName")
object UCJ {
    const val jobType = "uniqueClaims"
    const val profile = "uniqueClaims"
}

/**
 * Fills in, nightly, the claims of unique values that are missing (issue #1851): a form made while its client's field was
 * not unique on the node that wrote it, or a field a component's code made unique, which no reload reports. One task per
 * client; a client with nothing missing is left alone.
 */
fun uniqueClaimsJob(): JobDef = JobDef(
    jobType = UCJ.jobType,
    description = "Claims the unique values of forms that have no claim, so a value is refused whenever another form holds it.",
    profile = JobProfile(UCJ.profile),
    tasks = { run, client -> if (UniqueClaims.kindsFor(run.cxt, client).isEmpty()) emptyList() else listOf(client) },
    runTask = ::fillClient,
    schedule = JobSchedule.daily("03:30"),
    resourceOf = { it },
)

private fun fillClient(run: JobRunCxt, client: String): JobTaskResult {
    val filled = UniqueClaims.fill(run.cxt, client, run.dryRun)
    if (filled == 0) return JobTaskResult.nothingToDo
    run.trace("$filled form(s) ${if (run.dryRun) "have unique values to claim" else "had unique values claimed"}.", client)
    return JobTaskResult.done
}
