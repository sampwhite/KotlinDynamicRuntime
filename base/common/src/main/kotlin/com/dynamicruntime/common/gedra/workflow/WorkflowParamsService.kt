package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.LogGedra
import com.dynamicruntime.common.gedra.gedraConfigTopic
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong

/** Table and column names for a workflow's parameters (issue #1356). */
@Suppress("ConstPropertyName")
object WPT {
    const val workflowParams = "WorkflowParams"

    /** The workflow the row is for, in its client (the row's `client`): the scope it is set in. */
    const val workflowId = "workflowId"

    /** The values in force ([WfParams]). */
    const val params = "params"

    /** The earlier settings, under [settings]: a JSON object column, since a list column is stored as text. */
    const val history = "history"

    /** In [history]: the earlier settings, newest first ([WfParamsSetting]). */
    const val settings = "settings"

    /** The transaction a save holds the client's configuration lock under. */
    const val tranSave = "saveWorkflowParams"
}

/**
 * The table of a workflow's parameters: one row per scope (a client, or a sandbox) and workflow id. It is in the
 * configuration's topic, so a save takes the client's configuration lock, and a change spanning a workflow's
 * definition and its parameters can be made in one transaction -- while the parameters stay what they are: in force
 * at once, and never published.
 */
fun workflowParamsTables(cxt: KdrCxt): List<KdrTable> =
    tableModule(cxt, namespace = "workflowParams", topic = gedraConfigTopic) {
        table(WPT.workflowParams, "A workflow's parameters in one client or sandbox, and their earlier settings (issue #1356).") {
            column(WPT.workflowId, "The workflow the parameters are for.", required = true)
            column(WPT.params, "The values the scope sets: a workflow's time windows, and who sees it.", required = true) { type = SCT.kObject }
            column(WPT.history, "The earlier settings, newest first, each with when and by whom.", required = true) { type = SCT.kObject }
            forClient()
            primaryKey(PF.client, WPT.workflowId)
        }
    }

/** One stored row, read once: the scope's own setting and the earlier ones, newest first. Immutable, so it is shared. */
class WorkflowParamsRow(val current: WfParamsSetting, val history: List<WfParamsSetting>) {
    companion object {
        fun of(row: Map<String, Any?>): WorkflowParamsRow = WorkflowParamsRow(
            WfParamsSetting(WfParams.fromInfo(row[WPT.params]), row[PF.updatedAt].toOptInstant(), row[PF.updatedBy].toOptLong()),
            WfParamsSetting.listFrom(row[WPT.history].toJsonMapOrEmpty()[WPT.settings]),
        )
    }
}

/**
 * A workflow's parameters as one scope runs them (issues #1356, #1386): the settings of each layer that may set them,
 * the scope's earlier ones, and what the layers make together ([layers]) -- each parameter the scope's own, else, on a
 * sandbox, its client's, else the default the client's source code declares, else no limit.
 */
class ResolvedWorkflowParams(
    /** The scope's own setting, or null while it has none. */
    val own: WfParamsSetting?,
    /** The scope's earlier settings, newest first. */
    val history: List<WfParamsSetting>,
    /** On a sandbox: its client's own setting, or null while the client has none (and always for a client). */
    val parent: WfParamsSetting?,
    /** The defaults the client's source code declares for the workflow; none set when it declares none. */
    val source: WfParams,
) {
    val layers: WfParamsLayers = WfParamsLayers(own?.params ?: WfParams.none, parent?.params ?: WfParams.none, source)

    /** The windows the workflow runs with: what every phase check asks. */
    val windows: WfWindows get() = layers.inForce.windowsInForce

    /** Who sees the workflow (issue #1384): what [WorkflowAudience] asks. */
    val visibility: WfVisibility get() = layers.inForce.visibilityInForce
}

