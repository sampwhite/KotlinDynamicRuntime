package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.http.request.VBH
import com.dynamicruntime.common.http.request.VEP
import com.dynamicruntime.common.http.request.VariantRule
import com.dynamicruntime.common.http.request.VariantScenario
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The request-variant test facility (issue #471). What is proven here, in order of importance:
 *
 *  - **Off is inert.** With no scenarios configured, the selecting cookie does nothing -- the whole safety
 *    property, since the facility must not exist on a node that did not ask for it.
 *  - A configured scenario, once selected by cookie, delays or fails matching requests.
 *  - The escape-hatch endpoint validates the name and round-trips the cookie.
 *  - A real environment refuses to boot with the facility enabled.
 *
 * Scenarios here target `/health` (a plain anonymous 200) so the assertions are about the *injection*, not
 * about any particular endpoint; the same rules would target `/md/` for a fragment fetch in a real session.
 */
class VariantBehaviorTest : StringSpec({

    val slowMs = 300
    fun scenarios() = listOf(
        VariantScenario("failHealth", listOf(VariantRule(pathContains = "/health", failStatus = 503, failMessage = "injected outage"))),
        VariantScenario("slowHealth", listOf(VariantRule(pathContains = "/health", delayMs = slowMs))),
        // A deliberately broad rule -- used to prove the escape hatch stays reachable under one.
        VariantScenario("failAll", listOf(VariantRule(pathContains = "/kda", failStatus = 503))),
    )

    val enabledCxt = Startup.mkTestBootCxt("variantEnabled", "variantEnabledTest",
        mapOf(ACFG.testVariantScenarios to scenarios()))
    val disabledCxt = Startup.mkTestBootCxt("variantDisabled", "variantDisabledTest")

    "the escape-hatch endpoint selects a configured scenario and reports the choices" {
        val client = TestHttpClient(enabledCxt.instanceConfig)
        val results = client.sendJsonPostRequest(VEP.set, mapOf(VBH.scenario to "failHealth"))[EP.results]!!.toJsonMap()
        results[VEP.active] shouldBe "failHealth"
        results[VEP.available].toJsonListOfStrings() shouldBe listOf("failHealth", "slowHealth", "failAll")
        // The cookie is now in the jar and rides on the next request unmodified, which is the point.
        client.cookies[VBH.cookieName] shouldBe "failHealth"
    }

    "a selected failure scenario fails the matching request" {
        val client = TestHttpClient(enabledCxt.instanceConfig)
        client.sendJsonPostRequest(VEP.set, mapOf(VBH.scenario to "failHealth"))
        client.sendGetRequest("/health").rptStatusCode shouldBe 503
    }

    "a selected delay scenario slows the matching request but still succeeds" {
        val client = TestHttpClient(enabledCxt.instanceConfig)
        client.sendJsonPostRequest(VEP.set, mapOf(VBH.scenario to "slowHealth"))
        val start = System.currentTimeMillis()
        val response = client.sendGetRequest("/health")
        val elapsed = System.currentTimeMillis() - start
        response.rptStatusCode shouldBe 200
        (elapsed >= slowMs - 60) shouldBe true // small slack for scheduling jitter
    }

    "clearing returns the session to ordinary behavior" {
        val client = TestHttpClient(enabledCxt.instanceConfig)
        client.sendJsonPostRequest(VEP.set, mapOf(VBH.scenario to "failHealth"))
        client.sendGetRequest("/health").rptStatusCode shouldBe 503
        client.sendEditRequest(VEP.clear, null, null, HttpMethod.POST)
        client.sendGetRequest("/health").rptStatusCode shouldBe 200
    }

    "the escape-hatch endpoint refuses an unknown scenario" {
        val client = TestHttpClient(enabledCxt.instanceConfig)
        client.sendEditRequest(VEP.set, null, mapOf(VBH.scenario to "nope"), HttpMethod.POST).rptStatusCode shouldBe 400
    }

    // A broad rule ('/kda') must never fail the clear call itself -- the cookie is HttpOnly with a day's life, so
    // if clear were subject to the scenario there would be no way back. `apply` exempts the /fixture/variant/ paths.
    "a broad scenario never bricks the escape hatch" {
        val client = TestHttpClient(enabledCxt.instanceConfig)
        client.sendJsonPostRequest(VEP.set, mapOf(VBH.scenario to "failAll"))
        client.sendGetRequest("/health").rptStatusCode shouldBe 503
        // The clear call is under /fixture/variant/, so the broad rule does not touch it -- it succeeds...
        client.sendEditRequest(VEP.clear, null, null, HttpMethod.POST).rptStatusCode shouldBe 200
        // ...and having cleared the cookie, ordinary requests are no longer failed.
        client.sendGetRequest("/health").rptStatusCode shouldBe 200
    }

    // The one that matters most: the cookie is a token the deployment must have opted into. Present the cookie
    // by hand on a node that configured nothing, and it does nothing at all -- no delay, no failure.
    "without configured scenarios the cookie is inert" {
        val client = TestHttpClient(disabledCxt.instanceConfig)
        client.cookies[VBH.cookieName] = "failHealth"
        val start = System.currentTimeMillis()
        val response = client.sendGetRequest("/health")
        val elapsed = System.currentTimeMillis() - start
        response.rptStatusCode shouldBe 200
        (elapsed < 200) shouldBe true
        // And the escape hatch does not even exist on a node that configured nothing (registration is gated).
        client.sendEditRequest(VEP.set, null, mapOf(VBH.scenario to "failHealth"), HttpMethod.POST).rptStatusCode shouldBe 404
    }

    "a malformed scenario refuses the boot" {
        // env=unit (forced by mkTestBootCxt), so it is validation -- not the real-environment refusal -- that fires.
        val failure = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "variantBad", "variantBadNameTest",
                mapOf(ACFG.testVariantScenarios to listOf(
                    VariantScenario("bad name", listOf(VariantRule(pathContains = "/health", failStatus = 500))),
                )),
            )
        }
        failure.fullMessage() shouldContain "Refusing to start"
        failure.fullMessage() shouldContain "cookie token"
    }

    "a real environment refuses to boot with scenarios configured" {
        val failure = shouldThrow<KdrException> {
            Startup.mkBootCxt(
                "variantProd", "variantProdRefusalTest",
                mapOf(
                    ACFG.env to ENV.prod,
                    // Not a test instance (so SchemaService's own test-instance refusal is not what fires), yet
                    // in-memory so no real database is needed -- the ordinary-local-run-against-real-DB shape.
                    ACFG.isTestInstance to false,
                    ACFG.inMemoryOnly to true,
                    ACFG.testVariantScenarios to listOf(
                        VariantScenario("x", listOf(VariantRule(pathContains = "/health", delayMs = 1))),
                    ),
                ),
            )
        }
        failure.fullMessage() shouldContain "Refusing to start"
        failure.fullMessage() shouldContain ACFG.testVariantScenarios
    }
})
