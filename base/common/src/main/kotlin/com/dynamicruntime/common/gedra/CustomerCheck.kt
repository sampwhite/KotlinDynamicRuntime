package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.BootCheckMode

/**
 * A customer the load kept (issue #1107): its definition, and the config that declared it -- whose origin says
 * whether a source client may name it, and which holds any problem with it.
 */
class KeptCustomer(val def: CustomerDef, val config: GedraConfig) {
    /** Whether the customer was declared in source code rather than in stored configuration. */
    val isSource: Boolean get() = !config.isStored

    override fun toString(): String = "${def.customerId} (${config.gedraId})"
}

/**
 * The customers the configs declare that hold up (issue #1107), checked before the clients, which name them. Run
 * by `checkClientDefs`, beside the clients and under the same modes: what a problem does is its declaring config's
 * mode, and the degradation is dropping the **customer**, never the config.
 *
 * Judged on every load:
 * - **The id** is ASCII letters and digits, starting with a letter ([customerIdFault]): it is the prefix of its
 *   clients' ids, ended by the first `_`.
 * - **It is not reserved** ([CUST.reserved]), case aside. [CUST.kdr] is core's own customer, declared by `hub`'s
 *   source configuration, and that declaration is the one allowed it.
 * - **It is declared once**, case aside. Source declarations are taken ahead of stored ones, so a stored customer
 *   redefining a source one is the second, and goes: in production the source declaration wins, and elsewhere the
 *   clash is strict, as two definitions of one client are.
 *
 * On a **reload** -- the moment a customer arrives or leaves, which the boot has nothing to compare against -- two
 * more, for the customers this reload's own clients declare (as [only] picks out clients):
 * - **A customer new to the node** is refused when a client linked to **another** customer holds an id under its
 *   prefix: registering a prefix may not take ids somebody else's clients hold. A client linked to none may sit
 *   there, which is how an existing client is adopted into a customer registered after it.
 * - **A customer leaving** is reported when a client still uses it -- names it, or holds an id under its prefix: a
 *   prefix is permanent once a client uses it. The customer is gone all the same (it is no longer declared); the
 *   issue is what the write's trial refuses.
 *
 * Each kept customer comes back keyed by its id; one this reload does not judge is carried from [current] as the
 * node runs it.
 */
