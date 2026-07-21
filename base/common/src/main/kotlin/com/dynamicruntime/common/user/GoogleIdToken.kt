package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.KdrMsg
import com.dynamicruntime.common.util.base64Decode
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.jsonMap
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

/** Google sign-in constants: config keys, the endpoints, and the JWT/claim names we read. */
@Suppress("ConstPropertyName")
object GOOG {
    /** Instance-config key holding the deployment's Google OAuth client id (set by `KDR_GOOGLE_CLIENT_ID`). */
    const val googleClientId = "googleClientId"

    /** Environment variable naming the client id. */
    const val googleClientIdEnvVar = "KDR_GOOGLE_CLIENT_ID"

    /** Instance-config key overriding the JWKS endpoint. Exists so a test can serve its own key set. */
    const val googleJwksUri = "googleJwksUri"

    /**
     * Instance-config key holding a [JwtKeySource] instance outright, bypassing the JWKS fetch. For tests,
     * which sign their own tokens: it lets them drive the real verification path with no network at all.
     */
    const val googleKeySource = "googleKeySource"

    /** Google's published signing keys (the JWKS Google rotates its ID-token signing keys through). */
    const val defaultJwksUri = "https://www.googleapis.com/oauth2/v3/certs"

    /** The only signing algorithm we accept for an ID token. */
    const val rs256 = "RS256"

    /** The two spellings Google uses for the `iss` claim; both are legitimate. */
    val issuers = setOf("accounts.google.com", "https://accounts.google.com")

    // JWT header + claim names (Google's wire spelling, hence the snake_case values).
    const val kid = "kid"
    const val alg = "alg"
    const val sub = "sub"
    const val aud = "aud"
    const val iss = "iss"
    const val exp = "exp"
    const val email = "email"
    const val emailVerified = "email_verified"
    const val name = "name"

    // JWKS entry fields.
    const val keys = "keys"
    const val modulus = "n"
    const val publicExponent = "e"

    /**
     * Clock skew allowed on the `exp` check, in milliseconds. Small on purpose: it absorbs ordinary clock
     * drift between Google and this node without meaningfully extending a token's life.
     */
    const val expiryLeewayMs = 60L * 1000
}

/**
 * Resolves the Google sign-in configuration, following the house pattern (see [AdminRules.adminEmailDomain]):
 * the instance-config option wins so a test can set it directly, with the environment variable behind it.
 */
object GoogleAuthConfig {
    /**
     * The deployment's Google OAuth client id, or null when Google sign-in is not configured -- in which case
     * the feature is simply not offered. Never defaulted: a guessed client id would defeat the audience check.
     */
    fun clientId(config: KdrInstanceConfig): String? =
        ((config.get(GOOG.googleClientId) as? String) ?: config.getEnvVar(GOOG.googleClientIdEnvVar))
            ?.trim()?.ifEmpty { null }

    /** Where to fetch Google's signing keys. Overridable only so a test can serve its own key set. */
    fun jwksUri(config: KdrInstanceConfig): String =
        (config.get(GOOG.googleJwksUri) as? String)?.trim()?.ifEmpty { null } ?: GOOG.defaultJwksUri

    /**
     * The verifier for this instance, or null when no client id is configured. A configured [JwtKeySource]
     * (tests) is preferred over fetching Google's real JWKS.
     */
    fun mkVerifier(config: KdrInstanceConfig): GoogleIdTokenVerifier? {
        val clientId = clientId(config) ?: return null
        val keySource = (config.get(GOOG.googleKeySource) as? JwtKeySource) ?: GoogleJwksKeySource(jwksUri(config))
        return GoogleIdTokenVerifier(clientId, keySource)
    }
}

/**
 * The claims of a **verified** Google ID token. Constructing one is the assertion that the signature, issuer,
 * audience, and expiry were all checked -- so anything holding one may trust [subject] as an identity.
 *
 * [emailVerified] is carried rather than enforced here: it is Google's statement about whether *it* has
 * verified the address, and what to do about that is an auth-flow decision (see [AuthFormHandler.loginByGoogle]).
 */
