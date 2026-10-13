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
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
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
 * a parent administrator **opens** the sandbox as their own user in it, under the same identity. Rule 2 -- in a
 * sandbox, `admin` takes **effect** only for an identity that is an administrator in the parent (or an `allClients`
 * one). Stated by identity rather than by how a sandbox user came to exist: what is pinned is who acts as an
 * administrator there, whatever granted the role.
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

    /** The roles the access report says [user]'s row holds and a rule withholds, each with its rule. */
    fun accessWithheld(user: TestUser): Map<String, String> {
        // A diagnostic tag, so it answers a caller with test access (issue #1149).
        val resp = user.withTestAccess { user.client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainAccess)) }
        return resp[EP.meta]!!.toJsonMap()[SS.accessExplained]!!.toJsonMap()[SS.rolesWithheld].toJsonListOfMaps()
            .associate { it[SS.role].toOptStr().shouldNotBeNull() to it[SS.rule].toOptStr().shouldNotBeNull() }
    }

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
        // And an identity with no user in the parent at all fares the same.
        actsAsAdmin(TestUser.create(cxt, "stranger@$parent.test", level = ROLE.admin, userClient = sandboxOf(parent))) shouldBe false

        // The access report names the rule, so the missing role does not read as never held.
        accessWithheld(granted) shouldBe mapOf(ROLE.admin to SS.sandboxAdminRule)
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
    // A sandbox user who came from outside acts with what their user outside holds, asked at each request
    // (`SandboxAccess.actingRoles`) -- not with what its own row was made with the day the sandbox was opened. Each
    // case changes something outside and asks the sandbox session, which nothing has touched.

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

        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, parentUserId).shouldNotBeNull()
        row.enabled = false
        users.updateUser(cxt, row)
        // `admin` is withheld, which the case above already holds. What is asked here is whether anything is left:
        // the sandbox user is a row of its own, and nothing disabled it.
        actsAsAdmin(admin) shouldBe false
        withClue("the sandbox session's roles, after the person's user in the parent was disabled") { admin.selfRoles() shouldBe emptyList() }
        // And what a member may call refuses them, as it does a disabled account.
        admin.client.sendJsonGetRequest(GEP.formDocs)[EP.status].shouldNotBeNull()
        accessWithheld(admin).values.toSet() shouldBe setOf(SS.sandboxStandingRule)

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
        accessWithheld(admin) shouldBe mapOf(ROLE.admin to SS.sandboxAdminRule, ROLE.operator to SS.sandboxOutsideRule)
    }

    "a user made in the sandbox itself acts with the levels on its own row, and never a capability" {
        val parent = "sbxown"
        defineParent(parent)
        // Nobody outside: somebody made in the sandbox to try the client as a member.
        val own = TestUser.create(cxt, "tryout@$parent.test", userClient = sandboxOf(parent))
        own.selfRoles() shouldBe listOf(ROLE.user)
        setRoles(own.userId, listOf(ROLE.user, ROLE.operator, ROLE.allClients))
        own.selfRoles().sorted() shouldBe listOf(ROLE.operator, ROLE.user)
        accessWithheld(own) shouldBe mapOf(ROLE.allClients to SS.sandboxOutsideRule)

        // A member of the parent who is also a user there fares the same: the row is the sandbox's own.
        val member = "member@$parent.test"
        TestUser.create(cxt, member, userClient = parent)
        TestUser.create(cxt, member, userClient = sandboxOf(parent)).selfRoles() shouldBe listOf(ROLE.user)
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
