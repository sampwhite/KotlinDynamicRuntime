package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
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
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.ATOK
import com.dynamicruntime.common.user.InProcessScope
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
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
 * an API key is held to and to its own scope besides -- in the dispatcher and in the catalog alike -- never env-authed,
 * and refused the moment the user is disabled, becomes another's, or loses the standing an endpoint needs. Here, as the
 * person's own sandbox user, which is what the workflow assistant acts as.
 */
class InProcessCallerTest : StringSpec({
    val cxt = TestInstances.default("inProcess1524")

    /** Defines [parent] with a sandbox on [on]'s instance, and returns its administrator and their sandbox user's id. */
    fun parentWithSandboxUser(parent: String, on: KdrCxt = cxt): Pair<TestUser, Long> {
        val config = gedraConfig(on, "main", clientNamespace(parent), parent) {
            defineClient(
                ClientDef(
                    clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
        }
        GedraConfigService.get(on).writeConfig(on.mkSubContext("setup", parent).also { it.userId = 15240L }, config)
        GedraConfigReload.reloadClient(on, parent)
        val admin = TestUser.create(on, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        // Opening the sandbox claims the same user an in-process caller acts as (`claimOwnSandboxUser`).
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        return admin to sandboxUserId
    }

    /** A principal for [userId], as its row now stands, admitting [allowed]. */
    fun principal(userId: Long, vararg allowed: Pair<HttpMethod, String>, on: KdrCxt = cxt): InProcessPrincipal {
        val row = UserService.get(on).queryByUserId(on, userId).shouldNotBeNull()
        val scope = InProcessScope.of("the test caller", allowed.map { it.first.name to it.second }.toSet())
        return InProcessPrincipal(userId, row.identityId, row.client, "test:1524", scope)
    }

    fun call(
        p: InProcessPrincipal, method: HttpMethod, path: String,
        args: Map<String, Any?>? = null, body: Map<String, Any?>? = null, on: KdrCxt = cxt,
    ): InProcessResult = InProcessCaller.call(on.instanceConfig, p, method, path, args, body, traceId = "t1524")

    fun refusedWith(result: InProcessResult, status: Int): String = withClue(result.envelope) {
        result.status shouldBe status
        result.errorMessage.shouldNotBeNull()
    }

    "a call acts as the user it names, and sees what that user sees" {
        val (_, sandboxUserId) = parentWithSandboxUser("inprocone")
        val p = principal(sandboxUserId, HttpMethod.GET to AEP.selfInfo, HttpMethod.GET to CFEP.bundles)

        val self = call(p, HttpMethod.GET, AEP.selfInfo)
        self.isSuccess shouldBe true
        val info = self.envelope[EP.results].toJsonMapOrEmpty()
        info[UPF.userId].toOptLong() shouldBe sandboxUserId
        info[UPF.client] shouldBe sandboxOf("inprocone")
        // The client's configuration, as its administrator in the sandbox reads it.
        call(p, HttpMethod.GET, CFEP.bundles).isSuccess shouldBe true
    }

    "it reaches only what its scope lists, and nothing only a signed-in session may do" {
        val (_, sandboxUserId) = parentWithSandboxUser("inproctwo")
        // What is the person's to do -- publish, revert, delete, change the tier -- and what the work has no business
        // with -- users, keys -- are refused when unlisted, whatever the user may do.
        val narrow = principal(sandboxUserId, HttpMethod.GET to CFEP.bundles)
        for ((method, path) in listOf(
            HttpMethod.POST to CFEP.bundlePublish, HttpMethod.POST to CFEP.bundleRevert, HttpMethod.POST to CFEP.bundleDelete,
            HttpMethod.POST to CFEP.publishedOnly, HttpMethod.GET to UADEP.users, HttpMethod.POST to AKEY.selfCreate,
        )) {
            withClue(path) {
                refusedWith(call(narrow, method, path, body = if (method == HttpMethod.POST) emptyMap() else null), EXC.notAuthorized) shouldContain
                    "not open to the test caller"
            }
        }
        // A session-only endpoint stays closed even when the scope lists it, as it is to any API key.
        val session = principal(sandboxUserId, HttpMethod.POST to AEP.leaveSandbox)
        refusedWith(call(session, HttpMethod.POST, AEP.leaveSandbox, body = emptyMap()), EXC.notAuthorized) shouldContain "signed-in session"
        // There is no key to exchange for a token, even when the scope lists the exchange.
        val token = principal(sandboxUserId, HttpMethod.POST to ATOK.token)
        refusedWith(call(token, HttpMethod.POST, ATOK.token, body = emptyMap()), EXC.notAuthorized) shouldContain "not open to the test caller"
    }

    "the catalog lists what the scope lists, and nothing else" {
        val (_, sandboxUserId) = parentWithSandboxUser("inprocthree")
        fun listed(p: InProcessPrincipal): List<Any?> {
            val catalog = call(p, HttpMethod.GET, catalogPath)
            catalog.isSuccess shouldBe true
            return catalog.envelope[EP.results].toJsonMapOrEmpty()[EI.endpoints].toJsonListOfMaps().map { it[EI.path] }
        }
        // Listed when the scope lists it, so the user may see it...
        val wide = principal(sandboxUserId, HttpMethod.GET to catalogPath, HttpMethod.GET to CFEP.bundles, HttpMethod.POST to CFEP.bundlePatch)
        listed(wide) shouldContain CFEP.bundlePatch
        // ...and left out when it does not.
        val narrow = principal(sandboxUserId, HttpMethod.GET to catalogPath, HttpMethod.GET to CFEP.bundles)
        listed(narrow).let {
            it shouldContain CFEP.bundles
            it shouldNotContain CFEP.bundlePatch
        }
    }

    "a user who loses standing, is disabled, or is stranded is refused on the next call" {
        val (admin, sandboxUserId) = parentWithSandboxUser("inprocfour")
        val users = UserService.get(cxt)
        val p = principal(sandboxUserId, HttpMethod.GET to CFEP.bundles)
        call(p, HttpMethod.GET, CFEP.bundles).isSuccess shouldBe true

        // The sandbox user acts with what its opener holds outside, asked at each call: demoted there, refused by the
        // section's gate here.
        val outside = users.queryByUserId(cxt, admin.userId).shouldNotBeNull()
        outside.roles = listOf(ROLE.user)
        users.updateUser(cxt, outside)
        refusedWith(call(p, HttpMethod.GET, CFEP.bundles), EXC.notAuthorized) shouldContain "requires the 'admin' role"
        outside.roles = listOf(ROLE.user, ROLE.admin)
        users.updateUser(cxt, outside)
        call(p, HttpMethod.GET, CFEP.bundles).isSuccess shouldBe true

        // The opener disabled outside: nobody stands behind it, and it is acted for no more.
        outside.enabled = false
        users.updateUser(cxt, outside)
        refusedWith(call(p, HttpMethod.GET, CFEP.bundles), EXC.authNeeded) shouldContain "stands behind"
        outside.enabled = true
        users.updateUser(cxt, outside)
        call(p, HttpMethod.GET, CFEP.bundles).isSuccess shouldBe true

        // Disabled itself.
        val inside = users.queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        inside.enabled = false
        users.updateUser(cxt, inside)
        refusedWith(call(p, HttpMethod.GET, CFEP.bundles), EXC.authNeeded) shouldContain "gone, disabled, or no longer theirs"
    }

    "a principal naming another person or client than the user's is refused" {
        val (_, sandboxUserId) = parentWithSandboxUser("inprocfive")
        val p = principal(sandboxUserId, HttpMethod.GET to CFEP.bundles)
        val otherPerson = InProcessPrincipal(sandboxUserId, "not-their-identity", p.client, p.keyId, p.scope)
        refusedWith(call(otherPerson, HttpMethod.GET, CFEP.bundles), EXC.authNeeded) shouldContain "no longer theirs"
        val otherClient = InProcessPrincipal(sandboxUserId, p.identityId, "inprocfive", p.keyId, p.scope)
        refusedWith(call(otherClient, HttpMethod.GET, CFEP.bundles), EXC.authNeeded) shouldContain "no longer theirs"
    }

    "what a call cannot send is answered as a bad request" {
        val (_, sandboxUserId) = parentWithSandboxUser("inprocsix")
        val p = principal(sandboxUserId, HttpMethod.GET to CFEP.bundles)
        refusedWith(call(p, HttpMethod.GET, "clientAdmin/config/bundles"), EXC.badInput) shouldContain "must begin with '/'"
        refusedWith(call(p, HttpMethod.GET, CFEP.bundles, args = mapOf("names" to listOf("a", "b"))), EXC.badInput) shouldContain
            "single value"
        refusedWith(call(p, HttpMethod.GET, CFEP.bundles, args = mapOf("q" to "x".repeat(2100))), EXC.badInput) shouldContain "2048"
    }

    "on a node that assumes env auth, a call made in process is still not env-authed" {
        val envCxt = TestInstances.envAuth("inProcessEnv1524")
        val (_, sandboxUserId) = parentWithSandboxUser("inprocenv", envCxt)
        val p = principal(sandboxUserId, HttpMethod.GET to APP.uiConfig, HttpMethod.POST to TEP.becomeUser, on = envCxt)
        // The app's own view of the caller: neither env-authed nor holding test access, though the node gives both to
        // callers it assumes came straight to it.
        val config = call(p, HttpMethod.GET, APP.uiConfig, on = envCxt)
        config.isSuccess shouldBe true
        val features = config.envelope[EP.results].toJsonMapOrEmpty()[UIC.features].toJsonMapOrEmpty()
        features[APP.isEnvAuthed] shouldBe false
        features[APP.hasTestAccess] shouldBe false
        // Nor does a test-only fixture exist for it, though the scope lists it (it is also session-only, so it would be
        // refused regardless; that it is missing, not refused, is what says the call has no test access).
        refusedWith(call(p, HttpMethod.POST, TEP.becomeUser, body = mapOf(TEP.email to "x@inprocenv.test"), on = envCxt), EXC.notFound)
    }
})

/** The endpoint catalog's path. */
private const val catalogPath = "/schema/endpoints"
