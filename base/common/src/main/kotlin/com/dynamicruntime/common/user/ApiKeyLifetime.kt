package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt

/**
 * How long an API key lasts (issue #1402): a new key's life ([daysEnvVar]) unless its maker asks for less, and the
 * furthest from today any key's expiry may be ([maxDaysEnvVar]) -- which an extension is held to as well, so no key
 * is made to last indefinitely in one step. The rules themselves are the kernel's ([ApiKeyRules]), which the page
 * previewing an extension runs too; this holds the deployment's two numbers.
 */
@Suppress("ConstPropertyName")
object ApiKeyLifetime {
    /** A new key's life, in days. */
    val daysEnvVar = EnvVarDef(
        "KDR_API_KEY_DAYS", group = ENVGRP.application, defaultDoc = "${AKEY.defaultDays} (one year)",
        description = "How long a new API key lasts, in days, when its maker asks for no other. Held to " +
            "KDR_API_KEY_MAX_DAYS. A value that is not a whole number of at least 1 is ignored.",
    )

    /** The furthest from today an API key's expiry may be, in days. */
    val maxDaysEnvVar = EnvVarDef(
        "KDR_API_KEY_MAX_DAYS", group = ENVGRP.application, defaultDoc = "${AKEY.defaultMaxDays} (two years)",
        description = "The furthest from today an API key's expiry may be, in days, when it is made or extended; " +
            "it also caps KDR_API_KEY_DAYS. A value that is not a whole number of at least 1 is ignored.",
    )

    /** The deployment's two values, read once per instance. */
    private class Deployment(val days: Int, val maxDays: Int)

    /** Where [deployment] keeps them in the instance config. */
    private const val configKey = "apiKeyLifetimeDeployment"

    private fun daysOf(cxt: KdrCxt, def: EnvVarDef): Int? = cxt.getEnvVar(def)?.trim()?.toIntOrNull()?.takeIf { it >= 1 }

    private fun deployment(cxt: KdrCxt): Deployment {
        (cxt.instanceConfig.get(configKey) as? Deployment)?.let { return it }
        val most = daysOf(cxt, maxDaysEnvVar) ?: AKEY.defaultMaxDays
        return Deployment(minOf(daysOf(cxt, daysEnvVar) ?: AKEY.defaultDays, most), most)
            .also { cxt.instanceConfig.put(configKey, it) }
    }

    /** A new key's life, in days, held to [maxDays]. */
    fun days(cxt: KdrCxt): Int = deployment(cxt).days

    /** The furthest from today a key's expiry may be, in days. */
    fun maxDays(cxt: KdrCxt): Int = deployment(cxt).maxDays
}
