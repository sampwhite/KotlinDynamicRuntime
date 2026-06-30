package com.dynamicruntime.config

import com.dynamicruntime.common.config.KdrConfigData
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt

class AppConfigBuilder(cxt: KdrCxt, data: MutableMap<String,Any?>) : KdrConfigData(cxt, data) {
    init {
        // Default the env: keep an existing value if present, otherwise take the
        // KDR_ENV environment variable, otherwise fall back to local. getOrPut
        // reads the env var only when the key is absent, so an existing value wins.
        data.getOrPut(ACFG.env) { cxt.getEnvVar("KDR_ENV") ?: ENV.local }
        data.getOrPut(ACFG.inMemoryOnly, { true })
    }

    // See ENV constants in CxtConstants.
    var env: String by data
    var inMemoryOnly: Boolean by data
}