package com.dynamicruntime.common.counter

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.LogGedra
import com.dynamicruntime.common.gedra.configOwnerOf
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.ScriptZone
import com.dynamicruntime.common.util.checkTemplateSyntax
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.serverTimeZone
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.zonedDayText
import kotlin.time.Instant

/** An id a counter issued (issue #1849): the [value] a field holds, and the [token] its write sends back. */
class IssuedCounterId(val value: String, val token: String)

/**
 * What a token says (issue #1849), once opened: the id issued, what it was issued from, and to whom. Sealed by
 * [NodeService.encryptString] -- authenticated encryption, so a token nobody issued, or one changed after it was, does
 * not open.
 */
class CounterToken(
    val client: String,
    val counterId: String,
    val scopeKey: String,
    val periodKey: String,
    val count: Long,
    val value: String,
    /** The acting user it was issued to: who may store it. */
    val userId: Long,
) {
    fun seal(cxt: KdrCxt): String = NodeService.get(cxt).encryptString(
        mapOf(TOK.client to client, TOK.counter to counterId, TOK.scope to scopeKey, TOK.period to periodKey, TOK.count to count, TOK.value to value, TOK.user to userId)
            .toJsonStr(compact = true),
    )

    companion object {
        /** [sealed] opened, or a refusal: a token this deployment did not issue, or one changed since it was. */
        fun open(cxt: KdrCxt, sealed: String): CounterToken {
            val map = try {
                NodeService.get(cxt).decryptString(sealed).jsonMap()
            } catch (e: Exception) {
                null
            } ?: throw KdrException.mkInput("A counter token was sent that this deployment did not issue, or that was changed after it was.")
            return CounterToken(
                client = map[TOK.client].toOptStr().orEmpty(),
                counterId = map[TOK.counter].toOptStr().orEmpty(),
                scopeKey = map[TOK.scope].toOptStr().orEmpty(),
                periodKey = map[TOK.period].toOptStr().orEmpty(),
                count = map[TOK.count].toOptLong() ?: 0,
                value = map[TOK.value].toOptStr().orEmpty(),
                userId = map[TOK.user].toOptLong() ?: 0,
            )
        }
    }
}

/** The keys inside a sealed token: short, since every one travels in each write that stores an id. */
@Suppress("ConstPropertyName")
private object TOK {
    const val client = "c"
    const val counter = "k"
    const val scope = "s"
    const val period = "p"
    const val count = "n"
    const val value = "v"
    const val user = "u"
}

/**
 * A client's counters (issue #1849), and the ids they issue.
 *
 * **A registry per client**, built from the configurations the client holds as a file ref's is (`FileRefService`): at
 * boot, again on each reload of the client, and over a write's scratch configurations in its trial, so a counter that is
 * no good is refused as a write and dropped, with an issue, from a load. A stored counter stands over the code's of the
 * same id. A sandbox holds its parent's counters, copied with its configuration, and counts in rows of its own.
 *
 * **Issuing** ([issue]) takes the counter's row for the client, its scope (none yet), and the period -- the client's
 * day for a daily counter -- under that row's own lock, adds one to its count, and answers with the id and a sealed
 * token. Nothing else is stored, so an id that is never used is a gap, and there is nothing to clean up.
 *
 * **Using** an id is [CounterClaimHook]'s: the write that stores it must send its token, and a claim row in the write's
 * own transaction keeps any one id from being stored twice.
 *
 * Theme: #CFGFG.
 */
class CounterService : ServiceInitializer {
    override val serviceName: String = CounterService.serviceName

    @Volatile
    private var registries: Map<String, Map<String, CounterDef>> = emptyMap()

