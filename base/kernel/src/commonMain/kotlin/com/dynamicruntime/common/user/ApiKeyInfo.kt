package com.dynamicruntime.common.user

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.addDays
import com.dynamicruntime.common.util.addMonths
import com.dynamicruntime.common.util.formatDayPart
import com.dynamicruntime.common.util.getOptLong
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toOptInstantOrNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * An **API key**'s vocabulary (issue #1402): the paths that manage keys, the names their fields travel under, and the
 * values a key's access and status take. In the kernel so the frontend reads a key by the names the backend writes, and
 * so a renamed path breaks its compile rather than its runtime.
 *
 * A key is `kdr_<keyId>_<secret>`, sent as `Authorization: Bearer`. It acts as the user it belongs to, never above
 * them; the [keyId] is public and names the key wherever it is shown, and only a hash of the secret is stored, so the
 * whole key is seen once, as it is created.
 */
@Suppress("ConstPropertyName")
object AKEY {
    /** What every key begins with, so a log line or a secret scanner can recognize one. */
    const val prefix = "kdr_"

    /** Characters in a key's public id: 9 random bytes, base64url. */
    const val keyIdLength = 12

    /** A key that may only read: GET requests, and nothing else. */
    const val read = "read"

    /** A key that may do whatever its owner may, short of the endpoints only a signed-in session reaches. */
    const val full = "full"

    /** The access a key may have, in the order a choice lists them. */
    val accesses = listOf(read, full)

    // A key's status, as the listing says it and filters by it. Computed from its dates (`ApiKeyInfo.status`).
    const val live = "live"
    const val expiresSoon = "expiresSoon"
    const val expired = "expired"
    const val revoked = "revoked"

    /** Every status, in the order a filter lists them. */
    val statuses = listOf(live, expiresSoon, expired, revoked)

    /** Days before its expiry from which a live key is said to expire soon. */
    const val expiresSoonDays = 30

    /** The most keys one user may hold that are neither revoked nor expired. */
    const val maxLiveKeys = 10

    /** The longest name a key may be given. */
    const val maxNameLength = 80

    /** A new key's life, in days, unless the deployment sets another (`KDR_API_KEY_DAYS`). */
    const val defaultDays = 365

    /** The furthest from today any key's expiry may be, in days, unless the deployment sets another (`KDR_API_KEY_MAX_DAYS`). */
    const val defaultMaxDays = 730

    // --- field names ---
    const val keyId = "keyId"
    const val name = "name"
    const val access = "access"
    const val userId = "userId"
    const val client = "client"
    const val createdAt = "createdAt"
    const val createdBy = "createdBy"
    const val createdByName = "createdByName"
    const val expiresAt = "expiresAt"
    const val lastUsedAt = "lastUsedAt"
    const val revokedAt = "revokedAt"
    const val revokedBy = "revokedBy"
    const val ownerName = "ownerName"
    const val ownerAddress = "ownerAddress"
    const val status = "status"
    const val days = "days"
    const val months = "months"

    /** On a create's answer: the whole key, `kdr_<keyId>_<secret>` -- the one time it is sent. */
    const val secret = "secret"

    /** On a create's answer: the key's info. */
    const val key = "key"

    // On a listing's summary: what a create or an extension may ask for here.
    const val maxDays = "maxDays"
    const val latestExpiry = "latestExpiry"
    const val liveCount = "liveCount"
    const val maxLive = "maxLive"

    // --- paths ---
    // A person's own keys, in the login-gated `user` section.
    const val selfKeys = "/user/self/apiKeys"
    const val selfCreate = "/user/self/apiKey/create"
    const val selfExtend = "/user/self/apiKey/extend"
    const val selfAccess = "/user/self/apiKey/access"
    const val selfRevoke = "/user/self/apiKey/revoke"

    // An administrator's, in the scoped `clientAdmin` section: every key they may see, and any user's in their scope.
    const val adminKeys = "/clientAdmin/apiKeys"
    const val adminCreate = "/clientAdmin/apiKey/create"
    const val adminExtend = "/clientAdmin/apiKey/extend"
    const val adminAccess = "/clientAdmin/apiKey/access"
    const val adminRevoke = "/clientAdmin/apiKey/revoke"

    /** The header a key is sent in, and the scheme before it. */
    const val authorizationHeader = "Authorization"
    const val bearerScheme = "Bearer "
}

