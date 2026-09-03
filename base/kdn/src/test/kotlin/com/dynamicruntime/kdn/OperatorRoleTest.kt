package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.operator.OSI
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptDouble
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

/**
 * The operator privilege and its first tenant, `/operator/system/info`.
 *
 * `RoleLadderTest` (in the kernel) covers the ordering as pure logic. What this adds is that the *dispatcher*
 * applies it: the gate lives in `RequestService`, not in any handler, so only a real request through the
 * in-process pipeline exercises it -- against the real `operator` section rather than a marking invented by
 * the test.
 *
 * One instance for the spec, with a distinct email per block: the in-memory database is keyed by name rather
 * than by instance, so rows outlive a boot within the JVM and shared identifiers would collide across tests.
 *
 * Refusals come back as the standard error envelope (issue #103), so they are asserted with
 * [TestUser.expectError] on the envelope's `status` rather than as thrown exceptions.
 */
class OperatorRoleTest : StringSpec({

    val systemInfo = "/operator/system/info"
    val cxt = Startup.mkTestBootCxt("op", "operatorRoleTest")

    /** Grants `[user, operator]` to [target] through the real admin endpoint, as [admin]. */
    fun grantOperator(admin: TestUser, target: TestUser) {
        admin.postData(
            ADEP.userSetRoles,
            mapOf(ADF.userId to target.userId, ADF.roles to listOf(ROLE.user, ROLE.operator)),
        )
    }

    "an ordinary user is refused the operator section" {
        val plain = TestUser.create(cxt, "plain-op@example.com")

        plain.selfRoles() shouldContain ROLE.user
        // 403, not 401: they are logged in and simply lack the rung (issue #211).
        plain.expectError(EXC.notAuthorized, systemInfo)
    }

    /**
     * The change (issue #464): the operator section now demands the `allClients` capability as well as the
     * level, so granting the operator *role* alone -- which a client-scoped admin can do to one of their own
     * users -- does not open the deployment surface. The grant lands (the role really is held, the dispatcher
     * re-reads live roles) and the section still refuses it.
     */
    "an operator without allClients is refused the operator section" {
        val admin = TestUser.createFullAdmin(cxt, "grantor-op@example.com")
        val operator = TestUser.create(cxt, "operator-op@example.com")
        grantOperator(admin, operator)

        operator.selfRoles() shouldContain ROLE.operator
        operator.selfRoles().contains(ROLE.allClients) shouldBe false
        operator.expectError(EXC.notAuthorized, systemInfo)
    }

    /** A deployment operator -- the level plus the capability -- is what the section now asks for. */
    "a deployment operator (operator + allClients) reaches the operator section" {
        val operator = TestUser.createOperator(cxt, "deploy-op@example.com")

        operator.selfRoles() shouldContain ROLE.operator
        operator.selfRoles() shouldContain ROLE.allClients
        operator.getData(systemInfo).isEmpty() shouldBe false
    }

    /**
     * The point of #464: a **client-scoped** admin -- the admin level without `allClients` -- satisfies the
     * operator rung on the ladder but is laddered off the section by the missing capability. They administer
     * one client's users; the deployment's operational internals are not theirs.
     */
    "a client-scoped admin is refused the operator section" {
        val scoped = TestUser.create(cxt, "scoped-adm-op@example.com", level = ROLE.admin)

        scoped.selfRoles().contains(ROLE.allClients) shouldBe false
        scoped.expectError(EXC.notAuthorized, systemInfo)
    }

    /** A full-scope admin reaches it: the level satisfies the operator rung and the capability is held. */
    "a full-scope admin reaches the operator section" {
        val admin = TestUser.createFullAdmin(cxt, "boss-op@example.com")

        admin.selfRoles().contains(ROLE.operator) shouldBe false
        admin.getData(systemInfo).isEmpty() shouldBe false
    }

    /**
     * The other direction: the operator surface is not a way into the admin one. A deployment operator holds
     * `allClients` but not the admin *level*, so the admin section -- which needs both -- still refuses them.
     */
    "a deployment operator is still refused an admin section" {
        val operator = TestUser.createOperator(cxt, "operator2-op@example.com")

        operator.selfRoles() shouldContain ROLE.operator
        operator.expectError(EXC.notAuthorized, ADEP.users)
    }

    "the system report carries node identity, uptime and VM statistics" {
        val operator = TestUser.createOperator(cxt, "report-op@example.com")
        val info = operator.getData(systemInfo)

        val node = info[OSI.node].toJsonMapOrEmpty()
        node[OSI.nodeId].toOptStr().isNullOrBlank() shouldBe false
        node[OSI.uptimeMs].toOptLong()!! shouldBeGreaterThan -1L
        node[OSI.nodeStartTime].toOptStr() shouldNotBe null

        val runtime = info[OSI.runtime].toJsonMapOrEmpty()
        runtime[OSI.vmName].toOptStr().isNullOrBlank() shouldBe false
        runtime[OSI.pid].toOptLong()!! shouldBeGreaterThan 0L

        // Memory sizes are shown in gigabytes, two decimals (issue #560) -- a "X.XX GB" string, not raw bytes.
        val memory = info[OSI.memory].toJsonMapOrEmpty()
        memory[OSI.heapUsed].toOptStr()!! shouldMatch Regex("""\d+\.\d{2} GB""")
        memory[OSI.runtimeTotal].toOptStr()!! shouldMatch Regex("""\d+\.\d{2} GB""")
        // A field the JVM leaves uncapped reads as "unbounded" rather than a nonsense figure.
        memory[OSI.runtimeMax].toOptStr() shouldNotBe null

        info[OSI.threads].toJsonMapOrEmpty()[OSI.count].toOptLong()!! shouldBeGreaterThan 0L
        info[OSI.classes].toJsonMapOrEmpty()[OSI.loaded].toOptLong()!! shouldBeGreaterThan 0L
        info[OSI.os].toJsonMapOrEmpty()[OSI.availableProcessors].toOptLong()!! shouldBeGreaterThan 0L

        // Every JVM has at least one collector and one memory pool; these are the "all the VM stats" lists.
        info[OSI.gcCollectors].toJsonListOfMaps().shouldNotBeEmpty()
        // A pool's memory sizes are formatted the same way (issue #560).
        val pool = info[OSI.memoryPools].toJsonListOfMaps().also { it.shouldNotBeEmpty() }.first()
        pool[OSI.used].toOptStr()!! shouldMatch Regex("""(\d+\.\d{2} GB|unbounded)""")
    }

    /**
     * The collection is opt-in, so the default call must not trigger one. Asserted on the report rather than
     * on the collector counts, which a concurrent collector can move on its own at any moment.
     */
    "no collection happens unless one is asked for" {
        val operator = TestUser.createOperator(cxt, "nogc-op@example.com")

        val gc = operator.getData(systemInfo)[OSI.gc].toJsonMapOrEmpty()
        gc[OSI.requested] shouldBe false
        // Nothing ran, so there is no before/after to report -- a `freed` of 0 would claim a collection did.
        gc.containsKey(OSI.freed) shouldBe false
        gc.containsKey(OSI.durationMs) shouldBe false
    }

    "collect=true requests a collection and reports what it reclaimed" {
        val operator = TestUser.createOperator(cxt, "gc-op@example.com")

        val gc = operator.getData(systemInfo, mapOf(OSI.collect to true))[OSI.gc].toJsonMapOrEmpty()
        gc[OSI.requested] shouldBe true

        // The JVM may ignore the request, so `freed` is not asserted to be positive -- but the readings that
        // bracket it are real measurements either way (shown in GB, issue #560), and the duration is a real
        // elapsed time.
        gc[OSI.heapUsedBefore].toOptStr()!! shouldMatch Regex("""\d+\.\d{2} GB""")
        gc[OSI.heapUsedAfter].toOptStr()!! shouldMatch Regex("""\d+\.\d{2} GB""")
        gc[OSI.freed].toOptStr()!! shouldMatch Regex("""-?\d+\.\d{2} GB""")
        gc[OSI.durationMs].toOptDouble()!! shouldBeGreaterThan -0.1
    }

    /** The field is declared, so the input type is closed around it: a misspelling is a 400, not a silent no-op. */
    "an undeclared query parameter is rejected" {
        val operator = TestUser.createOperator(cxt, "badparam-op@example.com")

        operator.client.sendGetRequest(systemInfo, mapOf("collectt" to "true")).rptStatusCode shouldBe EXC.badInput
    }
})
