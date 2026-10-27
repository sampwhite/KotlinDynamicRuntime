package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.node.IC
import com.dynamicruntime.common.node.InstanceConfigService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.RandomUtil
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.sanitizeForDisplay
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLongOrNull
import com.dynamicruntime.common.util.toOptStr
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One key access tokens are signed with. It signs from [signsFrom] -- for a key rotated in, a while after it is
 * published, so every node holds it before a token names it -- until [retiredAt], when the key after it takes over, and
 * null until a rotation says when that is.
 */
class SigningKey(
    val kid: String,
    val privateKey: PrivateKey,
    val publicKey: ECPublicKey,
    val createdAt: Instant,
    val signsFrom: Instant = createdAt,
    val retiredAt: Instant? = null,
) {
    /** As the instance config stores it. */
    fun toStored(): Map<String, Any?> = linkedMapOf(
        JWS.kid to kid,
        privateField to Es256.encodePrivate(privateKey),
        publicField to Es256.encodePublic(publicKey),
        createdField to createdAt.formatDate(),
        signsFromField to signsFrom.formatDate(),
        retiredField to retiredAt?.formatDate(),
    )

    /** Whether it is the key that signs at [now]. */
    fun signsAt(now: Instant): Boolean = signsFrom <= now && (retiredAt == null || now < retiredAt)

    /**
     * Whether a token it signed may still be unexpired at [now]. Measured by the longest a deployment may let a token
     * last ([ATOK.maxHours]) rather than by its setting today, which a token issued under a longer one would outlive.
     */
    fun mayStillVerify(now: Instant): Boolean = retiredAt == null || now < retiredAt + ATOK.maxHours.hours + retirementGrace

    /** This key, ending its signing at [at]. */
    fun retiredAt(at: Instant): SigningKey = SigningKey(kid, privateKey, publicKey, createdAt, signsFrom, at)

    @Suppress("ConstPropertyName")
    companion object {
        const val privateField = "privateKey"
        const val publicField = "publicKey"
        const val createdField = "createdAt"
        const val signsFromField = "signsFrom"
        const val retiredField = "retiredAt"

        /**
         * How long past the longest a token it signed could last a retired key stays published: a margin for the clocks
         * of the nodes and services that check a token disagreeing.
         */
        val retirementGrace = 5.minutes

        fun fromStored(m: Map<String, Any?>): SigningKey {
            val createdAt = m[createdField].toOptInstant() ?: throw KdrException("A stored signing key has no '$createdField'.")
            return SigningKey(
                kid = m.getOptStr(JWS.kid) ?: throw KdrException("A stored signing key has no '${JWS.kid}'."),
                privateKey = Es256.decodePrivate(m.getOptStr(privateField) ?: throw KdrException("A stored signing key has no private key.")),
                publicKey = Es256.decodePublic(m.getOptStr(publicField) ?: throw KdrException("A stored signing key has no public key.")),
                createdAt = createdAt,
                signsFrom = m[signsFromField].toOptInstant() ?: createdAt,
                retiredAt = m[retiredField].toOptInstant(),
            )
        }

        /** A new key, made at [now], that signs from [signsFrom]. */
        fun mkNew(now: Instant, signsFrom: Instant = now): SigningKey {
            val pair = Es256.newKeyPair()
            val public = pair.public as? ECPublicKey ?: throw KdrException("The JDK made an EC key pair whose public half is not EC.")
            return SigningKey(RandomUtil.secureBytes(kidBytes).base64Encode(), pair.private, public, now, signsFrom)
        }

        /** Random bytes in a signing key's id. */
        const val kidBytes = 9
    }
}

/** The signing keys as this node last read them, and when. */
class SigningKeySet(val keys: List<SigningKey>, val loadedAt: Instant) {
    /** The key that signs at [now]: the newest whose turn has come. */
    fun signerAt(now: Instant): SigningKey? = keys.filter { it.signsAt(now) }.maxByOrNull { it.signsFrom }

    /** The key that signs next, if a rotation has published one that has not begun to: what a rotation answers. */
    val newest: SigningKey? get() = keys.maxByOrNull { it.signsFrom }

