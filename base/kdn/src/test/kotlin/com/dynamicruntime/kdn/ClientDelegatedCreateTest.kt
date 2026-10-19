package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CustomerDef
import com.dynamicruntime.common.gedra.CustomerStore
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client created by a customer's root administrator (issue #1112): under the customer's prefix, starting from the
 * creator's choices and what the root passes on, with the creator's own admin user in it. Over the shared instance,
 * with a customer of this issue's own.
 */
class ClientDelegatedCreateTest : StringSpec({
    val cxt = TestInstances.default("delegated1112")
    val operator = TestUser.createFullAdmin(cxt, "operator@dlg1112.test")
    val root = "dlg1112"
    operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to root, CLD.name to "Delegated", CLD.usageType to ClientUsageType.production.name,
            CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            CLD.webResourcesId to "dlgWeb", CLD.newCustomer to mapOf(CUST.name to "Delegated Co"),
        ),
    )
    val rootAdmin = TestUser.create(cxt, "admin@dlg1112.test", level = ROLE.admin, userClient = root)

    fun createBody(clientId: String) = mapOf(CLD.clientId to clientId, CLD.name to "Client $clientId")

    "a root administrator creates a client under the customer, and is its administrator there" {
        val east = "${root}_east"
        val result = rootAdmin.postData(UADEP.clientCreate, createBody(east) + (CLD.description to "The east office."))
        result[CLD.present] shouldBe true
        // The creator's choices, and what the root passes on -- nothing it would clear.
        val def = result[CLD.definition].toJsonMapOrEmpty()
        def[CLD.name] shouldBe "Client $east"
        def[CLD.description] shouldBe "The east office."
        def[CLD.customerId] shouldBe root
        def[CLD.usageType] shouldBe ClientUsageType.production.name
        def[CLD.audience] shouldBe ClientAudience.customer.name
        def[CLD.enabledEnvironments] shouldBe listOf(ENV.unit, ENV.local)
        def[CLD.webResourcesId] shouldBe "dlgWeb"
        ClientService.get(cxt).customerOf(east).shouldNotBeNull().customerId shouldBe root

        // The creator's own admin user there, which the switch lands on.
        val switched = rootAdmin.postData(AEP.switchClient, mapOf(AFLD.client to east))
        switched[UPF.userId] shouldBe result[CLD.userId]
        switched[UPF.client] shouldBe east
        switched[UPF.roles].toJsonListOfStrings() shouldContain ROLE.admin
    }

    "the id is held to the customer's prefix, and only the root's administrators create" {
        // Signed in at the root again: the first case moved this person's session into the client it made.
        val back = TestUser.create(cxt, "admin@dlg1112.test", userClient = root)
        back.expectError(EXC.badInput, UADEP.clientCreate, createBody("other1112"))
            .toString() shouldContain "a customer's client ids are its own id or '${root}_<name>'"
        // An administrator of another of the customer's clients is not the root's.
        val west = "${root}_west"
        back.postData(UADEP.clientCreate, createBody(west))
        val westAdmin = TestUser.create(cxt, "admin@$west.test", level = ROLE.admin, userClient = west)
        westAdmin.expectError(EXC.notAuthorized, UADEP.clientCreate, createBody("${root}_south"))
            .toString() shouldContain "'$west' is no customer's root"
        // An administrator confined to an organization of the root does not act for the customer.
        val confined = TestUser.create(cxt, "north@dlg1112.test", level = ROLE.admin, userClient = root)
        operator.postData(ADEP.userSetOrg, mapOf(ADF.userId to confined.userId, ADF.org to "north"))
        confined.expectError(EXC.notAuthorized, UADEP.clientCreate, createBody("${root}_north"))
            .toString() shouldContain "confined to the organization 'north'"
        // A member of the root is no administrator at all.
        val member = TestUser.create(cxt, "member@dlg1112.test", userClient = root)
        member.expectError(EXC.notAuthorized, UADEP.clientCreate, createBody("${root}_north"))
    }

    "an id outside the customer is refused alike whether it exists or not (issue #1288)" {
        // Signed in at the root again: the first case moved this person's session into the client it made.
        val atRoot = TestUser.create(cxt, "admin@dlg1112.test", userClient = root)
        // `hub` exists (in source code); `nosuch1288` does not; `HUB` differs from `hub` only by case. The refusal
        // must not tell them apart, or it says which of another customer's clients exist, and where.
        fun refusal(clientId: String): Pair<Any?, String> {
            val env = atRoot.expectError(EXC.badInput, UADEP.clientCreate, createBody(clientId))
            return env[EP.status] to env[EP.errorMessage].toString().replace(clientId, "<id>")
        }
        val missing = refusal("nosuch1288")
        refusal("hub") shouldBe missing
        refusal("HUB") shouldBe missing
        missing.second shouldContain "a customer's client ids are its own id or '${root}_<name>'"
    }

    "a customer on the free plan makes no clients" {
        CustomerStore.put(cxt, CustomerDef(root, "Delegated Co", rootClientId = root, plan = PLAN.free))
        try {
            // Signed in at the root again: the first case moved this person's session into the client it made.
            val atRoot = TestUser.create(cxt, "admin@dlg1112.test", userClient = root)
            atRoot.expectError(EXC.badInput, UADEP.clientCreate, createBody("${root}_free"))
                .toString() shouldContain "is on the free plan, which makes no clients"
        } finally {
            CustomerStore.put(cxt, CustomerDef(root, "Delegated Co", rootClientId = root))
        }
    }
})
