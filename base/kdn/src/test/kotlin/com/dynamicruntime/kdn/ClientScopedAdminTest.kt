package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AdminScope
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Client-scoped administration (issue #225): two surfaces over the same operations, and the read filter that
 * confines one of them.
 *
 *  - The **`admin`** section requires [ROLE.allClients] -- full-scope, every client.
 *  - The **`userAdmin`** section requires only [ROLE.admin] and confines every read to the caller's scope. A
 *    holder of the capability satisfies it too and is simply unconfined there, which is why a console can be
 *    built on it without branching on who is asking.
 *
 * The scoping is proven at both levels on purpose. End to end through `userAdmin`, which is how a real caller
 * meets it; and at the service level, where the two scopes can be compared *directly on the same rows* --
 * an endpoint test can only show an absence, which a seeding bug would satisfy just as well.
 *
 * Seeding a second client goes through [UserService.insertUser] directly, because no endpoint puts a user in a
 * client other than the caller's own.
 */
class ClientScopedAdminTest : StringSpec({

    val otherClient = "acme"

    fun users(cxt: KdrCxt): UserService =
        (UserService.get(cxt) ?: error("UserService is required by this test.")).also { it.checkInit(cxt) }

    /** Inserts a user directly into [client], which no endpoint can currently do. */
    fun seedUserInClient(cxt: KdrCxt, email: String, client: String): Long =
        users(cxt).insertUser(cxt, AuthUserRow.mkInitialUser(email, client, listOf(ROLE.user)))

    // --- the scope as policy ---------------------------------------------------

    "the scope narrows by role, and only allClients widens it" {
        val cxt = Startup.mkTestBootCxt("scope", "adminScopeTest")

        fun acting(vararg roles: String): KdrCxt = KdrCxt(
            "scopeCase", cxt.instanceConfig, null,
            UserProfile(authId = "1", userId = 1L, client = otherClient, roles = roles.toSet()),
        )

        AdminRules.adminScope(acting(ROLE.user)) shouldBe AdminScope.none
        AdminRules.adminScope(acting(ROLE.user, ROLE.operator)) shouldBe AdminScope.none
        AdminRules.adminScope(acting(ROLE.user, ROLE.admin)) shouldBe AdminScope.ownClient
        AdminRules.adminScope(acting(ROLE.user, ROLE.admin, ROLE.allClients)) shouldBe AdminScope.allClients

        AdminRules.adminReadScope(acting(ROLE.user, ROLE.admin)).client shouldBe otherClient
        AdminRules.adminReadScope(acting(ROLE.user, ROLE.admin, ROLE.allClients)).isUnrestricted shouldBe true
    }

    /**
     * The menu must not offer a page the caller would be refused -- the drift issue #211 exists to prevent.
     * Any administrator qualifies now that the scoped surface exists: what differs between them is how much
     * they see once there, not whether they may go.
     */
    "canManageUsers agrees with what the surface actually admits" {
        val cxt = Startup.mkTestBootCxt("canManage", "canManageUsersTest")

        fun acting(vararg roles: String): KdrCxt = KdrCxt(
            "canManageCase", cxt.instanceConfig, null,
            UserProfile(authId = "1", userId = 1L, client = CL.public, roles = roles.toSet()),
        )

        AdminRules.canManageUsers(acting(ROLE.user)) shouldBe false
        AdminRules.canManageUsers(acting(ROLE.user, ROLE.admin)) shouldBe true // scoped, but has a surface
        AdminRules.canManageUsers(acting(ROLE.user, ROLE.admin, ROLE.allClients)) shouldBe true
    }

    /** The capability is a capability: it must not become a rung, or it would confer a level of its own. */
    "allClients is not on the privilege ladder" {
        RoleLadder.ordered.contains(ROLE.allClients) shouldBe false
        RoleLadder.rankOf(ROLE.allClients) shouldBe null
    }

    // --- the surface is reserved (what this change adds) -----------------------

    /**
     * The point of the change: the level alone does not open the admin surface. Since #211 the catalog filters
     * on the same comparison the gate enforces, so this covers being able to *see* those endpoints too.
     */
    "the admin surface admits a full-scope administrator and refuses a scoped one" {
        val cxt = Startup.mkTestBootCxt("reserved", "adminReservedTest")

        val scoped = TestUser.create(cxt, "scoped-admin@example.com", level = ROLE.admin)
        scoped.selfRoles().contains(ROLE.admin) shouldBe true
        scoped.selfRoles().contains(ROLE.allClients) shouldBe false
        scoped.expectError(EXC.notAuthorized, ADEP.users)

        TestUser.createFullAdmin(cxt, "full-admin@example.com").getItems(ADEP.users).isEmpty() shouldBe false
    }

    // --- the scoped surface, end to end ---------------------------------------

    /**
     * The whole point of the two surfaces: the same operations, reached by a lesser role and confined. A
     * scoped administrator is refused the full-scope surface and admitted to their own.
     */
    "a scoped administrator works through userAdmin and is refused admin" {
        val cxt = Startup.mkTestBootCxt("scopedSurface", "scopedSurfaceTest")
        val scoped = TestUser.create(cxt, "surface-admin@example.com", level = ROLE.admin)
        seedUserInClient(cxt, "surface-outsider@acme.com", otherClient)

        scoped.expectError(EXC.notAuthorized, ADEP.users)

        val listed = scoped.getItems(UADEP.users).map { it[ADF.primaryId].toOptStr() }
        listed.contains("surface-admin@example.com") shouldBe true
        listed.contains("surface-outsider@acme.com") shouldBe false // the confinement, through a real request
    }

    /** A full-scope administrator uses the same surface and is simply unconfined on it. */
    "the scoped surface serves a full-scope administrator unconfined" {
        val cxt = Startup.mkTestBootCxt("scopedFull", "scopedFullTest")
        val full = TestUser.createFullAdmin(cxt, "surface-full@example.com")
        seedUserInClient(cxt, "surface-elsewhere@acme.com", otherClient)

        val listed = full.getItems(UADEP.users).map { it[ADF.primaryId].toOptStr() }
        listed.contains("surface-elsewhere@acme.com") shouldBe true
    }

    /**
     * The anti-escalation refusal, reachable again now that a granter without the capability has a surface --
     * this is the half that step 1 could not exercise, and the half that matters.
     */
    "a scoped administrator cannot grant reach they do not hold" {
        val cxt = Startup.mkTestBootCxt("scopedEscalate", "scopedEscalateTest")
        val scoped = TestUser.create(cxt, "surface-granter@example.com", level = ROLE.admin)
        val target = TestUser.create(cxt, "surface-target@example.com")

        scoped.selfRoles().contains(ROLE.allClients) shouldBe false
        scoped.expectError(
            EXC.badInput,
            UADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.allClients)),
        )

        // An ordinary grant beside it still works, so the guard is about the capability and not about roles.
        scoped.postData(
            UADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.operator)),
        )[ADF.roles] shouldBe listOf(ROLE.user, ROLE.operator)
    }

    /**
     * A created user lands in the creator's client, not always `public`. Otherwise a scoped administrator
     * would create somebody they could not then see -- which is how the old hardcoded client would have read.
     */
    "a user created through the scoped surface is visible to its creator" {
        val cxt = Startup.mkTestBootCxt("scopedCreate", "scopedCreateTest")
        val scoped = TestUser.create(cxt, "surface-creator@example.com", level = ROLE.admin)

        scoped.postData(UADEP.userCreate, mapOf(ADF.primaryId to "surface-made@example.com"))

        scoped.getItems(UADEP.users, mapOf(ADF.search to "surface-made@example.com"))
            .map { it[ADF.primaryId].toOptStr() } shouldBe listOf("surface-made@example.com")
    }

    /**
     * Anti-escalation is a check on *adding*, not on the resulting set. A role list replaces rather than
     * merges, so editing somebody who already holds the capability sends it back unchanged -- and judging the
     * result alone would refuse that, leaving a scoped administrator unable to touch such a user at all, for a
     * reason that has nothing to do with what they were changing.
     */
    "a scoped administrator may preserve a capability they cannot grant" {
        val cxt = Startup.mkTestBootCxt("preserveCap", "preserveCapTest")
        val full = TestUser.createFullAdmin(cxt, "preserve-full@example.com")
        val scoped = TestUser.create(cxt, "preserve-scoped@example.com", level = ROLE.admin)
        val target = TestUser.create(cxt, "preserve-target@example.com")

        // The full-scope administrator grants it; the scoped one then edits that user's *level*.
        full.postData(
            UADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.allClients)),
        )

        val after = scoped.postData(
            UADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.operator, ROLE.allClients)),
        )[ADF.roles] as List<*>
        after.contains(ROLE.operator) shouldBe true
        after.contains(ROLE.allClients) shouldBe true // preserved, not granted

        // Adding it to somebody who does not have it is still refused.
        val other = TestUser.create(cxt, "preserve-other@example.com")
        scoped.expectError(
            EXC.badInput,
            UADEP.userSetRoles,
            mapOf(ADF.userId to other.userId, ADF.roles to listOf(ROLE.user, ROLE.allClients)),
        )
    }

    // --- the read filter, at the service level ---------------------------------

    "the scope filters the user list, and unrestricted reach does not" {
        val cxt = Startup.mkTestBootCxt("listScope", "adminListScopeTest")
        val service = users(cxt)
        seedUserInClient(cxt, "insider@example.com", CL.public)
        seedUserInClient(cxt, "outsider@acme.com", otherClient)

        fun addresses(scope: ReadScope) = service.listUsers(cxt, null, 100, scope).map { it.primaryId }

        // Both rows exist; the scope is the only difference between the two answers.
        addresses(ReadScope.unrestricted).containsAll(
            listOf("insider@example.com", "outsider@acme.com"),
        ) shouldBe true
        addresses(ReadScope.ofClient(CL.public)).contains("insider@example.com") shouldBe true
        addresses(ReadScope.ofClient(CL.public)).contains("outsider@acme.com") shouldBe false
        addresses(ReadScope.ofClient(otherClient)).contains("outsider@acme.com") shouldBe true
    }

    /** The search path builds its own SQL, so the predicate has to be proven on it too. */
    "the scope filters the search path as well as the list-all path" {
        val cxt = Startup.mkTestBootCxt("searchScope", "adminSearchScopeTest")
        val service = users(cxt)
        seedUserInClient(cxt, "hidden@acme.com", otherClient)

        // The exact address of a user in another client still finds nothing...
        service.listUsers(cxt, "hidden@acme.com", 100, ReadScope.ofClient(CL.public)).isEmpty() shouldBe true
        // ...while the same term unrestricted finds them, so the emptiness is the scope and not the search.
        service.listUsers(cxt, "hidden@acme.com", 100, ReadScope.unrestricted).size shouldBe 1
    }

    /**
     * Out of scope reads as absent, not forbidden: a caller must not be able to learn that an id belongs to a
     * real user in a client they cannot see. The endpoint turns this null into its own 404.
     */
    "a user outside the scope loads as absent rather than forbidden" {
        val cxt = Startup.mkTestBootCxt("loadScope", "adminLoadScopeTest")
        val service = users(cxt)
        val hiddenId = seedUserInClient(cxt, "unreachable@acme.com", otherClient)

        service.queryAdministrableUser(cxt, hiddenId, ReadScope.ofClient(CL.public)) shouldBe null
        service.queryAdministrableUser(cxt, hiddenId, ReadScope.unrestricted) shouldNotBe null
        service.queryAdministrableUser(cxt, hiddenId, ReadScope.ofClient(otherClient)) shouldNotBe null
    }

    // --- anti-escalation -------------------------------------------------------

    /**
     * A granter may not hand out reach they do not hold. Only the *positive* half is reachable today: every
     * caller of the admin surface holds [ROLE.allClients], so nobody exists who could be refused. The refusal
     * path becomes testable end to end once a scoped administrator has a surface of their own; until then this
     * at least proves the guard does not block a legitimate granter.
     */
    "a full-scope administrator may grant the capability they hold" {
        val cxt = Startup.mkTestBootCxt("escalate", "adminEscalateTest")
        val admin = TestUser.createFullAdmin(cxt, "escalate-admin@example.com")
        val target = TestUser.create(cxt, "escalate-target@example.com")

        val roles = admin.postData(
            ADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.allClients)),
        )[ADF.roles] as List<*>
        roles.contains(ROLE.allClients) shouldBe true
    }
})
