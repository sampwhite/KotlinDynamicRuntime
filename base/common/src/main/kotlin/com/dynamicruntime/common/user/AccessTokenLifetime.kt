package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt

/**
 * How long an access token lasts (issue #1403): [hoursEnvVar] hours, and never past its key's expiry. Revoking a key
 * stops new tokens and ends its tokens on this deployment's own nodes at once, but a service verifying a token with the
 * published keys alone honors it until it expires -- which is why common practice keeps tokens to an hour or less, and
 * why this is a deployment's choice.
 */
object AccessTokenLifetime {
    val hoursEnvVar = EnvVarDef(
        "KDR_ACCESS_TOKEN_HOURS", group = ENVGRP.application, defaultDoc = "${ATOK.defaultHours} (one day)",
        description = "How long an access token exchanged for an API key lasts, in hours, and never past the key's " +
            "own expiry. A service that verifies tokens with the published keys alone honors one until it expires, " +
            "even after its key is revoked, so shorter is safer. A value that is not a whole number from 1 to " +
            "${ATOK.maxHours} is ignored.",
    )

    /** Where [hours] keeps the deployment's value in the instance config. */
    private const val configKey = "accessTokenHours"

    /** A token's life, in hours, read once per instance. */
    fun hours(cxt: KdrCxt): Int {
        (cxt.instanceConfig.get(configKey) as? Int)?.let { return it }
        val hours = cxt.getEnvVar(hoursEnvVar)?.trim()?.toIntOrNull()?.takeIf { it in 1..ATOK.maxHours } ?: ATOK.defaultHours
        cxt.instanceConfig.put(configKey, hours)
        return hours
    }
}