internal fun checkCustomers(
    cxt: KdrCxt,
    configs: GedraConfigCollector,
    only: Set<String>?,
    current: Map<String, KeptCustomer>,
    /** Every client the configs declare, kept or not: whose ids a new customer's prefix may not take. */
    declaredClients: List<ClientDef>,
    /** The clients the node runs now: who may still be using a customer that leaves. */
    currentClients: Map<String, ClientDef>,
    issues: MutableList<GedraConfigIssue>,
): LinkedHashMap<String, KeptCustomer> {
    fun unchecked(config: GedraConfig) = config.checkMode(cxt) == BootCheckMode.off
    fun judged(config: GedraConfig) = only == null || config.gedraId.client in only

    val kept = LinkedHashMap<String, KeptCustomer>()
    // Not this pass's to judge: carried as the node runs them, ahead of the judged ones, so a reloaded declaration
    // clashing with one of theirs is the newcomer.
    current.filterTo(kept) { !judged(it.value.config) }
    val declarations = configs.configs.flatMap { config -> config.customers.map { config to it } }
        .sortedBy { (config, _) -> if (config.isStored) 1 else 0 }
    for ((config, def) in declarations) {
        if (!judged(config)) continue
        val problem = if (unchecked(config)) null else customerProblem(config, def, kept)
        if (problem == null) {
            kept[def.customerId] = KeptCustomer(def, config)
        } else {
            reportConfigProblem(cxt, problem.heldBy(config, GCEL.customer, def.customerId), issues)
        }
    }
    if (only == null) return kept

    for ((id, customer) in kept.toList()) {
        if (id in current || !judged(customer.config) || unchecked(customer.config)) continue
        val taken = declaredClients.filter { client ->
            !isSandboxClient(client.clientId) && clientIdPrefix(client.clientId).equals(id, ignoreCase = true) &&
                client.customerId != null && client.customerId != id
        }.map { it.clientId }.distinct()
        if (taken.isNotEmpty()) {
            kept.remove(id)
            val issue = GedraConfigIssue(
                "Customer '$id' declared by '${customer.config.gedraId}' would take the prefix of " +
                    "${taken.joinToString(", ") { "'$it'" }}, which another customer's client(s) hold. A customer's " +
                    "id is the prefix of its clients' ids, so registering it may not take ids somebody else holds.",
                "Dropping the customer '$id'.",
            )
            reportConfigProblem(cxt, issue.heldBy(customer.config, GCEL.customer, id), issues)
        }
    }
    for ((id, was) in current) {
        if (!judged(was.config) || id in kept) continue
        val users = currentClients.values.filter { client ->
            !isSandboxClient(client.clientId) &&
                (client.customerId == id || clientIdPrefix(client.clientId).equals(id, ignoreCase = true))
        }.map { it.clientId }
        if (users.isEmpty()) continue
        val issue = GedraConfigIssue(
            "Customer '$id' is no longer declared, but ${users.joinToString(", ") { "'$it'" }} still use(s) it -- " +
                "naming it, or holding an id under its prefix. A customer is permanent once a client uses it.",
            "The customer is gone; its clients keep their ids and stand with no customer.",
        )
        reportConfigProblem(cxt, issue.heldBy(was.config, GCEL.customer, id), issues)
    }
    return kept
}

/** The first thing wrong with customer [def], declared by [config], beside the [kept] ones, or null. */
private fun customerProblem(
    config: GedraConfig,
    def: CustomerDef,
    kept: Map<String, KeptCustomer>,
): GedraConfigIssue? {
    val id = def.customerId
    customerIdFault(id)?.let { fault ->
        return GedraConfigIssue(
            "Customer id '$id' declared by '${config.gedraId}' $fault. A customer's id is the prefix of its clients' " +
                "ids, so it may hold only ASCII letters and digits, and must start with a letter.",
            "Dropping the customer '$id'.",
        )
    }
    val reserved = CUST.reserved.firstOrNull { it.equals(id, ignoreCase = true) }
    val isCoreOwn = id == CUST.kdr && config.gedraId.client == CL.hub && !config.isStored
    if (reserved != null && !isCoreOwn) {
        return GedraConfigIssue(
            "Customer id '$id' declared by '${config.gedraId}' is the reserved '$reserved': the platform's own " +
                "clients, roots and the self-serve prefix are never a customer's.",
            "Dropping the customer '$id'.",
        )
    }
    kept[id]?.let { first ->
        return GedraConfigIssue(
            "Customer '$id' is defined twice; '${config.gedraId}' is the second, after '${first.config.gedraId}'. " +
                "A customer is declared once, and a source declaration is never redefined by stored configuration.",
            "Keeping the first definition; '${config.gedraId}''s is dropped.",
        )
    }
    kept.keys.firstOrNull { it.equals(id, ignoreCase = true) }?.let { holder ->
        return GedraConfigIssue(
            "Customer '$id', declared by '${config.gedraId}', differs from the customer '$holder' only by case. A " +
                "customer's id is the prefix of its clients' ids, which are unique without regard to case.",
            "Keeping '$holder'; '$id' is dropped.",
        )
    }
    return null
}

/**
 * What is wrong with client [def]'s place among the [customers] (issue #1107), each a message, in the order found:
 * a link naming no customer there, a source client naming a customer only stored configuration declares, and an id
 * holding `_` whose prefix is no customer's and not [CUST.free]. None drops the client: a link is read through
 * `ClientService.customerOf`, which answers only for a customer that is there, so the client stands with no
 * customer -- and a write's trial refuses whichever of these it would introduce.
 */
