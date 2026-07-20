package com.dynamicruntime.common.util

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.spec.AlgorithmParameterSpec
import java.util.Base64
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encoding, hashing, and encryption helpers -- the crypto foundation the auth layer builds on. Ported and
 * modernized from dn's `EncodeUtil`: a thin, convenience wrapper over the JDK's `java.security` /
 * `javax.crypto` primitives (no third-party crypto library). Backend-only; not part of the KMP surface.
 *
 * Modernization vs. the dn original:
 *  - Base64 is URL-safe (see [base64Encode] / [base64EncodeStripped]);
 *  - [stdHash] uses SHA-256 rather than MD5;
 *  - Password hashing uses OWASP-current PBKDF2 parameters and a constant-time verified (no timing-jitter sleep).
 */

// --- password hashing (PBKDF2-HMAC-SHA512) ----------------------------------

// OWASP (2023) guidance for PBKDF2-HMAC-SHA512: >= 210k iterations. The derived key and salt are 256 and
// 128 bits. Hashing is deliberately CPU-intensive (~100ms); in a full system it belongs on an auth node.
private const val numHashIterations = 210_000

private const val hashKeyBits = 256

private const val saltBytes = 16

private const val passwordAlg = "pbkdf2"

private const val passwordAlgParam = "PBKDF2WithHmacSHA512"

// --- symmetric encryption (AES-128-GCM) -------------------------------------

private const val encryptionSig = "AGN"

private const val keySig = "UU"

private const val aesKeyBytes = 16

private const val gcmIvBytes = 12

private const val gcmTagBits = 128

// SecretKeyFactory and Cipher are not thread-safe, so each is held per-thread.
private val threadSecretKeyFactory = ThreadLocal.withInitial { mkSecretKeyFactory() }
private val threadCipher = ThreadLocal.withInitial { mkCipher() }

// --- base64 -----------------------------------------------------------------

// URL-safe base64 with no padding: the same output as Apache Commons Codec's encodeBase64URLSafeString, but
// straight from the JDK (no third-party dependency). The URL-safe alphabet uses `-`/`_` (not `+`/`/`), and
// dropping the `=` padding keeps the result fully URL-safe; the URL decoder round-trips unpadded input.
private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
private val urlDecoder = Base64.getUrlDecoder()

/** URL-safe, unpadded base64, reversible (decode with [base64Decode]). */
fun ByteArray.base64Encode(): String = urlEncoder.encodeToString(this)

/** Decodes a string produced by [base64Encode]. */
fun String.base64Decode(): ByteArray = urlDecoder.decode(this)

/**
 * [base64Encode] with `-` further replaced by `z`, leaving only characters a full-text log tokenizer keeps
 * in one token (`[A-Za-z0-9_]`). NOT reversible -- use this whenever the goal is a compact, opaque,
 * search-friendly string (a hash, a random id) rather than recoverable bytes.
 */
fun ByteArray.base64EncodeStripped(): String = base64Encode().replace('-', 'z')

// --- non-crypto hashing / random strings ------------------------------------

/** A fast, non-security digest of [this] (SHA-256), as raw bytes. */
fun String.stdHashToBytes(): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(this.toByteArray(Charsets.UTF_8))

/** A fast, non-security digest of [this], as a compact search-friendly string ([base64EncodeStripped]). */
fun String.stdHash(): String = this.stdHashToBytes().base64EncodeStripped()

/**
 * A CRC32 checksum of [this], as a short hex string. Not a security hash -- an inexpensive change-detection /
 * cache-busting content hash: the same bytes give the same value, and a change gives a (near-certainly)
 * different one. The runtime's one content-hash primitive: a content file's `buildId`
 * ([com.dynamicruntime.common.content.ContentResources.buildId]) and a response's `contentHash` (the endpoint
 * envelope) are both this.
 */
fun ByteArray.crc32Hex(): String {
    val crc = CRC32()
    crc.update(this)
    return crc.value.toString(16)
}

/** The [crc32Hex] of [this] string's UTF-8 bytes. */
fun String.crc32Hex(): String = this.toByteArray(Charsets.UTF_8).crc32Hex()

/**
 * Shortens [this] to at most [maxLen] characters while keeping some of the original visible: a slice of the
 * head and tail around a 20-char hash of the whole, which is near-certain to keep the result unique.
 * [maxLen] should be at least 40.
 */
fun String.mkUniqueShorterStr(maxLen: Int): String {
    val l = length
    if (l <= maxLen) {
        return this
    }
    val h = this.stdHash().substring(0, 20)
    val leftover = maxLen - 20
    val start = leftover / 2
    val end = leftover - start
    return substring(0, start) + h + substring(l - end, l)
}

/** A random string of [numBytes] bytes' worth of (non-secure) randomness, in the stripped encoding. */
fun mkRndString(numBytes: Int): String = RandomUtil.bytes(numBytes).base64EncodeStripped()

// Characters chosen so that, even with poor vision, they are likely to be discerned correctly.
private val uniqueLookingChars = charArrayOf(
    'A', 'F', 'H', 'K', 'M', 'P', 'T', 'X', 'Y', 'W', 'Z', '3', '4', '6', '8', '9',
)

/** Renders the first [numBytes] of [this] as pairs of easily distinguishable characters (for codes a human
 *  reads back). Each byte becomes two characters from a 16-symbol, low-confusion alphabet. */
