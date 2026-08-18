package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Which client a newly created user lands in, and what their address is allowed to say about it (issue #352).
 *
 * Two routes, deliberately tested apart because they answer a bad client differently. The **fixture** takes an
 * explicit client and refuses one this node does not carry; a **registration** reads the client off the
 * address and falls back to `public`. The asymmetry is the point -- a test that asked for the wrong client has
 * made a mistake worth stopping for, and a person registering has not.
 *
 * `hub` is the client used throughout because it is present in every environment, which makes it the one real
 * alternative to `public` that #343 left behind.
 *
 * Emails are distinct per test: the in-memory database is keyed by name rather than by instance, so rows
 * outlive a single boot within the JVM.
 */
class UserClientTest : StringSpec({

    fun boot(): KdrCxt = Startup.mkTestBootCxt("userClient", "userClientTest")

    // --- the fixture, which is told ---------------------------------------------

    "the fixture creates a user in the client it is given" {
        val user = TestUser.create(boot(), "fixture-hub@example.com", userClient = CL.hub)
        user.selfClient() shouldBe CL.hub
    }

    "the fixture defaults to what the address says, which for a plain address is public" {
        TestUser.create(boot(), "fixture-plain@example.com").selfClient() shouldBe CL.public
    }

    // Refused rather than quietly replaced: silently getting `public` is how the mistake goes unnoticed until
    // an assertion three files away fails for a reason that has nothing to do with what it was checking.
    "the fixture refuses a client this node does not carry" {
        val cxt = boot()
        val envelope = TestUser.create(cxt, "fixture-plain@example.com").expectError(
            EXC.badInput,
            TEP.becomeUser,
            data = mapOf(TEP.email to "fixture-nosuch@example.com", TEP.client to "nosuch"),
        )
        // The message names why, and lists what is present -- a test that got the client name wrong should
        // not also have to go looking for what it should have said.
        envelope[EP.errorMessage].toOptStr()!! shouldContain "no config declares it"
        envelope[EP.errorMessage].toOptStr()!! shouldContain CL.hub
    }

    // --- a registration, which is read ------------------------------------------

    "a plus tag on a controlled domain puts the new user in that client" {
        TestUser.register(boot(), "reg+hub@example.com", "reghub").selfClient() shouldBe CL.hub
    }

    "an address naming a client this node does not carry falls back to public" {
        TestUser.register(boot(), "reg+nosuch@example.com", "regnosuch").selfClient() shouldBe CL.public
    }

    "an ordinary address is a public user, as it always was" {
        TestUser.register(boot(), "reg-plain@example.com", "regplain").selfClient() shouldBe CL.public
    }

    // --- the administrator's choice, on create only ------------------------------

    "a full-scope administrator may create a user in another client" {
        val cxt = boot()
        val admin = TestUser.createFullAdmin(cxt, "create-full@example.com")
        val made = admin.postData(
            UADEP.userCreate,
            mapOf(ADF.primaryId to "created-in-hub@other.test", ADF.client to CL.hub),
        )
        made[ADF.client].toOptStr() shouldBe CL.hub
    }

    // The same reason the organization defaults the way it does: a confined administrator creating a user
    // outside their own scope would immediately lose sight of them.
    "an administrator without allClients may not name another client" {
        val cxt = boot()
        val scoped = TestUser.create(cxt, "create-scoped@other.test", level = ROLE.admin)
        val envelope = scoped.expectError(
            EXC.badInput,
            UADEP.userCreate,
            data = mapOf(ADF.primaryId to "created-refused@other.test", ADF.client to CL.hub),
        )
        envelope[EP.errorMessage].toOptStr()!! shouldContain ROLE.allClients
    }

    "naming your own client is not naming another, so it needs nothing" {
        val cxt = boot()
        val scoped = TestUser.create(cxt, "create-own@other.test", level = ROLE.admin)
        val made = scoped.postData(
            UADEP.userCreate,
            mapOf(ADF.primaryId to "created-in-own@other.test", ADF.client to CL.public),
        )
        made[ADF.client].toOptStr() shouldBe CL.public
    }

    "a client this node does not carry is refused even to a full-scope administrator" {
        val cxt = boot()
        val admin = TestUser.createFullAdmin(cxt, "create-nosuch@example.com")
        val envelope = admin.expectError(
            EXC.badInput,
            UADEP.userCreate,
            data = mapOf(ADF.primaryId to "created-nowhere@other.test", ADF.client to "nosuch"),
        )
        envelope[EP.errorMessage].toOptStr()!! shouldContain "no client 'nosuch'"
    }

    "naming no client puts the new user in the creator's own" {
        val cxt = boot()
        val admin = TestUser.createFullAdmin(cxt, "create-default@example.com")
        val made = admin.postData(UADEP.userCreate, mapOf(ADF.primaryId to "created-default@other.test"))
        made[ADF.client].toOptStr() shouldBe admin.selfClient()
    }

    // --- what a persona grants, and what it cannot -------------------------------

    "a persona grants its rung" {
        val user = TestUser.register(boot(), "reg+hub%admin@example.com", "reghubadmin")
        user.selfClient() shouldBe CL.hub
        user.selfRoles() shouldContain ROLE.admin
    }

    // The inversion: a `+` tag used to mean only *not an admin*. A client with no persona still is not one --
    // which is the half that reads backwards and so is worth asserting on its own.
    "a client with no persona is an ordinary user" {
        val user = TestUser.register(boot(), "reg+hub-plain@example.com", "reghubplain")
        user.selfRoles() shouldNotContain ROLE.admin
        user.selfRoles() shouldContain ROLE.user
    }

    // Structural rather than a check: `RoleLadder.rolesAtLevel` composes a level from the ladder plus the
    // capabilities already held, and a newly provisioned user holds none -- so no persona can name its way to
    // a capability. This is the escalation ceiling of the whole email convention.
    "a persona cannot grant a capability, allClients least of all" {
        val user = TestUser.register(boot(), "reg+hub%allClients@example.com", "reghuballclients")
        user.selfRoles() shouldNotContain ROLE.allClients
        user.selfRoles() shouldNotContain ROLE.admin
        user.selfRoles() shouldContain ROLE.user
    }

    "a persona that names nothing on the ladder makes an ordinary user" {
        val user = TestUser.register(boot(), "reg+hub%wizard@example.com", "reghubwizard")
        user.selfRoles() shouldContain ROLE.user
        user.selfRoles() shouldNotContain ROLE.admin
    }
})
