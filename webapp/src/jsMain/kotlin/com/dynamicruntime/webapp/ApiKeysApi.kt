package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.ApiKeyInfo
import com.dynamicruntime.common.user.ApiKeyRules
import com.dynamicruntime.common.util.getOptLong
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.time.Instant
import kotlinx.browser.window

/** What a create or an extension may ask for on this deployment (issue #1402), from a key listing's summary. */
class ApiKeyLimits(
    /** A new key's life, in days, when none is chosen. */
    val days: Int,
    /** The furthest from today an expiry may be, in days. */
    val maxDays: Int,
    val maxLive: Int,
    /** How many of the listed user's keys still work, on a listing of one user's. */
    val liveCount: Int?,
)

/** One page of keys: the keys, what a create may ask for, and the listing's total and whether more remain. */
class ApiKeyPage(val keys: List<ApiKeyInfo>, val limits: ApiKeyLimits?, val numAvailable: Int, val hasMore: Boolean)

/** A key just made: its info and the whole key, which is shown this once. */
class ApiKeyMade(val info: ApiKeyInfo, val secret: String)

/** [envelope], a key listing's answer, as the page reads it. Pure. */
fun parseApiKeyPage(envelope: Map<String, Any?>): ApiKeyPage {
    val keys = envelope[EP.items].toJsonListOfMaps().mapNotNull { ApiKeyInfo.fromInfo(it) }
    val summary = envelope[EP.summary].toJsonMapOrEmpty()
    val limits = summary.getOptLong(AKEY.days)?.let { days ->
        ApiKeyLimits(
            days = days.toInt(),
            maxDays = summary.getOptLong(AKEY.maxDays)?.toInt() ?: AKEY.defaultMaxDays,
            maxLive = summary.getOptLong(AKEY.maxLive)?.toInt() ?: AKEY.maxLiveKeys,
            liveCount = summary.getOptLong(AKEY.liveCount)?.toInt(),
        )
    }
    return ApiKeyPage(
        keys, limits, envelope.getOptLong(EP.numAvailable)?.toInt() ?: keys.size, envelope[EP.hasMore] == true,
    )
}

/** A create's answer, or null when it is not one. Pure. */
fun parseApiKeyMade(results: Map<String, Any?>): ApiKeyMade? {
    val info = ApiKeyInfo.fromInfo(results[AKEY.key].toJsonMapOrEmpty()) ?: return null
    return ApiKeyMade(info, results.getOptStr(AKEY.secret) ?: return null)
}

/** What a key's status is called on the page. Pure. */
fun apiKeyStatusText(status: String): String = when (status) {
    AKEY.live -> "Works"
    AKEY.expiresSoon -> "Expires soon"
    AKEY.expired -> "Expired"
    AKEY.revoked -> "Revoked"
    else -> status
}

/** What a key's access is called on the page. Pure. */
fun apiKeyAccessText(access: String): String = if (access == AKEY.read) "Read-only" else "Full"

/** What a key's access lets it do, as the start of a sentence: "It is read-only", "It has full access". Pure. */
fun apiKeyAccessSentence(access: String): String = if (access == AKEY.read) "It is read-only" else "It has full access"

/** A lifetime in days as a person says it: "1 year", "2 years", "90 days". Pure. */
fun apiKeyLifetimeText(days: Int): String = when {
    days % 365 == 0 -> (days / 365).let { if (it == 1) "1 year" else "$it years" }
    else -> if (days == 1) "1 day" else "$days days"
}

/**
 * The lifetimes a new key is offered (issue #1402): a month, a quarter, half a year, a year and two, those the deployment
 * allows ([ApiKeyLimits.maxDays]), and its default among them, which the form starts on. Pure.
 */
fun apiKeyLifetimeChoices(limits: ApiKeyLimits?): List<Int> {
    val most = limits?.maxDays ?: AKEY.defaultMaxDays
    val default = limits?.days ?: AKEY.defaultDays
    return (listOf(30, 90, 180, 365, 730).filter { it <= most } + default).distinct().sorted()
}

/** The units an extension is counted in, as the picker names them. */
@Suppress("ConstPropertyName")
object EXTU {
    const val days = "days"
    const val months = "months"
}

/** Where an extension being typed would put a key, or why it cannot. */
class ApiKeyExtensionPreview(val expiry: Instant?, val problem: String?)

/**
 * What extending [info] by [amount] [unit]s at [now] would do (issue #1402): the new expiry, by the kernel's rule the
 * server applies ([ApiKeyRules]), or what is wrong with it -- not a whole number, or past the deployment's furthest
 * ([maxDays]). Pure, so the date shown before saving is the date saving gives.
 */
