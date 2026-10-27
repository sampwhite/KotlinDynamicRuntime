package com.dynamicruntime.common.user

/**
 * An **access token**'s vocabulary (issue #1403): the paths that issue tokens and publish the keys that verify them, and
 * the names the OAuth 2.0 token answer and a JSON Web Key Set travel under. In the kernel beside [AKEY], since a token
 * is an API key exchanged for something short-lived.
 *
 * A program sends its API key to [token] with [grantType] `client_credentials` (RFC 6749 §4.4) and is answered a signed
 * JWT (RFC 9068) that lasts a day by default. Any service verifies one with the public keys at [jwks] alone. The
 * names below are OAuth's and JOSE's wire spelling, hence the snake_case values.
 */
@Suppress("ConstPropertyName")
object ATOK {
    // --- paths ---
    /** Exchanges the API key the request carries for an access token. In the anonymous `auth` section. */
    const val token = "/auth/token"

    /** The public keys that verify a token, as a JSON Web Key Set (RFC 7517). In the anonymous `auth` section. */
    const val jwks = "/auth/jwks"

    /**
     * Publishes a new signing key, which signs a couple of minutes later once every node holds it, keeping the old one
     * published until the tokens it signed may have expired.
     */
    const val rotateSigningKey = "/operator/accessToken/rotateSigningKey"

    // --- the token request and its answer (RFC 6749 §4.4, §5.1) ---
    const val grantType = "grant_type"
    const val clientCredentials = "client_credentials"
    const val accessToken = "access_token"
    const val tokenType = "token_type"
    const val expiresIn = "expires_in"
    const val scope = "scope"

    /** The [tokenType] of every token issued: it is sent as `Authorization: Bearer`. */
    const val bearer = "Bearer"

    /** The answer's headers, which keep anything between here and the program from storing a copy (§5.1). */
    const val cacheControlHeader = "Cache-Control"
    const val pragmaHeader = "Pragma"
    const val noCache = "no-cache"

    // --- the key set (RFC 7517) ---
    const val keys = "keys"

    /** What [rotateSigningKey] answers: the new key's id, when it begins to sign, and how many keys are published. */
    const val newKeyId = "newKeyId"
    const val signsFrom = "signsFrom"
    const val numPublished = "numPublished"

    // --- lifetimes ---
    /** Hours a token lasts by default (`KDR_ACCESS_TOKEN_HOURS`). */
    const val defaultHours = 24

    /** The most hours a deployment may let a token last: a week. */
    const val maxHours = 168
}
