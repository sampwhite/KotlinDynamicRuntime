package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
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
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Customers over the API (issue #1107): declared by the platform in `hub`'s stored configuration, holding the prefix
 * a client is created under, and changed only by an `allClients` administrator. Over the shared instance, with
 * customer and client ids of this issue's own.
 *
 * Also, since issue #1739, the tests that were `CustomerOverviewTest`, each kept whole below.
 */
class CustomerTest : StringSpec({
    include(customerTests)
    include(customerOverviewTests)
})

/** This file's own tests, as the class describes them. */
private val customerTests = stringSpec {
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
        // The client's definition is refused by its write -- it is enabled in no environment a developer runs -- so
        // the customer made for it is taken back.
        admin.expectError(EXC.badInput, ADEP.clientCreate, withNew("undo1107", CLD.enabledEnvironments to listOf(ENV.dev)))
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
}

/**
 * A customer's page (issue #1114): the deployment's customers for an administrator of every client, and one
 * customer's clients for them or for the administrators of its root -- read-only, facts only, and nobody else's.
 * Over the shared instance, with a customer of this issue's own.
 */
private val customerOverviewTests = stringSpec {
    val cxt = TestInstances.default("customer1114")
    val operator = TestUser.createFullAdmin(cxt, "operator@cust1114.test")
    val root = "cust1114"
    operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to root, CLD.name to "Cust Root", CLD.usageType to ClientUsageType.dev.name,
            CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            CLD.sandbox to true, CLD.newCustomer to mapOf(CUST.name to "Cust Co"),
        ),
    )
    val rootAdmin = TestUser.create(cxt, "admin@cust1114.test", level = ROLE.admin, userClient = root)
    val east = "${root}_east"
    rootAdmin.postData(UADEP.clientCreate, mapOf(CLD.clientId to east, CLD.name to "East"))

    fun menuIds(user: TestUser): List<Any?> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps().map { it[HFLD.id] }

    "an administrator of every client lists the customers and opens any one's clients" {
        val listed = operator.getItems(UADEP.customers).associateBy { it[CUST.customerId] }
        listed.keys shouldContain CUST.kdr
        with(listed.getValue(root)) {
            this[CUST.name] shouldBe "Cust Co"
            this[CUST.rootClientId] shouldBe root
            this[CUST.plan] shouldBe PLAN.unlimited
            this[CUST.clientCount] shouldBe 2
            this[CUST.isSource] shouldBe false
        }
        val page = operator.getItem(UADEP.customerOverview, mapOf(CUST.customer to root))
        page[CUST.customer].toJsonMapOrEmpty()[CUST.customerId] shouldBe root
        // Its clients as the overview's rows, the root's sandbox aside (the root's row says it has one).
        val rows = page[CUST.clients].toJsonListOfMaps().associateBy { it[CLD.clientId] }
        rows.keys shouldBe setOf(root, east)
        rows.getValue(root)[CLD.hasSandbox] shouldBe true
        operator.expectError(EXC.notFound, UADEP.customerOverview, args = mapOf(CUST.customer to "nosuch1114"))
        menuIds(operator) shouldContain HMENU.customers
        menuIds(operator) shouldNotContain HMENU.myCustomer
    }

    "a root's administrator sees their own customer, and nobody else does" {
        val page = rootAdmin.getItem(UADEP.customerOverview)
        page[CUST.customer].toJsonMapOrEmpty()[CUST.customerId] shouldBe root
        page[CUST.clients].toJsonListOfMaps().map { it[CLD.clientId] }.toSet() shouldBe setOf(root, east)
        rootAdmin.expectError(EXC.notAuthorized, UADEP.customerOverview, args = mapOf(CUST.customer to CUST.kdr))
            .toString() shouldContain "You administer the customer '$root', not '${CUST.kdr}'"
        rootAdmin.expectError(EXC.notAuthorized, UADEP.customers)
        menuIds(rootAdmin) shouldContain HMENU.myCustomer

        // An administrator of another of its clients is not the root's.
        val eastAdmin = TestUser.create(cxt, "admin@cust1114east.test", level = ROLE.admin, userClient = east)
        eastAdmin.expectError(EXC.notAuthorized, UADEP.customerOverview)
            .toString() shouldContain "for an administrator of its root client"
        menuIds(eastAdmin) shouldNotContain HMENU.myCustomer
        // Nor is one of the root confined to an organization: the customer is customer-wide authority.
        val confined = TestUser.create(cxt, "north@cust1114.test", level = ROLE.admin, userClient = root)
        operator.postData(ADEP.userSetOrg, mapOf(ADF.userId to confined.userId, ADF.org to "north"))
        confined.expectError(EXC.notAuthorized, UADEP.customerOverview)
    }
}
