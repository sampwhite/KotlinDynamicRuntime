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
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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

        ReadScopeRules.forCaller(acting(ROLE.user, ROLE.admin)).client shouldBe otherClient
        ReadScopeRules.forCaller(acting(ROLE.user, ROLE.admin, ROLE.allClients)).isUnrestricted shouldBe true
    }

    /**
     * The four widths in one place (issue #225), read off the resolver rather than off a surface -- because
     * the narrowest of them has no surface yet. Every scoped read today is on a user-list endpoint behind an
     * administrative section, so an ordinary caller never arrives at one; this is what the first ordinary
     * endpoint over an owned table will be handed, and covering it here is what stops it being invented by
     * hand later.
     */
    "the read scope resolves a width for every caller, not only administrators" {
        val cxt = Startup.mkTestBootCxt("widths", "readScopeWidthsTest")

        fun acting(org: String?, vararg roles: String): KdrCxt = KdrCxt(
            "widthCase", cxt.instanceConfig, null,
            UserProfile(authId = "7", userId = 7L, client = otherClient, org = org, roles = roles.toSet()),
        )

        // Narrowest: not an administrator at all -- their own rows, and not their client's.
        val own = ReadScopeRules.forCaller(acting(null, ROLE.user))
        own.userId shouldBe 7L
        own.client shouldBe null
        own.isUnrestricted shouldBe false

        // An operator is no wider: the ladder says what may be done, not over whose rows.
        ReadScopeRules.forCaller(acting(null, ROLE.user, ROLE.operator)).userId shouldBe 7L

        // An administrator with a primary organization: that org within their client...
        val orgScope = ReadScopeRules.forCaller(acting("eng", ROLE.user, ROLE.admin))
        orgScope.client shouldBe otherClient
        orgScope.org shouldBe "eng"
        orgScope.userId shouldBe null // widened past their own rows, which is the point of the level

        // ...and without one, the whole client.
        val clientScope = ReadScopeRules.forCaller(acting(null, ROLE.user, ROLE.admin))
        clientScope.client shouldBe otherClient
        clientScope.org shouldBe null

        // Widest: the capability, which is not confined by an organization the holder happens to have.
        ReadScopeRules.forCaller(acting("eng", ROLE.user, ROLE.admin, ROLE.allClients))
            .isUnrestricted shouldBe true
    }

    /**
     * Why the `GrantRole` script passes [ReadScope.unrestricted] by hand instead of asking the resolver.
     *
     * It boots the runtime with no logged-in caller, so the resolver confines it to the system user's own
     * (nonexistent) rows -- and the script's entire job is finding the first administrator in a deployment
     * that has none. What entitles it is the shell rather than a role. This pins both halves, so "simplifying"
     * the script to `forCaller` fails here rather than silently listing nobody.
     */
    "a caller with no session is confined, which is why the operator script asks for the whole table" {
        val cxt = Startup.mkTestBootCxt("scriptScope", "scriptScopeTest")
        seedUserInClient(cxt, "script-visible@acme.com", otherClient)

        // The context a script boots with: the system profile, holding no roles.
        val systemCxt = KdrCxt("scriptCase", cxt.instanceConfig, null)
        ReadScopeRules.forCaller(systemCxt).isUnrestricted shouldBe false
        users(cxt).listUsers(systemCxt, null, 50, ReadScopeRules.forCaller(systemCxt)).isEmpty() shouldBe true

        // Stated explicitly, the same call reaches the row -- what `--list` depends on.
        users(cxt).listUsers(systemCxt, null, 50, ReadScope.unrestricted)
            .map { it.primaryId } shouldContain "script-visible@acme.com"
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

    /**
     * The other half of "reserved", and the one that was wrong: the **capability alone** must not open the
     * full-scope surface either. A user demoted to [ROLE.user] keeps their off-ladder capabilities by design
     * (`RoleLadder.rolesAtLevel`, so a level edit does not silently drop one) -- which meant that while the
     * `admin` section named `allClients` as its *required role*, a demotion left cross-client user
     * administration intact while correctly removing the lesser scoped surface. Exactly the wrong way round.
     *
     * `RoleLadder.satisfies` falls back to exact membership off the ladder, so naming a capability as the
     * required role makes holding it sufficient on its own. It has to qualify the level, not replace it.
     */
    "the capability alone does not open the admin surface" {
        val cxt = Startup.mkTestBootCxt("demoted", "demotedCapabilityTest")

        val demoted = TestUser.create(
            cxt, "demoted-cap@example.com", level = ROLE.user, capabilities = listOf(ROLE.allClients),
        )
        demoted.selfRoles() shouldBe listOf(ROLE.user, ROLE.allClients)
        seedUserInClient(cxt, "demoted-outsider@acme.com", otherClient)

        // Both administration surfaces are closed: the full-scope one for want of the level, the scoped one
        // for the same reason. Reading and writing alike -- a create would otherwise be a way back in.
        demoted.expectError(EXC.notAuthorized, ADEP.users)
        demoted.expectError(EXC.notAuthorized, UADEP.users)
        demoted.expectError(EXC.notAuthorized, ADEP.userCreate, mapOf(ADF.primaryId to "demoted-made@example.com"))

        // And the catalog agrees, since #211 filters it on the same predicate the gate enforces: a surface
        // they cannot call is not one they are shown.
        val visible = demoted.getItems("/schema/endpoints").map { it["path"].toOptStr() }
        visible.any { it == ADEP.users } shouldBe false
        visible.any { it == UADEP.users } shouldBe false
    }

    /** The level alone is not enough either -- both halves are required, in both directions. */
    "the full-scope surface requires the level and the capability together" {
        val cxt = Startup.mkTestBootCxt("bothHalves", "bothHalvesTest")

        // Level without capability: refused (the scoped surface is what they get instead).
        TestUser.create(cxt, "halves-scoped@example.com", level = ROLE.admin)
            .expectError(EXC.notAuthorized, ADEP.users)
        // Capability without level: refused.
        TestUser.create(cxt, "halves-cap@example.com", level = ROLE.user, capabilities = listOf(ROLE.allClients))
            .expectError(EXC.notAuthorized, ADEP.users)
        // Both: admitted.
        TestUser.createFullAdmin(cxt, "halves-full@example.com").getItems(ADEP.users).isEmpty() shouldBe false
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
     * An administrator can make and manage a business account: create one, mark an existing account, rename it,
     * and clear it (which drops the name). This is the non-registration path -- registration marks a business
     * at signup, and this is how an administrator does it after the fact or on someone else's account.
     */
    "an administrator creates and edits a business account" {
        val cxt = Startup.mkTestBootCxt("entityAdmin", "entityAdminTest")
        val admin = TestUser.createFullAdmin(cxt, "entity-admin@example.com")

        // Created as a business, with a name.
        val created = admin.postData(
            UADEP.userCreate,
            mapOf(ADF.primaryId to "acme@example.com", ADF.isEntity to true, ADF.entityName to "Acme Co"),
        )
        created[ADF.isEntity] shouldBe true
        created[ADF.entityName] shouldBe "Acme Co"

        // An ordinary account, then marked a business after the fact.
        val person = TestUser.create(cxt, "shop@example.com")
        val marked = admin.postData(
            UADEP.userSetEntity,
            mapOf(ADF.userId to person.userId, ADF.isEntity to true, ADF.entityName to "Corner Shop"),
        )
        marked[ADF.isEntity] shouldBe true
        marked[ADF.entityName] shouldBe "Corner Shop"

        // Cleared -- the flag goes and so does the name, leaving no stale business name behind.
        val cleared = admin.postData(UADEP.userSetEntity, mapOf(ADF.userId to person.userId, ADF.isEntity to false))
        cleared[ADF.isEntity] shouldBe false
        (cleared[ADF.entityName] == null || cleared[ADF.entityName] == "") shouldBe true
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

    // --- organizations within a client -----------------------------------------

    /**
     * An administrator with a primary organization is confined one width further than their client. The
     * org-less row is the interesting one: it stays visible, which is the lenient rule that keeps a client's
     * pre-organization content from vanishing the day somebody is given an org.
     */
    "an administrator with a primary org sees their org and the org-less, but not another org" {
        val cxt = Startup.mkTestBootCxt("orgScope", "orgScopeTest")
        val service = users(cxt)
        val full = TestUser.createFullAdmin(cxt, "org-full@example.com")

        val inEng = TestUser.create(cxt, "org-eng@example.com", level = ROLE.admin)
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to inEng.userId, ADF.org to "eng"))
        val inSales = TestUser.create(cxt, "org-sales@example.com")
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to inSales.userId, ADF.org to "sales"))
        TestUser.create(cxt, "org-none@example.com") // no organization at all

        // The org lands on the profile, so it reaches the scope without a database read per request.
        val engAdmin = TestUser.create(cxt, "org-eng@example.com")
        val listed = engAdmin.getItems(UADEP.users).map { it[ADF.primaryId].toOptStr() }
        listed.contains("org-eng@example.com") shouldBe true    // their own org
        listed.contains("org-none@example.com") shouldBe true   // no org: belongs to the client
        listed.contains("org-sales@example.com") shouldBe false // another org
    }

    /** The capability outranks a primary organization: the two are different axes, and this is the wider. */
    "allClients is not confined by the administrator's own organization" {
        val cxt = Startup.mkTestBootCxt("orgFull", "orgFullTest")
        val full = TestUser.createFullAdmin(cxt, "orgfull-admin@example.com")
        val other = TestUser.create(cxt, "orgfull-other@example.com")
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to other.userId, ADF.org to "sales"))
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to full.userId, ADF.org to "eng"))

        ReadScopeRules.forCaller(
            KdrCxt(
                "orgFullCase", cxt.instanceConfig, null,
                UserProfile(
                    authId = "1", userId = 1L, client = CL.public, org = "eng",
                    roles = setOf(ROLE.user, ROLE.admin, ROLE.allClients),
                ),
            ),
        ).isUnrestricted shouldBe true
    }

    /**
     * Assigning an organization is itself scoped. A different one would edit a user out of the caller's sight;
     * clearing one is worse, since an org-less row is visible to the whole client -- so a confined
     * administrator would be widening someone's reach past their own. Applied to themselves, it is the escape
     * hatch from confinement, which is why no separate self-check is needed.
     */
    "an administrator confined to an org may only assign that org" {
        val cxt = Startup.mkTestBootCxt("orgAssign", "orgAssignTest")
        val full = TestUser.createFullAdmin(cxt, "assign-full@example.com")
        val confined = TestUser.create(cxt, "assign-confined@example.com", level = ROLE.admin)
        val target = TestUser.create(cxt, "assign-target@example.com")
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to confined.userId, ADF.org to "eng"))
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to target.userId, ADF.org to "eng"))

        val engAdmin = TestUser.create(cxt, "assign-confined@example.com")
        engAdmin.selfOrg() shouldBe "eng"

        // Their own organization: allowed.
        engAdmin.postData(UADEP.userSetOrg, mapOf(ADF.userId to target.userId, ADF.org to "eng"))
        // A different one, and clearing it: both refused.
        engAdmin.expectError(EXC.badInput, UADEP.userSetOrg, mapOf(ADF.userId to target.userId, ADF.org to "sales"))
        engAdmin.expectError(EXC.badInput, UADEP.userSetOrg, mapOf(ADF.userId to target.userId))
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
     * The search matches a business name too, not only the email and username. `entityName` is not an SQL
     * column, so the term is applied after the query -- this proves that path finds an entity whose email and
     * username share nothing with the name being searched.
     */
    "the search matches an entity's business name, case-insensitively" {
        val cxt = Startup.mkTestBootCxt("searchEntity", "adminSearchEntityTest")
        val service = users(cxt)
        // A personal user whose address has no overlap with the business name below, so it cannot be the hit.
        seedUserInClient(cxt, "person@example.com", CL.public)
        // An entity whose address is likewise unrelated to its name, so a match can only be the name matching.
        val entityId = seedUserInClient(cxt, "contact@example.com", CL.public)
        val entity = service.queryAdministrableUser(cxt, entityId, ReadScope.unrestricted)
            ?: error("Seeded entity should be readable.")
        entity.isEntity = true
        entity.entityName = "Umbrella Logistics"
        service.updateUser(cxt, entity)

        fun emails(term: String) = service.listUsers(cxt, term, 100, ReadScope.unrestricted).map { it.primaryId }

        // A substring of the business name finds the entity, and only the entity...
        emails("umbrella") shouldBe listOf("contact@example.com")
        // ...case-insensitively...
        emails("LOGISTICS") shouldBe listOf("contact@example.com")
        // ...while email still matches as before, and the name term does not drag in the personal account.
        emails("person@example.com") shouldBe listOf("person@example.com")
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