    fun byKid(kid: String): SigningKey? = keys.firstOrNull { it.kid == kid }
}

/** A token just issued, and what the OAuth answer says of it. */
class IssuedToken(val token: String, val expiresInSeconds: Long, val scope: String) {
    /** The token answer (RFC 6749 §5.1). */
    fun toAnswer(): Map<String, Any?> = linkedMapOf(
        ATOK.accessToken to token,
        ATOK.tokenType to ATOK.bearer,
        ATOK.expiresIn to expiresInSeconds,
        ATOK.scope to scope,
    )
}

/**
 * Access tokens (issue #1403): an API key exchanged for a signed JWT that lasts a day by default, which any service
 * verifies with the public keys published at [ATOK.jwks] alone -- the OAuth 2.0 client-credentials grant (RFC 6749
 * §4.4), with tokens shaped as RFC 9068 says.
 *
 * A token is signed with ES256 on the JDK. Its claims are `iss` (this deployment), `sub` (the user), `aud` (this
 * deployment's API), `client`, `scope` (`read` or `full`, the key's access), `iat`, `exp`, `jti`, and `client_id`, the
 * id of the key it was exchanged for: in client credentials the key *is* the OAuth client, which is what RFC 9068 names
 * that claim for. It never outlasts its key.
 *
 * **On this deployment's own nodes a token is its key**: it is honored only while the key still works and its owner is
 * enabled, with the key's read-only and session-only rules ([ApiKeyService.authenticateToken]). So revoking a key ends
 * its tokens here at once; a service verifying with the published keys alone honors one until it expires.
 *
 * **The signing keys** are kept as the instance encryption key is, in the instance config, shared by every node of the
 * instance. [rotate] publishes a new key that signs from [publishLead] later, and keeps the old one published until
 * every token it signed may have expired. A node rereads the keys each [reloadInterval] -- so by the time a new key
 * signs, every node holds it -- and at once (throttled) when a token names a key it does not hold.
 */
class AccessTokenService : ServiceInitializer {
    override val serviceName: String = AccessTokenService.serviceName

    @Volatile
    var keySet: SigningKeySet? = null

    override fun checkInit(cxt: KdrCxt) {
        if (keySet == null) keySet = loadKeys(cxt, createIfNone = true)
    }

    // --- signing keys --------------------------------------------------------------------------------------------

    /**
     * The signing keys as stored, those that may still verify a token. With [createIfNone], a set without a key that
     * signs is given one, under the row's lock, so nodes starting together on a fresh database agree on one key.
     */
    fun loadKeys(cxt: KdrCxt, createIfNone: Boolean = false): SigningKeySet {
        val now = cxt.now()
        val config = InstanceConfigService.get(cxt)
        var data = config.getConfig(cxt, configName)?.get(IC.configData)?.toJsonMap()
        if (createIfNone && storedKeys(data).none { it.retiredAt == null }) {
            data = config.updateConfig(cxt, configType, configName) { current ->
                val keys = storedKeys(current)
                if (keys.any { it.retiredAt == null }) null else stored(keys + SigningKey.mkNew(now))
            }
        }
        return SigningKeySet(storedKeys(data).filter { it.mayStillVerify(now) }, now)
    }

    /**
     * Rotates in a new signing key. It is published at once and signs from [publishLead] later, when every node has
     * reread the keys and holds it, so no node refuses a token it signs; the key signing now goes on until then. That
     * key is then retired, and stays published, verifying the tokens it signed, until the longest a token may last has
     * passed; keys retired longer ago than that are dropped. Answers the set as it now stands.
     */
    fun rotate(cxt: KdrCxt): SigningKeySet {
        val now = cxt.now()
        val takesOver = now + publishLead
        InstanceConfigService.get(cxt).updateConfig(cxt, configType, configName) { current ->
            val kept = storedKeys(current).filter { it.mayStillVerify(now) }.map { if (it.retiredAt == null) it.retiredAt(takesOver) else it }
            stored(kept + SigningKey.mkNew(now, takesOver))
        }
        val set = loadKeys(cxt)
        keySet = set
        LogAuth.info(cxt) {
            "User ${cxt.userProfile.userId} rotated the access-token signing key; ${set.newest?.kid} signs from ${takesOver.formatDate()}."
        }
        return set
    }

