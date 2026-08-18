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
        EnvAuthRules.resolve(trusted, "sam@gyassa.com", emptyMap()).email shouldBe "sam@gyassa.com"
        EnvAuthRules.resolve(untrusted, "sam@gyassa.com", emptyMap()).email shouldBe null
        EnvAuthRules.resolve(trusted, null, emptyMap()).email shouldBe null
    }

    "available and effective are separate answers, and suppression moves only one of them" {
        val c = config(ENV.local).apply { put(ACFG.trustEnvAuthHeader, true) }

        val plain = EnvAuthRules.resolve(c, "sam@gyassa.com", emptyMap())
        plain.isAvailable shouldBe true
        plain.isEffective shouldBe true

        // The point of two flags: suppressed, the UI must still know env auth is there, or the control that
        // restores it disappears along with the thing it controls.
        val off = EnvAuthRules.resolve(c, "sam@gyassa.com", mapOf(ENVA.suppressCookie to "1"))
        off.isAvailable shouldBe true
        off.isEffective shouldBe false
        off.email shouldBe "sam@gyassa.com" // the truth survives, because the log line needs it

        val nothing = EnvAuthRules.resolve(c, null, emptyMap())
        nothing.isAvailable shouldBe false
        nothing.isEffective shouldBe false
    }

    "suppression applies even where the node refuses the assertion, because subtracting is always safe" {
        val untrusted = config(ENV.local).apply { put(ACFG.trustEnvAuthHeader, false) }
        val state = EnvAuthRules.resolve(untrusted, "sam@gyassa.com", mapOf(ENVA.suppressCookie to "1"))
        state.isAvailable shouldBe false
        state.suppressed shouldBe true
    }

    /**
     * The half a `forTestingOnly` marking does not buy. Fencing the fixture endpoint stops the assert cookie
     * being *issued*; nothing stops one being typed into a browser. A reader that honored it anywhere would
     * hand env auth to anyone who can set a cookie -- so the fence has to live here, in the reader.
     */
    "the fixture's assert cookie is honored on a test instance and refused anywhere else" {
        val cookies = mapOf(ENVA.assertCookie to "fixture@gyassa.com")

        val testInstance = config(ENV.unit)
        testInstance.isTestInstance shouldBe true // guard the premise
        EnvAuthRules.resolve(testInstance, null, cookies).email shouldBe "fixture@gyassa.com"

        // A node shaped like a real one: trusting the header, but not a test instance.
        val real = config(ENV.local).apply {
            put(ACFG.trustEnvAuthHeader, true)
            put(ACFG.isTestInstance, false)
        }
        real.isTestInstance shouldBe false // guard the premise, or the next line proves nothing
        EnvAuthRules.resolve(real, null, cookies).email shouldBe null
    }

    "a real header wins over a fixture assertion, so a live edge is never shadowed by a stale cookie" {
        val c = config(ENV.unit)
        val cookies = mapOf(ENVA.assertCookie to "fixture@gyassa.com")
        EnvAuthRules.resolve(c, "real@gyassa.com", cookies).email shouldBe "real@gyassa.com"
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
        EnvAuthRules.resolve(c, "someone@example.org", emptyMap()).email shouldBe "someone@example.org"
    }
})
