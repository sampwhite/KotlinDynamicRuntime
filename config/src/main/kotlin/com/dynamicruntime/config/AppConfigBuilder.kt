package com.dynamicruntime.config

import com.dynamicruntime.common.config.KdrConfigData
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.DbEnv

@Suppress("MoveLambdaOutsideParentheses", "unused")
class AppConfigBuilder(cxt: KdrCxt, data: MutableMap<String,Any?>) : KdrConfigData(cxt, data) {
    init {
        // Default the env: keep an existing value if present, otherwise take the
        // KDR_ENV environment variable, otherwise fall back to local. getOrPut
        // reads the env var only when the key is absent, so an existing value wins.
        data.getOrPut(ACFG.env) { cxt.getEnvVar("KDR_ENV") ?: ENV.local }
        // Default inMemoryOnly from KDR_IN_MEMORY_ONLY (else true); an explicit value already present wins.
        data.getOrPut(ACFG.inMemoryOnly) { DbEnv.resolveInMemoryOnly(cxt) }
        data.getOrPut(ACFG.validateResponseSchema, { false })
    }

    // See ENV constants in CxtConstants.
    var env: String by data
    var inMemoryOnly: Boolean by data

    /** When true, endpoint responses are validated against their output schema. Defaults false (on in tests). */
    var validateResponseSchema: Boolean by data

    /**
     * How often (ms) the visible frontend refreshes itself (issue #146). Left unset here, so the app UI-config
     * endpoint applies its own default; set it to exercise the behavior quickly (e.g., a few seconds) without
     * waiting out the one-minute default. This is the intended, "code-side" way to tune a UI value -- as opposed
     * to an env var, which is for ops/environment concerns.
     */
    var idleBumpIntervalMs: Int by data
}