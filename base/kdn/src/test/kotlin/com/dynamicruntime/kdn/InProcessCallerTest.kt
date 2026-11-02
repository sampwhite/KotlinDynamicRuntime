package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.InProcessCaller
import com.dynamicruntime.common.http.request.InProcessPrincipal
import com.dynamicruntime.common.http.request.InProcessResult
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.ATOK
import com.dynamicruntime.common.user.InProcessScope
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A call made in this process for a user (issue #1524): through the whole request pipeline, as that user, held to what
 * an API key is held to and to its own scope besides -- in the dispatcher and in the catalog alike -- and refused the
 * moment the user is disabled or loses the standing an endpoint needs. Here, as the person's own sandbox user, which is
 * what the workflow assistant acts as.
 */
class InProcessCallerTest : StringSpec({
    val cxt = TestInstances.default("inProcess1524")
    val users = UserService.get(cxt)

    /** Defines [parent] with a sandbox, live on this instance, and returns its administrator and their sandbox user. */
    fun parentWithSandboxUser(parent: String): Pair<TestUser, Long> {
        val config = gedraConfig(cxt, "main", clientNamespace(parent), parent) {
            defineClient(
                ClientDef(
                    clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", parent).also { it.userId = 15240L }, config)
        GedraConfigReload.reloadClient(cxt, parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        // Opening the sandbox claims the same user an in-process caller acts as (`claimOwnSandboxUser`).
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        return admin to sandboxUserId
    }

    fun principal(userId: Long, vararg allowed: Pair<HttpMethod, String>) = InProcessPrincipal(
        userId, "test:1524", InProcessScope.of("the test caller", allowed.map { it.first.name to it.second }.toSet()),
    )

    fun call(p: InProcessPrincipal, method: HttpMethod, path: String, body: Map<String, Any?>? = null): InProcessResult =
        InProcessCaller.call(cxt.instanceConfig, p, method, path, body = body, traceId = "t1524")

    fun refusedWith(result: InProcessResult, status: Int): String = withClue(result.envelope) {
        result.status shouldBe status
        result.envelope[EP.errorMessage].toString()
    }

    "a call acts as the user it names, sees what that user sees, and sets no cookie" {
        val (_, sandboxUserId) = parentWithSandboxUser("inprocone")
        val p = principal(sandboxUserId, HttpMethod.GET to AEP.selfInfo, HttpMethod.GET to CFEP.bundles)

        val self = call(p, HttpMethod.GET, AEP.selfInfo)
        self.isSuccess shouldBe true
        val info = self.envelope[EP.results].toJsonMapOrEmpty()
        info[UPF.userId].toOptLong() shouldBe sandboxUserId
        info[UPF.client] shouldBe sandboxOf("inprocone")
        // The client's configuration, as its administrator in the sandbox reads it.
        call(p, HttpMethod.GET, CFEP.bundles).isSuccess shouldBe true
        // Nothing is kept between calls: no session is made or renewed.
        self.headers.keys.none { it.equals("Set-Cookie", ignoreCase = true) } shouldBe true
    }

    "it reaches only what its scope admits, and nothing only a signed-in session may do" {
        val (_, sandboxUserId) = parentWithSandboxUser("inproctwo")
        // Publishing is the person's: not in the scope, so refused, whatever the user may do.
        val narrow = principal(sandboxUserId, HttpMethod.GET to CFEP.bundles)
        refusedWith(call(narrow, HttpMethod.POST, CFEP.bundlePublish, mapOf(CFEP.names to listOf("main"))), EXC.notAuthorized) shouldContain
            "not open to the test caller"
        // A session-only endpoint stays closed even when the scope names it, as it is to any API key.
        val wide = principal(sandboxUserId, HttpMethod.POST to AEP.leaveSandbox)
        refusedWith(call(wide, HttpMethod.POST, AEP.leaveSandbox, emptyMap()), EXC.notAuthorized) shouldContain "signed-in session"
        // There is no key to exchange for a token, even when the scope names the exchange.
        val token = principal(sandboxUserId, HttpMethod.POST to ATOK.token)
        refusedWith(call(token, HttpMethod.POST, ATOK.token, emptyMap()), EXC.notAuthorized) shouldContain "not open to"
    }

    "the catalog lists only what the scope admits" {
        val (_, sandboxUserId) = parentWithSandboxUser("inprocthree")
        val p = principal(sandboxUserId, HttpMethod.GET to catalogPath, HttpMethod.GET to CFEP.bundles)
        val catalog = call(p, HttpMethod.GET, catalogPath)
        catalog.isSuccess shouldBe true
        val paths = catalog.envelope[EP.results].toJsonMapOrEmpty()[EI.endpoints].toJsonListOfMaps().map { it[EI.path] }
        paths shouldContain CFEP.bundles
        // Published, and the user may patch -- but this caller may not, so it is not shown.
        paths shouldNotContain CFEP.bundlePatch
    }

    "a user who loses the standing an endpoint needs, or is disabled, is refused on the next call" {
        val (admin, sandboxUserId) = parentWithSandboxUser("inprocfour")
        val p = principal(sandboxUserId, HttpMethod.GET to CFEP.bundles)
        call(p, HttpMethod.GET, CFEP.bundles).isSuccess shouldBe true

        // The sandbox user acts with what its opener holds outside, asked at each call: demoted there, refused here.
        val outside = users.queryByUserId(cxt, admin.userId).shouldNotBeNull()
        outside.roles = listOf(ROLE.user)
        users.updateUser(cxt, outside)
        refusedWith(call(p, HttpMethod.GET, CFEP.bundles), EXC.notAuthorized)
        outside.roles = listOf(ROLE.user, ROLE.admin)
        users.updateUser(cxt, outside)
        call(p, HttpMethod.GET, CFEP.bundles).isSuccess shouldBe true

        // Disabled, it is acted for no more.
        val inside = users.queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        inside.enabled = false
        users.updateUser(cxt, inside)
        refusedWith(call(p, HttpMethod.GET, CFEP.bundles), EXC.authNeeded) shouldContain "gone or disabled"
    }
})

/** The endpoint catalog's path. */
private const val catalogPath = "/schema/endpoints"
