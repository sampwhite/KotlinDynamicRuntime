package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlScopeUtil
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/** The job-exceptions table, its entry trait, and their field names (issue #871). Each name matches its value. */
@Suppress("ConstPropertyName")
object JOBX {
    const val jobExceptions = "JobExceptions"

    /** The id of the one trait an exceptions row holds entries of, one entry per job type. */
    const val traitId = "jobException"

    /** The code-declared type of that trait's data. */
    const val entryType = "JobExceptionEntry"

    const val gedraId = "gedraId"
    const val entries = "entries"
    const val scenario = "scenario"
    const val message = "message"
    const val details = "details"
    const val count = "count"
    const val at = "at"
    const val history = "history"

    /** In a `taskFailed` trace entry: whether the failure was recorded against its gedra. */
    const val recorded = "recorded"

    /** How many earlier failures an entry's history keeps, newest last. */
    const val historyLimit = 5
}

/**
 * The job-exceptions table (issue #871): per Gedra, the jobs whose tasks failed on it. Its own table -- never one
 * of the Gedra tables -- keyed by the gedra's id, and carrying the gedra's own ownership columns, so a read applies
 * a caller's scope exactly as a Gedra read does. Transactional, since two job types can record against one gedra.
 */
fun jobExceptionTables(cxt: KdrCxt): List<KdrTable> =
    tableModule(cxt, namespace = "job", topic = jobTopic) {
        table(JOBX.jobExceptions, "Per gedra, the jobs whose tasks failed on it (#871).") {
            column(JOBX.gedraId, "The gedra the failures were on.", required = true)
            column(JOB.data, "The entries: one per job type, of the jobException trait.") { type = SCT.kObject }
            primaryKey(JOBX.gedraId)
            forUsers()
            forOrg()
            index(PF.client)
            withTransactions()
        }
    }

/**
 * The jobException trait's data, declared in code (issue #871): what a job last found wrong with a gedra. Built
 * into the job schema module, so it is compiled with the rest of the schema and every write is validated against
 * it -- a job cannot record a shape nobody declared.
 */
fun SchTypesBuilder.jobExceptionEntryType() {
    type(JOBX.entryType) {
        type = SCT.kObject
        description = "What a job last found wrong with a gedra: one entry per job type."
        property(JOB.jobType, "The job whose task failed; the entry's key.", required = true)
        property(JOBX.scenario, "The code naming the logic scenario that failed.", required = true)
        property(JOBX.message, "The latest failure's message.", required = true)
        property(JOBX.count, "How many times the task has failed since the entry was created.", required = true) {
            type = SCT.integer
        }
        property(JOB.launchName, "The launch whose task last failed.")
        property(JOBX.details, "Identifying details of the gedra, as the job reported them.") {
            type = SCT.kObject
            additionalProperties = true
        }
        property(JOBX.history, "The failures before the latest, oldest first.") {
            type = SCT.array
            items {
                type = SCT.kObject
                additionalProperties = true
            }
        }
    }
}

/**
 * Records, clears, and reads per-gedra job failures (issue #871).
 *
 * Each row holds entries in the Gedra entry shape -- `traitId`, `data`, `createdAt`, `updatedAt` -- of the one
 * [JOBX.traitId] trait, keyed by job type: `createdAt` is the first failure, `updatedAt` the latest. A failure
 * that recurs updates its entry rather than adding one; a later success on the gedra removes it.
 *
 * **Gedras only** (a design decision): a resource id that is not a gedra's -- or names a gedra that no longer
 * exists -- is not recorded here. The failure is still counted and logged, and traced; it just has no gedra to be
 * shown beside.
 */
object JobExceptionRows {
    /**
     * Records a failure of [jobType]'s task on the gedra [gedraId]: a new entry, or the existing one updated with
     * a count one higher and the previous failure moved into its history. Answers false, recording nothing, when
     * [gedraId] names no gedra.
     */
    fun record(
        cxt: KdrCxt,
        jobType: String,
        gedraId: String,
        scenario: String,
        message: String,
        details: Map<String, Any?> = emptyMap(),
        launchName: String? = null,
    ): Boolean {
        val owner = ownerOf(cxt, gedraId) ?: return false
        val now = Instant.fromEpochMilliseconds(cxt.instanceNow().toEpochMilliseconds())
        inRowTran(cxt, "jobExceptionRecord", owner) { row ->
            val entries = entriesOf(row).toMutableList()
            val index = entries.indexOfFirst { it[GE.data].toJsonMapOrEmpty()[JOB.jobType] == jobType }
            val prior = if (index >= 0) entries[index] else null
            val priorData = prior?.get(GE.data).toJsonMapOrEmpty()
            val history = if (prior == null) {
                emptyList()
            } else {
                val earlier = linkedMapOf(
                    JOBX.at to prior[GE.updatedAt], JOBX.scenario to priorData[JOBX.scenario],
                    JOBX.message to priorData[JOBX.message], JOB.launchName to priorData[JOB.launchName],
                )
                (priorData[JOBX.history].toJsonListOfMaps() + earlier).takeLast(JOBX.historyLimit)
            }
            val data = linkedMapOf<String, Any?>(
                JOB.jobType to jobType,
                JOBX.scenario to scenario,
                JOBX.message to message,
                JOBX.count to (priorData[JOBX.count].toOptLong() ?: 0) + 1,
            )
            if (launchName != null) data[JOB.launchName] = launchName
            if (details.isNotEmpty()) data[JOBX.details] = details
            if (history.isNotEmpty()) data[JOBX.history] = history
            checkEntry(cxt, data)
            val entry = linkedMapOf(
                GE.traitId to JOBX.traitId,
                GE.data to data,
                GE.createdAt to (prior?.get(GE.createdAt) ?: now.fmt()),
                GE.updatedAt to now.fmt(),
            )
            if (index >= 0) entries[index] = entry else entries.add(entry)
            entries
        }
        return true
    }

