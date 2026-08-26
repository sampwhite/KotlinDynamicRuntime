package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for the Env Auth session's length (issue #386) -- the value that matters most about it, because it
 * is the **only revocation an edge has**. No user store means no live role refresh, so whoever holds a valid
 * cookie keeps their access until it expires and nothing can take it back.
 */
class EnvAuthSessionTest : StringSpec({

    fun config(env: String) = KdrInstanceConfig("envAuthSession", env, ENV.liveSource)

    "production sessions are short, and everywhere else is longer" {
        ENVAUTH.sessionMillis(config(ENV.prod)) shouldBe ENVAUTH.prodSessionMillis
        ENVAUTH.sessionMillis(config(ENV.local)) shouldBe ENVAUTH.defaultSessionMillis
        ENVAUTH.sessionMillis(config(ENV.dev)) shouldBe ENVAUTH.defaultSessionMillis
    }

    /**
     * Twelve hours against the application's thirty days is not an inconsistency: that one is safe because
     * `refreshActingRoles` overrules the cookie from the user's row within seconds. This has no row to consult.
     */
    "twelve hours in production, two days elsewhere" {
        ENVAUTH.prodSessionMillis shouldBe 12L * 3600 * 1000
        ENVAUTH.defaultSessionMillis shouldBe 2L * 24 * 3600 * 1000
    }

    "the config option wins, and the env var is the default beneath it" {
        val c = config(ENV.prod).apply { put(ENVAUTH.sessionMillisKey, 5_000L) }
        ENVAUTH.sessionMillis(c) shouldBe 5_000L

        val viaEnv = config(ENV.prod).apply { put(ENVAUTH.sessionMillisEnvVar.name, "9000") }
        ENVAUTH.sessionMillis(viaEnv) shouldBe 9_000L
    }
})
