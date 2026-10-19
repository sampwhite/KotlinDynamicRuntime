package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.util.toOptStr

/** Names and field keys for a customer definition (issue #1107). */
@Suppress("ConstPropertyName")
object CUST {
    const val customerId = "customerId"
    const val name = "name"
    const val description = "description"
    const val rootClientId = "rootClientId"
    const val plan = "plan"

    /**
     * The platform's own customer: the one `hub`, `public` and the deployment's internal clients belong to. Declared
     * by core, beside `hub` -- the only declaration that may hold this reserved id.
     */
    const val kdr = "kdr"

    /** The prefix of a self-serve client's generated id (`free_<slug><random>`), which no customer holds. */
    const val free = "free"

    /** What separates a customer's prefix from the rest of a client id under it: `acme_east`. */
    const val separator = '_'

    /**
     * Ids no customer may take (case aside), whatever else holds them: the platform's own clients and roots, and the
     * self-serve prefix. [kdr] is among them, and core's own customer is the one declaration allowed it.
     */
    val reserved: Set<String> = setOf(kdr, CL.hub, CL.public, CL.house, GID.globalClient, free)

    /**
     * The `hub` configuration the customers made over the API are stored in (issue #1107). One bundle, patched one
     * customer at a time under `hub`'s lock, so two changes cannot overwrite each other.
     */
    const val configName = "customers"

    /** The choice list of customers a client may be filed under, for a form (issue #1107). */
    const val customerOptions = "customerOptions"

    // --- the customer page (issue #1114) ---
    /** Schema type name for one customer as the deployment's list of customers shows it. */
    const val summaryTypeName = "CustomerSummary"

    /** Schema type name for one customer's page: what it is, and its clients. */
    const val overviewTypeName = "CustomerOverview"

    /** On a customer's summary: how many clients it owns. */
    const val clientCount = "clientCount"

    /** On a customer's page: its clients, each as the Clients overview's row shapes it. */
    const val clients = "clients"

    /** The customer a page asks for; the caller's own when absent. */
    const val customer = "customer"

    /** Whether the customer is declared in source code (true) or in stored configuration. */
    const val isSource = "isSource"
}

/**
 * A **customer** (issue #1107): who owns a set of clients. Its id is also the **prefix** of the ids of the clients
 * created for it -- `acme` owns the id `acme` itself and every `acme_<name>` -- so two customers creating clients can
 * never compete for one id. Only the platform declares one: in a source component, or in `hub`'s stored
 * configuration, written by an `allClients` administrator.
 *
 * The prefix is judged when a client is **created** under it, and only then. After that the client's
 * [ClientDef.customerId] is the link, and the authority: a client moved to another customer keeps its id.
 *
 * Like [ClientDef], nothing here checks the id: a definition arriving as data is judged by the load, which can
 * degrade rather than throw (`checkClientDefs`).
 */
data class CustomerDef(
    /** The customer's id, and the prefix of its clients' ids: ASCII letters and digits, starting with a letter. */
    val customerId: String,
    /** The name presented for the customer. */
    val name: String,
    /** An internal note about who, what or why. */
    val description: String? = null,
    /**
     * The customer's **root** client: the one whose administrators act for the customer as a whole. Set by the
     * platform, and usually the customer's first client. Null until one is named.
     */
    val rootClientId: String? = null,
    /**
     * The plan the customer's clients are on (issue #1109), a [PLAN] name: what they may hold. Null is [PLAN.unlimited].
     * Set by the platform, as the customer is.
     */
    val plan: String? = null,
) {
    override fun toString(): String = customerId

    /** The definition's JSON form, absent optionals omitted; its fields are [declareFields]'. */
    fun toInfo(): Map<String, Any?> = buildMap {
        put(CUST.customerId, customerId)
        put(CUST.name, name)
        description?.let { put(CUST.description, it) }
        rootClientId?.let { put(CUST.rootClientId, it) }
        plan?.let { put(CUST.plan, it) }
    }

    companion object {
        /** A [CustomerDef] from its stored JSON form -- leniently, as [ClientDef.fromInfo] reads a client. */
        fun fromInfo(m: Map<String, Any?>): CustomerDef = CustomerDef(
            customerId = m[CUST.customerId].toOptStr()
                ?: throw KdrException.mkConv("A stored customer has no '${CUST.customerId}'."),
            name = m[CUST.name].toOptStr() ?: throw KdrException.mkConv("A stored customer has no '${CUST.name}'."),
            description = m[CUST.description].toOptStr(),
            rootClientId = m[CUST.rootClientId].toOptStr(),
            plan = m[CUST.plan].toOptStr(),
        )

        /**
         * The fields of [toInfo]'s shape (issue #1107), declared into [builder]: what the `kdr:customerDef` slot's
         * entries hold, which a write is held to.
         */
        fun declareFields(builder: SchTypeBuilder) {
            with(builder) {
                property(
                    CUST.customerId,
                    "The customer's id, and the prefix of its clients' ids: letters and digits, starting with a letter.",
                    required = true,
                )
                property(CUST.name, "The name presented for the customer.", required = true)
                property(CUST.description, "An internal note about who, what or why.")
                property(CUST.rootClientId, "The client whose administrators act for the customer as a whole.")
                property(CUST.plan, "The plan the customer's clients are on; unlimited when absent.") { PLAN.names.forEach { option(it) } }
            }
        }
    }
}

/**
 * What is wrong with [customerId] as a customer's id, as a phrase completing "the id ...", or null (issue #1107).
 * Letters and digits only, ASCII, starting with a letter: an id is a client-id prefix, so it holds no
 * [CUST.separator] -- the first one in a client id is where its prefix ends.
 */
fun customerIdFault(customerId: String): String? {
    if (customerId.isEmpty()) return "is empty"
    val first = customerId[0]
    if (!(first in 'a'..'z' || first in 'A'..'Z')) return "starts with '$first'"
    val bad = customerId.firstOrNull { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }
    return bad?.let { "holds '$it'" }
}

/**
 * The prefix [clientId] falls under (issue #1107): the part before its first [CUST.separator], or the whole id when
 * it holds none -- `acme_east` and `acme` both fall under `acme`. Which customer that is, if any, is the caller's to
 * look up, without regard to case.
 */
fun clientIdPrefix(clientId: String): String = clientId.substringBefore(CUST.separator)

/**
 * Whether [clientId] is a **self-serve** client's (issues #1109, #1113): under the [CUST.free] prefix, which no
 * customer holds. What puts a client on the free plan -- not having no customer, which platform and test clients
 * have too.
 */
fun isSelfServeClient(clientId: String): Boolean = isPrefixedClientId(clientId) && clientIdPrefix(clientId) == CUST.free

/** Whether [clientId] holds a [CUST.separator], and so must fall under a customer's prefix or [CUST.free]. */
fun isPrefixedClientId(clientId: String): Boolean = CUST.separator in clientId