    /**
     * Clears [jobType]'s entry on [gedraId], the task having since succeeded there. Answers whether there was one.
     * Reads first, so a gedra with no row does not gain an empty one.
     */
    fun clear(cxt: KdrCxt, jobType: String, gedraId: String): Boolean {
        val stored = readRow(cxt, gedraId, ReadScope.unrestricted) ?: return false
        if (entriesOf(stored).none { it[GE.data].toJsonMapOrEmpty()[JOB.jobType] == jobType }) return false
        val owner = Owner(gedraId, stored[PF.client].toOptStr() ?: return false, stored[PF.userId].toOptLong() ?: 0, stored[PF.org].toOptStr())
        var removed = false
        inRowTran(cxt, "jobExceptionClear", owner) { row ->
            val entries = entriesOf(row)
            val kept = entries.filterNot { it[GE.data].toJsonMapOrEmpty()[JOB.jobType] == jobType }
            removed = kept.size < entries.size
            kept
        }
        return removed
    }

    /** The entries recorded against [gedraId], when [scope] admits the gedra; empty otherwise. */
    fun read(cxt: KdrCxt, gedraId: String, scope: ReadScope): List<Map<String, Any?>> =
        readRow(cxt, gedraId, scope)?.let { entriesOf(it) }.orEmpty()

    /** The gedras in [client] with an entry for [jobType]: what a run clears as their tasks succeed. */
    fun gedrasWithEntries(cxt: KdrCxt, jobType: String, client: String): Set<String> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qJobExceptionsByClient", table(cxt).columns,
            "select * from t:${JOBX.jobExceptions} where c:${PF.client} = :${PF.client}",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client)) }
        return rows.filter { row -> entriesOf(row).any { it[GE.data].toJsonMapOrEmpty()[JOB.jobType] == jobType } }
            .mapNotNull { it[JOBX.gedraId].toOptStr() }
            .toSet()
    }

    // --- internals -------------------------------------------------------------------------------------------

    private class Owner(val gedraId: String, val client: String, val userId: Long, val org: String?)

    /** The owner of the gedra [gedraId] names, or null when it is not a gedra id or the gedra is gone. */
    private fun ownerOf(cxt: KdrCxt, gedraId: String): Owner? {
        val id = GedraId.parseOrNull(gedraId) ?: return null
        val kind = id.dataType ?: return null
        val row = GedraDataService.get(cxt).queryGedra(cxt, id.fullId, kind, ReadScope.unrestricted) ?: return null
        return Owner(id.fullId, row.client, row.userId, row.org)
    }

    private fun entriesOf(row: Map<String, Any?>): List<Map<String, Any?>> =
        row[JOB.data].toJsonMapOrEmpty()[JOBX.entries].toJsonListOfMaps()

    /** Refuses an entry that does not match the declared [JOBX.entryType]: a job may record only what is declared. */
    private fun checkEntry(cxt: KdrCxt, data: Map<String, Any?>) {
        val type = cxt.getSchema().types["${JOBEP.namespace}.${JOBX.entryType}"]
            ?: throw KdrException("The ${JOBX.entryType} type is not in the schema.")
        val failures = validate(type, data)
        if (failures.isNotEmpty()) {
            throw KdrException("A job exception entry does not match its declared type: ${failures.joinToString("; ") { it.toString() }}")
        }
    }

    /** Runs [change] on the row for [owner]'s gedra under its lock, storing the entries it answers. */
    private fun inRowTran(cxt: KdrCxt, tranName: String, owner: Owner, change: (Map<String, Any?>) -> List<Map<String, Any?>>) {
        // Written on a context bound to the gedra's owner, so the row's ownership columns are the gedra's.
        val ownerCxt = cxt.mkSubContext("jobException", owner.client).also { it.userId = owner.userId }
        val sqlCxt = SqlTopicService.mkSqlCxt(ownerCxt, jobTopic)
        val key = linkedMapOf<String, Any?>(JOBX.gedraId to owner.gedraId, PF.client to owner.client, PF.userId to owner.userId)
        if (owner.org != null) key[PF.org] = owner.org
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranName, null, key, tranTableName = JOBX.jobExceptions) {
            val entries = change(sqlCxt.tranData)
            sqlCxt.tranData[JOB.data] = mapOf(JOBX.entries to entries)
        }
    }

    private fun readRow(cxt: KdrCxt, gedraId: String, scope: ReadScope): Map<String, Any?>? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val table = table(cxt)
        val data = mutableMapOf<String, Any?>(JOBX.gedraId to gedraId)
        val conditions = mutableListOf("c:${JOBX.gedraId} = :${JOBX.gedraId}")
        // The scope half is composed rather than written out, so this and every Gedra read agree on what a scope
        // means; a dimension the table cannot express throws rather than widening the answer.
        conditions.addAll(SqlScopeUtil.scopeConditions(scope, table, data))
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qJobExceptionById${scope.shapeKey}", table.columns,
            "select * from t:${JOBX.jobExceptions} where ${conditions.joinToString(" and ")}",
        )
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) { row = sqlCxt.sqlDb.queryOneEnabled(cxt, stmt, data) }
        return row
    }

    private fun table(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[JOBX.jobExceptions]
        ?: throw KdrException("${JOBX.jobExceptions} table is not registered in the schema store.")
}
