package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.UserService

/**
 * A client's plan, held to (issue #1109). A plan limits **content, never features**, and going over refuses only
 * what would add to it: nothing is ever deleted, and a client already over -- its customer moved to a smaller plan,
 * say -- may still change and remove what it has.
 *
 * - **Forms and users** are counted when one is created ([requireRoomForForm], [requireRoomForUser]): a soft limit,
 *   with the small race two creates at once leave.
 * - **Workflows, tasks, schemas, and attributes** are judged by a configuration write's trial ([requireConfigWithin]),
 *   against the candidate configuration as a whole.
 *
 * A sandbox is on its parent's plan and holds its own forms and users, counted apart from the parent's.
 */
object PlanLimits {
    /**
     * The plan [client] is on (a [PLAN] name), or null for one on none -- a platform or test client, which no customer
     * owns and is not self-serve. [def] is the client's definition: the one this node runs unless a trial supplies the
     * candidate's. A customer's names its plan, and one naming none is unlimited.
     */
    fun planOf(cxt: KdrCxt, client: String, def: ClientDef? = ClientService.get(cxt).known(client)): String? {
        sandboxParentOf(client)?.let { return planOf(cxt, it) }
        def?.customerId?.let { id -> ClientService.get(cxt).customers[id]?.let { return PLAN.named(it.def.plan) } }
        return if (isSelfServeClient(client)) PLAN.free else null
    }

    /** What [client]'s plan allows; [PlanQuotas.none] for a client on none. */
    fun quotasOf(cxt: KdrCxt, client: String): PlanQuotas = planOf(cxt, client)?.let { PLAN.quotasOf(it) } ?: PlanQuotas.none

    /** Refuses a new form in [client] when it holds as many as its plan allows. */
    fun requireRoomForForm(cxt: KdrCxt, client: String) {
        val limit = quotasOf(cxt, client).forms ?: return
        val held = GedraDataService.get(cxt).countLiveGedras(cxt, GedraDataType.formDoc, client)
        if (held >= limit) throw full(cxt, client, "$held forms, as many as its plan allows ($limit)")
    }

    /** Refuses a new (or re-enabled) user in [client] when it has as many as its plan allows. */
    fun requireRoomForUser(cxt: KdrCxt, client: String) {
        val limit = quotasOf(cxt, client).users ?: return
        val held = UserService.get(cxt).countActiveUsers(cxt, client).total
        if (held >= limit) throw full(cxt, client, "$held users, as many as its plan allows ($limit)")
    }

    private fun full(cxt: KdrCxt, client: String, what: String) =
        KdrException.mkInput("Client '$client' is on the ${planOf(cxt, client)} plan and holds $what.")

    /** What [client]'s own configuration in [collector] holds that a plan counts. */
    fun configUsageOf(collector: SchemaCollector, client: String): ConfigUsage =
        ConfigUsage.of(collector.gedraConfigs.configs.filter { it.gedraId.client == client }, collector.defs.keys)

    /**
     * Refuses a configuration change to [client] whose [candidate] usage takes a measure over its plan **and** past
     * what the client holds now -- so a client already over may still change, and shrink, what it has. [def] is the
     * candidate's definition, which decides the plan for a client the change creates.
     */
    fun requireConfigWithin(cxt: KdrCxt, client: String, def: ClientDef?, candidate: ConfigUsage) {
        val plan = planOf(cxt, client, def ?: ClientService.get(cxt).known(client)) ?: return
        val quotas = PLAN.quotasOf(plan)
        if (!quotas.isLimited) return
        val current = SchemaCollector.get(cxt)?.let { configUsageOf(it, client) } ?: ConfigUsage.empty
        val over = configOverages(quotas, candidate, current)
        if (over.isEmpty()) return
        throw KdrException.mkInput(
            "Client '$client' is on the $plan plan, and this change would take it over: ${over.joinToString("; ")}.",
        )
    }

    /** Each measure [candidate] takes over [quotas] and past [current], said as a phrase. */
    internal fun configOverages(quotas: PlanQuotas, candidate: ConfigUsage, current: ConfigUsage): List<String> = buildList {
        quotas.workflows?.let { limit ->
            if (candidate.workflows > limit && candidate.workflows > current.workflows) {
                add("${candidate.workflows} workflows (the plan allows $limit)")
            }
        }
        quotas.tasksPerWorkflow?.let { limit ->
            for ((id, tasks) in candidate.tasksByWorkflow) {
                if (tasks > limit && tasks > (current.tasksByWorkflow[id] ?: 0)) add("workflow '$id' with $tasks tasks (the plan allows $limit)")
            }
        }
        quotas.schemas?.let { limit ->
            if (candidate.schemas > limit && candidate.schemas > current.schemas) {
                add("${candidate.schemas} schemas (the plan allows $limit)")
            }
        }
        quotas.attributesPerSchema?.let { limit ->
            for ((name, attributes) in candidate.attributesBySchema) {
                if (attributes > limit && attributes > (current.attributesBySchema[name] ?: 0)) {
                    add("schema '$name' with $attributes attributes (the plan allows $limit)")
                }
            }
        }
    }

    /**
     * [client]'s usage against its plan, for the Clients page -- each counted measure its plan limits -- or null for a
     * client on none. [forms] and [users] are the client's counts when the caller already holds them, as the overview
     * does, so they are not read twice.
     */
    fun usageOf(cxt: KdrCxt, client: String, forms: Int? = null, users: Int? = null): PlanUsage? {
        val plan = planOf(cxt, client) ?: return null
        val quotas = PLAN.quotasOf(plan)
        val config = SchemaCollector.get(cxt)?.let { configUsageOf(it, client) } ?: ConfigUsage.empty
        val measures = buildList {
            quotas.forms?.let {
                add(QuotaMeasure(QUOTA.forms, forms ?: GedraDataService.get(cxt).countLiveGedras(cxt, GedraDataType.formDoc, client), it))
            }
            quotas.users?.let { add(QuotaMeasure(QUOTA.users, users ?: UserService.get(cxt).countActiveUsers(cxt, client).total, it)) }
            quotas.workflows?.let { add(QuotaMeasure(QUOTA.workflows, config.workflows, it)) }
            quotas.schemas?.let { add(QuotaMeasure(QUOTA.schemas, config.schemas, it)) }
        }
        return PlanUsage(plan, measures)
    }
}