    override fun checkInit(cxt: KdrCxt) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.checkInit ran with no schema collector.")
        val configs = collector.gedraConfigs.configs
        registries = clientsJudged(configs).associateWith { build(cxt, configs, it) }.filterValues { it.isNotEmpty() }
    }

    /**
     * Rebuilds [client]'s counters off the current collector and swaps them in: a step of the client's reload.
     *
     * Themes: #CFGFG, #RELSY.
     */
    fun reloadClient(cxt: KdrCxt, client: String) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val rebuilt = build(cxt, collector.gedraConfigs.configs, client)
        registries = if (rebuilt.isEmpty()) registries - client else registries + (client to rebuilt)
    }

    /** [client]'s candidate counters judged over a trial's [scratch] collector: problems to the trial's capture, nothing swapped in. */
    fun trialClient(cxt: KdrCxt, scratch: SchemaCollector, client: String) {
        build(cxt, scratch.gedraConfigs.configs, client)
    }

    /** [client]'s counters, by id; none for a client that has none. */
    fun forClient(client: String): Map<String, CounterDef> = registries[client].orEmpty()

    /**
     * The next id of [counterId] for [client] (issue #1849), issued to the acting user: the counter's count for the
     * client's current period, plus one, under the row's lock. Refused for a counter the client does not have (404) or
     * has disabled. [at] is the moment it is issued at -- the request's, unless a caller (a test) says another -- and
     * decides the day.
     */
    fun issue(cxt: KdrCxt, client: String, counterId: String, at: Instant = cxt.instanceNow()): IssuedCounterId {
        val def = forClient(client)[counterId]
            ?: throw KdrException("Client '$client' has no counter '$counterId'.", code = EXC.notFound)
        if (!def.enabled) {
            throw KdrException.mkInput("The counter '$counterId' is disabled, so it issues no ids.")
        }
        // Worked out once, and used both for the period and for the prefix: a template reading the clock itself could
        // put one day's prefix on the next day's count, at midnight.
        val zone = ClientService.getOrNull(cxt)?.timeZoneOf(client) ?: serverTimeZone
        val day = zonedDayText(at, ScriptZone.of(zone))
        val periodKey = if (def.resetsDaily) day else CNT.none
        val count = increment(cxt, client, counterId, CNT.none, periodKey)
        val value = def.valueFor(day, count)
        val token = CounterToken(client, counterId, CNT.none, periodKey, count, value, cxt.userProfile.userId).seal(cxt)
        LogGedra.info(cxt) { "Counter '$counterId' of client '$client' issued '$value' to user ${cxt.userProfile.userId}." }
        return IssuedCounterId(value, token)
    }

    /** One more than the row's last count, written back under its lock: the row is made, at 1, the first time. */
    private fun increment(cxt: KdrCxt, client: String, counterId: String, scopeKey: String, periodKey: String): Long {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, formCounterTopic)
        var count = 0L
        val key = mapOf(PF.client to client, CNT.counterId to counterId, CNT.scopeKey to scopeKey, CNT.periodKey to periodKey)
        sqlCxt.sqlDb.withSession(cxt) {
            SqlTopicTranProvider.executeTopicTran(sqlCxt, CNT.tranIssue, null, key, tranTableName = CNT.formCounters) {
                count = (sqlCxt.tranData[CNT.lastCount].toOptLong() ?: 0L) + 1
                sqlCxt.tranData[CNT.lastCount] = count
            }
        }
        return count
    }

    /**
     * The clients a load judges: each that holds a counter, and each whose types name one. Not a global configuration's
     * types: one naming a counter is shared, and each client that takes it defines the counter itself.
     */
    private fun clientsJudged(configs: List<GedraConfig>): List<String> =
        configs.filter { it.counters.isNotEmpty() || (it.gedraId.client != GID.globalClient && namedCounters(it).isNotEmpty()) }
            .map { it.gedraId.client }.distinct()

    /**
     * [client]'s counters, judged as a load judges them: a counter that is no good is reported and left out, the
     * first declaration of an id kept -- a stored one before the code's -- and a field naming a counter the client
     * does not have, or naming one where no id is filled, reported.
     */
    private fun build(cxt: KdrCxt, configs: List<GedraConfig>, client: String): Map<String, CounterDef> {
        val owner = configOwnerOf(client)
        val issues = mutableListOf<GedraConfigIssue>()
        val kept = LinkedHashMap<String, CounterDef>()
        // In an order of their own, as file refs are: a load and a trial read a client's configurations in different
        // orders, and which declaration of an id is kept must come out the same in both.
        val mine = configs.filter { it.gedraId.client == client }
        val holding = mine.filter { it.counters.isNotEmpty() }
            .sortedWith(compareBy({ config -> if (config.counters.any { it.stored }) 0 else 1 }, { it.gedraId.fullId }))
        for (config in holding) {
            for (def in config.counters) {
                val holder = kept[def.counterId]
                if (holder != null && holder.stored && !def.stored) continue
                val problem = defProblem(config, def, owner, holder != null)
                if (problem != null) {
                    reportConfigProblem(cxt, config.issue(problem, "Leaving the counter out; the rest of the configuration loads.", GCEL.counter, def.counterId), issues)
                    continue
                }
                kept[def.counterId] = def
            }
        }
        for (config in mine.filter { it.gedraId.client != GID.globalClient }) {
            for (named in namedCounters(config)) {
                val where = "Field '${named.field}' of type '${named.typeName}' (client '$owner', config '${config.name}')"
                val problem = when {
                    !named.topLevel ->
                        "$where names the counter '${named.counterId}' inside a nested object or a list, where no id is filled. " +
                            "A counter numbers a field of a trait's own data."
                    named.counterId !in kept ->
                        "$where names the counter '${named.counterId}', which the client does not have. Its forms cannot be " +
                            "given an id until it does."
                    else -> null
                } ?: continue
                reportConfigProblem(cxt, config.issue(problem, "Keeping the field; it is given no id.", GCEL.counter, named.counterId), issues)
            }
        }
        return kept
    }

    /**
     * What stops [def], declared by [config], from issuing ids, or null; [taken] is whether its id is already kept.
     * Worded by [owner], the client the configuration is stored under, so the same problem reads the same whichever
     * client's load found it.
     */
    private fun defProblem(config: GedraConfig, def: CounterDef, owner: String, taken: Boolean): String? {
        if (!CTR.idRegex.matches(def.counterId)) {
            return "Config '${config.name}' of client '$owner' declares a counter whose id '${def.counterId}' is not one: ${CTR.idRule}."
        }
        val counter = "Counter '${def.counterId}' of client '$owner' (config '${config.name}')"
        if (config.gedraId.client == GID.globalClient) {
            return "$counter is declared by a global configuration. A counter is a client's own: its counts are kept per client."
        }
        if (def.digits !in CTR.minDigits..CTR.maxDigits) {
            return "$counter pads its count to ${def.digits} digits; it must be ${CTR.minDigits} to ${CTR.maxDigits}."
        }
        if (def.reset !in CTR.resets) {
            return "$counter resets '${def.reset}', which is not one of ${CTR.resets.joinToString { "'$it'" }}."
        }
        prefixProblem(def)?.let { return "$counter $it" }
        return if (taken) "$counter is declared more than once among the client's configurations. The first is kept." else null
    }

    /**
     * What is wrong with [def]'s prefix, as the rest of a sentence about the counter, or null: a template that does not
     * parse or does not render, or -- for a daily counter -- one that renders the same for two days, whose ids would
     * then repeat from one day to the next.
     */
    private fun prefixProblem(def: CounterDef): String? {
        def.prefix.checkTemplateSyntax().firstOrNull()?.let { return "has a prefix that is not a template that parses: ${it.message}" }
        val (first, second) = try {
            def.prefixFor(sampleDays.first) to def.prefixFor(sampleDays.second)
        } catch (e: KdrException) {
            return "has a prefix that does not render: ${e.message}"
        }
        if (def.resetsDaily && first == second) {
            return "starts its count again each day, but its prefix ('$first') does not change with the day, so its ids would " +
                "repeat. Put the day in the prefix, with \${${CTR.day}} or \${${CTR.compactDay}}."
        }
        return null
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "CounterService"

        /** Two days a prefix is rendered for, to see that it renders, and that a daily one changes with the day. */
        private val sampleDays = "2026-01-30" to "2026-02-01"

        fun get(cxt: KdrCxt): CounterService =
            getOrNull(cxt) ?: throw KdrException("The $serviceName is not available on this node.")

        /** The service, or null on a node that runs no forms (an edge). */
        fun getOrNull(cxt: KdrCxt): CounterService? = cxt.instanceConfig.get(serviceName) as? CounterService
    }
}

/** A field of a configuration's type that names a counter (issue #1849), and whether it is one of the type's own. */
private class NamedCounter(val typeName: String, val field: String, val counterId: String, val topLevel: Boolean)

/**
 * The fields [config]'s types name a counter in, read from the raw bodies so a type the schema would refuse is still
 * judged: a type's own properties, and anywhere deeper.
 */
private fun namedCounters(config: GedraConfig): List<NamedCounter> = buildList {
    fun walk(typeName: String, node: Any?, path: List<String>) {
        when (node) {
            is Map<*, *> -> {
                val id = node[SCH.counter] as? String
                if (id != null && path.size >= 2 && path[path.size - 2] == SCH.properties) {
                    add(NamedCounter(typeName, path.last(), id, topLevel = path.size == 2))
                }
                for ((k, v) in node) if (k is String && k != SCH.counter) walk(typeName, v, path + k)
            }
            is List<*> -> node.forEachIndexed { i, v -> walk(typeName, v, path + i.toString()) }
        }
    }
    for ((typeName, body) in config.defs) walk(typeName, body.toJsonMapOrEmpty(), emptyList())
}
