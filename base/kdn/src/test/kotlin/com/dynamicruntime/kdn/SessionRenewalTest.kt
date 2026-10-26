package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AUTHC
import com.dynamicruntime.common.user.SessionLifetime
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserAuthCookie
import com.dynamicruntime.common.user.UserService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.hours

/**
 * Session renewal (issue #1372): a request more than halfway through a session's lifetime is answered with a fresh
 * cookie, good for the whole lifetime again; a polled endpoint's renews nothing; and no session outlasts the
 * deployment's limit from its sign-in, here 100 hours against the default lifetime of 48. Its own instance: it moves
 * the instance clock, which is instance-wide.
 *
 * Whether a session is still honored is asked through a polled endpoint ([TestUser.isSignedIn]), so asking renews
 * nothing.
 */
class SessionRenewalTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "sessionRenewal1372", "sessionRenewal1372", mapOf(SessionLifetime.sessionMaxHoursEnvVar.name to "100"),
    )
    // Frozen, so a cookie's times can be compared with the instance's to the millisecond.
    val clock = cxt.instanceConfig.clock.also { it.freeze() }

    fun cookieOf(user: TestUser): UserAuthCookie =
        UserAuthCookie.decode(NodeService.get(cxt), user.client.cookies[AUTHC.authCookie].orEmpty())
            ?: error("No session cookie for ${user.userId}.")

    fun nowMs(): Long = cxt.instanceNow().toEpochMilliseconds()

    /** A request the person made: any endpoint that is not polled. */
    fun act(user: TestUser) = user.getData(AEP.selfInfo)

    fun signedIn(user: TestUser): Boolean = user.isSignedIn()

    "a request past half the lifetime renews the session for the whole lifetime from then; one before does not" {
        val person = TestUser.create(cxt, "person@renewal1372.test")
        val signedInAt = cookieOf(person).signedInEpochMs
        clock.advanceBy(20.hours)
        act(person)
        cookieOf(person).issuedEpochMs shouldBe signedInAt
        clock.advanceBy(10.hours)
        act(person)
        val renewed = cookieOf(person)
        renewed.issuedEpochMs shouldBe nowMs()
        renewed.signedInEpochMs shouldBe signedInAt
        renewed.expireEpochMs shouldBe nowMs() + SessionLifetime.defaultHours * 3_600_000L
        // 77 hours in: past the 48 the sign-in gave, within the 48 the renewal at 30 did.
        clock.advanceBy(47.hours)
        signedIn(person) shouldBe true
        clock.advanceBy(2.hours)
        signedIn(person) shouldBe false
    }

    "a polled endpoint's read past half the lifetime renews nothing" {
        val person = TestUser.create(cxt, "idle@renewal1372.test")
        val signedInAt = cookieOf(person).signedInEpochMs
        clock.advanceBy(30.hours)
        signedIn(person) shouldBe true
        person.getData(APP.uiConfig)
        cookieOf(person).issuedEpochMs shouldBe signedInAt
        clock.advanceBy(19.hours)
        signedIn(person) shouldBe false
    }

    "a session ends at the deployment's limit from its sign-in, however recently it was renewed" {
        val person = TestUser.create(cxt, "steady@renewal1372.test")
        val signedInAt = cookieOf(person).signedInEpochMs
        clock.advanceBy(30.hours)
        act(person)
        clock.advanceBy(30.hours)
        act(person)
        // Renewed at 60, to the limit at 100 rather than the 108 a whole lifetime would give.
        cookieOf(person).expireEpochMs shouldBe signedInAt + 100 * 3_600_000L
        // A switch to one of the person's users -- here, to the one it is -- is not a sign-in: the new cookie keeps the
        // sign-in, so switching cannot restart the limit.
        person.postData(AEP.switchUser, mapOf(AFLD.userId to person.userId))
        cookieOf(person).signedInEpochMs shouldBe signedInAt
        cookieOf(person).expireEpochMs shouldBe signedInAt + 100 * 3_600_000L
        // At 90, past half the lifetime since 60, a renewal would gain nothing, so none is made.
        clock.advanceBy(30.hours)
        val held = cookieOf(person).issuedEpochMs
        act(person)
        cookieOf(person).issuedEpochMs shouldBe held
        clock.advanceBy(9.hours)
        signedIn(person) shouldBe true
        clock.advanceBy(2.hours)
        signedIn(person) shouldBe false
    }

    "shortening a client's lifetime still ends a renewed session at once" {
        val client = "short1372"
        TestUser.createFullAdmin(cxt, "operator@renewal1372.test").postData(
            ADEP.clientCreate,
            mapOf(
                CLD.clientId to client, CLD.name to "Client $client", CLD.usageType to ClientUsageType.dev.name,
                CLD.audience to ClientAudience.internal.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
                CLD.starterWorkflows to false,
            ),
        )
        val person = TestUser.create(cxt, "person@$client.test", userClient = client)
        clock.advanceBy(30.hours)
        act(person)
        clock.advanceBy(3.hours)
        signedIn(person) shouldBe true
        // An operator signed in now: the first one's two days are past.
        TestUser.createFullAdmin(cxt, "later@renewal1372.test")
            .postData(UADEP.clientDefinitionSet, mapOf(CLD.client to client, CLD.sessionHours to "2"))
        signedIn(person) shouldBe false
    }

    "a renewal re-reads the roles, and does not renew a user that no longer acts for the session" {
        val users = UserService.get(cxt)
        val chief = TestUser.create(cxt, "chief@renewal1372.test", level = ROLE.admin)
        clock.advanceBy(20.hours)
        val row = users.queryAdministrableUser(cxt, chief.userId, ReadScope.unrestricted)!!
        row.roles = listOf(ROLE.user)
        users.updateUser(cxt, row)
        // The cookie still carries what the sign-in gave until the session is renewed.
        cookieOf(chief).roles shouldContain ROLE.admin
        clock.advanceBy(10.hours)
        act(chief)
        cookieOf(chief).roles shouldNotContain ROLE.admin
        cookieOf(chief).issuedEpochMs shouldNotBe cookieOf(chief).signedInEpochMs

        val gone = TestUser.create(cxt, "disabled@renewal1372.test")
        val signedInAt = cookieOf(gone).signedInEpochMs
        val goneRow = users.queryAdministrableUser(cxt, gone.userId, ReadScope.unrestricted)!!
        goneRow.enabled = false
        users.updateUser(cxt, goneRow)
        clock.advanceBy(30.hours)
        act(gone)
        cookieOf(gone).issuedEpochMs shouldBe signedInAt
    }
})
