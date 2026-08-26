package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlin.time.Duration.Companion.milliseconds

/**
 * The JWKS cache bounds (issue #430): a hit is served from memory; a miss fetches at most once per
 * [GOOG.jwksMinRefetchMs], and within that interval an unknown `kid` is answered from memory (a negative
 * cache). The real caching path is driven with an **injected** fetch -- a test instance refuses a real
 * outbound call -- and a **travelled clock**, so the throttle is exercised with no network and no wall-clock
 * wait. Signature verification is `GoogleIdTokenVerifierTest`'s job; this file is only the cache.
 */
class GoogleJwksKeySourceTest : StringSpec({

    fun rsaKey(): RSAPublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey

    val keyA = rsaKey()
    val keyB = rsaKey()

    /** A fresh context per test, each with its own travellable clock. */
    fun cxt() = KdrCxt("jwks", KdrInstanceConfig("jwks", ENV.unit, ENV.liveSource))

    val pastInterval = (GOOG.jwksMinRefetchMs + 1).milliseconds

    "a known kid is served from cache, and a second call does not refetch" {
        val c = cxt()
        var fetches = 0
        val source = GoogleJwksKeySource("x", fetch = { fetches++; mapOf("kid1" to keyA) })
        source.rsaKey(c, "kid1") shouldBe keyA
        source.rsaKey(c, "kid1") shouldBe keyA
        fetches shouldBe 1
    }

    "an unknown kid within the interval is answered from memory without refetching (negative cache)" {
        val c = cxt()
        var fetches = 0
        val source = GoogleJwksKeySource("x", fetch = { fetches++; mapOf("kid1" to keyA) })
        source.rsaKey(c, "kid1") shouldBe keyA // the one fetch that populates the set
        source.rsaKey(c, "nope") shouldBe null
        source.rsaKey(c, "also-nope") shouldBe null
        fetches shouldBe 1
    }

    "after the interval, an unknown kid triggers exactly one more fetch, then is throttled again" {
        val c = cxt()
        var fetches = 0
        val source = GoogleJwksKeySource("x", fetch = { fetches++; mapOf("kid1" to keyA) })
        source.rsaKey(c, "kid1")
        c.instanceConfig.clock.advanceBy(pastInterval)
        source.rsaKey(c, "nope") shouldBe null // interval elapsed -> refetch, but still absent
        source.rsaKey(c, "nope") shouldBe null // back within the interval -> no fetch
        fetches shouldBe 2
    }

    "a rotation is picked up: a new signing kid resolves once the interval elapses" {
        val c = cxt()
        var served = mapOf("kid1" to keyA)
        val source = GoogleJwksKeySource("x", fetch = { served })
        source.rsaKey(c, "kid1") shouldBe keyA
        // Google rotates: kid1 retired, kid2 now signing.
        served = mapOf("kid2" to keyB)
        source.rsaKey(c, "kid2") shouldBe null // within interval -> negative cache, not yet visible
        c.instanceConfig.clock.advanceBy(pastInterval)
        source.rsaKey(c, "kid2") shouldBe keyB // interval elapsed -> refetch picks up the new key
    }

    // The amplification bound must hold during an outage too: a failing fetch advances the throttle exactly
    // like a succeeding one, so a Google outage cannot turn every miss into a retry.
    "a failed fetch is throttled the same as a successful one" {
        val c = cxt()
        var fetches = 0
        var down = true
        val source = GoogleJwksKeySource("x", fetch = {
            fetches++
            if (down) throw KdrException("JWKS endpoint down") else mapOf("kid1" to keyA)
        })
        shouldThrow<KdrException> { source.rsaKey(c, "kid1") } // fetch attempt throws
        source.rsaKey(c, "kid1") shouldBe null // within the interval of the FAILED fetch -> no retry
        fetches shouldBe 1
        c.instanceConfig.clock.advanceBy(pastInterval)
        down = false
        source.rsaKey(c, "kid1") shouldBe keyA // interval elapsed -> retry succeeds
        fetches shouldBe 2
    }
})
