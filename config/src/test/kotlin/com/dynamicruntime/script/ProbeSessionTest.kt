package com.dynamicruntime.script

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.server.TestHttpServer
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.kdn.Startup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * [ProbeSession] against a real socket, because everything it exists to get right lives below the in-process
 * client: actual cookies, actual status codes, actual refusals.
 *
 * The point of these is narrow and deliberate. `kdr-probe`'s scenarios *report* rather than assert -- their
 * output is for a person to read -- so what is worth pinning here is not what any scenario prints, but the
 * property the whole tool rests on: **a probe that cannot do its job must fail, not answer plausibly.** The
 * reason issue #215 exists is that a hand-rolled probe once ran every request anonymously and returned a
 * clean-looking table of identical numbers, which read as a finding about the code and cost far more to
 * unpick than an error would have.
 */
class ProbeSessionTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("probe", "probeSessionTest")
    val server = TestHttpServer(cxt.instanceConfig.instanceName)
    val baseUrl = "http://localhost:${server.port}"

    afterSpec { server.close() }

    "a session logs in and carries the cookie to the next call" {
        ProbeSession(ROLE.admin, baseUrl).use { session ->
            session.becomeUser("probe-admin@example.com", ROLE.admin, listOf(ROLE.allClients))

            // The second call proves the session stuck: an anonymous caller would be refused this outright.
            val response = session.sendGetRequest(ADEP.users)
            response.statusCode shouldBe 200
        }
    }

    "a deployment operator reaches the operator section but not an admin one" {
        ProbeSession(ROLE.operator, baseUrl).use { session ->
            // The operator section is a deployment surface since #464: the level needs the `allClients`
            // capability beside it, so a client-confined operator would be refused.
            val info = session.becomeUser(
                "probe-operator@example.com", ROLE.operator, capabilities = listOf(ROLE.allClients),
            )
            info["roles"].toJsonListOfStrings() shouldContain ROLE.operator

            session.sendGetRequest("/operator/system/info").statusCode shouldBe 200
            // 403 rather than 401 -- logged in, holds the capability but not the admin *level*, and the admin
            // section needs both (issue #211).
            session.sendGetRequest(ADEP.users).statusCode shouldBe 403
        }
    }

    "an anonymous session is refused, and says so with a status rather than an empty body" {
        ProbeSession(anonymousLabel, baseUrl).use { session ->
            val response = session.sendGetRequest(ADEP.users)
            response.statusCode shouldBe 401
            response.errorMessage.isNullOrBlank() shouldBe false
        }
    }

    // The one that matters most. A probe pointed somewhere with no server must raise, so a scenario stops
    // instead of printing zeroes that look like an answer about the code under test.
    "a session pointed at nothing fails loudly instead of reporting an empty result" {
        ProbeSession(anonymousLabel, "http://localhost:1").use { session ->
            val failure = shouldThrow<KdrException> { session.sendGetRequest("/health") }
            failure.fullMessage() shouldContain "Could not reach"
            // And it names a cause: a connection refusal carries no message of its own, and "(null)" would be
            // useless from the tool whose entire purpose is diagnosis.
            failure.fullMessage() shouldContain "Exception"
        }
    }

    "becoming a user throws when the instance refuses, rather than handing back an anonymous session" {
        ProbeSession(ROLE.user, baseUrl).use { session ->
            // `failIfUserAlreadyExists` is not set, so the only way to be refused here is a bad level -- the
            // field is a closed option list, so this is a 400. Whatever the reason, the session must not come
            // back looking usable.
            val failure = shouldThrow<KdrException> { session.becomeUser("probe-bad@example.com", "sysadmin") }
            failure.fullMessage() shouldContain "Could not become"
            session.userInfo shouldBe null
        }
    }
})
