package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toOptLong
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The assistant's node-wide state (issue #1525): the deployment's [settings], the [model] this node talks to -- null
 * when it has none, with the [reason] -- and the operator's switches and budgets per client, with each client's use.
 *
 * **Which model**, decided once at boot:
 * - one a test handed in under [modelConfigKey] (a [ScriptedModel]);
 * - with `KDR_ASSISTANT_PROVIDER=fake`, the built-in demo -- on a test instance only, and refused at boot elsewhere,
 *   as simulated mail is: its canned answers would pass for a broken assistant on a real deployment;
 * - otherwise Claude ([AnthropicModel]), when the secret [AssistantSettings.keySecret] names holds a key. With none,
 *   the assistant is unavailable, nothing else changes, and the operator's view names the secret it looked for.
 *
 * **Who may use it** is the operator's switch per client (`AssistantClients`), read through a table cache, since the
 * `assistant:on` cfact asks it on every cfact assembly. A sandbox goes by its parent, for the switch and the budget.
 */
class AssistantService : ServiceInitializer {
    override val serviceName: String = AssistantService.serviceName

    lateinit var settings: AssistantSettings
        private set

    /** What answers on this node, or null when nothing can ([reason] says why). */
    @Volatile
    var model: AssistantModel? = null
        private set

    /** Why [model] is null; null when it is not. */
    @Volatile
    var reason: String? = null
        private set

    /** The secret the assistant looked for and did not find, when that is why it is unavailable. */
    @Volatile
    var missingSecret: String? = null
        private set

    /** The enabled `AssistantClients` rows, or null when this node runs no table cache (then each read is SQL). */
    @Volatile
    private var clientCache: SqlTableCache<Map<String, Any?>>? = null

    @Volatile
    private var created = false

    @Volatile
    private var initialized = false

    val available: Boolean get() = model != null

    override fun onCreate(cxt: KdrCxt) {
        if (created) return
        val s = AssistantSettings.read(cxt)
        settings = s
        val injected = cxt.instanceConfig.get(modelConfigKey) as? AssistantModel
        when {
            injected != null -> model = injected
            s.provider == AENV.fakeProvider -> {
                if (!cxt.instanceConfig.isTestInstance) {
                    throw KdrException(
                        "Refusing to start: ${AENV.provider.name} is '${AENV.fakeProvider}' but this is not a test " +
                            "instance. The demo assistant answers every message with canned text, which on a real " +
                            "deployment would pass for a broken one -- unset ${AENV.provider.name}, or make this a test " +
                            "instance (${KdrInstanceConfig.testInstanceEnvVar.name}, in-memory only, or the unit environment).",
                    )
                }
                model = ScriptedModel.demo()
            }
            else -> {
                val key = SecretsUtil.getSecret(s.keySecret)?.trim()?.ifEmpty { null }
                if (key == null) {
                    missingSecret = s.keySecret
                    reason = "No Anthropic API key: the secret '${s.keySecret}' is not in ${SecretsUtil.secretsPath}."
                    LogStartup.info(cxt, "The workflow assistant is unavailable on this node. ${reason.orEmpty()}")
                } else {
                    model = AnthropicModel(key).also { InstanceRegistry.registerForShutdown(it) }
                }
            }
        }
        created = true
    }

    override fun checkInit(cxt: KdrCxt) {
        if (initialized) return
        clientCache = SqlTableCacheService.registerCache(
            cxt, SqlCacheParams(topic = ATBL.topic, tableName = ATBL.clients, extract = { _, data -> data }),
        )
        initialized = true
    }

    /** The client that pays for, and is switched on for, [client]'s use: its parent when it is a sandbox. */
    fun payerOf(client: String): String = sandboxParentOf(client) ?: client

    /**
     * Whether the assistant is on for [client]: this node has a model, and an operator has switched the client (a
     * sandbox's parent) on. Not whether it has budget left: the page says when a month's is spent.
     *
     * Read from the cache, which holds exactly the enabled rows, so a client that is not there is off -- the one case
     * a cache miss is the answer rather than a reason to ask SQL. With no cache, SQL.
     */
    fun isOn(cxt: KdrCxt, client: String): Boolean {
        if (model == null) return false
        val payer = payerOf(client)
        val cache = clientCache
        if (cache == null) return clientRow(cxt, payer)?.get(PF.enabled) == true
        cache.checkRefresh(cxt)
        return cache.snapshot.get(cache.idOf(payer)) != null
    }

