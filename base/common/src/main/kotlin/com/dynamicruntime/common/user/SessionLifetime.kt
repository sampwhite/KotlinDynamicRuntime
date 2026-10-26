package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientService

/**
 * How long a sign-in lasts (issue #1308): the deployment's default ([sessionHoursEnvVar]), for a client that sets no
 * lifetime of its own; the most a client may set ([sessionMaxHoursEnvVar]); and a client's own
 * (`ClientDef.sessionHours`), shorter or longer than the default but never past the most.
 *
 * The session cookie carries when it was issued ([UserAuthCookie.issuedEpochMs]), and a session is honored only while
 * that is within [hoursFor] its client now -- so a client that shortens its lifetime ends longer sessions at once,
 * rather than when the cookies already issued expire. A client's value past the most is refused when written (the
 * client check), and capped here should the most later drop below it.
 *
 * A session a person keeps using is renewed (issue #1372): a request of theirs more than halfway through the
 * lifetime ([renewalDue]) is answered with a fresh cookie, good for the whole lifetime again. The cookie also keeps
 * the original sign-in ([UserAuthCookie.signedInEpochMs]), through every renewal and every switch between the
 * person's users, and no session outlasts [maxHours] from it -- a cookie that keeps being used, a stolen one
 * included, still ends.
 */
object SessionLifetime {
    /** The deployment's default when [sessionHoursEnvVar] is unset: two days. */
    const val defaultHours = 2 * 24

    /** The most a client may set when [sessionMaxHoursEnvVar] is unset: 30 days. */
    const val defaultMaxHours = 30 * 24

    /** The deployment's default session lifetime, in hours. */
    val sessionHoursEnvVar = EnvVarDef(
        "KDR_SESSION_HOURS", group = ENVGRP.application, defaultDoc = "$defaultHours (two days)",
        description = "How long a sign-in lasts, in hours, for a client that sets no lifetime of its own. Held to " +
            "KDR_SESSION_MAX_HOURS. A value that is not a whole number of at least ${CLD.minSessionHours} is ignored.",
    )

    /** The most a client's session lifetime may be, in hours. */
    val sessionMaxHoursEnvVar = EnvVarDef(
        "KDR_SESSION_MAX_HOURS", group = ENVGRP.application, defaultDoc = "$defaultMaxHours (30 days)",
        description = "The longest a client's administrator may make its people's sign-ins last, in hours; it also " +
            "caps KDR_SESSION_HOURS. A value that is not a whole number of at least ${CLD.minSessionHours} is ignored.",
    )

    /** The deployment's two values, read once per instance: every authenticated request asks for them. */
    private class Deployment(val defaultHours: Int, val maxHours: Int)

    /** Where [deployment] keeps them in the instance config. */
    private const val configKey = "sessionLifetimeDeployment"

    private fun hoursOf(cxt: KdrCxt, def: EnvVarDef): Int? =
        cxt.getEnvVar(def)?.trim()?.toIntOrNull()?.takeIf { it >= CLD.minSessionHours }

    private fun deployment(cxt: KdrCxt): Deployment {
        (cxt.instanceConfig.get(configKey) as? Deployment)?.let { return it }
        val most = hoursOf(cxt, sessionMaxHoursEnvVar) ?: defaultMaxHours
        return Deployment(minOf(hoursOf(cxt, sessionHoursEnvVar) ?: defaultHours, most), most)
            .also { cxt.instanceConfig.put(configKey, it) }
    }

    /** The most a client's lifetime may be, in hours. */
    fun maxHours(cxt: KdrCxt): Int = deployment(cxt).maxHours

    /** The deployment's default lifetime, in hours, held to [maxHours]. */
    fun deploymentHours(cxt: KdrCxt): Int = deployment(cxt).defaultHours

    /** How long a sign-in to [client] lasts, in hours: its own lifetime, else the deployment's, held to [maxHours]. */
    fun hoursFor(cxt: KdrCxt, client: String): Int {
        val own = ClientService.getOrNull(cxt)?.known(client)?.sessionHours ?: return deploymentHours(cxt)
        return minOf(own, maxHours(cxt))
    }

    /** [hoursFor], in milliseconds. */
    fun millisFor(cxt: KdrCxt, client: String): Long = hoursFor(cxt, client) * 3_600_000L

    /** [maxHours], in milliseconds. */
    fun maxMillis(cxt: KdrCxt): Long = maxHours(cxt) * 3_600_000L

    /**
     * When [cookie]'s session ends: its client's lifetime now from when the cookie was issued, held to the
     * deployment's limit from the original sign-in (issue #1372), so renewing never makes a session endless.
     */
    fun endOf(cxt: KdrCxt, cookie: UserAuthCookie): Long =
        minOf(cookie.issuedEpochMs + millisFor(cxt, cookie.client), cookie.signedInEpochMs + maxMillis(cxt))

    /** Whether [cookie]'s session is honored at [nowMs] ([endOf]). */
    fun honors(cxt: KdrCxt, cookie: UserAuthCookie, nowMs: Long): Boolean = nowMs <= endOf(cxt, cookie)

    /**
     * When a cookie for [client] issued at [nowMs] ends (issue #1372): the client's whole lifetime from then, held to
     * the deployment's limit from [signedInMs], the session's original sign-in -- now, for a sign-in.
     */
    fun issuedEnd(cxt: KdrCxt, client: String, signedInMs: Long, nowMs: Long): Long =
        minOf(nowMs + millisFor(cxt, client), signedInMs + maxMillis(cxt))

    /**
     * Whether [cookie]'s session is due a renewal at [nowMs] (issue #1372): more than half its lifetime has passed
     * since it was issued, and renewing would make it last longer. So a session is reissued at most once per half
     * lifetime, and not at all once the deployment's limit is what ends it.
     */
    fun renewalDue(cxt: KdrCxt, cookie: UserAuthCookie, nowMs: Long): Boolean =
        nowMs - cookie.issuedEpochMs > millisFor(cxt, cookie.client) / 2 &&
            issuedEnd(cxt, cookie.client, cookie.signedInEpochMs, nowMs) > endOf(cxt, cookie)
}
