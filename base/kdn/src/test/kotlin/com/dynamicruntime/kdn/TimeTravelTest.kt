package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.ClockOp
import com.dynamicruntime.common.test.TCLK
import com.dynamicruntime.common.user.AUTHC
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

/**
 * Time travel end to end (issue #160): the `forTestingOnly` `/test/clock` endpoint moves the instance clock,
 * and advancing past a lifetime makes a real expiry fire with no wall-clock wait -- the case #120 could not
 * exercise before.
 */
class TimeTravelTest : StringSpec({

    "/test/clock advances the instance clock" {
        val cxt = Startup.mkTestBootCxt("clock", "clockEndpointTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val before = cxt.instanceNow().toEpochMilliseconds()
        val results = client.sendJsonPostRequest(
            TCLK.path, mapOf(TCLK.op to ClockOp.advance.name, TCLK.deltaMs to 3_600_000L),
        )[EP.results].toJsonMapOrEmpty()
        val after = results[TCLK.instanceNowMs].toOptLong()!!
        (after - before >= 3_600_000L) shouldBe true
    }

    "an unknown clock op is rejected by the choice-list validation (the enum drives the schema)" {
        val cxt = Startup.mkTestBootCxt("clockBad", "clockBadOpTest")
        TestHttpClient(cxt.instanceConfig).sendJsonPostRequest(TCLK.path, mapOf(TCLK.op to "bogus"))[EP.status] shouldBe
            EXC.badInput
    }

    "advancing past the session lifetime expires an existing session, with no real wait (issue #120)" {
        val cxt = Startup.mkTestBootCxt("clockSession", "clockSessionTest")
        val user = TestUser.create(cxt, "carol@example.com")
        UserProfile.fromUserInfo(user.getData("/auth/self/info")).isLoggedIn shouldBe true

        // Travel past the 30-day session: the same cookie is now stale on its next request.
        cxt.instanceConfig.clock.advanceBy((AUTHC.sessionMillis + 1000).milliseconds)
        UserProfile.fromUserInfo(user.getData("/auth/self/info")).isLoggedIn shouldBe false
    }
})
