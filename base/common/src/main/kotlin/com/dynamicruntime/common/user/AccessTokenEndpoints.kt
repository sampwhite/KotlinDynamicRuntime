package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT

/**
 * The access-token endpoints (issue #1403), over [AccessTokenService]:
 * - **The exchange**, [ATOK.token], in the anonymous `auth` section: the API key the request carries, for a signed
 *   token -- the OAuth 2.0 client-credentials grant (RFC 6749 §4.4), answered as §5.1 says.
 * - **The key set**, [ATOK.jwks], in the anonymous `auth` section: the public keys that verify a token, as a JSON Web
 *   Key Set (RFC 7517), which is all a service checking a token needs.
 * - **The rotation**, [ATOK.rotateSigningKey], in the `operator` section: a new signing key, the old one kept
 *   published until its tokens have expired.
 *
 * The first two answer as their standards do, the result as the whole body
 * ([bareResponse][com.dynamicruntime.common.endpoint.KdrEndpoint.bareResponse]), since a client library or a gateway
 * reads them and expects nothing around it.
 */
fun accessTokenSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.accessToken") {
    type(answerTypeName) {
        type = SCT.kObject
        description = "An access token, as the OAuth 2.0 token answer gives one (RFC 6749 §5.1)."
        property(ATOK.accessToken, "The token: a signed JWT, sent as 'Authorization: Bearer'.", required = true)
        property(ATOK.tokenType, "Always '${ATOK.bearer}'.", required = true)
        property(ATOK.expiresIn, "Seconds until the token expires.", required = true) { type = SCT.integer }
        property(ATOK.scope, "What the token may do: '${AKEY.read}' (GET requests only) or '${AKEY.full}', the key's access.",
            required = true)
    }
    type(jwkTypeName) {
        type = SCT.kObject
        description = "One public key that verifies a token: a JSON Web Key (RFC 7517), an ES256 key on P-256."
        for (member in listOf(JWS.kty, JWS.crv, JWS.x, JWS.y, JWS.kid, JWS.use, JWS.alg)) {
            property(member, "The JSON Web Key's '$member' member.", required = true)
        }
    }
    type(jwksTypeName) {
        type = SCT.kObject
        description = "The public keys that verify a token, newest first: a JSON Web Key Set (RFC 7517 §5)."
        property(ATOK.keys, "The keys. A token's header names the one that signed it, by 'kid'.", required = true) {
            type = SCT.array
            items { ref(jwkTypeName) }
        }
    }
    type(rotationTypeName) {
        type = SCT.kObject
        property(ATOK.signingKeyId, "The id of the key that now signs tokens.", required = true)
        property(ATOK.numPublished, "How many keys are published: the new one, and any whose tokens may still be live.",
            required = true) { type = SCT.integer }
    }

    generalEndpoint(
        ATOK.token,
        "Exchanges the API key sent as 'Authorization: Bearer kdr_…' for an access token: a signed JWT acting as the key " +
            "would, lasting a day by default and never past the key's expiry. Asked again with the key for a new one. " +
            "Form-encoded, as an OAuth client library sends it, or JSON.",
        HttpMethod.POST, outputRef = answerTypeName, publicApi = true, bareResponse = true,
        inputFields = {
            field(ATOK.grantType, "The OAuth 2.0 grant: '${ATOK.clientCredentials}', the only one there is.", required = true) {
                option(ATOK.clientCredentials)
            }
        },
    ) { c, _ ->
        val access = c.apiKey
            ?: throw KdrException("Send the API key to exchange as 'Authorization: Bearer kdr_…'.", code = EXC.authNeeded)
        // A token is a credential; nothing between here and the program should keep a copy (RFC 6749 §5.1).
        c.request?.webRequest?.let {
            it.addResponseHeader("Cache-Control", "no-store")
            it.addResponseHeader("Pragma", "no-cache")
        }
        AccessTokenService.get(c).issue(c, access).toAnswer()
    }
    generalEndpoint(
        ATOK.jwks,
        "The public keys that verify an access token, as a JSON Web Key Set: what a service checking a token needs, " +
            "with no call back here.",
        HttpMethod.GET, outputRef = jwksTypeName, publicApi = true, bareResponse = true,
    ) { c, _ ->
        AccessTokenService.get(c).jwks(c)
    }
    generalEndpoint(
        ATOK.rotateSigningKey,
        "Starts signing access tokens with a new key. The old key stays published, and goes on verifying, until every " +
            "token it signed has expired.",
        HttpMethod.POST, outputRef = rotationTypeName, sessionOnly = true,
    ) { c, _ ->
        val set = AccessTokenService.get(c).rotate(c)
        mapOf(ATOK.signingKeyId to set.signer?.kid, ATOK.numPublished to set.keys.size)
    }
}

private const val answerTypeName = "AccessTokenAnswer"
private const val jwkTypeName = "JsonWebKey"
private const val jwksTypeName = "JsonWebKeySet"
private const val rotationTypeName = "SigningKeyRotation"