/**
 * One API key as it is listed (issue #1402) -- everything about it but its secret, which is never stored. Its
 * serialization, its schema type and its field names live together here, as the code guide asks.
 */
data class ApiKeyInfo(
    val keyId: String,
    val name: String,
    /** [AKEY.read] or [AKEY.full]. */
    val access: String,
    /** The user the key acts as. */
    val userId: Long,
    val client: String,
    val createdAt: Instant?,
    /** The user who made it: the owner, or an administrator who made it for them. */
    val createdBy: Long?,
    /** [createdBy]'s name or address, when the listing knows it. */
    val createdByName: String? = null,
    val expiresAt: Instant,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val revokedBy: Long? = null,
    /** The owner's name and address, on an administrator's listing. */
    val ownerName: String? = null,
    val ownerAddress: String? = null,
) {
    /** How the key is shown once its secret is gone: its prefix and public id. */
    val shown: String get() = "${AKEY.prefix}$keyId…"

    val isReadOnly: Boolean get() = access == AKEY.read

    /** Whether an administrator, rather than the owner, made the key. */
    val madeByAnother: Boolean get() = createdBy != null && createdBy != userId

    /** The key's status at [now]: [AKEY.revoked], [AKEY.expired], [AKEY.expiresSoon] or [AKEY.live]. Pure. */
    fun status(now: Instant): String = when {
        revokedAt != null -> AKEY.revoked
        now > expiresAt -> AKEY.expired
        expiresAt - now <= AKEY.expiresSoonDays.days -> AKEY.expiresSoon
        else -> AKEY.live
    }

    /** Whether the key still lets anyone in at [now]: not revoked and not expired. */
    fun isUsable(now: Instant): Boolean = revokedAt == null && now <= expiresAt

    fun toInfo(now: Instant): Map<String, Any?> = buildMap {
        put(AKEY.keyId, keyId)
        put(AKEY.name, name)
        put(AKEY.access, access)
        put(AKEY.userId, userId)
        put(AKEY.client, client)
        createdAt?.let { put(AKEY.createdAt, it) }
        createdBy?.let { put(AKEY.createdBy, it) }
        createdByName?.let { put(AKEY.createdByName, it) }
        put(AKEY.expiresAt, expiresAt)
        lastUsedAt?.let { put(AKEY.lastUsedAt, it) }
        revokedAt?.let { put(AKEY.revokedAt, it) }
        revokedBy?.let { put(AKEY.revokedBy, it) }
        ownerName?.let { put(AKEY.ownerName, it) }
        ownerAddress?.let { put(AKEY.ownerAddress, it) }
        put(AKEY.status, status(now))
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Schema type name for [toInfo]. */
        const val infoTypeName = "ApiKeyInfo"

        /** Schema type name for a create's answer: the key's info and, this once, the whole key. */
        const val createdTypeName = "ApiKeyCreated"

        /** Schema type name for a listing's summary: what a create or an extension may ask for here. */
        const val limitsTypeName = "ApiKeyLimits"

        /** The mirror of [toInfo]; a map missing an id, owner or expiry is not a key and yields null. */
        fun fromInfo(info: Map<String, Any?>): ApiKeyInfo? = ApiKeyInfo(
            keyId = info.getOptStr(AKEY.keyId) ?: return null,
            name = info.getOptStr(AKEY.name).orEmpty(),
            access = info.getOptStr(AKEY.access) ?: AKEY.read,
            userId = info.getOptLong(AKEY.userId) ?: return null,
            client = info.getOptStr(AKEY.client).orEmpty(),
            createdAt = info[AKEY.createdAt].toOptInstantOrNull(),
            createdBy = info.getOptLong(AKEY.createdBy),
            createdByName = info.getOptStr(AKEY.createdByName),
            expiresAt = info[AKEY.expiresAt].toOptInstantOrNull() ?: return null,
            lastUsedAt = info[AKEY.lastUsedAt].toOptInstantOrNull(),
            revokedAt = info[AKEY.revokedAt].toOptInstantOrNull(),
            revokedBy = info.getOptLong(AKEY.revokedBy),
            ownerName = info.getOptStr(AKEY.ownerName),
            ownerAddress = info.getOptStr(AKEY.ownerAddress),
        )

        /** Defines [infoTypeName], [createdTypeName] and [limitsTypeName] on [builder], kept beside the serialization. */
        fun defineInfoTypes(builder: SchTypesBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                property(AKEY.keyId, "The key's public id: what it is called wherever it is shown.", required = true)
                property(AKEY.name, "What the key is for, as its maker named it.", required = true)
                property(AKEY.access, "'${AKEY.read}' (GET requests only) or '${AKEY.full}'.", required = true)
                property(AKEY.userId, "The user the key acts as.", required = true) { type = SCT.integer }
                property(AKEY.client, "The owner's client.", required = true)
                property(AKEY.createdAt, "When the key was made.") { dateTime() }
                property(AKEY.createdBy, "The user who made it: the owner, or an administrator for them.") { type = SCT.integer }
                property(AKEY.createdByName, "The maker's name or address, when it is not the owner.")
                property(AKEY.expiresAt, "When the key stops working.", required = true) { dateTime() }
                property(AKEY.lastUsedAt, "When the key was last used, to within an hour.") { dateTime() }
                property(AKEY.revokedAt, "When the key was revoked.") { dateTime() }
                property(AKEY.revokedBy, "Who revoked it.") { type = SCT.integer }
                property(AKEY.ownerName, "The owner's name, on an administrator's listing.")
                property(AKEY.ownerAddress, "The owner's address, on an administrator's listing.")
                property(AKEY.status, "'${AKEY.live}', '${AKEY.expiresSoon}', '${AKEY.expired}' or '${AKEY.revoked}'.", required = true)
            }
            builder.type(createdTypeName) {
                type = SCT.kObject
                property(AKEY.key, "The new key's info.", required = true) { ref(infoTypeName) }
                property(AKEY.secret, "The whole key, to be sent as 'Authorization: Bearer'. Sent this once; only its hash is kept.", required = true)
            }
            builder.type(limitsTypeName) {
                type = SCT.kObject
                property(AKEY.days, "A new key's life, in days, when the create names none.", required = true) { type = SCT.integer }
                property(AKEY.maxDays, "The furthest from today an expiry may be, in days.", required = true) { type = SCT.integer }
                property(AKEY.latestExpiry, "The latest expiry a create or an extension may give a key now.", required = true) { dateTime() }
                property(AKEY.maxLive, "The most keys a user may hold that are neither revoked nor expired.", required = true) { type = SCT.integer }
                property(AKEY.liveCount, "How many such keys the listed user holds, on a listing of one user's keys.") { type = SCT.integer }
            }
        }
    }
}

