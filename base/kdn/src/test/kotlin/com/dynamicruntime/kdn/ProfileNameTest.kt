package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.defaultDisplayLen
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Setting the name you are shown under (issue #323).
 *
 * The gap this closes is narrow but user-visible: `name` had writers at registration and in the admin console
 * and none for the account itself, so a user provisioned without one was displayed by their **email address**
 * with no way to change it. That is the state every `becomeUser` account is in.
 *
 * The call is session-authorized with no verification code, unlike the password calls beside it, and the tests
 * below pin both halves of that choice: a logged-in user may rename themselves, and a logged-out one may not.
 */
class ProfileNameTest : StringSpec({

    "a user sets the name they are shown under, and it survives the round trip" {
        val cxt = Startup.mkTestBootCxt("profileName", "profileNameTest")
        val user = TestUser.create(cxt, "profile-name@example.com")

        // Before: no name, so the display falls back to the login name -- which for a provisioned user with a
        // placeholder username is their email address. That is the symptom in the issue.
        val before = user.getData(AEP.profileUiConfig)
        before.toString().contains("profile-name@example.com") shouldBe true

        val info = user.postData(AEP.profileSetName, mapOf(AFLD.name to "Grace Hopper"))
        info[UPF.name] shouldBe "Grace Hopper"

        // Durable: a fresh read of who I am carries it, rather than only the response that set it.
        user.getData(AEP.selfInfo)[UPF.name] shouldBe "Grace Hopper"
    }

    "a blank name clears it rather than being rejected as an empty form" {
        val cxt = Startup.mkTestBootCxt("profileNameClear", "profileNameClearTest")
        val user = TestUser.create(cxt, "profile-clear@example.com")

        user.postData(AEP.profileSetName, mapOf(AFLD.name to "Temporary"))[UPF.name] shouldBe "Temporary"
        // Clearing is a legitimate thing to want: the display falls back to the login name.
        val cleared = user.postData(AEP.profileSetName, mapOf(AFLD.name to "  "))
        cleared.containsKey(UPF.name) shouldBe false
    }

    /** Whitespace is trimmed on the way in, so a name never carries padding into a heading or the app bar. */
    "the stored name is trimmed" {
        val cxt = Startup.mkTestBootCxt("profileNameTrim", "profileNameTrimTest")
        val user = TestUser.create(cxt, "profile-trim@example.com")
        user.postData(AEP.profileSetName, mapOf(AFLD.name to "  Ada Lovelace  "))[UPF.name] shouldBe "Ada Lovelace"
    }

    /**
     * Capped by the input schema rather than by hand, at the length past which a display would truncate it
     * anyway. The point is that an over-long value is *refused* rather than silently stored and then cut off
     * wherever it happens to be rendered.
     */
    "an over-long name is refused by validation" {
        val cxt = Startup.mkTestBootCxt("profileNameLong", "profileNameLongTest")
        val user = TestUser.create(cxt, "profile-long@example.com")
        val tooLong = "x".repeat(defaultDisplayLen + 1)
        user.expectError(EXC.badInput, AEP.profileSetName, mapOf(AFLD.name to tooLong))
        // And the one that just fits is accepted, so the boundary is where it claims to be.
        val atLimit = "y".repeat(defaultDisplayLen)
        user.postData(AEP.profileSetName, mapOf(AFLD.name to atLimit))[UPF.name] shouldBe atLimit
    }

    /**
     * A name is display copy, not an identifier, so two accounts may share one -- unlike `username`, which
     * carries a unique index. This is why the endpoint needs no collision handling at all.
     */
    "two users may hold the same name" {
        val cxt = Startup.mkTestBootCxt("profileNameDup", "profileNameDupTest")
        TestUser.create(cxt, "profile-dup-a@example.com")
            .postData(AEP.profileSetName, mapOf(AFLD.name to "Alex Taylor"))[UPF.name] shouldBe "Alex Taylor"
        TestUser.create(cxt, "profile-dup-b@example.com")
            .postData(AEP.profileSetName, mapOf(AFLD.name to "Alex Taylor"))[UPF.name] shouldBe "Alex Taylor"
    }

    /**
     * The session is the authority, so no session is no rename. It answers **401**, not 403: there is nothing
     * to authorize, the caller simply is not signed in -- which is also what tells the frontend to send them to
     * the login page rather than show a refusal.
     */
    "a logged-out caller cannot set a name" {
        val cxt = Startup.mkTestBootCxt("profileNameAnon", "profileNameAnonTest")
        val anon = TestHttpClient(cxt.instanceConfig)
        val resp = anon.sendJsonPostRequest(AEP.profileSetName, mapOf(AFLD.name to "Nobody"))
        (resp[EP.status] as? Number)?.toInt() shouldBe EXC.authNeeded
    }
})
