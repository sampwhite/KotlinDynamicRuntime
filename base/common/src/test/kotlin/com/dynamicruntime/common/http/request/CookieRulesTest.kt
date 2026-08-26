package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * How `Secure` is decided (issue #431): from the deployment's own configuration, never from a request header.
 * The config option wins, then the env var, then the environment -- secure everywhere but local/unit, which
 * keeps plain-HTTP localhost working. Same shape as `EnvAuthRulesTest`, and for the same reason: pure config
 * resolution, built by hand rather than booted.
 */
class CookieRulesTest : StringSpec({

    fun config(env: String) = KdrInstanceConfig("cookie-$env", env, ENV.liveSource)

    "secure by default everywhere but local and unit, so plain-HTTP localhost keeps working" {
        CookieRules.isSecure(config(ENV.prod)) shouldBe true
        CookieRules.isSecure(config(ENV.local)) shouldBe false
        CookieRules.isSecure(config(ENV.unit)) shouldBe false
    }

    "the config option decides it outright, either way" {
        CookieRules.isSecure(config(ENV.prod).apply { put(ACFG.cookieSecure, false) }) shouldBe false
        CookieRules.isSecure(config(ENV.local).apply { put(ACFG.cookieSecure, true) }) shouldBe true
    }

    "the env var is the default when the option is unset, and the option still wins over it" {
        // A config entry under the env-var key is read by getEnvBool as if it were the process env var.
        val c = config(ENV.local).apply { put(CKI.cookieSecureEnvVar.name, "true") }
        CookieRules.isSecure(c) shouldBe true

        c.put(ACFG.cookieSecure, false)
        CookieRules.isSecure(c) shouldBe false
    }
})
