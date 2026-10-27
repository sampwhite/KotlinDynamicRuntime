package com.dynamicruntime.common.user

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.base64Decode
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * JOSE header, JWT claim and JSON Web Key member names (RFC 7515, 7517, 7518, 7519, 9068), in their wire spelling --
 * hence the snake_case values.
 */
@Suppress("ConstPropertyName")
object JWS {
    // Header.
    const val alg = "alg"
    const val kid = "kid"
    const val typ = "typ"

    // Claims.
    const val iss = "iss"
    const val sub = "sub"
    const val aud = "aud"
    const val exp = "exp"
    const val iat = "iat"
    const val jti = "jti"
    const val clientId = "client_id"
    const val scope = "scope"

    /** The one signing algorithm this deployment signs and accepts: ECDSA on P-256 with SHA-256. */
    const val es256 = "ES256"

    /** The `typ` of an access token (RFC 9068 §2.1), which keeps one from passing for any other kind of JWT. */
    const val accessTokenType = "at+jwt"

    // A JSON Web Key (RFC 7517 §4, RFC 7518 §6.2).
    const val kty = "kty"
    const val ec = "EC"
    const val crv = "crv"
    const val p256 = "P-256"
    const val x = "x"
    const val y = "y"
    const val use = "use"
    const val sig = "sig"
}

/**
 * A compact JWS read apart and **not yet verified**: its header and claims, the bytes its signature covers, and the
 * signature. [parse] answers null for anything not shaped as one, so a caller has one thing to refuse.
 */
class JwsParts(
    val header: Map<String, Any?>,
    val claims: Map<String, Any?>,
    val signingInput: ByteArray,
    val signature: ByteArray,
) {
    companion object {
        /**
         * Longer than any JWT this deployment reads -- its own tokens, which are well under a kilobyte, and Google's ID
         * tokens -- so only an absurd one is refused before it is decoded.
         */
        const val maxLength = 16384

        /**
         * [token] read apart, or null when it is not three base64url segments of which the first two are JSON
         * objects. The decode and the parse share one `try`: ordinary letters are valid base64url, and decode to
         * bytes that are not JSON, whose parse error would otherwise reach the caller naming a byte and an offset.
         */
        fun parse(token: String): JwsParts? {
            if (token.length > maxLength) return null
            val parts = token.split(".")
            if (parts.size != 3) return null
            return try {
                JwsParts(
                    header = parts[0].base64Decode().toString(Charsets.UTF_8).jsonMap() ?: return null,
                    claims = parts[1].base64Decode().toString(Charsets.UTF_8).jsonMap() ?: return null,
                    signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8),
                    signature = parts[2].base64Decode(),
                )
            } catch (_: Exception) {
                null
            }
        }

        /** A compact JWS of [header] and [claims], signed by [sign] over the two encoded segments. */
        fun encode(header: Map<String, Any?>, claims: Map<String, Any?>, sign: (ByteArray) -> ByteArray): String {
            fun segment(m: Map<String, Any?>) = m.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
            val input = "${segment(header)}.${segment(claims)}"
            return "$input.${sign(input.toByteArray(Charsets.UTF_8)).base64Encode()}"
        }
    }
}

/**
 * ES256 (RFC 7518 §3.4) on the JDK's `java.security`, with no library: ECDSA on P-256 with SHA-256. A JWS carries the
 * signature as the two 32-byte integers side by side, which is the JDK's `P1363` format, not the DER the plain
 * `SHA256withECDSA` writes.
 */
object Es256 {
    private const val curve = "secp256r1"
    private const val signatureAlgorithm = "SHA256withECDSAinP1363Format"

    /** Bytes in each coordinate of a P-256 point, and in each half of a signature. */
    const val coordinateBytes = 32

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    fun sign(key: PrivateKey, input: ByteArray): ByteArray =
        Signature.getInstance(signatureAlgorithm).run {
            initSign(key)
            update(input)
            sign()
        }

    /** Whether [signature] is [key]'s over [input]; false, never a throw, for one malformed. */
    fun verify(key: PublicKey, input: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(signatureAlgorithm).run {
            initVerify(key)
            update(input)
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    /** [key] as a public JSON Web Key named [kid], for signing with ES256. */
    fun toJwk(key: ECPublicKey, kid: String): Map<String, Any?> = linkedMapOf(
        JWS.kty to JWS.ec,
        JWS.crv to JWS.p256,
        JWS.x to coordinate(key.w.affineX).base64Encode(),
        JWS.y to coordinate(key.w.affineY).base64Encode(),
        JWS.kid to kid,
        JWS.use to JWS.sig,
        JWS.alg to JWS.es256,
    )

    /** A coordinate as a JWK writes it: unsigned, big-endian, exactly [coordinateBytes] long. */
    fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(coordinateBytes - bytes.size) + bytes
    }

    /** A private key as stored: PKCS #8, base64url. */
    fun encodePrivate(key: PrivateKey): String = key.encoded.base64Encode()

    fun decodePrivate(stored: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(stored.base64Decode()))

    /** A public key as stored: X.509, base64url. */
    fun encodePublic(key: PublicKey): String = key.encoded.base64Encode()

    fun decodePublic(stored: String): ECPublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(stored.base64Decode())) as? ECPublicKey
            ?: throw KdrException("A stored signing key is not an elliptic-curve public key.")
}
