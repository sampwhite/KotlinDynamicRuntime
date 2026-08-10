package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AdminScope
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Client-scoped administration (issue #225): an administrator reaches their own client, and every client only
 * with [ROLE.allClients].
 *
 * The scope is enforced on the **read**, in `UserService`, rather than per endpoint -- writes have always
 * stamped the owning client while reads filtered by nothing, and closing that asymmetry is what makes a
 * scoped administrator correct by construction instead of correct-until-someone-adds-an-endpoint.
 *
 * Seeding the second client goes through [UserService.insertUser] directly, because no API can currently put
 * a user anywhere but `public`: both `becomeUser` and `admin/user/create` hardcode it. That is a real gap for
 * a multi-client deployment, but it is a *write*-side one and deliberately outside this change.
 */
class ClientScopedAdminTest : StringSpec({

    val otherClient = "acme"

    /**
     * Inserts a user directly into [client] with [roles], which no endpoint can currently do -- both
     * `becomeUser` and `admin/user/create` hardcode `public`, and `allClients` cannot be granted by an
     * administrator who lacks it (which is every administrator, to begin with).
     */
    fun seedUserInClient(cxt: KdrCxt, email: String, client: String, vararg roles: String): Long {
        val service = UserService.get(cxt) ?: error("UserService is required by this test.")
        service.checkInit(cxt)
        val granted = if (roles.isEmpty()) listOf(ROLE.user) else roles.toList()
        return service.insertUser(cxt, AuthUserRow.mkInitialUser(email, client, granted))
    }

    // --- the scope itself (pure policy) --------------------------------------

    "the scope narrows by role, and only allClients widens it" {
        val cxt = Startup.mkTestBootCxt("scope", "adminScopeTest")

        // Contexts built by hand: this is pure policy, and the capability has no endpoint that grants it.
        fun acting(vararg roles: String): KdrCxt = KdrCxt(
            "scopeCase", cxt.instanceConfig, null,
            UserProfile(authId = "1", userId = 1L, client = otherClient, roles = roles.toSet()),
        )

        AdminRules.adminScope(acting(ROLE.user)) shouldBe AdminScope.none
        AdminRules.adminScope(acting(ROLE.user, ROLE.operator)) shouldBe AdminScope.none
        AdminRules.adminScope(acting(ROLE.user, ROLE.admin)) shouldBe AdminScope.ownClient
        AdminRules.adminScope(acting(ROLE.user, ROLE.admin, ROLE.allClients)) shouldBe AdminScope.allClients

        // `canManageUsers` keeps answering the question its callers ask, now derived from the scope.
        AdminRules.canManageUsers(acting(ROLE.user)) shouldBe false
        AdminRules.canManageUsers(acting(ROLE.user, ROLE.admin)) shouldBe true

        // The scope follows: a scoped admin is confined to their own client, a global one to nothing.
        AdminRules.adminReadScope(acting(ROLE.user, ROLE.admin)).client shouldBe otherClient
        AdminRules.adminReadScope(acting(ROLE.user, ROLE.admin, ROLE.allClients)).isUnrestricted shouldBe true
        AdminRules.adminReadScope(acting(ROLE.user)).isUnrestricted shouldBe true // gated out, nothing to confine

        // Distinct shapes must not share a cached prepared statement; identical shapes must share one.
        ReadScope.ofClient("a").shapeKey shouldBe ReadScope.ofClient("b").shapeKey
        (ReadScope.ofClient("a").shapeKey == ReadScope.unrestricted.shapeKey) shouldBe false
        (ReadScope.ofUser(1L).shapeKey == ReadScope.ofClient("a").shapeKey) shouldBe false
    }

    /** The capability is a capability: it must not become a rung, or it would confer a level of its own. */
    "allClients is not on the privilege ladder" {
        com.dynamicruntime.common.http.request.RoleLadder.ordered.contains(ROLE.allClients) shouldBe false
        com.dynamicruntime.common.http.request.RoleLadder.rankOf(ROLE.allClients) shouldBe null
    }

    // --- the read filter, end to end -----------------------------------------

    "an administrator's user list excludes users in another client" {
        val cxt = Startup.mkTestBootCxt("listScope", "adminListScopeTest")
        val admin = TestUser.create(cxt, "scoped-admin@example.com", level = ROLE.admin)
        seedUserInClient(cxt, "outsider@acme.com", otherClient)

        val listed = admin.getItems(ADEP.users).map { it[ADF.primaryId].toOptStr() }
        listed.contains("scoped-admin@example.com") shouldBe true
        listed.contains("outsider@acme.com") shouldBe false
    }

    /** The search path has its own SQL, so the predicate has to be proven on it too, not just the list-all. */
    "the search path is scoped as well as the list-all path" {
        val cxt = Startup.mkTestBootCxt("searchScope", "adminSearchScopeTest")
        val admin = TestUser.create(cxt, "search-admin@example.com", level = ROLE.admin)
        seedUserInClient(cxt, "hidden@acme.com", otherClient)

        // Searching for the exact address of a user in another client still finds nothing.
        admin.getItems(ADEP.users, mapOf(ADF.search to "hidden@acme.com")).isEmpty() shouldBe true
    }

    /**
     * Out of scope reads as absent, not forbidden. A 403 would confirm the id belongs to a real user in a
     * client the caller cannot see, which is the probe a scoped administrator must not have.
     */
    "a user in another client is a 404 rather than a 403" {
        val cxt = Startup.mkTestBootCxt("loadScope", "adminLoadScopeTest")
        val admin = TestUser.create(cxt, "load-admin@example.com", level = ROLE.admin)
        val hiddenId = seedUserInClient(cxt, "unreachable@acme.com", otherClient)

        admin.expectError(EXC.notFound, ADEP.userSetEnabled, mapOf(ADF.userId to hiddenId, ADF.enabled to false))
    }

    /**
     * The counterpart that makes the exclusion tests mean something: proving the hidden rows are *there* and
     * merely filtered, rather than never created. Without this, a seeding bug would make every scope test
     * above pass for the wrong reason.
     */
    "an allClients administrator sees the clients a scoped one cannot" {
        val cxt = Startup.mkTestBootCxt("globalScope", "adminGlobalScopeTest")
        seedUserInClient(cxt, "elsewhere@acme.com", otherClient)
        seedUserInClient(cxt, "global-admin@example.com", CL.public, ROLE.user, ROLE.admin, ROLE.allClients)

        // Becoming an existing user returns them as they are, so this logs in with the seeded capability.
        val global = TestUser.create(cxt, "global-admin@example.com")
        global.selfRoles().contains(ROLE.allClients) shouldBe true

        val listed = global.getItems(ADEP.users).map { it[ADF.primaryId].toOptStr() }
        listed.contains("elsewhere@acme.com") shouldBe true   // the row a scoped admin cannot see
        listed.contains("global-admin@example.com") shouldBe true
    }

    "an administrator can still act on a user in their own client" {
        val cxt = Startup.mkTestBootCxt("ownScope", "adminOwnScopeTest")
        val admin = TestUser.create(cxt, "own-admin@example.com", level = ROLE.admin)
        val target = TestUser.create(cxt, "own-target@example.com")

        val updated = admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to target.userId, ADF.enabled to false))
        updated[ADF.enabled] shouldBe false
        updated[ADF.userId].toOptLong() shouldBe target.userId
    }

    // --- anti-escalation ------------------------------------------------------

    /**
     * Without this, a client-scoped administrator could promote someone inside their own client to see every
     * client, and then act through them -- the scope would be a suggestion rather than a boundary.
     */
    "an administrator cannot grant a reach they do not have" {
        val cxt = Startup.mkTestBootCxt("escalate", "adminEscalateTest")
        val admin = TestUser.create(cxt, "escalate-admin@example.com", level = ROLE.admin)
        val target = TestUser.create(cxt, "escalate-target@example.com")

        admin.selfRoles().contains(ROLE.allClients) shouldBe false
        admin.expectError(
            EXC.badInput,
            ADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.allClients)),
        )

        // The ordinary grant beside it still works, so the guard is about the capability and not about roles.
        admin.postData(
            ADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.operator)),
        )[ADF.roles] shouldBe listOf(ROLE.user, ROLE.operator)
    }
})
