package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for the env-auth header rules (issue #348): whether a node believes `X-Kdr-Env-Email` at all, and
 * what it will repeat from one.
 *
 * Pure config resolution and pure string handling, so these build a config by hand rather than booting -- the
 * pattern `ErrorObfuscationConfigTest` uses for the same shape of question.
 */
class EnvAuthRulesTest : StringSpec({

    // A hand-built config: `local` env, nothing in memory, so `isTestInstance` infers false and the default is
    // genuinely off. `unit` flips that inference on, which is what the first test contrasts.
    fun config(env: String = ENV.local) = KdrInstanceConfig("envAuthTest", env, ENV.liveSource)

    "the default follows isTestInstance -- off on a real-shaped node, on where the app is developed" {
        EnvAuthRules.isTrusted(config(ENV.local)) shouldBe false
        EnvAuthRules.isTrusted(config(ENV.unit)) shouldBe true
    }

    // The half that matters. A node that is not behind an edge must ignore the header, or anyone who can reach
    // its port can assert an identity to it.
    "the config option turns trust off even where the fence would infer it on" {
        val c = config(ENV.unit).apply { put(ACFG.trustEnvAuthHeader, false) }
        c.isTestInstance shouldBe true // guard the premise, or the next line proves nothing
        EnvAuthRules.isTrusted(c) shouldBe false
    }

    "the config option turns trust on where it would otherwise be off" {
        EnvAuthRules.isTrusted(config(ENV.local).apply { put(ACFG.trustEnvAuthHeader, true) }) shouldBe true
    }

    "the env var is the default when the config option is unset, and the option still wins over it" {
        // A config entry under the env-var key is read by getEnvBool as if it were the process env var.
        val c = config(ENV.local).apply { put(ENVA.trustEnvAuthHeaderEnvVar, "true") }
        EnvAuthRules.isTrusted(c) shouldBe true

        c.put(ACFG.trustEnvAuthHeader, false)
        EnvAuthRules.isTrusted(c) shouldBe false
    }

    "an untrusted node resolves no address, however well-formed the header" {
        val trusted = config(ENV.local).apply { put(ACFG.trustEnvAuthHeader, true) }
        val untrusted = config(ENV.local).apply { put(ACFG.trustEnvAuthHeader, false) }
        EnvAuthRules.resolveEnvEmail(trusted, "sam@gyassa.com") shouldBe "sam@gyassa.com"
        EnvAuthRules.resolveEnvEmail(untrusted, "sam@gyassa.com") shouldBe null
        EnvAuthRules.resolveEnvEmail(trusted, null) shouldBe null
    }

    "an address is normalized the way it will be logged and compared" {
        EnvAuthRules.sanitizeAddress("  Sam@Gyassa.COM ") shouldBe "sam@gyassa.com"
        EnvAuthRules.sanitizeAddress("sam+edge@mail.gyassa.com") shouldBe "sam+edge@mail.gyassa.com"
    }

    /**
     * The reason this sanitizing exists. On a node that trusts the header the value is attacker-supplied and
     * lands in log lines, so a carriage return in it forges log entries and an unbounded one floods them --
     * the same treatment `RequestHandler`'s appId/traceId get, and for the same reason.
     */
    "a value that must not reach a log line is dropped rather than repeated" {
        EnvAuthRules.sanitizeAddress("sam@gyassa.com\r\nINFO fabricated log line") shouldBe null
        EnvAuthRules.sanitizeAddress("sam@\tgyassa.com") shouldBe null
        EnvAuthRules.sanitizeAddress("a".repeat(ENVA.maxAddressLength) + "@gyassa.com") shouldBe null
        EnvAuthRules.sanitizeAddress("sam <sam@gyassa.com>") shouldBe null

        // Surrounding whitespace is a different case and is *trimmed*, not refused: what remains is a clean
        // address with nothing left to inject. Only a break inside the value can forge a second log line, and
        // rejecting a stray trailing newline would fail addresses over how a header happened to be written.
        EnvAuthRules.sanitizeAddress("sam@gyassa.com\n") shouldBe "sam@gyassa.com"
    }

    "a value that is not an address at all is no assertion" {
        EnvAuthRules.sanitizeAddress("") shouldBe null
        EnvAuthRules.sanitizeAddress("   ") shouldBe null
        EnvAuthRules.sanitizeAddress("sam") shouldBe null
        EnvAuthRules.sanitizeAddress("@gyassa.com") shouldBe null
        EnvAuthRules.sanitizeAddress("sam@") shouldBe null
        EnvAuthRules.sanitizeAddress("sam@gyassa@com") shouldBe null
    }

    /**
     * Deliberately NOT the edge's job done twice. Whether an address may enter is settled at the edge against
     * the admin email domain; a second copy of that rule here would be free to drift from it. This node
     * repeats what it is told or ignores the header -- it never second-guesses the decision.
     */
    "an address outside any admin domain still resolves -- the domain is the edge's call, not this node's" {
        val c = config(ENV.local).apply {
            put(ACFG.trustEnvAuthHeader, true)
            put(ACFG.adminEmailDomain, "gyassa.com")
        }
        EnvAuthRules.resolveEnvEmail(c, "someone@example.org") shouldBe "someone@example.org"
    }
})