/**
 * A workflow's parameters (issue #1356): values that are not part of its definition -- a normal workflow's time windows,
 * and who sees a workflow of any kind (issue #1384) --
 * held per scope in a table of their own (`WorkflowParams`, one row per client and workflow id) and in force the moment
 * they are saved: no draft, no publish, and a publish never carries them.
 *
 * **Per scope, per parameter.** A row holds the parameters its scope sets, and each one it does not set follows the
 * fallback ([ResolvedWorkflowParams]): a sandbox its client's, so its preview matches production until somebody
 * changes it there, and a client the defaults its source code declares (issue #1386, `GedraConfigBuilder.workflowParams`).
 * Nothing copies values between scopes, and a save that unsets a parameter returns it to the fallback.
 *
 * **Read from memory.** The table is cached ([WorkflowParamsCache]), each row parsed once as it loads, so the phase
 * check every workflow view, listing, and report makes is a lookup by key, and its change stream keeps every node
 * current.
 *
 * **Written under the client's configuration lock** ([GedraConfigService.withClientLock]). Saves of one client's
 * parameters are ordered with each other and with writes to its configuration, so no save is lost to a concurrent
 * one, and a change to both can be made in one transaction.
 *
 * **History.** A save that changes the values pushes the values it replaces -- with when and by whom they were set,
 * the row's own `updatedAt` and `updatedBy` -- onto the front of the row's history, which keeps [WFP.historyKept]. A
 * save that changes nothing writes nothing. Each change is logged in full.
 *
 * **A row outlives its workflow.** Removing a workflow leaves its parameters and their history, as it leaves every
 * other stored trace of it: a workflow made again under the same id runs with the windows the old one had.
 *
 * The caller judges what a save may change -- windows on a normal workflow only, and that nest -- and what it would do to forms
 * engaged with the workflow (`ClientWorkflowEdit.saveParams`); this keeps the values.
 */
class WorkflowParamsService : ServiceInitializer {
    override val serviceName: String = WorkflowParamsService.serviceName

    private var cache: SqlTableCache<WorkflowParamsRow>? = null

    override fun checkInit(cxt: KdrCxt) {
        cache = SqlTableCacheService.registerCache(cxt, WorkflowParamsCache.params())
    }

    private fun table(cxt: KdrCxt): KdrTable = cxt.getGlobalSchema().tables[WPT.workflowParams]
        ?: throw KdrException("The ${WPT.workflowParams} table is not registered in the schema store.")

    /**
     * [client]'s own row for [workflowId], or null when it has none: from memory, or the database on a node with no
     * cache service.
     *
     * **A miss in memory is the answer, not a reason to query**, unlike the caches whose misses fall back to SQL. Here
     * "no row" is the routine answer -- most workflows have no parameters set -- and is asked inside a form's own
     * transaction (its state is derived there), where a fallback is refused (issue #1266). The table is loaded whole
     * and never disables a row, so a loaded cache holds every row there is; a row another node has just written
     * arrives with the cache's next refresh, as any of its changes do.
     */
    private fun rowOf(cxt: KdrCxt, client: String, workflowId: String): WorkflowParamsRow? {
        cache?.let { c ->
            c.checkRefresh(cxt)
            return c.snapshot.get(c.idOf(client, workflowId))?.value
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) { row = select(cxt, sqlCxt, client, workflowId) }
        return row?.let(WorkflowParamsRow::of)
    }