/**
 * The rules for how long a key lasts (issue #1402), in the kernel so the page previewing an extension and the server
 * making it land on the same moment. Pure; the deployment's own lifetimes are handed in.
 */
object ApiKeyRules {
    /** The expiry of a key made at [now] to last [days]. */
    fun newExpiry(now: Instant, days: Int): Instant = now.addDays(days)

    /**
     * What is wrong with an extension of [days] or [months], or null when it is one of them, positive. Exactly one: an
     * extension of "3 months and 10 days" is two extensions, and saying which one counts would be a guess.
     */
    fun extensionProblem(days: Int?, months: Int?): String? = when {
        days == null && months == null -> "Say how many days or months to extend the key by."
        days != null && months != null -> "Extend the key by days or by months, not both."
        (days ?: months!!) <= 0 -> "An extension must be at least one ${if (days != null) "day" else "month"}."
        else -> null
    }

    /**
     * The expiry a key reaches when extended by [days] or [months] (one of them; [extensionProblem] says): added to its
     * current [expiresAt], or to [now] when that has passed -- an expired key extended by a month works for a month from
     * today, not for whatever was left of a month that ended last week.
     */
    fun extendedExpiry(expiresAt: Instant, now: Instant, days: Int?, months: Int?): Instant {
        val from = if (expiresAt > now) expiresAt else now
        return if (months != null) from.addMonths(months) else from.addDays(days ?: 0)
    }

    /** The latest expiry any key may have at [now], [maxDays] from it. */
    fun latestExpiry(now: Instant, maxDays: Int): Instant = now.addDays(maxDays)

    /** What is wrong with [expiry] at [now], or null when it is within [maxDays] of today. */
    fun capProblem(expiry: Instant, now: Instant, maxDays: Int): String? {
        val latest = latestExpiry(now, maxDays)
        return if (expiry > latest) {
            "A key may expire at most $maxDays days from today, by ${latest.formatDayPart()}; " +
                "that would make it ${expiry.formatDayPart()}."
        } else {
            null
        }
    }
}
