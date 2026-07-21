package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.toJsonStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Coverage for Google ID-token verification (issue #157). The test signs its **own** tokens with a generated
 * RSA key and hands the verifier a key source holding the matching public key, so the real verification path
 * runs end to end -- signature included -- with no network and no stubbing of the check under test.
 *
 * The rejection cases are the point of the file. In particular the audience case: a token that is perfectly
 * well signed by "Google" but minted for a different application must not verify, or every other site's ID
 * tokens become valid logins here.
 */
class GoogleIdTokenVerifierTest : StringSpec({

    val ourClientId = "kdr-test.apps.googleusercontent.com"
    val testKid = "test-key-1"

    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** A key source holding only [withKid] -> the generated public key, standing in for Google's JWKS. */
    fun keySource(withKid: String = testKid, key: RSAPublicKey = keyPair.public as RSAPublicKey) = object : JwtKeySource {
        override fun rsaKey(cxt: KdrCxt, kid: String): RSAPublicKey? = if (kid == withKid) key else null
    }

    fun cxt() = KdrCxt.mkSimpleCxt("googleVerify")

    /** Builds a signed JWT from [header] and [claims], signing with [signingKey] unless told to corrupt it. */
    fun mkToken(
        claims: Map<String, Any?>,
        header: Map<String, Any?> = mapOf(GOOG.alg to GOOG.rs256, GOOG.kid to testKid),
        signingKey: KeyPair = keyPair,
        corruptSignature: Boolean = false,
    ): String {
        val h = header.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val c = claims.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(signingKey.private)
        signer.update("$h.$c".toByteArray(Charsets.UTF_8))
        val sig = signer.sign()
        if (corruptSignature) sig[0] = (sig[0] + 1).toByte()
        return "$h.$c.${sig.base64Encode()}"
    }

    /** Claims for a well-formed token, a full hour from expiring, which individual tests then vary. */
    fun goodClaims(
        aud: String = ourClientId,
        iss: String = "https://accounts.google.com",
        expSeconds: Long = (System.currentTimeMillis() / 1000) + 3600,
        email: String? = "alice@example.com",
        emailVerified: Any? = true,
        sub: String? = "google-subject-123",
    ): Map<String, Any?> = buildMap {
        put(GOOG.aud, aud)
        put(GOOG.iss, iss)
        put(GOOG.exp, expSeconds)
        if (sub != null) put(GOOG.sub, sub)
        if (email != null) put(GOOG.email, email)
        put(GOOG.emailVerified, emailVerified)
        put(GOOG.name, "Alice Example")
    }

    fun verifier(clientId: String = ourClientId, source: JwtKeySource = keySource()) =
        GoogleIdTokenVerifier(clientId, source)

    /**
     * Asserts [block] is refused with the **opaque** `googleTokenInvalid` message.
     *
     * Deliberately stricter than `shouldThrow<KdrException>`: there is only one exception type here, so a bare
     * type assertion also passes when some internal error escapes -- which is exactly how a leaked JSON-parser
     * message once slipped through this file. Requiring the specific message is what makes these tests prove
     * the "every failure looks identical from outside" property rather than merely that something failed.
     */
    fun shouldBeRefused(block: () -> Unit) {
        val e = shouldThrow<KdrException>(block)
        e.msg?.key shouldBe AERR.googleTokenInvalid
    }

    "a well-formed token for our client id verifies, and its claims come back" {
        val token = verifier().verify(cxt(), mkToken(goodClaims()))
        token.subject shouldBe "google-subject-123"
        token.email shouldBe "alice@example.com"
        token.emailVerified shouldBe true
        token.displayName shouldBe "Alice Example"
    }

    "both of Google's issuer spellings are accepted" {
        verifier().verify(cxt(), mkToken(goodClaims(iss = "accounts.google.com"))).subject shouldBe "google-subject-123"
        verifier().verify(cxt(), mkToken(goodClaims(iss = "https://accounts.google.com"))).subject shouldBe "google-subject-123"
    }

    // The security case this class exists for: a real Google signature is not evidence the token was minted
    // for *us*. Without the audience check, any other application's ID token would log its bearer in here.
    "a token minted for a different application is refused" {
        shouldBeRefused { verifier().verify(cxt(), mkToken(goodClaims(aud = "someone-else.apps.googleusercontent.com"))) }
    }

    "a token from a non-Google issuer is refused" {
        shouldBeRefused { verifier().verify(cxt(), mkToken(goodClaims(iss = "https://evil.example.com"))) }
    }

    "an expired token is refused" {
        val longExpired = (System.currentTimeMillis() / 1000) - 3600
        shouldBeRefused { verifier().verify(cxt(), mkToken(goodClaims(expSeconds = longExpired))) }
    }

    "a token signed by some other key is refused" {
        val attackerKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        // Signed with the attacker's key, but naming the kid whose public key we trust.
        shouldBeRefused { verifier().verify(cxt(), mkToken(goodClaims(), signingKey = attackerKey)) }
    }

    "a token whose signature has been tampered with is refused" {
        shouldBeRefused { verifier().verify(cxt(), mkToken(goodClaims(), corruptSignature = true)) }
    }

    // "alg": "none" is the classic JWT forgery: strip the signature and claim there is nothing to check.
    "an unsigned token claiming alg none is refused" {
        val h = mapOf(GOOG.alg to "none", GOOG.kid to testKid).toJsonStr(compact = true)
            .toByteArray(Charsets.UTF_8).base64Encode()
        val c = goodClaims().toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        shouldBeRefused { verifier().verify(cxt(), "$h.$c.") }
    }

    "a token naming an unknown key id is refused" {
        val source = keySource(withKid = "some-other-kid")
        shouldBeRefused { verifier(source = source).verify(cxt(), mkToken(goodClaims())) }
    }

    "a token with no subject is refused" {
        shouldBeRefused { verifier().verify(cxt(), mkToken(goodClaims(sub = null))) }
    }

    "malformed input is refused rather than crashing" {
        shouldBeRefused { verifier().verify(cxt(), "not-a-jwt") } // one segment: fails the three-part check
        shouldBeRefused { verifier().verify(cxt(), "a.b") } // two segments: same
        shouldBeRefused { verifier().verify(cxt(), "!!!.!!!.!!!") } // three segments, not base64url
    }

    /*
     * Three segments of *valid* base64url characters that decode to bytes which are not JSON. Its own test
     * because it is the one malformed shape that reaches the JSON parser -- the cases above each fail earlier,
     * at the segment count or at base64 decoding, so none of them exercise this branch. A live probe with
     * exactly this input once got the parser's "Unexpected character ... at offset 0" back, because the parser
     * *throws* on bad JSON rather than returning null and the reject was written as an elvis on null.
     */
    "a three-part token whose segments are valid base64 but not JSON is refused opaquely" {
        shouldBeRefused { verifier().verify(cxt(), "not.a.jwt") }
    }

    // Google reports email_verified as a real boolean, but it has historically also been sent as a string.
    // The claim is carried, not enforced, here -- the login path is what refuses an unverified address.
    "email_verified false is carried through as false" {
        verifier().verify(cxt(), mkToken(goodClaims(emailVerified = false))).emailVerified shouldBe false
        verifier().verify(cxt(), mkToken(goodClaims(emailVerified = "false"))).emailVerified shouldBe false
    }

    "a JWKS document parses into keys by kid, skipping entries that are not usable" {
        val n = (keyPair.public as RSAPublicKey).modulus.toByteArray().base64Encode()
        val e = (keyPair.public as RSAPublicKey).publicExponent.toByteArray().base64Encode()
        val jwks = mapOf(
            GOOG.keys to listOf(
                mapOf(GOOG.kid to "good", GOOG.modulus to n, GOOG.publicExponent to e),
                mapOf(GOOG.kid to "no-modulus"), // skipped, and must not take the good one down with it
            ),
        ).toJsonStr(compact = true)
        val parsed = GoogleJwksKeySource.parseJwks(jwks)
        parsed.keys shouldBe setOf("good")
    }
})