class GoogleIdToken(
    /** Google's stable per-account identifier (the `sub` claim) -- the key we link a local user by. */
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
)

/**
 * Supplies the RSA public keys an ID token's signature is checked against, by JWT `kid`. An interface so a
 * test can verify tokens it signed itself, exercising the real verification path rather than stubbing past it.
 */
interface JwtKeySource {
    /** The key for [kid], or null when this source does not have it. */
    fun rsaKey(cxt: KdrCxt, kid: String): RSAPublicKey?
}

/**
 * Google's published signing keys, fetched from the JWKS endpoint and cached in memory. Google rotates these
 * keys, so a `kid` that misses the cache triggers exactly one refetch -- which is also what makes a rotation
 * self-healing without a restart. Fetching is the only network call the login path makes; the far more common
 * case is a cache hit.
 */
class GoogleJwksKeySource(private val jwksUri: String = GOOG.defaultJwksUri) : JwtKeySource {
    private val httpClient: HttpClient by lazy { HttpClient.newHttpClient() }

    // Guarded by `this`: a rotation can have several request threads miss at once, and they must not each
    // start a fetch or race the map into an inconsistent state.
    private var cached: Map<String, RSAPublicKey> = emptyMap()

    override fun rsaKey(cxt: KdrCxt, kid: String): RSAPublicKey? {
        synchronized(this) {
            cached[kid]?.let { return it }
            cached = fetchKeys(cxt)
            return cached[kid]
        }
    }

    private fun fetchKeys(cxt: KdrCxt): Map<String, RSAPublicKey> {
        val request = HttpRequest.newBuilder().uri(URI.create(jwksUri)).GET().build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw KdrException("Could not reach Google's signing keys at '$jwksUri'.", cause = e)
        }
        if (response.statusCode() != 200) {
            throw KdrException("Google's signing keys returned status ${response.statusCode()}.")
        }
        return parseJwks(response.body())
    }

    companion object {
        /**
         * Extracts the RSA keys from a JWKS document, keyed by `kid`. Entries that are not RSA signing keys,
         * or that are malformed, are skipped rather than failing the whole set -- one unusable key must not
         * take out the others alongside it.
         */
        fun parseJwks(body: String): Map<String, RSAPublicKey> {
            val doc = body.jsonMap() ?: throw KdrException("Google's signing keys were not a JSON object.")
            val keys = doc[GOOG.keys] as? List<*> ?: throw KdrException("Google's signing keys had no 'keys' array.")
            val factory = KeyFactory.getInstance("RSA")
            val out = mutableMapOf<String, RSAPublicKey>()
            for (entry in keys) {
                val m = entry as? Map<*, *> ?: continue
                val kid = m[GOOG.kid]?.toString() ?: continue
                val n = m[GOOG.modulus]?.toString() ?: continue
                val e = m[GOOG.publicExponent]?.toString() ?: continue
                val key = try {
                    // A JWKS carries the modulus/exponent as unsigned big-endian base64url; the 1 signum keeps
                    // a high bit set in the modulus from being read as a negative number.
                    factory.generatePublic(
                        RSAPublicKeySpec(BigInteger(1, n.base64Decode()), BigInteger(1, e.base64Decode())),
                    ) as? RSAPublicKey ?: continue
                } catch (_: Exception) {
                    continue
                }
                out[kid] = key
            }
            return out
        }
    }
}

/**
 * Verifies Google ID tokens (issue #157) -- the JWT the browser's Google sign-in hands back. Hand-written
 * rather than pulling in a JWT library: the JDK supplies RSA verification and the codebase already supplies
 * JSON parsing and URL-safe base64 (which is exactly a JWT's encoding), so a dependency would buy little.
 *
 * **[clientId] is checked against the token's `aud`, and that check is the point of this class.** Google
 * signs an ID token for *every* application using Google sign-in, all with the same keys; without the audience
 * check, a token minted for any other site would verify here and log its bearer in as whatever email it names.
 * The signature alone proves only that Google issued the token, not that it issued it to us.
 */
