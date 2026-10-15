package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientSelfServeCreate
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.gedra.PlanLimits
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.IDD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch

/**
 * A person's own self-serve client (issue #1113): created from `public`, one per person, on the free plan, with the
 * creator as its administrator and the session moved there. Over the shared instance.
 */
class ClientSelfServeCreateTest : StringSpec({
    val cxt = TestInstances.default("selfServe1113")

    "a person in public creates their client, and is its administrator there" {
        val person = TestUser.create(cxt, "maker@self1113.test")
        person.selfClient() shouldBe CL.public
        val result = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Maker's Shop!", CLD.description to "Mine."))
        val client = result[CLD.client].toOptStr().shouldNotBeNull()
        // The person names the client; the system names its id.
        client shouldMatch Regex("free_makersshop[a-z0-9]{6}")
        val def = result[CLD.definition].toJsonMapOrEmpty()
        def[CLD.name] shouldBe "Maker's Shop!"
        def[CLD.description] shouldBe "Mine."
        def[CLD.customerId] shouldBe null
        def[CLD.audience] shouldBe ClientAudience.customer.name
        def[CLD.usageType] shouldBe ClientUsageType.dev.name
        def[CLD.enabledEnvironments] shouldBe listOf(ENV.unit, ENV.local)
        // On the free plan, with a sandbox, so it runs only what is published.
        PlanLimits.planOf(cxt, client) shouldBe PLAN.free
        ClientService.get(cxt).present(sandboxOf(client)).shouldNotBeNull()
        GedraConfigService.get(cxt).publishedOnly(cxt, client) shouldBe true

        // The session moved to the creator's own admin user there.
        result[CLD.switched] shouldBe true
        val info = person.getData(AEP.selfInfo)
        info[UPF.client] shouldBe client
        info[UPF.userId] shouldBe result[CLD.userId]
        info[UPF.roles].toJsonListOfStrings() shouldContain ROLE.admin
    }

    "a person creates one, from public only" {
        val person = TestUser.create(cxt, "once@self1113.test")
        val first = person.postData(AEP.createOwnClient, mapOf(CLD.name to "First"))[CLD.client]
        // Back in public -- or as any other user of the same person -- the one is used.
        val again = TestUser.create(cxt, "once@self1113.test", userClient = CL.public)
        again.expectError(EXC.conflict, AEP.createOwnClient, mapOf(CLD.name to "Second"))
            .toString() shouldContain "You have created your client, '$first'; a person creates one."
        // Another client's user does not create one.
        val elsewhere = TestUser.create(cxt, "away@self1113.test", userClient = first.toOptStr())
        elsewhere.expectError(EXC.badInput, AEP.createOwnClient, mapOf(CLD.name to "Away"))
            .toString() shouldContain "A self-serve client is created from 'public'"
    }

    "the one is claimed by a guarded write, so two creates at once cannot both pass" {
        // Two requests read the identity before either writes: the second's claim is refused.
        val person = TestUser.create(cxt, "race@self1113.test")
        val users = UserService.get(cxt)
        val identityId = users.queryByUserId(cxt, person.userId).shouldNotBeNull().identityId
        val one = users.queryIdentityFresh(cxt, identityId).shouldNotBeNull()
        val two = users.queryIdentityFresh(cxt, identityId).shouldNotBeNull()
        one.identityData[IDD.selfServeClient] = "free_one"
        users.updateIdentity(cxt, one, guarded = true)
        two.identityData[IDD.selfServeClient] = "free_two"
        shouldThrow<KdrException> { users.updateIdentity(cxt, two, guarded = true) }.code shouldBe EXC.conflict
        users.queryIdentityFresh(cxt, identityId).shouldNotBeNull().identityData[IDD.selfServeClient] shouldBe "free_one"
    }

    "the usage type follows the deployment's environment" {
        ClientSelfServeCreate.usageTypeIn(ENV.prod) shouldBe ClientUsageType.production
        ClientSelfServeCreate.usageTypeIn(ENV.dev) shouldBe ClientUsageType.dev
        ClientSelfServeCreate.usageTypeIn(ENV.unit) shouldBe ClientUsageType.dev
    }
})
