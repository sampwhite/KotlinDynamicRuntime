package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for the three-level resolution of whether a deployment obfuscates sensitive error messages
 * (issue #108): the config option wins, then the KDR_OBFUSCATE_ERRORS env var, then whether the env is prod.
 */
class ErrorObfuscationConfigTest : StringSpec({

    fun config(env: String) = KdrInstanceConfig("obfTest", env, ENV.liveSource)

    "off by default outside prod, on by default in prod" {
        RequestHandler.obfuscateSensitiveErrors(config(ENV.local)) shouldBe false
        RequestHandler.obfuscateSensitiveErrors(config(ENV.prod)) shouldBe true
    }

    "the config option wins over the prod default (a prod test can turn it off)" {
        val c = config(ENV.prod).apply { put(ACFG.obfuscateSensitiveErrors, false) }
        RequestHandler.obfuscateSensitiveErrors(c) shouldBe false
    }

    "the config option turns it on outside prod (how a test exercises obfuscation)" {
        val c = config(ENV.local).apply { put(ACFG.obfuscateSensitiveErrors, true) }
        RequestHandler.obfuscateSensitiveErrors(c) shouldBe true
    }

    "the env var is the default when the config option is unset, and the config option still wins over it" {
        // A config entry under the env-var key is read by getEnvVar as if it were the process env var.
        val c = config(ENV.local).apply { put(RequestHandler.obfuscateErrorsEnvVar, "true") }
        RequestHandler.obfuscateSensitiveErrors(c) shouldBe true

        c.put(ACFG.obfuscateSensitiveErrors, false)
        RequestHandler.obfuscateSensitiveErrors(c) shouldBe false
    }
})
