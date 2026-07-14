package com.dynamicruntime.common.user

import java.util.concurrent.ConcurrentHashMap

/**
 * Rate-limit windows and thresholds for auth (issue #69). Values are deliberately generous -- the aim is to
 * blunt brute-force and email flooding, not to inconvenience a fumbling but legitimate user.
 */
@Suppress("ConstPropertyName")
object RL {
    /** Failed password-login attempts allowed per username within [pwWindowMs]. */
    const val pwPerUserMax = 5

    /** Failed password-login attempts allowed per source IP within [pwWindowMs] (across all usernames). */
    const val pwPerIpMax = 20

    /** The password-login failure window, in milliseconds (15 minutes). */
    const val pwWindowMs = 15L * 60 * 1000

    /** Failed verification-code attempts allowed per targeted contact within [verifyWindowMs]. */
    const val verifyMax = 6

    /** The verification-code failure window, in milliseconds (15 minutes). */
    const val verifyWindowMs = 15L * 60 * 1000

    /** Verification emails allowed per contact within [sendPerContactWindowMs]. */
    const val sendPerContactMax = 5

    /** The per-contact send window, in milliseconds (1 hour). */
    const val sendPerContactWindowMs = 60L * 60 * 1000

    /** Verification emails allowed per source IP within [sendPerIpWindowMs] (across all contacts). */
    const val sendPerIpMax = 20

    /** The per-IP send window, in milliseconds (15 minutes). */
    const val sendPerIpWindowMs = 15L * 60 * 1000
}

/**
 * A minimal in-memory, fixed-window rate limiter (issue #69): the first line of defense against password and
 * verification-code brute force and against verification-email flooding. It is independent of the future
 * single-use verify-code table -- the limiter throttles attempts *before* a code is validated, whereas that
 * table's consume/replay check runs only *after* a code validates.
 *
 * Per-node and non-durable by design: a restart forgets its counters, which is acceptable for a throttle and
 * avoids any storage coupling. Not distributed; a cluster-aware limiter can replace it behind this same shape.
 */
class AuthRateLimiter {
    private class Window(val startMs: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Records one attempt against [key] and reports whether it is still within [limit] over [windowMs]. The
     * window is fixed: the first attempt opens it, and it resets once [windowMs] has elapsed since then.
     * Returns false once the count within the current window exceeds [limit].
     */
    fun allow(key: String, limit: Int, windowMs: Long, nowMs: Long): Boolean {
        val w = windows.compute(key) { _, cur ->
            if (cur == null || nowMs - cur.startMs > windowMs) Window(nowMs, 1)
            else cur.also { it.count++ }
        }!!
        return w.count <= limit
    }

    /** Clears the counter for [key] (e.g., after a success) so earlier failures do not linger against it. */
    fun reset(key: String) {
        windows.remove(key)
    }
}
