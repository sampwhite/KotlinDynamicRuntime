package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * API keys for a sandbox's users (issue #1453). A sandbox user's standing is worked out from stored rows on every
 * request (`SandboxAccess`), so a key on one acts exactly as a session on it does: a user somebody opened the sandbox as
 * acts as the user outside it was opened from, and with nothing once that user stands for nothing; a sandbox's own user
 * acts with its row's roles. A key's configuration writes land as the parent's drafts, as a session's do. One booted
 * instance; a parent client per case.
 */
class SandboxApiKeyTest : StringSpec({
    val cxt = TestInstances.default("sandboxApiKey1453")
    val svc = GedraConfigService.get(cxt)

    /** Defines [parent] with a sandbox, published, so it runs only what is published. */
    fun defineParent(parent: String) {
        val setup = cxt.mkSubContext("setup", parent).also { it.userId = 14530L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(parent), parent) {
                defineClient(
                    ClientDef(
                        clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                    ),
                )
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, parent, "main")))
        GedraConfigReload.reloadClient(cxt, parent)
    }

    /** A full key made by [by] -- for themselves, or with [ownerId] for that user as their administrator. */
    fun makeKey(by: TestUser, ownerId: Long? = null): String {
        val body = buildMap {
            put(AKEY.name, "program")
            put(AKEY.access, AKEY.full)
            ownerId?.let { put(AKEY.userId, it) }
        }
        return by.postData(if (ownerId == null) AKEY.selfCreate else AKEY.adminCreate, body)[AKEY.secret].toOptStr().shouldNotBeNull()
    }

    fun keyed(key: String): TestHttpClient =
        TestHttpClient(cxt.instanceConfig).apply { setHeader(AKEY.authorizationHeader, "${AKEY.bearerScheme}$key") }

    fun selfInfo(program: TestHttpClient): Map<String, Any?> = program.sendJsonGetRequest(AEP.selfInfo)[EP.results].toJsonMapOrEmpty()

    "a key made in the sandbox acts there as its opener, writes the parent's drafts, and acts with nothing once the opener is gone" {
        val parent = "sbxkey1453"
        defineParent(parent)
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val outside = chief.userId
        chief.postData(AEP.openSandbox, emptyMap())
        val key = makeKey(chief)
        val program = keyed(key)

        val info = selfInfo(program)
        info[UPF.client] shouldBe sandboxOf(parent)
        info[UPF.roles].toJsonListOfStrings() shouldContain ROLE.admin

        // A configuration write with the key is the parent's draft, which the sandbox runs and the parent does not.
        val fact = "${parent}Ready"
        val written = TestUser(program, cxt, info).postData(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "extra", CFEP.namespaceField to clientNamespace(parent), CFEP.reloadField to true,
                CFEP.slots to mapOf(
                    CCT.cfactDef to listOf(
                        mapOf(CCT.name to fact, CCT.group to "grp", CCT.description to "Ready.", CCT.toFrontend to false),
                    ),
                ),
            ),
        )
        written[CFEP.client] shouldBe parent
        written[CFEP.published] shouldBe false
        (fact in SchemaService.get(cxt).cfactsFor(sandboxOf(parent)).names) shouldBe true
        (fact in SchemaService.get(cxt).cfactsFor(parent).names) shouldBe false

        // The user it was opened from, disabled outside, stands for nothing; so neither does the key.
        val peer = TestUser.create(cxt, "peer@$parent.test", level = ROLE.admin, userClient = parent)
        peer.postData(UADEP.userSetEnabled, mapOf(ADF.userId to outside, ADF.enabled to false))
        selfInfo(program)[UPF.roles].toJsonListOfStrings() shouldNotContain ROLE.admin
        program.sendJsonGetRequest(CFEP.bundles)[EP.status] shouldBe 403
    }

    "an administrator in the sandbox makes keys for its own users, and not for another person's opened user" {
        val parent = "sbxkeyadm1453"
        defineParent(parent)
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        chief.postData(AEP.openSandbox, emptyMap())

        // A sandbox's own member: its key acts with the member's roles, in the sandbox.
        val member = TestUser.create(cxt, "member@$parent.test", userClient = sandboxOf(parent))
        val memberInfo = selfInfo(keyed(makeKey(chief, member.userId)))
        memberInfo[UPF.client] shouldBe sandboxOf(parent)
        memberInfo[UPF.roles].toJsonListOfStrings() shouldNotContain ROLE.admin

        // Another administrator's opened user acts as that administrator, so its keys are theirs to make -- whatever its
        // row says: an opened user acts with its opener's roles, not its own, so the row is set below what it acts with.
        val other = TestUser.create(cxt, "other@$parent.test", level = ROLE.admin, userClient = parent)
        val opened = other.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        val users = UserService.get(cxt)
        users.queryByUserId(cxt, opened).shouldNotBeNull().also { it.roles = listOf(ROLE.user) }.let { users.updateUser(cxt, it) }
        chief.expectError(400, AKEY.adminCreate, mapOf(AKEY.name to "program", AKEY.access to AKEY.full, AKEY.userId to opened))
            .toString() shouldContain "is an administrator"
    }
})

