package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.test.testSchema
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
    fun roles(userInfo: Map<String, Any?>): List<String> = (userInfo[UPF.roles] as? List<*>)?.map { it.toString() } ?: emptyList()

    "TestUser.create makes a new user and the client is authenticated as them" {
        val cxt = Startup.mkTestBootCxt("becomeNew", "becomeNewTest")
        val alice = TestUser.create(cxt, "alice@example.com")
        alice.userId shouldBeGreaterThan 0L
        // A follow-up call through the same client is made as that user -- proving the session cookie stuck.
        alice.getData(AEP.selfInfo)[UPF.userId].toOptLong() shouldBe alice.userId
    }

    "becoming an existing user returns the same user, and grantAdmin is ignored" {
        val cxt = Startup.mkTestBootCxt("becomeExisting", "becomeExistingTest")
        val first = TestUser.create(cxt, "bob@example.com", admin = false)
        val again = TestUser.create(cxt, "bob@example.com", admin = true) // exists already -> admin ignored
        again.userId shouldBe first.userId
        roles(again.userInfo).contains(ROLE.admin) shouldBe false
    }

    "grantAdmin gives a freshly created user the admin role" {
        val cxt = Startup.mkTestBootCxt("becomeAdmin", "becomeAdminTest")
        val admin = TestUser.create(cxt, "carol@example.com", admin = true)
        roles(admin.userInfo).contains(ROLE.admin) shouldBe true
    }

    "failIfUserAlreadyExists rejects an existing user with a 400" {
        val cxt = Startup.mkTestBootCxt("becomeFail", "becomeFailTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(TEP.becomeUser, mapOf(TEP.email to "dave@example.com"))
        val handler = client.sendEditRequest(
            TEP.becomeUser, null,
            mapOf(TEP.email to "dave@example.com", TEP.failIfUserAlreadyExists to true), isPut = false,
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