internal fun customerLinkFaults(
    def: ClientDef,
    isSource: Boolean,
    customers: Map<String, KeptCustomer>,
): List<String> {
    val out = mutableListOf<String>()
    def.customerId?.let { id ->
        val customer = customers[id]
        when {
            customer == null -> out.add(
                "Client '${def.clientId}' names the customer '$id', which this deployment does not declare.",
            )
            isSource && !customer.isSource -> out.add(
                "Client '${def.clientId}' is defined in source code and names the customer '$id', which only " +
                    "stored configuration declares. A source client names a source customer, so that what it " +
                    "belongs to changes with a deployment rather than with a database write.",
            )
        }
    }
    if (isPrefixedClientId(def.clientId) && !isSandboxClient(def.clientId)) {
        val prefix = clientIdPrefix(def.clientId)
        val held = prefix.equals(CUST.free, ignoreCase = true) ||
            customers.keys.any { it.equals(prefix, ignoreCase = true) }
        if (!held) {
            out.add(
                "Client id '${def.clientId}' falls under the prefix '$prefix', which no customer holds. An id " +
                    "holding '${CUST.separator}' is a customer's ('<customer>${CUST.separator}<name>') or a " +
                    "self-serve client's ('${CUST.free}${CUST.separator}...').",
            )
        }
    }
    return out
}

/**
 * What is wrong with a client **coming into being** as [clientId] under [customerId] -- or under none -- as
 * [clients]' customers stand, or null (issue #1107). The one moment a client's id is held to a customer's prefix,
 * asked by every write that defines a client this deployment does not have yet (`GedraConfigService`), so the
 * operator's create, a bundle write and an import are held alike; after it, the link is the authority, and a client
 * moved to another customer keeps its id.
 *
 * Under a customer, the id is the customer's own id or `<customer>_<name>`, the customer's case as written. Under
 * none -- a platform or test client, or a self-serve one -- it may not fall under any customer's prefix, case aside,
 * and an id holding `_` is a self-serve client's (`free_...`).
 */
internal fun newClientPrefixFault(clients: ClientService, clientId: String, customerId: String?): String? {
    val prefix = clientIdPrefix(clientId)
    if (customerId != null) {
        clients.customer(customerId)
            ?: return "Client '$clientId' cannot be created under the customer '$customerId', which this deployment does not declare."
        if (prefix != customerId) {
            return "Client '$clientId' cannot be created under the customer '$customerId': a customer's client ids " +
                "are its own id or '$customerId${CUST.separator}<name>'."
        }
        return null
    }
    clients.customers.keys.firstOrNull { it.equals(prefix, ignoreCase = true) }?.let { holder ->
        return "Client '$clientId' falls under the prefix of the customer '$holder'; create it under that customer, " +
            "or choose an id outside it."
    }
    if (isPrefixedClientId(clientId) && prefix != CUST.free) {
        return "Client '$clientId' holds '${CUST.separator}', which a client id does only under a customer " +
            "('<customer>${CUST.separator}<name>') or as a self-serve client ('${CUST.free}${CUST.separator}...')."
    }
    return null
}

/**
 * Whether [rootClientId] -- customer [customerId]'s root -- names one of its clients among [clients], or the fault
 * (issue #1107). The root's administrators act for the customer as a whole, so it must be the customer's own.
 */
internal fun customerRootFault(customerId: String, rootClientId: String, clients: Map<String, ClientDef>): String? {
    val root = clients[rootClientId]
        ?: return "Customer '$customerId' names the root client '$rootClientId', which this deployment does not define."
    if (root.customerId == customerId) return null
    return "Customer '$customerId' names the root client '$rootClientId', which is " +
        (root.customerId?.let { "the customer '$it''s" } ?: "no customer's") + ". A customer's root is one of its own clients."
}
