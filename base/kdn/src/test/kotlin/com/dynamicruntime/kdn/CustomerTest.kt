package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Customers over the API (issue #1107): declared by the platform in `hub`'s stored configuration, holding the prefix
 * a client is created under, and changed only by an `allClients` administrator. Over the shared instance, with
 * customer and client ids of this issue's own.
 */
class CustomerTest : StringSpec({
    val cxt = TestInstances.default("customers1107")
    val admin = TestUser.createFullAdmin(cxt, "operator@customers1107.test")
    val customer = "cust1107"
    val bundle = "customers1107"

    /** `hub`'s customer bundle, holding [ids]. */
    fun customersBody(vararg ids: String): Map<String, Any?> = mapOf(
        CFEP.client to CL.hub, CFEP.name to bundle, CFEP.namespaceField to clientNamespace(CL.hub),
        CFEP.slots to mapOf(CCT.customerDef to ids.map { mapOf(CUST.customerId to it, CUST.name to "Customer $it") }),
    )
    fun createBody(clientId: String, customerId: String? = null): Map<String, Any?> = buildMap {
        put(CLD.clientId, clientId)
        put(CLD.name, "Created $clientId")
        put(CLD.usageType, ClientUsageType.dev.name)
        put(CLD.audience, ClientAudience.customer.name)
        put(CLD.enabledEnvironments, listOf(ENV.unit, ENV.local))
        customerId?.let { put(CLD.customerId, it) }
    }
    fun createRefusal(clientId: String, customerId: String? = null): String =
        admin.expectError(EXC.badInput, ADEP.clientCreate, createBody(clientId, customerId)).toString()

    admin.postData(ACEP.bundleWrite, customersBody(customer))
    GedraConfigReload.reloadClient(cxt, CL.hub)

    "the platform's customer, declared in hub's stored configuration, is the one a client is created under" {
        ClientService.get(cxt).customer(customer).shouldNotBeNull().name shouldBe "Customer $customer"
        for (id in listOf(customer, "${customer}_east")) {
            admin.postData(ADEP.clientCreate, createBody(id, customer))[CLD.present] shouldBe true
            ClientService.get(cxt).customerOf(id).shouldNotBeNull().customerId shouldBe customer
        }
    }

    "a create is held to its customer's prefix, and one under none to no customer's" {
        createRefusal("other1107", customer) shouldContain "a customer's client ids are its own id or '${customer}_<name>'"
        createRefusal("nobody1107_x", "nobody1107") shouldContain "the customer 'nobody1107', which this deployment does not declare"
        createRefusal("${customer}_west") shouldContain "falls under the prefix of the customer '$customer'"
        createRefusal("Cust1107_west") shouldContain "falls under the prefix of the customer '$customer'"
        createRefusal("loose_1107") shouldContain "which a client id does only under a customer"
        // The self-serve prefix is no customer's.
        admin.postData(ADEP.clientCreate, createBody("${CUST.free}_t1107"))[CLD.present] shouldBe true

        // A bundle write defining a client that does not exist yet is held alike: whichever write brings a client
        // into being.
        val bundled = "${customer}_bundled"
        admin.expectError(
            EXC.badInput, ACEP.bundleWrite,
            mapOf(
                CFEP.client to bundled, CFEP.name to CLD.definitionConfigName, CFEP.namespaceField to clientNamespace(bundled),
                CFEP.slots to mapOf(CCT.clientDef to listOf(createBody(bundled))),
            ),
        ).toString() shouldContain "falls under the prefix of the customer '$customer'"
    }

    "a client and a new customer are made in one call: the customer takes the client's id, and the client is its root" {
        val client = "newco1107"
        val result = admin.postData(ADEP.clientCreate, createBody(client) + (CLD.newCustomer to mapOf(CUST.name to "Newco")))
        result[CLD.present] shouldBe true
        result[CLD.customer] shouldBe mapOf(CUST.customerId to client, CUST.name to "Newco", CUST.rootClientId to client)
        result.containsKey(CLD.customerFailure) shouldBe false
        val clients = ClientService.get(cxt)
        clients.customerOf(client).shouldNotBeNull().name shouldBe "Newco"
        clients.rootOf(client).shouldNotBeNull().clientId shouldBe client
        // Its prefix is held from now on.
        createRefusal("${client}_x") shouldContain "falls under the prefix of the customer '$client'"
    }

    "a new customer is refused where the client's id cannot be one, and goes with a client that is refused" {
        fun withNew(id: String, vararg more: Pair<String, Any?>) = createBody(id) + mapOf(*more) + (CLD.newCustomer to mapOf(CUST.name to "New $id"))
        admin.expectError(EXC.badInput, ADEP.clientCreate, withNew("both1107", CLD.customerId to customer))
            .toString() shouldContain "names an existing customer or asks for a new one, not both"
        admin.expectError(EXC.badInput, ADEP.clientCreate, withNew("free_n1107"))
            .toString() shouldContain "'free_n1107' cannot be a customer's: it holds '_'"
        admin.expectError(EXC.conflict, ADEP.clientCreate, withNew("CUST1107b") - CLD.clientId + (CLD.clientId to "Cust1107"))
        // The client's definition is refused by its write -- it extends a client that is not there -- so the
        // customer made for it is taken back.
        admin.expectError(EXC.badInput, ADEP.clientCreate, withNew("undo1107", CLD.extendsFromClientId to "nothere1107"))
        ClientService.get(cxt).customer("undo1107") shouldBe null
    }

    "only hub's stored configuration holds customers, and only the platform changes them" {
        admin.expectError(
            EXC.badInput, ACEP.bundleWrite,
            customersBody("elsewhere1107") + mapOf(CFEP.client to customer, CFEP.namespaceField to clientNamespace(customer)),
        ).toString() shouldContain "A customer is the platform's to declare"
        // hub's own administrator, without `allClients`, is not the platform.
        val hubAdmin = TestUser.create(cxt, "hubadmin@customers1107.test", level = ROLE.admin, userClient = CL.hub)
        hubAdmin.expectError(EXC.notAuthorized, CFEP.bundleWrite, customersBody(customer, "more1107") - CFEP.client)
            .toString() shouldContain "customers may be declared, changed or removed only by a platform operator"
    }

    "a customer a client uses is not removed, and a client's administrator does not move it" {
        admin.expectError(EXC.badInput, ACEP.bundleWrite, customersBody())
            .toString() shouldContain "Customer '$customer' is no longer declared, but '$customer', '${customer}_east' still use(s) it"
        ClientService.get(cxt).customer(customer).shouldNotBeNull()

        val east = "${customer}_east"
        val eastAdmin = TestUser.create(cxt, "admin@$east.test", level = ROLE.admin, userClient = east)
        val moved = ClientService.get(cxt).known(east)!!.toInfo() + (CLD.customerId to "elsewhere1107")
        eastAdmin.expectError(
            EXC.notAuthorized, CFEP.bundleWrite,
            mapOf(CFEP.name to CLD.definitionConfigName, CFEP.namespaceField to clientNamespace(east), CFEP.slots to mapOf(CCT.clientDef to listOf(moved))),
        ).toString() shouldContain "customerId may be set only by a platform operator"
    }
})
