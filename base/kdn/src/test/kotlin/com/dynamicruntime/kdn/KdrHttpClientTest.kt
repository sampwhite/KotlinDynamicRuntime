package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.client.KdrHttpClient
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.server.TestHttpServer
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.util.toJsonStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The one outbound client against a real socket (issue #420). A directly-constructed [KdrHttpClient] -- the
 * form `ProbeSession` uses -- is not gated by `OutboundHttpService`'s test-instance refusal, so it can drive
 * the Jetty blocking path the whole layer rests on against a local [TestHttpServer]: real status codes, a real
 * body, real headers.
 */
class KdrHttpClientTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("kdrHttpClient", "kdrHttpClientTest")
    val server = TestHttpServer(cxt.instanceConfig.instanceName)
    val client = KdrHttpClient("test")

    afterSpec {
        client.close()
        server.close()
    }

    "a GET returns the status and body of a real endpoint, carrying a custom header" {
        val resp = client.get(server.url("/kda/health"), headers = mapOf("X-Kdr-Probe" to "1"))
        resp.status shouldBe 200
        resp.isSuccess shouldBe true
        resp.body shouldContain EP.results
    }

    // A non-2xx is the answer, not a failure: an anonymous caller over a socket is refused the admin listing
    // with a 401 (issue #211), and the client hands that back rather than throwing.
    "a non-2xx status is reported on the response, not thrown" {
        val resp = client.get(server.url("/kda${ADEP.users}"))
        resp.status shouldBe 401
        resp.isSuccess shouldBe false
    }

    // A POST carries method, content type and body: the fixture becomeUser takes a JSON body and answers 200.
    "a POST carries its JSON body to the endpoint" {
        val body = mapOf(
            TEP.email to "http-client@example.com",
            TEP.level to ROLE.user,
            TEP.capabilities to emptyList<String>(),
        ).toJsonStr()
        val resp = client.post(server.url("/kda${TEP.becomeUser}"), "application/json", body)
        resp.status shouldBe 200
    }

    // Close is terminal: a use-after-close must fail, not silently start a fresh, unowned client whose threads
    // nothing would then stop.
    "a closed client refuses further use rather than reviving" {
        val throwaway = KdrHttpClient("throwaway")
        throwaway.get(server.url("/kda/health")).status shouldBe 200
        throwaway.close()
        shouldThrow<KdrException> { throwaway.get(server.url("/kda/health")) }.fullMessage() shouldContain "closed"
    }
})
