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

    "an operator reaches the operator section once the role is granted" {
        val admin = TestUser.create(cxt, "grantor-op@example.com", admin = true)
        val operator = TestUser.create(cxt, "operator-op@example.com")
        operator.expectError(EXC.notAuthorized, systemInfo) // an ordinary user until the grant lands

        grantOperator(admin, operator)

        // The dispatcher re-reads live roles before enforcing, so the grant takes effect on the very next
        // request -- the session cookie's stale role list does not have to be re-issued first.
        operator.selfRoles() shouldContain ROLE.operator
        operator.getData(systemInfo).isEmpty() shouldBe false
    }

    /** The point of ranking: nobody grants the admin `operator`, and the admin gets in regardless. */
    "an admin reaches the operator section without holding the operator role" {
        val admin = TestUser.create(cxt, "boss-op@example.com", admin = true)

        admin.selfRoles().contains(ROLE.operator) shouldBe false
        admin.getData(systemInfo).isEmpty() shouldBe false
    }

    /** The other direction, which is the whole request: operator must not be a way into admin surfaces. */
    "an operator is still refused an admin section" {
        val admin = TestUser.create(cxt, "boss2-op@example.com", admin = true)
        val operator = TestUser.create(cxt, "operator2-op@example.com")
        grantOperator(admin, operator)

        operator.selfRoles() shouldContain ROLE.operator
        operator.expectError(EXC.notAuthorized, ADEP.users)
    }

    "the system report carries node identity, uptime and VM statistics" {
        val admin = TestUser.create(cxt, "report-op@example.com", admin = true)
        val info = admin.getData(systemInfo)

        val node = info[OSI.node].toJsonMapOrEmpty()
        node[OSI.nodeId].toOptStr().isNullOrBlank() shouldBe false
        node[OSI.uptimeMs].toOptLong()!! shouldBeGreaterThan -1L
        node[OSI.nodeStartTime].toOptStr() shouldNotBe null

        val runtime = info[OSI.runtime].toJsonMapOrEmpty()
        runtime[OSI.vmName].toOptStr().isNullOrBlank() shouldBe false
        runtime[OSI.pid].toOptLong()!! shouldBeGreaterThan 0L

        val memory = info[OSI.memory].toJsonMapOrEmpty()
        memory[OSI.heapUsed].toOptLong()!! shouldBeGreaterThan 0L
        memory[OSI.runtimeTotal].toOptLong()!! shouldBeGreaterThan 0L

        info[OSI.threads].toJsonMapOrEmpty()[OSI.count].toOptLong()!! shouldBeGreaterThan 0L
        info[OSI.classes].toJsonMapOrEmpty()[OSI.loaded].toOptLong()!! shouldBeGreaterThan 0L
        info[OSI.os].toJsonMapOrEmpty()[OSI.availableProcessors].toOptLong()!! shouldBeGreaterThan 0L

        // Every JVM has at least one collector and one memory pool; these are the "all the VM stats" lists.
        info[OSI.gcCollectors].toJsonListOfMaps().shouldNotBeEmpty()
        info[OSI.memoryPools].toJsonListOfMaps().shouldNotBeEmpty()
    }

    /**
     * The collection is opt-in, so the default call must not trigger one. Asserted on the report rather than
     * on the collector counts, which a concurrent collector can move on its own at any moment.
     */
    "no collection happens unless one is asked for" {
        val admin = TestUser.create(cxt, "nogc-op@example.com", admin = true)

        val gc = admin.getData(systemInfo)[OSI.gc].toJsonMapOrEmpty()
        gc[OSI.requested] shouldBe false
        // Nothing ran, so there is no before/after to report -- a `freed` of 0 would claim a collection did.
        gc.containsKey(OSI.freed) shouldBe false
        gc.containsKey(OSI.durationMs) shouldBe false
    }

    "collect=true requests a collection and reports what it reclaimed" {
        val admin = TestUser.create(cxt, "gc-op@example.com", admin = true)

        val gc = admin.getData(systemInfo, mapOf(OSI.collect to true))[OSI.gc].toJsonMapOrEmpty()
        gc[OSI.requested] shouldBe true

        // The JVM may ignore the request, so `freed` is not asserted to be positive -- but the readings that
        // bracket it are real measurements either way, and the duration is a real elapsed time.
        gc[OSI.heapUsedBefore].toOptLong()!! shouldBeGreaterThan 0L
        gc[OSI.heapUsedAfter].toOptLong()!! shouldBeGreaterThan 0L
        gc[OSI.durationMs].toOptDouble()!! shouldBeGreaterThan -0.1
    }

    /** The field is declared, so the input type is closed around it: a misspelling is a 400, not a silent no-op. */
    "an undeclared query parameter is rejected" {
        val admin = TestUser.create(cxt, "badparam-op@example.com", admin = true)

        admin.client.sendGetRequest(systemInfo, mapOf("collectt" to "true")).rptStatusCode shouldBe EXC.badInput
    }
})
