package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toOptStr

/**
 * Who may see a customer's page (issue #1114). An administrator who sees every client sees any customer. An
 * administrator of a customer's **root** client sees that customer: the root's administrators act for the customer
 * as a whole (#1107), as they do when they create its clients (#1112) -- client-wide, so not one confined to an
 * organization of the root. Read-only: each still edits only their own client.
 *
 * A rule, not a read width. The page shows facts the overview computes for itself, so nothing reads through a
 * `ReadScope` here; a customer-wide width arrives with the first customer-wide data read that needs SQL to honor it.
 */
object CustomerAccess {
    /** The customer whose root [cxt]'s caller administers, client-wide; null for anybody else. */
    fun administeredCustomer(cxt: KdrCxt): CustomerDef? {
        if (!AdminRules.isClientAdministrator(cxt) || cxt.userProfile.org != null) return null
        val client = cxt.userProfile.client
        return ClientService.getOrNull(cxt)?.customerOf(client)?.takeIf { it.rootClientId == client }
    }

    /**
     * The customer [named] -- or the caller's own when none is named -- that the caller may see: any for an
     * administrator who sees every client, their own for a root's administrator. A 403 for anybody else, and a 404
     * for a customer this deployment does not declare.
     */
    fun customerFor(cxt: KdrCxt, named: String?): CustomerDef {
        val clients = ClientService.get(cxt)
        val own = administeredCustomer(cxt)
        if (AdminRules.canSeeAllClients(cxt)) {
            val id = named ?: own?.customerId
                ?: throw KdrException.mkInput("Name the customer to show; you administer no customer's root client.")
            return clients.customer(id) ?: throw KdrException("This deployment declares no customer '$id'.", code = EXC.notFound)
        }
        own ?: throw KdrException(
            "A customer's page is for an administrator of its root client, client-wide, or of every client.",
            code = EXC.notAuthorized,
        )
        if (named != null && named != own.customerId) {
            throw KdrException("You administer the customer '${own.customerId}', not '$named'.", code = EXC.notAuthorized)
        }
        return own
    }

    /** The clients [customerId] owns, in declaration order: every client the link names it for, sandboxes aside. */
    fun clientsOf(cxt: KdrCxt, customerId: String): List<ClientDef> {
        val clients = ClientService.get(cxt)
        return clients.clients.values.filter { !isSandboxClient(it.clientId) && clients.customerOf(it.clientId)?.customerId == customerId }
    }
}

/** The customer page's endpoints (issue #1114), in the clients module beside the Clients overview whose rows it shows. */
internal fun SchModuleBuilder.customerOverviewEndpoints() {
    type(CUST.summaryTypeName) {
        type = SCT.kObject
        description = "One customer, as the deployment's list of customers shows it (issue #1114)."
        property(CUST.customerId, "The customer's id: the prefix of its clients' ids.", required = true)
        property(CUST.name, "The name presented for the customer.", required = true)
        property(CUST.description, "An internal note about who, what, or why.")
        property(CUST.plan, "The plan its clients are on (issue #1109), as every plan decision reads it.", required = true)
        property(CUST.rootClientId, "The root client, whose administrators act for the customer; absent when none is named.")
        property(CUST.clientCount, "How many clients it owns, sandboxes aside.", required = true) { type = SCT.integer }
        property(CUST.isSource, "Whether it is declared in source code rather than stored configuration.", required = true) {
            type = SCT.boolean
        }
    }

    type(CUST.overviewTypeName) {
        type = SCT.kObject
        description = "One customer's page (issue #1114): what it is, and each of its clients as the Clients overview shows it."
        property(CUST.customer, "The customer.", required = true) { ref(CUST.summaryTypeName) }
        property(CUST.clients, "Its clients, sandboxes aside, each with its facts, plan usage, publish and sandbox state, and issues.", required = true) {
            type = SCT.array
            items { ref(CLD.overviewTypeName) }
        }
    }

    listEndpoint(
        UADEP.customers,
        "The customers this deployment declares (issue #1114), for an administrator who sees every client: each with " +
            "its plan, its root client, and how many clients it owns. Clients no customer owns -- platform, test, and " +
            "self-serve ones -- are on the Clients page.",
        outputRef = CUST.summaryTypeName,
        // A declared set, small enough that paging would pretend otherwise, as the Clients overview is.
        noLimit = true,
    ) { c, _ ->
        if (!AdminRules.canSeeAllClients(c)) {
            throw KdrException("The list of customers is for an administrator of every client.", code = EXC.notAuthorized)
        }
        ClientService.get(c).customers.values.map { customerSummary(c, it.def, it.isSource) }
    }

    itemEndpoint(
        UADEP.customerOverview,
        "One customer and its clients (issue #1114): any customer, for an administrator who sees every client; their " +
            "own, for an administrator of the customer's root client, client-wide -- read-only, each still editing only " +
            "their own client. Each client is a row of the Clients overview: its facts, its plan usage, its publish and " +
            "sandbox state, and its issues.",
        HttpMethod.GET,
        outputRef = CUST.overviewTypeName,
        inputFields = {
            field(CUST.customer, "The customer; the caller's own when absent. Another takes an administrator of every client.")
        },
    ) { c, request ->
        val customer = CustomerAccess.customerFor(c, request[CUST.customer].toOptStr())
        val clients = ClientService.get(c)
        val isSource = clients.customers[customer.customerId]?.isSource == true
        val recalcLimit = formStatesRecalcLimit(c)
        mapOf(
            CUST.customer to customerSummary(c, customer, isSource),
            CUST.clients to CustomerAccess.clientsOf(c, customer.customerId).map { def ->
                val status = when {
                    clients.isPresent(def.clientId) -> ClientStatus.present
                    def.retired -> ClientStatus.retired
                    else -> ClientStatus.notEnabled
                }
                overviewRow(c, def.clientId, def.name, status, recalcLimit)
            },
        )
    }
}

/** [def] as the customer summary shapes it (issue #1114). */
private fun customerSummary(cxt: KdrCxt, def: CustomerDef, isSource: Boolean): Map<String, Any?> = buildMap {
    put(CUST.customerId, def.customerId)
    put(CUST.name, def.name)
    def.description?.let { put(CUST.description, it) }
    put(CUST.plan, PLAN.named(def.plan))
    def.rootClientId?.let { put(CUST.rootClientId, it) }
    put(CUST.clientCount, CustomerAccess.clientsOf(cxt, def.customerId).size)
    put(CUST.isSource, isSource)
}