    /** [client]'s row, switched on or off; null when an operator has never set it. Always from SQL. */
    fun clientRow(cxt: KdrCxt, client: String): Map<String, Any?>? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, ATBL.topic)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, ATBL.table(cxt, ATBL.clients))
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) { row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(ASST.client to client)) }
        return row
    }

    /** Every client's row, switched on or off. */
    fun clientRows(cxt: KdrCxt): List<Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, ATBL.topic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qAssistantClientsAll", ATBL.table(cxt, ATBL.clients).columns, "select * from t:${ATBL.clients}",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, emptyMap()) }
        return rows
    }

    /** [row]'s monthly budget: its own, else the deployment's default. */
    fun budgetOf(row: Map<String, Any?>?): Long = row?.get(ASST.monthlyBudget).toOptLong() ?: settings.defaultMonthlyBudget

    /**
     * Switches [client] on or off ([enabled]; null leaves it as it is) and sets its [monthlyBudget] -- or, with
     * [useDefaultBudget], drops its own for the deployment's default -- as the caller on [cxt]. Switching it on from off
     * needs [acknowledgedNotice]: the operator has read what is sent to the model ([DATA.notice]), and when and by whom
     * is recorded. Returns the row as written.
     */
    fun setClient(
        cxt: KdrCxt,
        client: String,
        enabled: Boolean?,
        monthlyBudget: Long?,
        useDefaultBudget: Boolean,
        acknowledgedNotice: Boolean,
    ): Map<String, Any?> {
        if (isSandboxClient(client)) {
            throw KdrException.mkInput("'$client' is a sandbox; it goes by its parent, '${payerOf(client)}'.")
        }
        if (ClientService.getOrNull(cxt)?.known(client) == null) {
            throw KdrException("No client '$client' is known here.", code = EXC.notFound)
        }
        if (monthlyBudget != null && useDefaultBudget) {
            throw KdrException.mkInput("Give a ${ASST.monthlyBudget} or ask for the default budget, not both.")
        }
        if (monthlyBudget != null && monthlyBudget < 1) {
            throw KdrException.mkInput("A ${ASST.monthlyBudget} must be at least 1, not $monthlyBudget.")
        }
        val prior = clientRow(cxt, client)
        val wasOn = prior?.get(PF.enabled) == true
        val on = enabled ?: wasOn
        if (on && !wasOn && !acknowledgedNotice) {
            throw KdrException.mkInput(
                "Switching the assistant on for '$client' sends its administrators' messages, and what the assistant " +
                    "reads for them, to Anthropic. Acknowledge that with ${ASST.acknowledgeDataNotice}.",
            )
        }
        // A read-modify-write, so the row's prior `updatedAt` goes through and the cache sees the change.
        val row = LinkedHashMap(prior ?: mapOf(ASST.client to client))
        if (monthlyBudget != null) row[ASST.monthlyBudget] = monthlyBudget
        if (useDefaultBudget) row[ASST.monthlyBudget] = null
        if (on && !wasOn) {
            row[ASST.noticeAcknowledgedAt] = cxt.now()
            row[ASST.noticeAcknowledgedBy] = cxt.userProfile.userId
        }
        val table = ATBL.table(cxt, ATBL.clients)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, ATBL.topic)
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        row[PF.enabled] = on
        val stmt = if (prior == null) SqlTopicUtil.mkTableInsertStmt(sqlCxt, table) else SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, row) }
        return row
    }

    /**
     * Adds what [model] was billed for one response ([usage]) to [client]'s use this month -- its parent's, for a
     * sandbox -- in one statement, so two calls finishing together both count. Returns the weighted tokens added.
     * Called outside any transaction, as a model call is: the month's first call may try an insert that loses a race.
     */
    fun recordUsage(cxt: KdrCxt, client: String, model: String, usage: ModelUsage): Long {
        val weighted = usage.weighted(model)
        val table = ATBL.table(cxt, ATBL.usage)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, ATBL.topic)
        val now = cxt.now()
        val bind = linkedMapOf<String, Any?>(
            ASST.client to payerOf(client), ATBL.yearMonth to monthOf(now), ASST.used to weighted,
            ASST.inputTokens to usage.inputTokens, ASST.cacheWriteTokens to usage.cacheWriteTokens,
            ASST.cacheReadTokens to usage.cacheReadTokens, ASST.outputTokens to usage.outputTokens,
            ASST.calls to 1L, ASST.lastUsedAt to now,
        )
        val adds = listOf(ASST.used, ASST.inputTokens, ASST.cacheWriteTokens, ASST.cacheReadTokens, ASST.outputTokens, ASST.calls)
            .joinToString(", ") { "c:$it = c:$it + :$it" }
        val add = SqlTopicUtil.mkPartialUpdateStmt(
            sqlCxt, table, "uAssistantUsageAdd", "$adds, c:${ASST.lastUsedAt} = :${ASST.lastUsedAt}",
            "c:${ASST.client} = :${ASST.client} and c:${ATBL.yearMonth} = :${ATBL.yearMonth}",
        )
        SqlTopicUtil.prepForStdUpdate(cxt, table, bind, prior = null)
        sqlCxt.sqlDb.withSession(cxt) {
            if (sqlCxt.sqlDb.executeStatement(cxt, add, bind) == 0) {
                // The month's first call. Another may make the row first; then this one adds to it instead.
                val first = LinkedHashMap(bind).also { SqlTopicUtil.prepForStdExecute(cxt, table, it) }
                val insertFailure = runCatching {
                    sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, table), first)
                }.exceptionOrNull()
                if (insertFailure != null && sqlCxt.sqlDb.executeStatement(cxt, add, bind) == 0) {
                    throw KdrException("Could not record the assistant's use for '${payerOf(client)}'.", insertFailure)
                }
            }
        }
        return weighted
    }

    /** Every client's use in [month], keyed by client. */
    fun usageIn(cxt: KdrCxt, month: String): Map<String, Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, ATBL.topic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qAssistantUsageOfMonth", ATBL.table(cxt, ATBL.usage).columns,
            "select * from t:${ATBL.usage} where c:${ATBL.yearMonth} = :${ATBL.yearMonth}",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(ATBL.yearMonth to month)) }
        return rows.associateBy { it[ASST.client].toString() }
    }

    /** What the operator's listing says about this node, beside the clients. */
    fun status(): Map<String, Any?> = buildMap {
        put(ASST.available, available)
        put(ASST.provider, model?.label ?: settings.provider)
        put(ASST.model, settings.model)
        reason?.let { put(ASST.reason, it) }
        missingSecret?.let { put(ASST.missingSecret, it) }
        put(ASST.defaultMonthlyBudget, settings.defaultMonthlyBudget)
        put(ASST.dataNotice, DATA.notice)
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "AssistantService"

        /** The instance-config key a test hands in its own [AssistantModel] under. */
        const val modelConfigKey = "assistantModel"

        fun get(cxt: KdrCxt): AssistantService =
            getOrNull(cxt) ?: throw KdrException("The $serviceName is not available on this node.")

        fun getOrNull(cxt: KdrCxt): AssistantService? = cxt.instanceConfig.get(serviceName) as? AssistantService

        /** The calendar month [at] falls in, as `YYYY-MM` in UTC: what a client's use is kept by. */
        fun monthOf(at: Instant): String = at.toLocalDateTime(TimeZone.UTC).date.toString().take(7)
    }
}

/** What the operator acknowledges when switching the assistant on for a client. */
@Suppress("ConstPropertyName")
object DATA {
    const val notice = "When this client's administrators use the workflow assistant, what they write, the conversation " +
        "so far, and what the assistant reads for them through this deployment's endpoints -- the client's configuration, " +
        "workflows, traits, and the documentation -- are sent to Anthropic to produce each answer. The assistant reads " +
        "no form data, users, or keys, and it only drafts in the client's sandbox: a person publishes."
}
