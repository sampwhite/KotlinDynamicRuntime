package com.dynamicruntime.config

import com.dynamicruntime.common.config.KdrConfigData
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.DbEnv

@Suppress("MoveLambdaOutsideParentheses")
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
}