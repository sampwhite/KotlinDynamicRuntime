package com.dynamicruntime.common.util

import java.security.SecureRandom
import java.util.Random

/**
 * Sources of randomness for the backend. Two kinds: a shared [SecureRandom] for anything security-bearing
 * (salts, keys, IVs), and a fast, per-thread non-secure [Random] (seeded once from the secure source) for
 * everything else (e.g. the random suffix of a unique id). Ported from dn's `RandomUtil`.
 *
 * Backend-only (uses `java.security`); not part of the KMP surface.
 */
object RandomUtil {
    // Constructing a SecureRandom can be slow, so it is created lazily rather than at class load. It is
    // thread-safe, so a single shared instance serves every caller.
    private val secureRandomHolder = lazy { SecureRandom() }
    val secureRandom: SecureRandom get() = secureRandomHolder.value

    // A fast PRNG per thread, seeded from the secure source. Not for security use.
    private val threadRandom = ThreadLocal.withInitial { Random(secureRandom.nextLong()) }
    fun random(): Random = threadRandom.get()

    /** [n] cryptographically strong random bytes -- for salts, keys, and IVs. */
    fun secureBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    /** [n] fast, non-secure random bytes -- for ids and other non-security randomness. */
    fun bytes(n: Int): ByteArray = ByteArray(n).also { random().nextBytes(it) }
}
