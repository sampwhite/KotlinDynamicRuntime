package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.ApiKeyInfo
import com.dynamicruntime.common.util.addMonths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Pure-logic coverage for the API-key listings and their dialog (issue #1402). */
class ApiKeysTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z")

    private fun key(expiresAt: Instant, revokedAt: Instant? = null, createdBy: Long? = 7L, createdByName: String? = null) = ApiKeyInfo(
        keyId = "abcdefghijkl", name = "nightly sync", access = AKEY.read, userId = 7L, client = "acme",
        createdAt = now - 10.days, createdBy = createdBy, createdByName = createdByName, expiresAt = expiresAt, revokedAt = revokedAt,
    )

    @Test
    fun aKeysStatusIsReadFromItsDates() {
        assertEquals(AKEY.live, key(now + 100.days).status(now))
        assertEquals(AKEY.expiresSoon, key(now + 10.days).status(now))
        assertEquals(AKEY.expired, key(now - 1.days).status(now))
        assertEquals(AKEY.revoked, key(now + 100.days, revokedAt = now).status(now))
        assertEquals("Expires soon", apiKeyStatusText(AKEY.expiresSoon))
        assertEquals("Read-only", apiKeyAccessText(AKEY.read))
        assertEquals("It has full access", apiKeyAccessSentence(AKEY.full))
        assertEquals("kdr_abcdefghijkl…", key(now).shown)
    }

    @Test
    fun anExtensionIsPreviewedByTheRuleTheServerApplies() {
        val live = key(Instant.parse("2027-01-31T12:00:00Z"))
        // From the expiry, by calendar months: January 31 plus a month is February 28.
        assertEquals(Instant.parse("2027-02-28T12:00:00Z"), apiKeyExtensionPreview(live, now, "1", EXTU.months, 730).expiry)
        assertEquals(Instant.parse("2027-02-10T12:00:00Z"), apiKeyExtensionPreview(live, now, "10", EXTU.days, 730).expiry)
        // An expired key from today.
        val expired = key(now - 30.days)
        assertEquals(now.addMonths(2), apiKeyExtensionPreview(expired, now, "2", EXTU.months, 730).expiry)
        // Not a number, and past the furthest an expiry may be.
        assertEquals("Enter a whole number of days, at least 1.", apiKeyExtensionPreview(live, now, "x", EXTU.days, 730).problem)
        assertEquals("Enter a whole number of months, at least 1.", apiKeyExtensionPreview(live, now, "0", EXTU.months, 730).problem)
        val tooFar = apiKeyExtensionPreview(live, now, "30", EXTU.months, 730)
        assertTrue(tooFar.problem!!.contains("at most 730 days from today"), tooFar.problem)
    }

    @Test
    fun aCreateIsOfferedUntilTheUserHoldsAsManyKeysAsAllowed() {
        assertNull(apiKeyCreateBlock(ApiKeyLimits(365, 730, 10, liveCount = 9), forSelf = true))
        assertEquals(
            "You hold 10 keys that work, the most allowed. Revoke one to make another.",
            apiKeyCreateBlock(ApiKeyLimits(365, 730, 10, liveCount = 10), forSelf = true),
        )
        assertTrue(apiKeyCreateBlock(ApiKeyLimits(365, 730, 10, liveCount = 10), forSelf = false)!!.startsWith("This user holds"))
        // A listing that does not count says nothing to stop it; the server still does.
        assertNull(apiKeyCreateBlock(null, forSelf = true))
    }

    @Test
    fun theLifetimesOfferedAreTheDeploymentsAndItsDefaultAmongThem() {
        assertEquals(listOf(30, 90, 180, 365, 730), apiKeyLifetimeChoices(ApiKeyLimits(365, 730, 10, null)))
        assertEquals(listOf(30, 90, 180, 200), apiKeyLifetimeChoices(ApiKeyLimits(200, 200, 10, null)))
        assertEquals("1 year", apiKeyLifetimeText(365))
        assertEquals("2 years", apiKeyLifetimeText(730))
        assertEquals("90 days", apiKeyLifetimeText(90))
    }

    @Test
    fun aKeyMadeByAnotherSaysWho() {
        assertNull(apiKeyMadeByText(key(now, createdBy = 7L)))
        assertEquals("by Pat Lee", apiKeyMadeByText(key(now, createdBy = 3L, createdByName = "Pat Lee")))
        assertEquals("by user 3", apiKeyMadeByText(key(now, createdBy = 3L)))
    }

    @Test
    fun aListingAndACreateAreReadFromTheirAnswers() {
        val info = key(now + 100.days).toInfo(now)
        val page = parseApiKeyPage(
            mapOf(
                EP.items to listOf(info),
                EP.summary to mapOf(AKEY.days to 365L, AKEY.maxDays to 730L, AKEY.maxLive to 10L, AKEY.liveCount to 1L),
                EP.numAvailable to 1L,
                EP.hasMore to false,
            ),
        )
        assertEquals(listOf("abcdefghijkl"), page.keys.map { it.keyId })
        assertEquals(1, page.limits!!.liveCount)
        assertEquals(730, page.limits!!.maxDays)
        val made = assertNotNull(parseApiKeyMade(mapOf(AKEY.key to info, AKEY.secret to "kdr_abcdefghijkl_s3cret")))
        assertEquals("kdr_abcdefghijkl_s3cret", made.secret)
        assertNull(parseApiKeyMade(mapOf(AKEY.key to info)))
        assertEquals("https://h/kda/auth/self/info", absoluteUrl("https://h/", "/kda/auth/self/info"))
        assertEquals("https://api.h/kda/x", absoluteUrl("https://h", "https://api.h/kda/x"))
        assertEquals(
            "curl -H \"Authorization: Bearer kdr_x_y\" https://h/kda/auth/self/info",
            apiKeyTryCommand("https://h/kda/auth/self/info", "kdr_x_y"),
        )
    }
}