    /** The keys, reread when this node's copy is older than [reloadInterval]. */
    fun currentKeys(cxt: KdrCxt): SigningKeySet {
        val held = keySet
        if (held != null && cxt.now() < held.loadedAt + reloadInterval) return held
        return loadKeys(cxt, createIfNone = true).also { keySet = it }
    }

    /**
     * The key [kid], rereading the keys when this node does not hold it -- a rotation elsewhere it has not heard of yet
     * -- at most once each [unknownKidReloadInterval], so a run of made-up ids costs no run of reads.
     */
    fun keyFor(cxt: KdrCxt, kid: String): SigningKey? {
        val held = currentKeys(cxt)
        held.byKid(kid)?.let { return it }
        if (cxt.now() < held.loadedAt + unknownKidReloadInterval) return null
        return loadKeys(cxt).also { keySet = it }.byKid(kid)
    }

    /** The public keys that verify a token, as a JSON Web Key Set (RFC 7517 §5). */
    fun jwks(cxt: KdrCxt): Map<String, Any?> =
        mapOf(ATOK.keys to currentKeys(cxt).keys.sortedByDescending { it.createdAt }.map { Es256.toJwk(it.publicKey, it.kid) })

    // --- issuing and verifying -----------------------------------------------------------------------------------

    /**
     * A token for the key [access], which [cxt]'s request authenticated: acting as the request's user, with the key's
     * access, lasting [AccessTokenLifetime.hours] and never past the key's expiry.
     */
    fun issue(cxt: KdrCxt, access: ApiKeyAccess): IssuedToken {
        val now = cxt.now()
        val signer = currentKeys(cxt).signerAt(now) ?: throw KdrException("No access-token signing key is available.")
        val issuer = issuerOf(cxt)
        val lifetimeEnd = now + AccessTokenLifetime.hours(cxt).hours
        val expiresAt = access.expiresAt?.let { minOf(it, lifetimeEnd) } ?: lifetimeEnd
        val profile = cxt.userProfile
        val header = linkedMapOf(JWS.alg to JWS.es256, JWS.typ to JWS.accessTokenType, JWS.kid to signer.kid)
        val claims = linkedMapOf(
            JWS.iss to issuer,
            JWS.sub to profile.userId.toString(),
            JWS.aud to issuer,
            clientClaim to profile.client,
            JWS.scope to access.access,
            JWS.iat to now.epochSeconds,
            JWS.exp to expiresAt.epochSeconds,
            JWS.jti to RandomUtil.secureBytes(jtiBytes).base64Encode(),
            JWS.clientId to access.keyId,
        )
        val token = JwsParts.encode(header, claims) { Es256.sign(signer.privateKey, it) }
        LogAuth.info(cxt) {
            "Issued an access token for API key ${access.keyId} of user ${profile.userId}, '${access.access}' until ${expiresAt.formatDate()}."
        }
        return IssuedToken(token, (expiresAt.epochSeconds - now.epochSeconds).coerceAtLeast(0), access.access)
    }

