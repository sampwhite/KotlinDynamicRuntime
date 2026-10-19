package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A customer's page (issue #1114): the deployment's customers for an administrator of every client, and one
 * customer's clients for them or for the administrators of its root -- read-only, facts only, and nobody else's.
 * Over the shared instance, with a customer of this issue's own.
 */
class CustomerOverviewTest : StringSpec({
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
})