class GoogleIdTokenVerifier(private val clientId: String, private val keySource: JwtKeySource) {
    /**
     * Verifies [idToken] and returns its claims, or throws [KdrException] carrying the `googleTokenInvalid`
     * message. Every failure reports the same thing to the caller -- the specific reason is only logged, so a
     * probe cannot learn which of the checks it tripped.
     */
    fun verify(cxt: KdrCxt, idToken: String): GoogleIdToken {
        val parts = idToken.split(".")
        if (parts.size != 3) reject(cxt, "the token is not a three-part JWT")

        val header = decodeSegment(cxt, parts[0], "header")
        if (header.getOptStr(GOOG.alg) != GOOG.rs256) {
            // Pinning the algorithm is what stops an "alg": "none" token, and stops a token signed with a
            // symmetric algorithm keyed by a public value we publish.
            reject(cxt, "the signing algorithm is not ${GOOG.rs256}")
        }
        val kid = header.getOptStr(GOOG.kid) ?: reject(cxt, "the token header has no key id")

        val key = keySource.rsaKey(cxt, kid) ?: reject(cxt, "no Google signing key matches key id '$kid'")
        val signedContent = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        val signature = try {
            parts[2].base64Decode()
        } catch (_: Exception) {
            reject(cxt, "the signature is not valid base64url")
        }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(signedContent)
        if (!verifier.verify(signature)) reject(cxt, "the signature does not match Google's key")

        val claims = decodeSegment(cxt, parts[1], "payload")
        if (claims.getOptStr(GOOG.iss) !in GOOG.issuers) reject(cxt, "the issuer is not Google")
        // The audience check. See the class comment: a valid Google signature is not by itself evidence that
        // the token was minted for *this* application.
        if (claims.getOptStr(GOOG.aud) != clientId) reject(cxt, "the token was issued for a different application")
        val expSeconds = claims[GOOG.exp].toString().toLongOrNull() ?: reject(cxt, "the token has no expiry")
        if (cxt.now().toEpochMilliseconds() > expSeconds * 1000 + GOOG.expiryLeewayMs) {
            reject(cxt, "the token has expired")
        }
        val subject = claims.getOptStr(GOOG.sub) ?: reject(cxt, "the token has no subject")

        return GoogleIdToken(
            subject = subject,
            email = claims.getOptStr(GOOG.email),
            emailVerified = claims.getOptBool(GOOG.emailVerified) == true,
            displayName = claims.getOptStr(GOOG.name),
        )
    }

    /**
     * Decodes one base64url JWT segment into its claims map, or rejects.
     *
     * The parse has to be inside the `try` with the decode: a segment of ordinary letters ("not" in
     * "not.a.jwt") is *valid* base64url, so it decodes cleanly to bytes that are not JSON, and the JSON parser
     * then throws its own `KdrException` naming the offending byte and offset. Left uncaught that becomes the
     * response an anonymous caller sees -- leaking parser internals and breaking this class's rule that every
     * failure looks identical from outside.
     */
    private fun decodeSegment(cxt: KdrCxt, segment: String, what: String): Map<String, Any?> = try {
        segment.base64Decode().toString(Charsets.UTF_8).jsonMap()
    } catch (_: Exception) {
        null
    } ?: reject(cxt, "the $what is not base64url-encoded JSON")

    /**
     * Logs why verification failed and throws the single opaque error the caller sees. Returns [Nothing] so
     * call sites can use it in an elvis branch.
     */
    private fun reject(cxt: KdrCxt, reason: String): Nothing {
        LogAuth.info(cxt) { "Google ID token rejected: $reason." }
        throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.googleTokenInvalid))
    }
}
