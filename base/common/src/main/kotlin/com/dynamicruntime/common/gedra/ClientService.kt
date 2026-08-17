package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * Which clients this deployment knows, which of them are present here, and what each one declared (issue
 * #343).
 *
 * A **startup** service, and ahead of `SchemaService` in that tier, because the direction of travel is that
 * schema compilation becomes per-client: the store gets a variant per client, and a variant cannot be built
 * before it is known which clients there are. Nothing depends on that ordering yet, which is exactly when it
 * is cheap to establish.
 *
 * Two questions, deliberately kept apart:
 *
 * - **known** ([clients]) -- declared somewhere in this deployment's configs, whether or not this node runs
 *   it. An administrator asking why a client is not working needs to be able to see a client that is not
 *   working.
 * - **present** ([isPresent]) -- enabled in *this* environment. This is the operational question, and the one
 *   everything later gates on: a client that is not present behaves as though it were not there, its users
 *   cannot get in, and its content cannot be read.
 *
 * The distinction is testable today without a database or a configuration reload: a client defined in source code
 * and not enabled in `unit` is precisely a client that is known and not present.
 *
 * **Nothing consults this yet except the administrative listing.** That is the point of a declaration-first
 * slice -- the refusals become easy to trust before anything depends on them.
 */
class ClientService : ServiceInitializer {
    override val serviceName: String = ClientService.serviceName

    private var collector: SchemaCollector? = null
    private var isInit: Boolean = false

    /** Every client this deployment declares, keyed by id, in contribution order. Present or not. */
    var clients: Map<String, ClientDef> = emptyMap()
        private set

    /** Client definitions that did not hold up. Empty unless a production node degraded; see [checkClientDefs]. */
    var issues: List<GedraConfigIssue> = emptyList()
        private set

    /** The clients enabled in this environment, in contribution order. */
    val presentClients: List<ClientDef> get() = clients.values.filter { it.isEnabledIn(env) }

    private var env: String = ""

    /**
     * Whether [clientId] is a client this node carries.
     *
     * The cheapest gate there is -- a map lookup, answerable before any database access -- which is what makes
     * it usable as a check on a `GedraId` before anything is read. A false answer means *absent*, not
     * forbidden: it joins the states a read path collapses into a single 404.
     */
    fun isPresent(clientId: String): Boolean = clients[clientId]?.isEnabledIn(env) == true

    /** [clientId]'s definition when this node carries it, and null when it does not. */
    fun present(clientId: String): ClientDef? = clients[clientId]?.takeIf { it.isEnabledIn(env) }

    /** [clientId]'s definition whether or not this node carries it; null when nothing declares it. */
    fun known(clientId: String): ClientDef? = clients[clientId]

    override fun onCreate(cxt: KdrCxt) {
        collector = SchemaCollector.get(cxt)
            ?: throw KdrException("Schema collector was not created for $serviceName.")
    }

    override fun checkInit(cxt: KdrCxt) {
        if (isInit) {
            return
        }
        val collected = collector ?: throw KdrException("$serviceName.checkInit ran before onCreate.")
        env = cxt.instanceConfig.env
        val result = checkClientDefs(cxt, collected.gedraConfigs)
        clients = result.clients
        issues = result.issues
        val present = presentClients.map { it.clientId }
        LogStartup.info(
            cxt,
            "Clients declared: ${clients.keys.joinToString(", ").ifEmpty { "(none)" }}; " +
                "present in '$env': ${present.joinToString(", ").ifEmpty { "(none)" }}.",
        )
        isInit = true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "ClientService"

        /** The service, or null on a node that does not run it. */
        fun get(cxt: KdrCxt): ClientService? = cxt.instanceConfig.get(serviceName) as? ClientService

        /** The service, or a fault naming it -- for a caller that cannot proceed without one. */
        fun require(cxt: KdrCxt): ClientService = get(cxt)
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
