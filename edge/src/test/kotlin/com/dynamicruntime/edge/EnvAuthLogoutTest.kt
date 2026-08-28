package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Signing out of an environment (issue #486): the counterpart to `EnvAuthLoginTest`.
 *
 * Seeds a live session by encoding a cookie with the instance key -- the same thing the login endpoint does on
 * success -- rather than driving Google, since what is under test is the *clearing*, not the minting.
 */
class EnvAuthLogoutTest : StringSpec({

    InstanceRegistry.register(listOf(EdgeComponent()))
    val cxt = Startup.mkTestBootCxt("envLogout", "envLogoutTest", mapOf(ACFG.bootRole to BOOT.edge))
    val node = NodeService.get(cxt)

    fun seedSession(client: TestHttpClient, email: String) {
        val expireMs = cxt.now().toEpochMilliseconds() + 3_600_000
        client.cookies[ENVAUTH.cookie] = EnvAuthCookie(email, expireMs).encode(node)
    }

    "signing out clears the session, so the next request is anonymous" {
        val client = TestHttpClient(cxt.instanceConfig)
        seedSession(client, "sam@gyassa.com")
        // The seeded session is live -- proving the logout below is what ends it.
        client.sendGetRequest("/health").createdCxt?.userProfile?.isLoggedIn shouldBe true

        client.sendGetRequest(EAEP.logout).rptStatusCode shouldBe EXC.ok
        // The very next request no longer carries an identity: the cleared cookie decodes to nothing.
        client.sendGetRequest("/health").createdCxt?.userProfile?.isLoggedIn shouldBe false
    }

    "signing out with no session is not an error -- logout is idempotent" {
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendGetRequest(EAEP.logout).rptStatusCode shouldBe EXC.ok
        client.sendGetRequest("/health").createdCxt?.userProfile?.isLoggedIn shouldBe false
    }

    // The grace note is a pure function of the flag, so it is asserted directly on the rendered page.
    "the sign-in page greets a freshly signed-out caller, and does not otherwise" {
        EnvAuthPage.render("cid", "/", "/ea/auth/env/login", loggedOut = true)
            .shouldContain("signed out of this environment")
        EnvAuthPage.render("cid", "/", "/ea/auth/env/login", loggedOut = false)
            .shouldNotContain("signed out of this environment")
    }
})
