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
        val c = config(ENV.local).apply { put(RequestHandler.obfuscateErrorsEnvVar.name, "true") }
        RequestHandler.obfuscateSensitiveErrors(c) shouldBe true

        c.put(ACFG.obfuscateSensitiveErrors, false)
        RequestHandler.obfuscateSensitiveErrors(c) shouldBe false
    }

    /**
     * The variable is read through `getEnvBool`, so it accepts the same spellings every other boolean variable
     * does. It used to parse strictly: `yes` was not `true`, it was *unreadable*, and an unreadable value falls
     * through to the default -- so setting `KDR_OBFUSCATE_ERRORS=yes` in prod looked like it turned something on
     * and did exactly nothing.
     */
    "the env var accepts the loose spellings, not only 'true' and 'false'" {
        fun withEnv(value: String) =
            RequestHandler.obfuscateSensitiveErrors(
                config(ENV.local).apply { put(RequestHandler.obfuscateErrorsEnvVar.name, value) },
            )

        for (yes in listOf("true", "TRUE", "yes", "y", "t", "1")) withEnv(yes) shouldBe true
        for (no in listOf("false", "no", "n", "f", "0")) withEnv(no) shouldBe false
    }

    /**
     * An unrecognized value is *not* read as false. It means "nobody can tell", so it falls through to the
     * environment default -- which for prod is on. A typo must never be the thing that quietly stops a
     * deployment obfuscating.
     */
    "an unreadable value falls through to the default rather than reading as false" {
        val local = config(ENV.local).apply { put(RequestHandler.obfuscateErrorsEnvVar.name, "maybe") }
        RequestHandler.obfuscateSensitiveErrors(local) shouldBe false // local's default, not the value

        val prod = config(ENV.prod).apply { put(RequestHandler.obfuscateErrorsEnvVar.name, "maybe") }
        RequestHandler.obfuscateSensitiveErrors(prod) shouldBe true // prod stays on
    }
})