    private fun select(cxt: KdrCxt, sqlCxt: SqlCxt, client: String, workflowId: String): Map<String, Any?>? =
        sqlCxt.sqlDb.queryOneStatement(
            cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table(cxt)), mapOf(PF.client to client, WPT.workflowId to workflowId),
        )

    /**
     * [workflowId]'s parameters as [client] runs them, each layer that may set them with what they make together
     * ([ResolvedWorkflowParams]). The source defaults are those of [client]'s own workflow registry, which a sandbox's
     * copies from its client's source configuration. [client] null (the global registry, which no form runs) has none.
     */
    fun resolve(cxt: KdrCxt, client: String?, workflowId: String): ResolvedWorkflowParams {
        if (client == null) return none
        val own = rowOf(cxt, client, workflowId)
        val parent = sandboxParentOf(client)?.let { rowOf(cxt, it, workflowId) }
        val source = WorkflowService.get(cxt).forClient(client).paramDefaults[workflowId] ?: WfParams.none
        return ResolvedWorkflowParams(own?.current, own?.history.orEmpty(), parent?.current, source)
    }

    /** The windows [workflowId] runs with in [client] ([resolve]): what every phase check asks. */
    fun windowsFor(cxt: KdrCxt, client: String?, workflowId: String): WfWindows = resolve(cxt, client, workflowId).windows

    /** Who sees [workflowId] in [client] ([resolve], issue #1384): what [WorkflowAudience] asks. */
    fun visibilityFor(cxt: KdrCxt, client: String?, workflowId: String): WfVisibility = resolve(cxt, client, workflowId).visibility

    /**
     * Changes [client]'s own parameters for [workflowId]: those [edit] sets replace its own, and those named in [unset]
     * are no longer its own, so they follow the fallback again ([WfParams.edited]). What they replace is kept in the
     * history. Returns false, writing nothing, when that leaves its own values as they were. A sandbox saving values for
     * the first time gets a row of its own, whatever its client's say. The row is read and written under the client's
     * configuration lock, on its transaction's connection.
     */
    fun save(cxt: KdrCxt, client: String, workflowId: String, edit: WfParams, unset: Set<String> = emptySet()): Boolean =
        GedraConfigService.get(cxt).withClientLock(cxt, client, WPT.tranSave) { sqlCxt ->
            // The transaction's own context: its session holds the connection, and it acts as the caller does.
            val tcxt = sqlCxt.cxt
            val table = table(tcxt)
            val existing = select(tcxt, sqlCxt, client, workflowId)
            val before = existing?.let { WfParams.fromInfo(it[WPT.params]) }
            val params = (before ?: WfParams.none).edited(edit, unset)
            if (before == params || (before == null && params.isEmpty)) return@withClientLock false
            val history = existing?.let { e ->
                val replaced = WfParamsSetting(before ?: WfParams.none, e[PF.updatedAt].toOptInstant(), e[PF.updatedBy].toOptLong())
                (listOf(replaced) + WfParamsSetting.listFrom(e[WPT.history].toJsonMapOrEmpty()[WPT.settings])).take(WFP.historyKept)
            }.orEmpty()
            // Carried through from the row it replaces, so `updatedAt` advances past it and the cache sees the change.
            val row = LinkedHashMap(existing ?: mapOf(PF.client to client, WPT.workflowId to workflowId))
            row[WPT.params] = params.toInfo()
            row[WPT.history] = mapOf(WPT.settings to history.map { it.toInfo() })
            SqlTopicUtil.prepForStdExecute(tcxt, table, row)
            val stmt = if (existing != null) SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table) else SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
            sqlCxt.sqlDb.executeStatement(tcxt, stmt, row)
            LogGedra.info(tcxt) {
                "Workflow '$workflowId' of client '$client': parameters set by user ${tcxt.userProfile.userId}, from " +
                    "${(before ?: WfParams.none).toInfo()} to ${params.toInfo()}."
            }
            true
        }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "WorkflowParamsService"

        private val none = ResolvedWorkflowParams(null, emptyList(), null, WfParams.none)

        fun get(cxt: KdrCxt): WorkflowParamsService =
            getOrNull(cxt) ?: throw KdrException("The $serviceName is not available on this node.")

        /** The service, or null on a node that runs no forms (an edge), where every workflow has no parameters. */
        fun getOrNull(cxt: KdrCxt): WorkflowParamsService? = cxt.instanceConfig.get(serviceName) as? WorkflowParamsService
    }
}

/**
 * The in-memory copy of `WorkflowParams` (issue #1356): read by primary key, the scope and the workflow id, each row
 * parsed once as it loads.
 */
object WorkflowParamsCache {
    fun params(): SqlCacheParams<WorkflowParamsRow> = SqlCacheParams(
        topic = gedraConfigTopic,
        tableName = WPT.workflowParams,
        extract = { _, data -> WorkflowParamsRow.of(data) },
    )
}
