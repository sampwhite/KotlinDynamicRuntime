package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.test.testSchema
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptLong
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end coverage for the test-only become-user endpoint and its [TestUser] wrapper (issue #125): creating
 * a user and immediately acting as them, the existing-user and grant-admin behaviors, and the startup guard
 * that keeps test endpoints out of any non-local/unit environment.
 */
class BecomeUserEndpointTest : StringSpec({

    @Suppress("UNCHECKED_CAST")

    "TestUser.create makes a new user and the client is authenticated as them" {
        val cxt = Startup.mkTestBootCxt("becomeNew", "becomeNewTest")
        val alice = TestUser.create(cxt, "become-alice@example.com")
        alice.userId shouldBeGreaterThan 0L
        // A follow-up call through the same client is made as that user -- proving the session cookie stuck.
        alice.getData(AEP.selfInfo)[UPF.userId].toOptLong() shouldBe alice.userId
    }

    "becoming an existing user returns the same user, and the requested level is ignored" {
        val cxt = Startup.mkTestBootCxt("becomeExisting", "becomeExistingTest")
        // Prefixed, like every address in this spec: the in-memory database is keyed by name rather than by
        // instance, so a bare `bob@example.com` is shared with whichever other spec also chose it -- and
        // since #352 a plain address on a controlled domain is provisioned as an administrator, so a spec
        // that registers one decides what this spec finds.
        val first = TestUser.create(cxt, "become-bob@example.com", level = ROLE.user)
        val again = TestUser.create(cxt, "become-bob@example.com", level = ROLE.admin) // exists already -> level ignored
        again.userId shouldBe first.userId
        TestUser.rolesOf(again.userInfo).contains(ROLE.admin) shouldBe false
    }

    "a level places a freshly created user on the ladder" {
        val cxt = Startup.mkTestBootCxt("becomeAdmin", "becomeAdminTest")
        val admin = TestUser.create(cxt, "become-carol@example.com", level = ROLE.admin)
        TestUser.rolesOf(admin.userInfo).contains(ROLE.admin) shouldBe true
    }

    /**
     * The reason the flag became a level: an operator is a rung the old boolean could not express, and the
     * base role has to come with it or the user could not log in at all.
     */
    "a level of operator creates an operator, and carries the base role with it" {
        val cxt = Startup.mkTestBootCxt("becomeOperator", "becomeOperatorTest")
        val operator = TestUser.create(cxt, "become-operator@example.com", level = ROLE.operator)

        val roles = TestUser.rolesOf(operator.userInfo)
        roles.contains(ROLE.operator) shouldBe true
        roles.contains(ROLE.user) shouldBe true
        roles.contains(ROLE.admin) shouldBe false

        // The level really is applied, but on its own opens nothing: since #464 the operator section demands
        // the `allClients` capability as well as the level (a client-confined operator is not a deployment
        // one), and the admin section demands the admin level. Both refuse a bare operator.
        operator.expectError(EXC.notAuthorized, "/operator/system/info")
        operator.expectError(EXC.notAuthorized, ADEP.users)
    }

    /** An unrecognized level can only under-grant, so a typo cannot hand out privileges. */
    "an unknown level is rejected by the endpoint's declared options" {
        val cxt = Startup.mkTestBootCxt("becomeBadLevel", "becomeBadLevelTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val handler = client.sendEditRequest(
            TEP.becomeUser,
            null,
            mapOf(TEP.email to "become-badlevel@example.com", TEP.level to "wizard"),
            HttpMethod.POST,
        )
        handler.rptStatusCode shouldBe EXC.badInput
    }

    "failIfUserAlreadyExists rejects an existing user with a 400" {
        val cxt = Startup.mkTestBootCxt("becomeFail", "becomeFailTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(TEP.becomeUser, mapOf(TEP.email to "become-dave@example.com"))
        val handler = client.sendEditRequest(
            TEP.becomeUser, null,
            mapOf(TEP.email to "become-dave@example.com", TEP.failIfUserAlreadyExists to true), HttpMethod.POST,
        )
        handler.rptStatusCode shouldBe 400
    }

    "the become-user endpoint is marked forTestingOnly" {
        val cxt = Startup.mkTestBootCxt("becomeMarker", "becomeMarkerTest")
        testSchema(cxt).endpoints.single { it.path == TEP.becomeUser }.forTestingOnly shouldBe true
    }

    "a test instance outside local/unit fails startup with an aggressive error" {
        val ex = shouldThrow<KdrException> {
            Startup.mkBootCxt("guardCxt", "guardInstance", mapOf(ACFG.env to ENV.dev, ACFG.inMemoryOnly to true))
        }
        ex.message.orEmpty() shouldContain "test instance"
    }
})
