package com.dynamicruntime.kdn

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.util.toOptInstant
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The four dates a user carries, and which events move which one (issue #462).
 *
 * A flow test: the whole subject is what one account's dates do as things happen to it in order, and the
 * clock moves forward between steps so "did it change" is answerable rather than a coin toss. Every assertion
 * is a **pair** — the date that should have moved and one that should not — because a test that only checked
 * the mover would pass just as well if every event stamped every field.
 */
class UserDatesTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("userDates", "userDatesTest")
    val admin = TestUser.createFullAdmin(cxt, "dates-admin@example.com")

    /**
     * The dates on [userId], as the admin surface reports them.
     *
     * Read from the plain listing rather than the cache search on purpose: the cache holds **enabled rows
     * only**, so a disabled account is simply absent from `userSearch` — and disabling one is a step this
     * test takes.
     */
    fun dates(userId: Long): Map<String, Instant?> {
        val row = admin.getItems(ADEP.users).single { (it[ADF.userId] as Number).toLong() == userId }
        return listOf(USF.registered, USF.activated, USF.lastLoggedIn, USF.lastEdited)
            .associate { it.root to row[it.at].toOptInstant() }
    }

    val subject = TestUser.create(cxt, "dates-subject@example.com")

    "creation sets registeredAt and activatedAt together, and nothing else" {
        val d = dates(subject.userId)
        // Creation is the first activation, so both are stamped from one moment.
        d.getValue(USF.registered.root).shouldNotBeNull() shouldBe d.getValue(USF.activated.root)
        // ...and an account that has just been made has not been edited.
        d.getValue(USF.lastEdited.root).shouldBeNull()
    }

    "becoming a user is a login, so lastLoggedInAt is set and lastEditedAt still is not" {
        // `TestUser.create` logs in as well as creating, so the subject already has a login recorded. The
        // pair is what makes this worth asserting: signing in is not an edit of the account.
        dates(subject.userId).getValue(USF.lastLoggedIn.root).shouldNotBeNull()
        dates(subject.userId).getValue(USF.lastEdited.root).shouldBeNull()
    }

    "logging in again moves only lastLoggedInAt" {
        val before = dates(subject.userId)
        cxt.instanceConfig.clock.advanceBy(1.minutes)
        TestUser.create(cxt, "dates-subject@example.com")

        val after = dates(subject.userId)
        // Asserted as "later than it was", not as equal to the clock now: the stamp is taken inside the
        // request and stored at millisecond precision, so an equality against a microsecond reading taken
        // afterwards fails on precision rather than on behavior.
        after.getValue(USF.lastLoggedIn.root)!! shouldBeGreaterThan before.getValue(USF.lastLoggedIn.root)!!
        // The two that must not have moved: signing in neither edits the account nor re-activates it.
        after.getValue(USF.lastEdited.root).shouldBeNull()
        after.getValue(USF.activated.root) shouldBe before.getValue(USF.activated.root)
    }

    "an edit moves lastEditedAt and leaves the login alone" {
        cxt.instanceConfig.clock.advanceBy(1.minutes)
        val before = dates(subject.userId)
        admin.postData(ADEP.userSetName, mapOf(ADF.userId to subject.userId, ADF.name to "Renamed"))

        val after = dates(subject.userId)
        after.getValue(USF.lastEdited.root).shouldNotBeNull()
        // The pair: the login is untouched by an edit, to the exact instant.
        after.getValue(USF.lastLoggedIn.root) shouldBe before.getValue(USF.lastLoggedIn.root)
    }

    "disabling is an edit; re-enabling is an activation" {
        // The asymmetry, and the reason it is asserted rather than left to read as a bug later. Disabling is
        // an ordinary administrative change; re-enabling is the account coming back into being, which is the
        // same event as its creation.
        cxt.instanceConfig.clock.advanceBy(1.minutes)
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to subject.userId, ADF.enabled to false))
        val disabled = dates(subject.userId)
        disabled.getValue(USF.lastEdited.root).shouldNotBeNull()

        cxt.instanceConfig.clock.advanceBy(1.minutes)
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to subject.userId, ADF.enabled to true))
        val enabled = dates(subject.userId)
        enabled.getValue(USF.activated.root)!! shouldBeGreaterThan disabled.getValue(USF.activated.root)!!
        // Re-enabling did NOT count as an edit, so that timestamp is still the disable's.
        enabled.getValue(USF.lastEdited.root) shouldBe disabled.getValue(USF.lastEdited.root)
    }

    "registeredAt never moves, whatever else happens to the account" {
        // Checked at the end of everything above rather than in isolation, because a re-enable is exactly the
        // event that would have overwritten it under the name this field nearly had. By now the account has
        // logged in twice, been renamed, disabled and re-enabled -- and the two dates that started equal at
        // creation have parted company, which is the whole reason both exist.
        val d = dates(subject.userId)
        d.getValue(USF.registered.root).shouldNotBeNull()
        d.getValue(USF.activated.root)!! shouldBeGreaterThan d.getValue(USF.registered.root)!!
    }
})