fun apiKeyExtensionPreview(info: ApiKeyInfo, now: Instant, amount: String, unit: String, maxDays: Int): ApiKeyExtensionPreview {
    val n = amount.trim().toIntOrNull()
    if (n == null || n <= 0) return ApiKeyExtensionPreview(null, "Enter a whole number of $unit, at least 1.")
    val days = if (unit == EXTU.days) n else null
    val months = if (unit == EXTU.months) n else null
    val expiry = ApiKeyRules.extendedExpiry(info.expiresAt, now, days, months)
    return ApiKeyExtensionPreview(expiry, ApiKeyRules.capProblem(expiry, now, maxDays))
}

/** Why a key cannot be made for this user now, or null when it can: they hold as many working keys as allowed. Pure. */
fun apiKeyCreateBlock(limits: ApiKeyLimits?, forSelf: Boolean): String? {
    val live = limits?.liveCount ?: return null
    if (live < limits.maxLive) return null
    val who = if (forSelf) "You hold" else "This user holds"
    return "$who ${limits.maxLive} keys that work, the most allowed. Revoke one to make another."
}

/** Who made [info], when it was not its owner: "by Pat Lee". Pure. */
fun apiKeyMadeByText(info: ApiKeyInfo): String? {
    if (!info.madeByAnother) return null
    return "by ${info.createdByName ?: "user ${info.createdBy}"}"
}

/** The question a revoke asks first. Pure. */
fun apiKeyRevokeQuestion(info: ApiKeyInfo): String = "Revoke “${info.name}”?"

/** What a revoke does, beneath its question. */
const val apiKeyRevokeConsequence = "Anything using it stops working at once. This cannot be undone."

/** [url] as an address that works outside the page: a path is put under [origin], a full address kept. Pure. */
fun absoluteUrl(origin: String, url: String): String = if (url.startsWith("/")) origin.trimEnd('/') + url else url

/** A command that tries [secret] out, against the endpoint that says who a caller is. Pure. */
fun apiKeyTryCommand(apiUrl: String, secret: String): String = "curl -H \"Authorization: Bearer $secret\" $apiUrl"

/** The key calls (issue #1402): a person's own through the `user` paths, an administrator's through `clientAdmin`. */
object ApiKeysApi {
    /** The signed-in user's keys, or, with [ownerId], that user's as their administrator sees them. */
    suspend fun list(ownerId: Long?): ApiKeyPage = parseApiKeyPage(
        if (ownerId == null) Http.getApi(AKEY.selfKeys) else Http.getApi(AKEY.adminKeys + queryString(mapOf(AKEY.userId to ownerId))),
    )

    /** Every key the caller administers, of [status] when given. */
    suspend fun listAdministered(status: String?, limit: Int): ApiKeyPage = parseApiKeyPage(
        Http.getApi(AKEY.adminKeys + queryString(buildMap {
            status?.let { put(AKEY.status, it) }
            put(EP.limit, limit)
        })),
    )

    /** Makes a key for the signed-in user, or for [ownerId] as their administrator. */
    suspend fun create(ownerId: Long?, name: String, access: String, days: Int?): ApiKeyMade {
        val body = buildMap {
            ownerId?.let { put(AKEY.userId, it) }
            put(AKEY.name, name.trim())
            put(AKEY.access, access)
            days?.let { put(AKEY.days, it) }
        }
        val answer = Http.sendApi("POST", if (ownerId == null) AKEY.selfCreate else AKEY.adminCreate, body)
        return parseApiKeyMade(answer[EP.results].toJsonMapOrEmpty()) ?: error("The key's create answered no key.")
    }

    /** Extends [keyId] by [amount] [unit]s; [asAdmin] through the administrator's path. */
    suspend fun extend(asAdmin: Boolean, keyId: String, amount: Int, unit: String): ApiKeyInfo? {
        val body = mapOf(AKEY.keyId to keyId, (if (unit == EXTU.months) AKEY.months else AKEY.days) to amount)
        val answer = Http.sendApi("POST", if (asAdmin) AKEY.adminExtend else AKEY.selfExtend, body)
        return ApiKeyInfo.fromInfo(answer[EP.results].toJsonMapOrEmpty())
    }

    /** Revokes [keyId]; [asAdmin] through the administrator's path. */
    suspend fun revoke(asAdmin: Boolean, keyId: String): ApiKeyInfo? {
        val answer = Http.sendApi("POST", if (asAdmin) AKEY.adminRevoke else AKEY.selfRevoke, mapOf(AKEY.keyId to keyId))
        return ApiKeyInfo.fromInfo(answer[EP.results].toJsonMapOrEmpty())
    }

    /** The address a key can be tried against -- who the caller is -- in full, since a command runs outside the page. */
    fun tryUrl(): String = absoluteUrl(window.location.origin, Http.apiUrl(AEP.selfInfo))
}
