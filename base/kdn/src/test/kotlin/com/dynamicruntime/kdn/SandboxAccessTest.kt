package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
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
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
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
    val cxt = Startup.mkTestBootCxt("sandboxAccess929", "sandboxAccess929")

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
        val resp = granted.client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainAccess))
        val withheld = resp[EP.meta]!!.toJsonMap()[SS.accessExplained]!!.toJsonMap()[SS.rolesWithheld].toJsonListOfMaps()
        withheld.single()[SS.role] shouldBe ROLE.admin
        withheld.single()[SS.rule].toOptStr().shouldNotBeNull() shouldBe SS.sandboxAdminRule
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
