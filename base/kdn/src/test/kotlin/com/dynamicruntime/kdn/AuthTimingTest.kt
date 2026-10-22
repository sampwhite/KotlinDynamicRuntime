package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AuthTiming
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The auth calls' timing floor and concurrency cap (issue #1299), on an instance of its own: one that obfuscates its
 * errors, which is what turns the floor on, with a short floor, no jitter, and room for one auth call at a time.
 */
class AuthTimingTest : StringSpec({

    "the wait is the floor plus jitter less the time already spent, and never less than nothing" {
        AuthTiming.remainingMs(100.0, 400, 0) shouldBe 300L
        AuthTiming.remainingMs(500.0, 400, 0) shouldBe 0L
        repeat(20) { AuthTiming.remainingMs(100.0, 400, 200, Random(it)) shouldBeInRange 300L..500L }
    }

    "the concurrency cap is a positive whole number, or the start is refused" {
        val cxt = KdrCxt.mkSimpleCxt("cap")
        AuthTiming.concurrency(cxt.instanceConfig) shouldBe AuthTiming.defaultConcurrency
        cxt.instanceConfig.put(AuthTiming.concurrencyEnvVar.name, "8")
        AuthTiming.concurrency(cxt.instanceConfig) shouldBe 8
        cxt.instanceConfig.put(AuthTiming.concurrencyEnvVar.name, "0")
        shouldThrow<KdrException> { AuthTiming.concurrency(cxt.instanceConfig) }
    }

    // Its own instance: the floor and the cap are node-wide, and turning them on would slow and refuse whatever else
    // ran there.
    val floorMs = 500L
    val cxt = Startup.mkTestBootCxt(
        "authTiming", "authTimingTest",
        mapOf(
            ACFG.obfuscateSensitiveErrors to true, AuthTiming.floorMsKey to floorMs, AuthTiming.jitterMsKey to 0,
            AuthTiming.concurrencyEnvVar.name to "1",
        ),
    )

    fun tokenOf(client: TestHttpClient): String =
        client.sendJsonGetRequest(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken].toOptStr()!!

    /** A code login for an address nobody has: the quickest refusal there is, timed. */
    fun unknownCodeLogin(client: TestHttpClient, token: String): Pair<Int, Long> {
        val start = System.nanoTime()
        val handler = client.sendEditRequest(
            AEP.loginByCode, null,
            mapOf(AFLD.loginId to "nobody-here@example.com", AFLD.formAuthToken to token, AFLD.verifyCode to "WRONGCODE"),
            HttpMethod.POST,
        )
        return handler.rptStatusCode to (System.nanoTime() - start) / 1_000_000
    }

    "a refusal for an unknown account still waits out the floor" {
        val client = TestHttpClient(cxt.instanceConfig)
        val (status, tookMs) = unknownCodeLogin(client, tokenOf(client))
        status shouldBe EXC.badInput
        tookMs shouldBeGreaterThanOrEqual floorMs
    }

    "an auth call that is not a sign-in step is not padded" {
        val client = TestHttpClient(cxt.instanceConfig)
        val start = System.nanoTime()
        client.sendGetRequest(AEP.createToken).rptStatusCode shouldBe EXC.ok
        ((System.nanoTime() - start) / 1_000_000) shouldBeLessThan floorMs
    }

    "past the cap, a sign-in step is answered 429 until a permit is free again, and cheap auth reads are not counted" {
        val holder = TestHttpClient(cxt.instanceConfig)
        val token = tokenOf(holder)
        val other = TestHttpClient(cxt.instanceConfig)
        val otherToken = tokenOf(other)
        // One step held at the floor takes the one permit. Waited for, rather than slept for, so a slow machine
        // cannot let the second call win the permit instead.
        var heldStatus = 0
        val held = thread { heldStatus = unknownCodeLogin(holder, token).first }
        val requests = RequestService.get(cxt)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (requests.authPermitsAvailable > 0 && System.nanoTime() < deadline) Thread.sleep(5)
        requests.authPermitsAvailable shouldBe 0

        unknownCodeLogin(other, otherToken).first shouldBe EXC.tooManyRequests
        // A form token is no sign-in step: a burst of sign-ins does not turn it away.
        other.sendGetRequest(AEP.createToken).rptStatusCode shouldBe EXC.ok
        held.join()
        heldStatus shouldBe EXC.badInput
        unknownCodeLogin(other, otherToken).first shouldBe EXC.badInput
    }
})