fun ByteArray.toReadableChars(numBytes: Int = size): String {
    val sb = StringBuilder(numBytes * 2)
    for (i in 0 until numBytes) {
        val b = this[i].toInt()
        sb.append(uniqueLookingChars[(b ushr 4) and 0x0F])
        sb.append(uniqueLookingChars[b and 0x0F])
    }
    return sb.toString()
}

/**
 * This project's standard unique id, used in preference to a GUID because it sorts by time -- handy for
 * troubleshooting and paging. A compact millisecond timestamp (instant.formatCompactId) followed by 4
 * bytes of randomness, all in search-friendly characters. (The date is not meant to be parsed back out.)
 */
fun KdrCxt.mkUniqueId(): String = now().formatCompactId() + mkRndString(4)

// --- password hashing -------------------------------------------------------

private fun mkSecretKeyFactory(): SecretKeyFactory =
    try {
        SecretKeyFactory.getInstance(passwordAlgParam)
    } catch (e: GeneralSecurityException) {
        throw KdrException("Password algorithm '$passwordAlgParam' is not supported.", e)
    }

/** Hashes [this] password with a fresh random salt, returning `pbkdf2|<salt>|<hash>` (base64 parts). */
fun String.hashPassword(): String = hashPasswordWithSalt(this, RandomUtil.secureBytes(saltBytes))

private fun hashPasswordWithSalt(password: String, salt: ByteArray): String {
    val spec = PBEKeySpec(password.toCharArray(), salt, numHashIterations, hashKeyBits)
    try {
        val hash = threadSecretKeyFactory.get().generateSecret(spec).encoded
        // Pipe separates the parts: URL-safe base64 never produces it.
        return "$passwordAlg|${salt.base64Encode()}|${hash.base64Encode()}"
    } catch (e: GeneralSecurityException) {
        throw KdrException("Could not generate the password hash.", e)
    } finally {
        spec.clearPassword()
    }
}

/**
 * Verifies [this] password against a value produced by [hashPassword]. A stored value that is not in the
 * `alg|salt|hash` shape is treated as a plaintext password and compared directly -- a deliberate escape
 * hatch for bootstrap / developer accounts. The comparison is constant-time.
 */
fun String.checkPassword(storedHash: String): Boolean {
    val parts = storedHash.split("|")
    if (parts.size != 3) {
        return constantTimeEquals(this, storedHash)
    }
    if (parts[0] != passwordAlg) {
        throw KdrException.mkConv("Password algorithm '${parts[0]}' is not supported.")
    }
    val salt = parts[1].base64Decode()
    val predicted = hashPasswordWithSalt(this, salt)
    return constantTimeEquals(predicted, storedHash)
}

/** Constant-time string comparison (via [MessageDigest.isEqual]) to avoid leaking match progress through timing. */
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

// --- symmetric encryption ---------------------------------------------------

private fun mkCipher(): Cipher =
    try {
        Cipher.getInstance("AES/GCM/NoPadding")
    } catch (e: GeneralSecurityException) {
        throw KdrException("Could not create the AES/GCM cipher.", e)
    }

private fun getCipher(mode: Int, key: SecretKey, spec: AlgorithmParameterSpec): Cipher {
    val cipher = threadCipher.get()
    try {
        cipher.init(mode, key, spec)
    } catch (e: GeneralSecurityException) {
        throw KdrException("Could not initialize the cipher.", e)
    }
    return cipher
}

/** Encodes raw AES key bytes as a signed key string (the form [encrypt] / [decrypt] expect). */
fun ByteArray.encodeKey(): String = keySig + this.base64Encode()

/** Creates a fresh, random AES encryption key in the signed key-string form. */
fun mkEncryptionKey(): String = RandomUtil.secureBytes(aesKeyBytes).encodeKey()

private fun String.keyBytes(): ByteArray {
    if (!startsWith(keySig)) {
        throw KdrException("Encryption key is not usable for doing encryption.")
    }
    val decoded = substring(keySig.length).base64Decode()
    return ByteArray(aesKeyBytes).also { decoded.copyInto(it, endIndex = minOf(decoded.size, aesKeyBytes)) }
}

/** Encrypts [this] plaintext with [key] (AES-GCM); the result is the [encryptionSig] tag plus base64(iv‖ciphertext). */
fun String.encrypt(key: String): String {
    val iv = RandomUtil.secureBytes(gcmIvBytes)
    val cipher = getCipher(Cipher.ENCRYPT_MODE, SecretKeySpec(key.keyBytes(), "AES"), GCMParameterSpec(gcmTagBits, iv))
    try {
        val encoded = cipher.doFinal(this.toByteArray(Charsets.UTF_8))
        val buf = ByteBuffer.allocate(iv.size + encoded.size)
        buf.put(iv)
        buf.put(encoded)
        return encryptionSig + buf.array().base64Encode()
    } catch (e: GeneralSecurityException) {
        throw KdrException.mkConv("Could not encrypt the text.", e)
    }
}

/** Decrypts a string produced by [encrypt] using the same [key]. A wrong key or tampered text fails the GCM tag. */
fun String.decrypt(key: String): String {
    if (!startsWith(encryptionSig)) {
        throw KdrException.mkConv("Encrypted text does not start with the proper signature.")
    }
    val keyBytes = key.keyBytes()
    val buf = ByteBuffer.wrap(substring(encryptionSig.length).base64Decode())
    val iv = ByteArray(gcmIvBytes)
    buf.get(iv)
    val cipherText = ByteArray(buf.remaining())
    buf.get(cipherText)
    val cipher = getCipher(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(gcmTagBits, iv))
    try {
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        throw KdrException.mkConv("Could not decrypt the text.", e)
    }
}
