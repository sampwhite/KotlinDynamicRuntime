package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.user.SandboxAccess
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The Shadow Sandbox's access rules (issue #929): authority flows from a parent to its sandbox, never back. Rule 1 --
 * a parent administrator **opens** the sandbox as their own user in it, under the same identity. Rule 2 -- a user
 * somebody opened the sandbox as acts as the user outside they opened it from, while that one stands for the parent;
 * a sandbox's own user acts with its row, `admin` taking **effect** only for an identity that is an administrator in
 * the parent (or an `allClients` one). What is pinned is what a session can do, read at each request, whatever its
 * row was made with.
 *
 * One booted instance; a parent client per case.
 */
class SandboxAccessTest : StringSpec({
    val cxt = TestInstances.default("sandboxAccess929")

    /** Defines [parent], with a sandbox unless [sandbox] is false, and makes it live. */
    fun defineParent(parent: String, sandbox: Boolean = true) {
        val config = gedraConfig(cxt, "main", clientNamespace(parent), parent) {
            defineClient(
                ClientDef(
                    clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                ),
            )
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", parent).also { it.userId = 9290L }, config)
        GedraConfigReload.reloadClient(cxt, parent)
    }

    /** Whether [user]'s session acts as an administrator: its roles say so, and the `clientAdmin` gate admits it. */
    fun actsAsAdmin(user: TestUser): Boolean {
        val listed = ROLE.admin in user.selfRoles()
        // An error comes back as an envelope with a status, not a throw; a success carries none.
        val admitted = user.client.sendJsonGetRequest(CFEP.bundles)[EP.status] == null
        admitted shouldBe listed
        return admitted
    }

    /** [user]'s access report. A diagnostic tag, so it answers a caller with test access (issue #1149). */
    fun accessReport(user: TestUser): Map<String, Any?> {
        val resp = user.withTestAccess { user.client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainAccess)) }
        return resp[EP.meta]!!.toJsonMap()[SS.accessExplained]!!.toJsonMap()
    }

    /**
     * The roles the access report says [user]'s row holds and a rule withholds, each with its rule. Read from the
     * session's own row, so an answer also says the session is still that user's -- which an empty role list alone
     * would not, since a lost session has none either.
     */
    fun accessWithheld(user: TestUser): Map<String, String> =
        accessReport(user)[SS.rolesWithheld].toJsonListOfMaps()
            .associate { it[SS.role].toOptStr().shouldNotBeNull() to it[SS.rule].toOptStr().shouldNotBeNull() }

    /** The user outside the sandbox the access report says [user]'s session acts as, or null. */
    fun accessActsAs(user: TestUser): Long? = accessReport(user)[SS.sandboxActsAs].toJsonMapOrEmpty()[UPF.userId].toOptLong()

    /** Whether [user] may call what any member may: the gate's `user` level, on an endpoint every client serves. */
    fun actsAsMember(user: TestUser): Boolean = user.client.sendJsonGetRequest(AEP.selfUsers)[EP.status] == null

    fun setRoles(userId: Long, roles: List<String>) {
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, userId).shouldNotBeNull()
        row.roles = roles
        users.updateUser(cxt, row)
    }

    "a parent administrator opens the sandbox as their own user there, and acts as an administrator in it" {
        val parent = "sbxopen"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val opened = admin.postData(AEP.openSandbox, emptyMap())
        opened[UPF.client] shouldBe sandboxOf(parent)
        opened[UPF.userId].toOptLong() shouldNotBe admin.userId
        admin.selfClient() shouldBe sandboxOf(parent)
        actsAsAdmin(admin) shouldBe true
    }

    // The shell offers what the open endpoint admits, and marks the sandbox once inside (issue #931).
    "the shell config offers opening to a parent administrator, and names the parent inside the sandbox" {
        val parent = "sbxshell"
        defineParent(parent)
        fun shell(user: TestUser): Map<String, Any?> = user.getData(HEP.homeUiConfig)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        shell(admin)[UIC.features].toJsonMapOrEmpty()[HFEAT.canOpenSandbox] shouldBe true
        shell(TestUser.create(cxt, "member@$parent.test", userClient = parent))[UIC.features].toJsonMapOrEmpty()[HFEAT.canOpenSandbox] shouldBe false

        admin.postData(AEP.openSandbox, emptyMap())
        val inside = shell(admin)
        inside[UIC.features].toJsonMapOrEmpty()[HFEAT.canOpenSandbox] shouldBe false
        inside[UIC.state].toJsonMapOrEmpty()[HFLD.sandboxOf] shouldBe parent
        inside[UIC.state].toJsonMapOrEmpty()[HFLD.sandboxOfName] shouldBe "Client $parent"
        // The way back is among the users the switcher lists: the person's own user in the parent.
        inside[UIC.state].toJsonMapOrEmpty()[HFLD.users].toJsonListOfMaps().map { it[UCF.client] } shouldContain parent
    }

    "opening twice finds the same user rather than making a second" {
        val parent = "sbxtwice"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val first = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong()
        admin.postData(AEP.switchUser, mapOf(AFLD.userId to admin.userId))
        admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong() shouldBe first
    }

    "granting does not help: an identity that is no parent administrator is not one in the sandbox" {
        val parent = "sbxgrant"
        defineParent(parent)
        // A member of the parent, granted admin in the sandbox directly -- the role is held, and withheld.
        val member = "member@$parent.test"
        TestUser.create(cxt, member, userClient = parent)
        val granted = TestUser.create(cxt, member, level = ROLE.admin, userClient = sandboxOf(parent))
        actsAsAdmin(granted) shouldBe false
        // Its other roles stand: only admin is withheld.
        granted.selfRoles() shouldContain ROLE.user
        actsAsMember(granted) shouldBe true
        // The access report names the rule, so the missing role does not read as never held.
        accessWithheld(granted) shouldBe mapOf(ROLE.admin to SandboxAccess.Withheld.admin.rule)

        // An identity with no user in the parent at all is nobody there: a sandbox user holding admin acts for
        // somebody outside, and there is nobody.
        val stranger = TestUser.create(cxt, "stranger@$parent.test", level = ROLE.admin, userClient = sandboxOf(parent))
        actsAsAdmin(stranger) shouldBe false
        actsAsMember(stranger) shouldBe false
        accessWithheld(stranger) shouldBe mapOf(ROLE.admin to SandboxAccess.Withheld.noStanding.rule, ROLE.user to SandboxAccess.Withheld.noStanding.rule)
    }

    "demoting or removing the parent administrator revokes the sandbox user's admin at its next request" {
        val parent = "sbxrevoke"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentUserId = admin.userId
        admin.postData(AEP.openSandbox, emptyMap())
        actsAsAdmin(admin) shouldBe true

        setRoles(parentUserId, listOf(ROLE.user))
        actsAsAdmin(admin) shouldBe false
        setRoles(parentUserId, listOf(ROLE.user, ROLE.admin))
        actsAsAdmin(admin) shouldBe true

        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, parentUserId).shouldNotBeNull()
        row.enabled = false
        users.updateUser(cxt, row)
        actsAsAdmin(admin) shouldBe false
    }

    // --- What a sandbox user holds besides `admin` -----------------------------------------------------------------
    //
    // A user somebody opened the sandbox as acts as the user outside they opened it from, asked at each request
    // (`SandboxAccess.standingOf`) -- not with what its own row was made with the day the sandbox was opened. Each
    // case changes something outside, or the sandbox row, and asks the sandbox session.

    /** What [user]'s session can do across clients: what its roles say, and what three gates answer for [other]. */
    class Reach(val listed: Boolean, val adminSection: Boolean, val namesOther: Boolean, val overviewShowsOther: Boolean) {
        val any: Boolean get() = listed || adminSection || namesOther || overviewShowsOther
        override fun toString(): String =
            "allClients listed=$listed, admin section admits=$adminSection, names another client=$namesOther, overview lists another=$overviewShowsOther"
    }

    fun reachOf(user: TestUser, other: String): Reach = Reach(
        listed = ROLE.allClients in user.selfRoles(),
        // The `admin` section: `admin` and `allClients` both.
        adminSection = user.client.sendJsonGetRequest(ACEP.bundles, mapOf(CFEP.client to other))[EP.status] == null,
        // The scoped surface, naming a client that is not the caller's own: admitted only to one who sees every client.
        namesOther = user.client.sendJsonGetRequest(UADEP.clientDefinition, mapOf(CLD.client to other))[EP.status] == null,
        overviewShowsOther = user.getItems(UADEP.clientsOverview).any { it[CLD.clientId] == other },
    )

    "allClients taken away outside the sandbox is no longer held inside it, for an administrator of the parent" {
        val (parent, other) = "sbxcap" to "sbxcapother"
        defineParent(parent)
        defineParent(other, sandbox = false)
        // An administrator of the parent who also sees every client.
        val admin = TestUser.createFullAdmin(cxt, "full@$parent.test", userClient = parent)
        val parentUserId = admin.userId
        val opened = admin.postData(AEP.openSandbox, emptyMap())
        opened[UPF.client] shouldBe sandboxOf(parent)
        withClue("in the sandbox, while they hold it outside") { reachOf(admin, other).toString() shouldBe Reach(true, true, true, true).toString() }
        // Held through their user outside: the sandbox user's own row was never given it.
        UserService.get(cxt).queryByUserId(cxt, opened[UPF.userId].toOptLong().shouldNotBeNull()).shouldNotBeNull().roles shouldBe listOf(ROLE.admin, ROLE.user)

        // The capability is taken away. They stay an administrator of the parent, so they stay one in its sandbox --
        // of that one client.
        setRoles(parentUserId, listOf(ROLE.user, ROLE.admin))
        actsAsAdmin(admin) shouldBe true
        withClue("in the sandbox, after allClients was taken away outside") { reachOf(admin, other).toString() shouldBe Reach(false, false, false, false).toString() }
    }

    "opening the sandbox again does not bring back what was taken away" {
        val (parent, other) = "sbxreopen" to "sbxreopenother"
        defineParent(parent)
        defineParent(other, sandbox = false)
        val admin = TestUser.createFullAdmin(cxt, "full@$parent.test", userClient = parent)
        val parentUserId = admin.userId
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong()
        admin.postData(AEP.switchUser, mapOf(AFLD.userId to parentUserId))
        setRoles(parentUserId, listOf(ROLE.user, ROLE.admin))
        // Outside, it is gone at once.
        withClue("outside the sandbox") { reachOf(admin, other).any shouldBe false }

        // The same sandbox user is found again, made when the person held more.
        admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong() shouldBe sandboxUserId
        withClue("in the sandbox, opened again after allClients was taken away") { reachOf(admin, other).toString() shouldBe Reach(false, false, false, false).toString() }
    }

    "an allClients administrator from elsewhere who loses the capability is nobody in the sandbox they opened" {
        val (parent, other) = "sbxaway" to "sbxawayother"
        defineParent(parent)
        defineParent(other, sandbox = false)
        // Their own client is not the parent: what made them an administrator of its sandbox was the capability alone.
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test")
        val homeUserId = full.userId
        full.postData(AEP.openSandbox, mapOf(AFLD.client to parent))[UPF.client] shouldBe sandboxOf(parent)
        actsAsAdmin(full) shouldBe true

        setRoles(homeUserId, listOf(ROLE.user, ROLE.admin))
        // No administrator of the parent and none of every client: the existing rule withholds `admin`.
        actsAsAdmin(full) shouldBe false
        withClue("in the sandbox, after allClients was taken away outside") { reachOf(full, other).toString() shouldBe Reach(false, false, false, false).toString() }
        // Nobody in the parent, and no longer of every client: nothing of theirs stands here.
        full.selfRoles() shouldBe emptyList()
    }

    "allClients given outside the sandbox is held inside it too" {
        val (parent, other) = "sbxgain" to "sbxgainother"
        defineParent(parent)
        defineParent(other, sandbox = false)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentUserId = admin.userId
        admin.postData(AEP.openSandbox, emptyMap())
        withClue("in the sandbox, before") { reachOf(admin, other).any shouldBe false }

        // The other direction, and no hole whichever way it comes out: only whether the two agree.
        setRoles(parentUserId, listOf(ROLE.user, ROLE.admin, ROLE.allClients))
        withClue("in the sandbox, after allClients was given outside") { reachOf(admin, other).toString() shouldBe Reach(true, true, true, true).toString() }
    }

    "a person whose user in the parent is disabled has no session left in its sandbox" {
        val parent = "sbxgone"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentUserId = admin.userId
        admin.postData(AEP.openSandbox, emptyMap())
        admin.selfRoles() shouldContain ROLE.user
        actsAsMember(admin) shouldBe true

        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, parentUserId).shouldNotBeNull()
        row.enabled = false
        users.updateUser(cxt, row)
        // `admin` is withheld, which the case above already holds. What is asked here is whether anything is left:
        // the sandbox user is a row of its own, and nothing disabled it.
        actsAsAdmin(admin) shouldBe false
        withClue("the sandbox session's roles, after the person's user in the parent was disabled") { admin.selfRoles() shouldBe emptyList() }
        // And what a member may call refuses them, as it does a disabled account -- the session still theirs.
        actsAsMember(admin) shouldBe false
        accessWithheld(admin).values.toSet() shouldBe setOf(SandboxAccess.Withheld.noStanding.rule)

        // Enabled again, they are who they were: nothing was written to the sandbox user either way.
        row.enabled = true
        users.updateUser(cxt, row)
        actsAsAdmin(admin) shouldBe true
    }

    "a level taken away outside the sandbox is no longer held inside it" {
        val parent = "sbxlevel"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentUserId = admin.userId
        setRoles(parentUserId, listOf(ROLE.user, ROLE.operator, ROLE.admin))
        admin.postData(AEP.openSandbox, emptyMap())
        fun operates(): Boolean = admin.client.sendJsonGetRequest(CFD.cfactsPath)[EP.status] == null
        operates() shouldBe true

        // Demoted to a member: what stood below `admin` on the sandbox user's own row does not stand in for it.
        setRoles(parentUserId, listOf(ROLE.user))
        admin.selfRoles() shouldBe listOf(ROLE.user)
        operates() shouldBe false
        // The report says whose roles these are, rather than listing the row's as withheld: they are a record.
        accessActsAs(admin) shouldBe parentUserId
        accessWithheld(admin) shouldBe emptyMap()
    }

    "a sandbox user acts as the user it was opened from, not another of the person's users in the parent" {
        val parent = "sbxpersona"
        defineParent(parent)
        val address = "chief@$parent.test"
        // A member first -- the lowest user id -- then the administrator the sandbox is opened from.
        val member = TestUser.create(cxt, address, userClient = parent)
        val admin = TestUser.create(cxt, address, level = ROLE.admin, userClient = parent, persona = PERSONA.admin)
        admin.userId shouldNotBe member.userId
        admin.postData(AEP.openSandbox, emptyMap())[UPF.client] shouldBe sandboxOf(parent)

        // Demoted to an operator: the session follows that user, not the member it would find first.
        setRoles(admin.userId, listOf(ROLE.user, ROLE.operator))
        admin.selfRoles().sorted() shouldBe listOf(ROLE.operator, ROLE.user)
        accessActsAs(admin) shouldBe admin.userId
        // And when that user is gone, the member does not take its place.
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, admin.userId).shouldNotBeNull()
        row.enabled = false
        users.updateUser(cxt, row)
        admin.selfRoles() shouldBe emptyList()
    }

    "editing the roles on an opened sandbox user changes nothing it acts with" {
        val parent = "sbxedit"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentUserId = admin.userId
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        // Its `admin` taken off the sandbox row: it is still the person's user from outside, and acts as it.
        setRoles(sandboxUserId, listOf(ROLE.user))
        actsAsAdmin(admin) shouldBe true
        // So it does not become a sandbox user of its own that outlives the person's user in the parent.
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, parentUserId).shouldNotBeNull()
        row.enabled = false
        users.updateUser(cxt, row)
        actsAsMember(admin) shouldBe false
    }

    "opening a sandbox the person already has a user of their own in makes it theirs from outside" {
        val parent = "sbxadopt"
        defineParent(parent)
        val address = "member@$parent.test"
        val inParent = TestUser.create(cxt, address, userClient = parent)
        // A user made in the sandbox under the same key (identity, persona, suffix): the one opening will find.
        val own = TestUser.create(cxt, address, userClient = sandboxOf(parent))
        own.selfRoles() shouldBe listOf(ROLE.user)

        // Promoted in the parent, they open the sandbox: the found user now acts as them.
        setRoles(inParent.userId, listOf(ROLE.user, ROLE.admin))
        inParent.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong() shouldBe own.userId
        actsAsAdmin(inParent) shouldBe true
    }

    "the console does not grant admin in a sandbox, where it is never a grant" {
        val parent = "sbxconsole"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        admin.postData(AEP.openSandbox, emptyMap())
        actsAsAdmin(admin) shouldBe true
        val tryout = TestUser.create(cxt, "tryout@$parent.test", userClient = sandboxOf(parent))

        val refused = admin.expectError(EXC.badInput, UADEP.userSetRoles, mapOf(ADF.userId to tryout.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)))
        (refused[EP.errorMessage] as String).contains("sandbox") shouldBe true
        admin.expectError(EXC.badInput, UADEP.userCreate, mapOf(ADF.primaryId to "made@$parent.test", ADF.roles to listOf(ROLE.user, ROLE.admin)))
        // What is not a grant of admin still goes through.
        admin.client.sendJsonPostRequest(UADEP.userSetRoles, mapOf(ADF.userId to tryout.userId, ADF.roles to listOf(ROLE.user)))[EP.status] shouldBe null
    }

    "a sandbox user nobody stands behind is not where the person's next login lands" {
        val (parent, home) = "sbxland" to "sbxlandhome"
        defineParent(parent)
        defineParent(home, sandbox = false)
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test", userClient = home)
        val homeUserId = full.userId
        val sandboxUserId = full.postData(AEP.openSandbox, mapOf(AFLD.client to parent))[UPF.userId].toOptLong().shouldNotBeNull()
        val users = UserService.get(cxt)
        val identityId = users.queryByUserId(cxt, homeUserId).shouldNotBeNull().identityId
        // The sandbox user is where they last acted, so it is where a login would land while it stands.
        users.queryIdentityById(cxt, identityId).shouldNotBeNull().lastUsedUserId shouldBe sandboxUserId

        // No longer of every client: nobody stands behind the sandbox user, and a login lands at home instead.
        setRoles(homeUserId, listOf(ROLE.user, ROLE.admin))
        full.selfRoles() shouldBe emptyList()
        val identity = users.queryIdentityById(cxt, identityId).shouldNotBeNull()
        users.defaultUserOf(cxt, identity).shouldNotBeNull().userId shouldBe homeUserId
        users.registeredDefaultOf(cxt, identity).shouldNotBeNull().userId shouldBe homeUserId
    }

    // --- Leaving a sandbox (issue #1197) -----------------------------------------------------------------------------

    /** Whether the shell config says [user]'s sandbox user is one nobody stands behind any more. */
    fun shellSaysStranded(user: TestUser): Boolean =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty().let {
            it[HFLD.sandboxOf].shouldNotBeNull()
            it[HFLD.sandboxStranded] == true
        }

    "a sandbox session nobody stands behind can still leave for another of the person's users" {
        val (parent, home) = "sbxleave" to "sbxleavehome"
        defineParent(parent)
        defineParent(home, sandbox = false)
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test", userClient = home)
        val homeUserId = full.userId
        full.postData(AEP.openSandbox, mapOf(AFLD.client to parent))[UPF.client] shouldBe sandboxOf(parent)
        shellSaysStranded(full) shouldBe false

        // No longer of every client: nobody stands behind the sandbox user, and the gated switch is closed to it.
        setRoles(homeUserId, listOf(ROLE.user, ROLE.admin))
        actsAsMember(full) shouldBe false
        shellSaysStranded(full) shouldBe true
        full.expectError(EXC.notAuthorized, AEP.switchUser, mapOf(AFLD.userId to homeUserId))

        // Leaving is not gated by role, so the way back stays open.
        full.postData(AEP.leaveSandbox, mapOf(AFLD.userId to homeUserId))[UPF.client] shouldBe home
        full.selfClient() shouldBe home
        actsAsAdmin(full) shouldBe true
    }

    "leaving goes only from a sandbox, to one of the person's own users" {
        val parent = "sbxleaveonly"
        defineParent(parent)
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val other = TestUser.create(cxt, "other@$parent.test", level = ROLE.admin, userClient = parent)
        // From outside a sandbox: nothing to leave.
        chief.expectError(EXC.badInput, AEP.leaveSandbox, mapOf(AFLD.userId to chief.userId))
        // Signed out: nobody to leave as.
        TestHttpClient(cxt.instanceConfig).sendJsonPostRequest(AEP.leaveSandbox, mapOf(AFLD.userId to chief.userId))[EP.status]
            .toOptLong() shouldBe EXC.authNeeded.toLong()

        val inside = chief.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        // Not to a user of somebody else's.
        chief.expectError(EXC.badInput, AEP.leaveSandbox, mapOf(AFLD.userId to other.userId))
        chief.selfClient() shouldBe sandboxOf(parent)

        // A sandbox user that was itself disabled goes no further here than at the gate -- and, acting with no
        // roles, is said to be stranded and offered no doors, since none would open.
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, inside).shouldNotBeNull()
        row.enabled = false
        users.updateUser(cxt, row)
        chief.expectError(EXC.badInput, AEP.leaveSandbox, mapOf(AFLD.userId to chief.userId))
        shellSaysStranded(chief) shouldBe true
        chief.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.users].toJsonListOfMaps() shouldBe emptyList()
        row.enabled = true
        users.updateUser(cxt, row)
        shellSaysStranded(chief) shouldBe false

        // And a session somebody stands behind leaves the same way.
        chief.postData(AEP.leaveSandbox, mapOf(AFLD.userId to chief.userId))[UPF.client] shouldBe parent
    }

    "a sandbox's own administrator with nobody in the parent is said to be stranded" {
        val parent = "sbxleaveown"
        defineParent(parent)
        val stranger = TestUser.create(cxt, "stranger@$parent.test", level = ROLE.admin, userClient = sandboxOf(parent))
        actsAsMember(stranger) shouldBe false
        shellSaysStranded(stranger) shouldBe true
        // A sandbox's own member, acting with its row's roles, is not.
        shellSaysStranded(TestUser.create(cxt, "tryout@$parent.test", userClient = sandboxOf(parent))) shouldBe false
    }

    // --- What the console says of a sandbox user (issue #1198) -------------------------------------------------------

    "the console says what a sandbox user acts with, and which user an opened one acts as" {
        val parent = "sbxconsoleview"
        defineParent(parent)
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val inside = chief.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        val tryout = TestUser.create(cxt, "tryout@$parent.test", userClient = sandboxOf(parent))
        val stranger = TestUser.create(cxt, "stranger@$parent.test", level = ROLE.admin, userClient = sandboxOf(parent))

        // Every user of the person, through an administrator of every client: the person's users, as the console shows them.
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test")
        fun shown(userId: Long): Map<String, Any?> =
            full.getData(ADEP.userIdentity, mapOf(ADF.userId to userId))[ADF.users].toJsonListOfMaps()
                .single { it[ADF.userId].toOptLong() == userId }

        shown(inside).let {
            it[ADF.actingRoles] shouldBe listOf(ROLE.admin, ROLE.user)
            it[ADF.opened] shouldBe true
            it[ADF.openedBy].toOptLong() shouldBe chief.userId
            it[ADF.actsAsClient] shouldBe parent
        }
        shown(tryout.userId).let { it[ADF.actingRoles] shouldBe listOf(ROLE.user); it.containsKey(ADF.opened) shouldBe false }
        // Holding admin with nobody in the parent: it acts with nothing, which its row's roles alone would hide.
        shown(stranger.userId)[ADF.actingRoles] shouldBe emptyList<String>()
        // Outside a sandbox the row's roles are what it acts with, and nothing more is said.
        shown(chief.userId).let { info -> listOf(ADF.actingRoles, ADF.opened, ADF.openedBy).none { info.containsKey(it) } shouldBe true }

        // As the sandbox's own administrator sees it, through the scoped surface the webapp calls.
        val hub = "sbxconsolehub"
        defineParent(hub, sandbox = false)
        val visitor = TestUser.createFullAdmin(cxt, "visitor@$parent.test", userClient = hub)
        val visiting = visitor.postData(AEP.openSandbox, mapOf(AFLD.client to parent))[UPF.userId].toOptLong().shouldNotBeNull()
        val listed = chief.getItems(UADEP.users).associateBy { it[ADF.userId].toOptLong() }
        // Opened from the parent: the user it acts as is named, since a sandbox administrator is trusted there.
        listed[inside].shouldNotBeNull().let { it[ADF.openedBy].toOptLong() shouldBe chief.userId; it[ADF.actsAsClient] shouldBe parent }
        // Opened from another client by an administrator of every client: said to be opened, and that user left
        // unnamed -- the sandbox's administrator administers nobody there.
        listed[visiting].shouldNotBeNull().let {
            it[ADF.opened] shouldBe true
            it[ADF.actingRoles] shouldBe listOf(ROLE.admin, ROLE.allClients, ROLE.user)
            it.containsKey(ADF.openedBy) shouldBe false
            it.containsKey(ADF.actsAsClient) shouldBe false
        }
        // Disabled, an opened user acts with nothing, whoever it was opened from.
        full.client.sendJsonPostRequest(ADEP.userSetEnabled, mapOf(ADF.userId to visiting, ADF.enabled to false))[EP.status] shouldBe null
        shown(visiting).let { it[ADF.actingRoles] shouldBe emptyList<String>(); it.containsKey(ADF.actsAsClient) shouldBe false }

        // Its roles are not set on the opened user itself: they would change nothing it may do.
        val refused = full.expectError(EXC.badInput, ADEP.userSetRoles, mapOf(ADF.userId to inside, ADF.roles to listOf(ROLE.user)))
        (refused[EP.errorMessage] as String).contains("opened") shouldBe true

        // Once the user it was opened from is gone, the console says it no longer acts as anyone.
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, chief.userId).shouldNotBeNull()
        row.enabled = false
        users.updateUser(cxt, row)
        shown(inside).let {
            it[ADF.actingRoles] shouldBe emptyList<String>()
            it[ADF.openedBy].toOptLong() shouldBe chief.userId
            it[ADF.actsAsClient] shouldBe null
        }
    }

    "with table caching switched off, a sandbox administrator still stands" {
        val parent = "sbxnocache"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        admin.postData(AEP.openSandbox, emptyMap())
        actsAsAdmin(admin) shouldBe true

        // As a node started with `KDR_TABLE_CACHE_DISABLED` holds it: a user cache that is there, and never loads.
        val service = SqlTableCacheService.get(cxt)
        val users = UserService.get(cxt)
        val cache = users.userCache.shouldNotBeNull()
        service.isDisabled = true
        users.userCache = SqlTableCache(cache.params).also { it.isDetached = false }
        try {
            actsAsAdmin(admin) shouldBe true
        } finally {
            users.userCache = cache
            service.isDisabled = false
            cache.markChanged()
        }
    }

    "a user made in the sandbox itself acts with the levels on its own row, and never a capability" {
        val parent = "sbxown"
        defineParent(parent)
        // Nobody outside: somebody made in the sandbox to try the client as a member.
        val own = TestUser.create(cxt, "tryout@$parent.test", userClient = sandboxOf(parent))
        own.selfRoles() shouldBe listOf(ROLE.user)
        setRoles(own.userId, listOf(ROLE.user, ROLE.operator, ROLE.allClients))
        own.selfRoles().sorted() shouldBe listOf(ROLE.operator, ROLE.user)
        accessWithheld(own) shouldBe mapOf(ROLE.allClients to SandboxAccess.Withheld.allClients.rule)

        // An administrator of the parent who is also a member there fares the same: the row is the sandbox's own,
        // and nobody opened the sandbox as it.
        val chief = "chief@$parent.test"
        TestUser.create(cxt, chief, level = ROLE.admin, userClient = parent)
        TestUser.create(cxt, chief, userClient = sandboxOf(parent)).selfRoles() shouldBe listOf(ROLE.user)
    }

    // --- Which organization a sandbox user acts in (issue #1196) ----------------------------------------------------

    fun setOrg(userId: Long, org: String?) {
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, userId).shouldNotBeNull()
        row.org = org
        users.updateUser(cxt, row)
    }

    "an administrator confined to an organization in the parent is confined to it in the sandbox" {
        val parent = "sbxorg"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        setOrg(admin.userId, "north")
        admin.postData(AEP.openSandbox, emptyMap())[UPF.client] shouldBe sandboxOf(parent)
        admin.selfOrg() shouldBe "north"

        // Three users of the sandbox's own: one in each organization, and one in none.
        val (north, south, none) = listOf("north", "south", null).mapIndexed { i, org ->
            TestUser.create(cxt, "tryout$i@$parent.test", userClient = sandboxOf(parent)).also { setOrg(it.userId, org) }.userId
        }
        fun listed(): List<Long?> = admin.getItems(UADEP.users).map { it[ADF.userId].toOptLong() }
        withClue("confined to north") {
            listed().let { it.containsAll(listOf(north, none)) shouldBe true; it.contains(south) shouldBe false }
        }

        // Moved outside, it is moved inside at the next request.
        setOrg(admin.userId, "south")
        admin.selfOrg() shouldBe "south"
        withClue("moved to south") {
            listed().let { it.containsAll(listOf(south, none)) shouldBe true; it.contains(north) shouldBe false }
        }
        // And unconfined outside, unconfined inside.
        setOrg(admin.userId, null)
        admin.selfOrg() shouldBe null
        listed().containsAll(listOf(north, south, none)) shouldBe true
    }

    /** The organization the stored row of [userId] holds. */
    fun storedOrg(userId: Long): String? = UserService.get(cxt).queryByUserId(cxt, userId).shouldNotBeNull().org

    /** Creates a user in [creator]'s client through the console, naming [org] or not; the created user's id. */
    fun consoleCreate(creator: TestUser, address: String, org: String? = null): Long =
        creator.client.sendJsonPostRequest(UADEP.userCreate, buildMap {
            put(ADF.primaryId, address)
            if (org != null) put(ADF.org, org)
        }).let { it[EP.status] shouldBe null; it[EP.results].toJsonMapOrEmpty()[ADF.userId].toOptLong().shouldNotBeNull() }

    "an allClients administrator from elsewhere brings no organization of theirs into the sandbox" {
        val (parent, home) = "sbxorgaway" to "sbxorgawayhome"
        defineParent(parent)
        defineParent(home, sandbox = false)
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test", userClient = home)
        // An organization of their own client, which names nothing in the parent's sandbox.
        setOrg(full.userId, "west")
        full.selfOrg() shouldBe "west"
        val sandboxUserId = full.postData(AEP.openSandbox, mapOf(AFLD.client to parent))[UPF.userId].toOptLong().shouldNotBeNull()
        // Read with the client, so an answer is the sandbox session's own and not a refusal's empty one.
        full.getData(AEP.selfInfo).let { it[UPF.client] shouldBe sandboxOf(parent); it[UPF.org] shouldBe null }
        storedOrg(sandboxUserId) shouldBe null
        // And what the gate binds: a user they create in the sandbox takes no organization by default.
        storedOrg(consoleCreate(full, "made@$parent.test")) shouldBe null
    }

    "a confined administrator in the sandbox neither sees nor administers another organization's opened user" {
        val parent = "sbxorgtwo"
        defineParent(parent)
        val north = TestUser.create(cxt, "north@$parent.test", level = ROLE.admin, userClient = parent).also { setOrg(it.userId, "north") }
        val south = TestUser.create(cxt, "south@$parent.test", level = ROLE.admin, userClient = parent).also { setOrg(it.userId, "south") }
        val northInside = north.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        val southInside = south.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        // Each opened user is stored in its opener's organization, which is what the console goes by.
        storedOrg(northInside) shouldBe "north"
        storedOrg(southInside) shouldBe "south"

        val listed = north.getItems(UADEP.users).map { it[ADF.userId].toOptLong() }
        listed.contains(northInside) shouldBe true
        listed.contains(southInside) shouldBe false
        fun disable(userId: Long): Any? = north.client.sendJsonPostRequest(UADEP.userSetEnabled, mapOf(ADF.userId to userId, ADF.enabled to false))[EP.status]
        // The same request on a user of their own organization goes through; on the other one's, it does not.
        disable(consoleCreate(north, "made@$parent.test")) shouldBe null
        disable(southInside).shouldNotBeNull()
        UserService.get(cxt).queryByUserId(cxt, southInside).shouldNotBeNull().enabled shouldBe true
    }

    "an organization changed outside is stored on the opened sandbox user too, and is not set on it directly" {
        val parent = "sbxorgfollow"
        defineParent(parent)
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent).also { setOrg(it.userId, "north") }
        val inside = chief.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        storedOrg(inside) shouldBe "north"

        // Moved by an administrator of every client, through the console.
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test")
        full.client.sendJsonPostRequest(ADEP.userSetOrg, mapOf(ADF.userId to chief.userId, ADF.org to "south"))[EP.status] shouldBe null
        storedOrg(inside) shouldBe "south"
        chief.selfOrg() shouldBe "south"

        // Set on the opened user itself, it would be overwritten at the next opening and was never what it acts in.
        val refused = full.expectError(EXC.badInput, ADEP.userSetOrg, mapOf(ADF.userId to inside, ADF.org to "west"))
        (refused[EP.errorMessage] as String).contains("opened") shouldBe true
        storedOrg(inside) shouldBe "south"

        // Changed some other way than the console: the session follows at once, and the stored one at the next opening.
        setOrg(chief.userId, "west")
        chief.selfOrg() shouldBe "west"
        storedOrg(inside) shouldBe "south"
        chief.postData(AEP.switchUser, mapOf(AFLD.userId to chief.userId))
        chief.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong() shouldBe inside
        storedOrg(inside) shouldBe "west"
    }

    "a confined opener creates sandbox users in their own organization only" {
        val parent = "sbxorgmake"
        defineParent(parent)
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent).also { setOrg(it.userId, "north") }
        chief.postData(AEP.openSandbox, emptyMap())
        storedOrg(consoleCreate(chief, "made@$parent.test")) shouldBe "north"
        chief.expectError(EXC.badInput, UADEP.userCreate, mapOf(ADF.primaryId to "elsewhere@$parent.test", ADF.org to "south"))
    }

    "a level beside admin on a sandbox user nobody recorded opening takes effect only while held outside" {
        val parent = "sbxunrecorded"
        defineParent(parent)
        val address = "chief@$parent.test"
        val chief = TestUser.create(cxt, address, level = ROLE.admin, userClient = parent)
        setRoles(chief.userId, listOf(ROLE.user, ROLE.operator, ROLE.admin))
        // Holding admin in the sandbox with no opening recorded, and the operator level its person held that day.
        val legacy = TestUser.create(cxt, address, level = ROLE.admin, userClient = sandboxOf(parent))
        setRoles(legacy.userId, listOf(ROLE.user, ROLE.operator, ROLE.admin))
        fun operates(): Boolean = legacy.client.sendJsonGetRequest(CFD.cfactsPath)[EP.status] == null
        legacy.selfRoles().sorted() shouldBe listOf(ROLE.admin, ROLE.operator, ROLE.user)
        accessWithheld(legacy) shouldBe emptyMap()

        // Demoted outside to an operator: admin goes, as it always did; the operator level the person still holds stays.
        setRoles(chief.userId, listOf(ROLE.user, ROLE.operator))
        legacy.selfRoles().sorted() shouldBe listOf(ROLE.operator, ROLE.user)
        operates() shouldBe true

        // Demoted to a member: the copied operator level goes with what it stood on.
        setRoles(chief.userId, listOf(ROLE.user))
        legacy.selfRoles() shouldBe listOf(ROLE.user)
        operates() shouldBe false
        accessWithheld(legacy) shouldBe mapOf(
            ROLE.admin to SandboxAccess.Withheld.admin.rule,
            ROLE.operator to SandboxAccess.Withheld.levelNotHeldOutside.rule,
        )
    }

    "a level beside admin is not granted to a sandbox user in the console, where it would change nothing" {
        val parent = "sbxlevelgrant"
        defineParent(parent)
        val address = "chief@$parent.test"
        TestUser.create(cxt, address, level = ROLE.admin, userClient = parent)
        val legacy = TestUser.create(cxt, address, level = ROLE.admin, userClient = sandboxOf(parent))
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test")
        val refused = full.expectError(EXC.badInput, ADEP.userSetRoles, mapOf(ADF.userId to legacy.userId, ADF.roles to listOf(ROLE.user, ROLE.operator, ROLE.admin)))
        (refused[EP.errorMessage] as String).contains("beside") shouldBe true
        // Sent back as it is, the list goes through: a check on adding, as the console's others are.
        full.client.sendJsonPostRequest(ADEP.userSetRoles, mapOf(ADF.userId to legacy.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)))[EP.status] shouldBe null
    }

    "a level beside admin stands on an administrator outside, whom admin outranks it in, and each withheld role says why" {
        val (parent, hub) = "sbxlevelhub" to "sbxlevelhubhome"
        defineParent(parent)
        defineParent(hub, sandbox = false)
        val address = "full@$parent.test"
        TestUser.createFullAdmin(cxt, address, userClient = hub)
        // Nobody recorded opening it. Through an administrator of every client its admin takes effect, and an
        // administrator holds the operator level too -- the ladder's rule, as at the gate.
        val legacy = TestUser.create(cxt, address, level = ROLE.admin, userClient = sandboxOf(parent))
        setRoles(legacy.userId, listOf(ROLE.user, ROLE.operator, ROLE.admin, ROLE.allClients))
        legacy.selfRoles().sorted() shouldBe listOf(ROLE.admin, ROLE.operator, ROLE.user)
        accessWithheld(legacy) shouldBe mapOf(ROLE.allClients to SandboxAccess.Withheld.allClients.rule)

        // A member of the parent and nobody's administrator: three roles withheld, each with its own rule.
        val member = "member@$parent.test"
        TestUser.create(cxt, member, userClient = parent)
        val granted = TestUser.create(cxt, member, level = ROLE.admin, userClient = sandboxOf(parent))
        setRoles(granted.userId, listOf(ROLE.user, ROLE.operator, ROLE.admin, ROLE.allClients))
        granted.selfRoles() shouldBe listOf(ROLE.user)
        accessWithheld(granted) shouldBe mapOf(
            ROLE.admin to SandboxAccess.Withheld.admin.rule,
            ROLE.allClients to SandboxAccess.Withheld.allClients.rule,
            ROLE.operator to SandboxAccess.Withheld.levelNotHeldOutside.rule,
        )
    }

    "a sandbox user nobody recorded opening acts in the organization of the parent administrator it acts through" {
        val parent = "sbxorglegacy"
        defineParent(parent)
        val address = "chief@$parent.test"
        TestUser.create(cxt, address, level = ROLE.admin, userClient = parent).also { setOrg(it.userId, "north") }
        // Holding admin in the sandbox with no opening recorded: how a user opened before openings were recorded reads.
        val legacy = TestUser.create(cxt, address, level = ROLE.admin, userClient = sandboxOf(parent))
        actsAsAdmin(legacy) shouldBe true
        legacy.selfOrg() shouldBe "north"
    }

    "an allClients administrator opens any client's sandbox, and qualifies there" {
        val parent = "sbxhub"
        defineParent(parent)
        val full = TestUser.createFullAdmin(cxt, "full@$parent.test")
        full.postData(AEP.openSandbox, mapOf(AFLD.client to parent))[UPF.client] shouldBe sandboxOf(parent)
        actsAsAdmin(full) shouldBe true
    }

    "a parent user who is no administrator cannot open the sandbox, nor anyone a client without one" {
        val parent = "sbxdeny"
        defineParent(parent)
        val member = TestUser.create(cxt, "member@$parent.test", userClient = parent)
        member.expectError(EXC.notAuthorized, AEP.openSandbox, emptyMap())
        // A client administrator names another client's sandbox: not theirs to open.
        val other = "sbxother"
        defineParent(other)
        TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
            .expectError(EXC.notAuthorized, AEP.openSandbox, mapOf(AFLD.client to other))

        val plain = "sbxnone"
        defineParent(plain, sandbox = false)
        val chief = TestUser.create(cxt, "chief@$plain.test", level = ROLE.admin, userClient = plain)
        chief.expectError(EXC.badInput, AEP.openSandbox, emptyMap())
        chief.selfClient() shouldBe plain
    }
})