    /**
     * Authenticates the request in [cxt] by [presented], a bearer shaped as a JWT, and answers whether it was one of
     * this deployment's: a token naming another issuer is not this node's to judge, and is left alone (false), as any
     * bearer that is not ours is. One naming this deployment is verified -- the algorithm and type pinned, the signature
     * against the key its header names, the audience, the expiry -- and its key's rules are then applied, as for the key
     * itself ([ApiKeyService.authenticateToken]). Anything wrong is one 401, counted against [sourceAddress].
     */
    fun authenticate(cxt: KdrCxt, presented: String, sourceAddress: String?): Boolean {
        val parts = JwsParts.parse(presented) ?: return false
        val issuer = issuerOf(cxt)
        if (parts.claims[JWS.iss] != issuer) return false
        val keys = ApiKeyService.get(cxt)
        val refuse = keys.refuser(cxt, sourceAddress, "The access token is not valid.")
        val header = parts.header
        // Pinning both is what stops an "alg": "none" token, and a JWT of another kind passing for an access token.
        if (header[JWS.alg] != JWS.es256 || header[JWS.typ] != JWS.accessTokenType) refuse("its algorithm or type is not ours")
        val kid = header.getOptStr(JWS.kid) ?: refuse("it names no signing key")
        // The header is the caller's to write, so what it names goes into the log tamed: one line, and short.
        val shownKid = kid.sanitizeForDisplay(maxLen = 40)
        val key = keyFor(cxt, kid) ?: refuse("no signing key '$shownKid' verifies tokens now")
        if (!Es256.verify(key.publicKey, parts.signingInput, parts.signature)) refuse("its signature does not match key '$shownKid'")
        val claims = parts.claims
        val audience = claims[JWS.aud]
        val forUs = if (audience is List<*>) issuer in audience else audience == issuer
        if (!forUs) refuse("it was issued for another audience")
        val nowSeconds = cxt.now().epochSeconds
        val exp = claims[JWS.exp].toOptLongOrNull() ?: refuse("it has no expiry")
        if (nowSeconds >= exp) refuse("it expired")
        val iat = claims[JWS.iat].toOptLongOrNull() ?: refuse("it has no issue time")
        if (iat > nowSeconds + clockSkewSeconds) refuse("it was issued in the future")
        val keyId = claims[JWS.clientId].toOptStr() ?: refuse("it names no API key")
        val userId = claims[JWS.sub].toOptStr()?.toLongOrNull() ?: refuse("it names no user")
        val client = claims[clientClaim].toOptStr() ?: refuse("it names no client")
        val scope = claims[JWS.scope].toOptStr()?.takeIf { it in AKEY.accesses } ?: refuse("its scope is not one a key has")
        keys.authenticateToken(cxt, keyId, userId, client, scope, refuse)
        return true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "AccessTokenService"

        /** Where the signing keys are kept in the instance config, as the instance encryption key is. */
        const val configType = "accessToken"
        const val configName = "accessTokenSigningKeys"

        /** The claim naming the client the token's user belongs to. */
        const val clientClaim = "client"

        /** Random bytes in a token's `jti`. */
        const val jtiBytes = 12

        /** How far ahead of this node's clock a token's issue time may be: the clocks of the nodes disagree a little. */
        const val clockSkewSeconds = 60L

        /** How long a node keeps its copy of the signing keys before rereading them. */
        val reloadInterval = 1.minutes

        /**
         * How long after a rotation publishes a key it begins to sign: past [reloadInterval], with a margin, so every node
         * has reread the keys and holds it by then.
         */
        val publishLead = reloadInterval + 1.minutes

        /** How soon after a read a token naming a key this node does not hold rereads them. */
        val unknownKidReloadInterval = 10.seconds

        fun get(cxt: KdrCxt): AccessTokenService =
            getOrNull(cxt) ?: throw KdrException("The $serviceName is not available on this node.", code = EXC.authNeeded)

        fun getOrNull(cxt: KdrCxt): AccessTokenService? = cxt.instanceConfig.get(serviceName) as? AccessTokenService

        /**
         * Who issues this deployment's tokens, which is also their audience: the deployment's public URL
         * (`KDR_PUBLIC_URL`) when set, which a service checking a token recognizes; otherwise a name built from the
         * instance's, so every node of it agrees.
         */
        fun issuerOf(cxt: KdrCxt): String = INVITE.configuredPublicUrl(cxt) ?: "urn:kdr:${cxt.instanceConfig.instanceName}"

        /**
         * The token in an `Authorization` header [value] when it is shaped as a JWT -- a bearer with two dots that is not
         * an API key -- or null. Whose it is, [authenticate] decides.
         */
        fun bearerTokenOf(value: String?): String? =
            ApiKeyService.bearerOf(value)?.takeIf { !it.startsWith(AKEY.prefix) && it.count { c -> c == '.' } == 2 }

        /** [keys] as the instance config stores them. */
        fun stored(keys: List<SigningKey>): Map<String, Any?> = mapOf(ATOK.keys to keys.map { it.toStored() })

        fun storedKeys(data: Map<String, Any?>?): List<SigningKey> =
            data?.get(ATOK.keys).toJsonListOfMaps().map { SigningKey.fromStored(it) }
    }
}
