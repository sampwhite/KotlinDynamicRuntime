package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Exercises the UI-config endpoints (issue #70): the per-widget-group "construction manifests" the frontend
 * fetches to build the auth flow and profile page. Verifies the shared `{ fragments, features, state }`
 * envelope, that `auth` config is anonymous-friendly while `profile` config is login-gated, and the
 * `fragmentBuildId` caching contract.
 */
class UiConfigEndpointTest : StringSpec({

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()
    fun Map<String, Any?>.obj(key: String): Map<String, Any?> = getValue(key)!!.toJsonMap()
    fun Map<String, Any?>.list(key: String): List<Any?> = getValue(key) as List<*>

    "auth ui config is anonymous-friendly and carries the fragments/features/state envelope" {
        val cxt = Startup.mkTestBootCxt("uiAuth", "uiAuthTest")

        val cfg = results(TestHttpClient(cxt.instanceConfig).sendJsonGetRequest("/auth/ui/config"))
        val frag = cfg.list(UIC.fragments).first()!!.toJsonMap()
        frag[UIC.fileId] shouldBe "auth"
        (frag[UIC.buildId] as String).isNotEmpty() shouldBe true
        cfg.obj(UIC.features)["passwordLogin"] shouldBe true
        cfg.obj(UIC.state).obj("userInfo")["authId"] shouldBe UserProfile.anonymousAuthId

        // Once logged in, the same call reflects the real user in its state.
        val carol = TestUser.register(cxt, "carol@example.com", "carol")
        val loggedIn = carol.getData("/auth/ui/config").obj(UIC.state).obj("userInfo")
        loggedIn["authId"] shouldBe carol.userId.toString()
        loggedIn["publicName"] shouldBe "carol"
    }

    "profile ui config is login-gated and reports password status" {
        val cxt = Startup.mkTestBootCxt("uiProfile", "uiProfileTest")

        // Anonymous caller cannot reach the profile group.
        TestHttpClient(cxt.instanceConfig).sendGetRequest("/profile/ui/config").rptStatusCode shouldBe 401

        val dave = TestUser.register(cxt, "dave@example.com", "davey")
        val cfg = dave.getData("/profile/ui/config")
        cfg.list(UIC.fragments).first()!!.toJsonMap()[UIC.fileId] shouldBe "profile"
        cfg.obj(UIC.features)["hasPassword"] shouldBe false // set no password
        cfg.obj(UIC.features)["canSetPassword"] shouldBe true
        cfg.obj(UIC.state).obj("userInfo")["userId"] shouldBe dave.userId
    }

    "fragmentBuildId is stable for a present file and null for an absent one" {
        val first = MarkdownFragmentService.fragmentBuildId("auth")
        first.shouldNotBeNull()
        MarkdownFragmentService.fragmentBuildId("auth") shouldBe first // cached, same value
        MarkdownFragmentService.fragmentBuildId("no-such-fragment-file") shouldBe null
    }
})
