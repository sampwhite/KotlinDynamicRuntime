package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.RequestHandler
import kotlin.random.Random

/**
 * The time side of the sign-in and registration flows (issue #1299). They answer an unknown account the same as a
 * known one (#275), and must not differ in **time** either: a lookup, a password hash, or a mail runs on some paths
 * and not others, and how long a response took would say which.
 *
 * - **A floor, with jitter.** An endpoint declaring `timingFloor` answers no sooner than [floorMs] plus a random
 *   0-[jitterMs] after its request began, less the time it already took: the floor removes the difference in the
 *   average, and the jitter hides what is left, which random noise alone would not, since noise averages out.
 * - **Tied to error obfuscation** ([isOn]): where a deployment obfuscates its sensitive errors (production, by default)
 *   it pads; elsewhere -- development and the test suites -- it costs nothing. A test turns it on with a tiny floor.
 * - **A concurrency cap.** Padding holds a request for its whole duration, and a password hash is CPU-bound, so a
 *   node works on at most [concurrency] of the `timingFloor` steps at once and answers 429 past it, beside the
 *   per-address and per-contact limits ([AuthRateLimiter], also per node). Only those steps: the auth section's cheap
 *   reads -- its UI config, a form token -- are never turned away by a burst of sign-ins.
 */
@Suppress("ConstPropertyName")
object AuthTiming {
    /** The floor, in milliseconds, when nothing configures one. */
    const val defaultFloorMs = 400L

    /** The most jitter added to the floor, in milliseconds, when nothing configures it. */
    const val defaultJitterMs = 200L

    /** Instance-config key setting [floorMs] -- for a test, which wants a floor far shorter than a real one. */
    const val floorMsKey = "authTimingFloorMs"

    /** Instance-config key setting [jitterMs]. */
    const val jitterMsKey = "authTimingJitterMs"

    /** The concurrency cap when [concurrencyEnvVar] is unset. */
    const val defaultConcurrency = 32

    val concurrencyEnvVar = EnvVarDef(
        "KDR_AUTH_CONCURRENCY", group = ENVGRP.application, defaultDoc = "$defaultConcurrency",
        description = "How many sign-in steps -- the logins, registration, the code mails, the claim and the " +
            "invitation -- this node works on at once (issue #1299); one past it is answered 429, to be retried. The " +
            "steps are held for the auth timing floor where errors are obfuscated, and a password hash is CPU-bound, so " +
            "this bounds what a burst of them can cost. The auth section's cheap reads are not counted. Per node, so a " +
            "cluster multiplies it. A positive whole number.",
    )

    /** Whether the deployment pads its auth calls: where it obfuscates sensitive errors. */
    fun isOn(config: KdrInstanceConfig): Boolean = RequestHandler.obfuscateSensitiveErrors(config)

    fun floorMs(config: KdrInstanceConfig): Long = (config.get(floorMsKey) as? Number)?.toLong() ?: defaultFloorMs

    fun jitterMs(config: KdrInstanceConfig): Long = (config.get(jitterMsKey) as? Number)?.toLong() ?: defaultJitterMs

    /** The concurrency cap [config] sets; refuses the start on a value that is not a positive whole number. */
    fun concurrency(config: KdrInstanceConfig): Int {
        val value = config.getEnvVar(concurrencyEnvVar)?.trim()?.ifEmpty { null } ?: return defaultConcurrency
        return value.toIntOrNull()?.takeIf { it > 0 }
            ?: throw KdrException("Refusing to start: $concurrencyEnvVar is '$value', not a positive whole number.")
    }

    /**
     * How long to wait still, in milliseconds, for a request that has taken [spentMs]: the floor plus a jitter drawn
     * from 0 to [jitterMs], less what was spent, and never less than nothing.
     */
    fun remainingMs(spentMs: Double, floorMs: Long, jitterMs: Long, random: Random = Random.Default): Long {
        val jitter = if (jitterMs > 0) random.nextLong(jitterMs + 1) else 0L
        return (floorMs + jitter - spentMs.toLong()).coerceAtLeast(0L)
    }
}
