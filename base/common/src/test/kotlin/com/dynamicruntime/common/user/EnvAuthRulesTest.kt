package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
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

    /**
     * Every case below describes a request that arrived **through a proxy** unless it says otherwise, so the
     * local "assume env auth" convenience -- which fires only on a direct request -- stays out of the way of
     * cases that are about something else.
     */
    fun resolve(
        c: KdrInstanceConfig,
        header: String? = null,
        cookies: Map<String, String> = emptyMap(),
        forwardedFor: String? = "10.0.0.1",
    ): EnvAuthState = EnvAuthRules.resolve(c, header, cookies, forwardedFor)

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
        resolve(trusted, "sam@gyassa.com", emptyMap()).email shouldBe "sam@gyassa.com"
        resolve(untrusted, "sam@gyassa.com", emptyMap()).email shouldBe null
        resolve(trusted, null, emptyMap()).email shouldBe null
    }

    "available and effective are separate answers, and suppression moves only one of them" {
        val c = config(ENV.local).apply { put(ACFG.trustEnvAuthHeader, true) }

        val plain = resolve(c, "sam@gyassa.com", emptyMap())
        plain.isAvailable shouldBe true
        plain.isEffective shouldBe true

        // The point of two flags: suppressed, the UI must still know env auth is there, or the control that
        // restores it disappears along with the thing it controls.
        val off = resolve(c, "sam@gyassa.com", mapOf(ENVA.suppressCookie to "1"))
        off.isAvailable shouldBe true
        off.isEffective shouldBe false
        off.email shouldBe "sam@gyassa.com" // the truth survives, because the log line needs it

        val nothing = resolve(c, null, emptyMap())
        nothing.isAvailable shouldBe false
        nothing.isEffective shouldBe false
    }

    "suppression applies even where the node refuses the assertion, because subtracting is always safe" {
        val untrusted = config(ENV.local).apply { put(ACFG.trustEnvAuthHeader, false) }
        val state = resolve(untrusted, "sam@gyassa.com", mapOf(ENVA.suppressCookie to "1"))
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
        resolve(testInstance, null, cookies).email shouldBe "fixture@gyassa.com"

        // A node shaped like a real one: trusting the header, but not a test instance.
        val real = config(ENV.local).apply {
            put(ACFG.trustEnvAuthHeader, true)
            put(ACFG.isTestInstance, false)
        }
        real.isTestInstance shouldBe false // guard the premise, or the next line proves nothing
        resolve(real, null, cookies).email shouldBe null
    }

    "a real header wins over a fixture assertion, so a live edge is never shadowed by a stale cookie" {
        val c = config(ENV.unit)
        val cookies = mapOf(ENVA.assertCookie to "fixture@gyassa.com")
        resolve(c, "real@gyassa.com", cookies).email shouldBe "real@gyassa.com"
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
        resolve(c, "someone@example.org", emptyMap()).email shouldBe "someone@example.org"
    }

    /**
     * The local-developer convenience (issue #360): a box with no edge in front behaves like one that has, so
     * the env-authed surface is there from the first page load.
     *
     * Note what makes this safe is NOT the missing forwarded-for -- that is the signature of a request which
     * bypassed the proxy, and rewarding it is the last thing a real node should do. The fence is the test
     * instance, which cannot boot outside local/unit. The forwarded-for check separates "through the edge"
     * from "straight at the server" on a machine where a developer does both.
     */
    "a local test instance assumes env auth for a direct request, and never for a proxied one" {
        val local = config(ENV.local).apply { put(ACFG.isTestInstance, true) }
        local.isTestInstance shouldBe true // guard the premise

        resolve(local, forwardedFor = null).email shouldBe ENVA.assumedAddress
        resolve(local, forwardedFor = "10.0.0.1").email shouldBe null
    }

    /**
     * The half that would otherwise be discovered as a wall of red: `TestHttpClient` sends no forwarded-for
     * header, so defaulting this on the test-instance flag alone would make **every request in the suite**
     * env-authed and silently move the baseline every other test reasons from.
     */
    "the unit environment does not assume, even though it is a test instance" {
        val unit = config(ENV.unit)
        unit.isTestInstance shouldBe true // guard the premise: it is a test instance, and still does not assume
        EnvAuthRules.assumesEnvAuth(unit) shouldBe false
        resolve(unit, forwardedFor = null).email shouldBe null
    }

    /**
     * The assumption means "behave as if an edge vouched for you", which is incoherent on the node that *is*
     * the edge -- it leaves the perimeter never challenging anybody. Found by running an edge locally and
     * being unable to reach its own sign-in page.
     */
    "an edge does not assume, because there is no edge in front of it" {
        val edge = KdrInstanceConfig("edgeAssume", ENV.local, ENV.liveSource, BOOT.edge)
            .apply { put(ACFG.isTestInstance, true) }
        EnvAuthRules.assumesEnvAuth(edge) shouldBe false

        // ...but it can still be asked for, which is what KDR_EDGE_ASSUME_ENV_AUTH does under the role prefix.
        edge.put(ACFG.assumeEnvAuth, true)
        EnvAuthRules.assumesEnvAuth(edge) shouldBe true
    }

    "the assumption is configurable in both directions, and independent of header trust" {
        val off = config(ENV.local).apply {
            put(ACFG.isTestInstance, true)
            put(ACFG.assumeEnvAuth, false)
        }
        resolve(off, forwardedFor = null).email shouldBe null

        // Independent of trust on purpose: turning off header trust in a test must not silently disable a
        // developer's local convenience, because the two answer different questions.
        val untrusting = config(ENV.local).apply {
            put(ACFG.isTestInstance, true)
            put(ACFG.trustEnvAuthHeader, false)
        }
        resolve(untrusting, forwardedFor = null).email shouldBe ENVA.assumedAddress

        val viaEnvVar = config(ENV.unit).apply { put(ENVA.assumeEnvAuthEnvVar, "true") }
        resolve(viaEnvVar, forwardedFor = null).email shouldBe ENVA.assumedAddress
    }

    "a real header outranks an assumption, so testing through the edge is never masked by it" {
        val local = config(ENV.local).apply { put(ACFG.isTestInstance, true) }
        // Direct *and* carrying a header is not a combination a browser produces, but the ordering is what
        // matters: whatever an edge actually said wins over anything invented.
        resolve(local, header = "real@gyassa.com", forwardedFor = null).email shouldBe "real@gyassa.com"
    }

    "an assumed env auth can still be suppressed -- which is why a local developer needs the toggle" {
        val local = config(ENV.local).apply { put(ACFG.isTestInstance, true) }
        val state = resolve(local, cookies = mapOf(ENVA.suppressCookie to "1"), forwardedFor = null)
        state.isAvailable shouldBe true
        state.isEffective shouldBe false
    }
})
