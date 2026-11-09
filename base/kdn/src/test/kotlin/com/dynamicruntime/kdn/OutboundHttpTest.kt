package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.client.KdrHttpClient
import com.dynamicruntime.common.http.client.OutboundHttpService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.http.server.TestHttpServer
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.util.toJsonStr
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Outbound HTTP (issue #1739 gathered these; issue #420): the one outbound client against a real socket, and the service
 * that refuses a real outbound call in the unit test environment. Each part below is one of the specs that used to be a
 * file of its own, kept whole.
 */
class OutboundHttpTest : StringSpec({
    include(kdrHttpClientTests)
    include(outboundHttpServiceTests)
})

/**
 * The one outbound client against a real socket (issue #420). A directly-constructed [KdrHttpClient] -- the
 * form `ProbeSession` uses -- is not gated by `OutboundHttpService`'s test-instance refusal, so it can drive
 * the Jetty blocking path the whole layer rests on against a local [TestHttpServer]: real status codes, a real
 * body, real headers.
 */
private val kdrHttpClientTests = stringSpec {

    val cxt = TestInstances.default("kdrHttpClient")
    val server = TestHttpServer(cxt.instanceConfig)
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

    // A POST carries method, content type and body: the fixture becomeUser takes a JSON body and answers 200 -- to a
    // caller with test access, which the env-auth header gives on a test instance (issue #1149).
    "a POST carries its JSON body to the endpoint" {
        val body = mapOf(
            TEP.email to "http-client@example.com",
            TEP.level to ROLE.user,
            TEP.capabilities to emptyList<String>(),
        ).toJsonStr()
        val resp = client.post(
            server.url("/kda${TEP.becomeUser}"), "application/json", body,
            headers = mapOf(ENVA.header to TestHttpClient.fixtureEnvAddress),
        )
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
}

/**
 * The service refuses a real outbound call in the unit test environment (issue #420). A test that reaches this
 * path is a test that should have been given a stub, and failing loudly says so -- which is what keeps the
 * suite from ever touching the network (and from spinning up a Jetty client's threads thousands of times).
 *
 * `mkTestBootCxt` forces `ENV.unit`, which is what the refusal is keyed on -- deliberately not `isTestInstance`,
 * so a developer's own in-memory local node still calls out (see `OutboundHttpService`).
 */
private val outboundHttpServiceTests = stringSpec {

    "fast and slow both refuse in the unit test environment" {
        val cxt = TestInstances.default("outboundHttp")
        val service = OutboundHttpService.get(cxt)
        shouldThrow<KdrException> { service.fast(cxt) }.fullMessage() shouldContain "unit test"
        shouldThrow<KdrException> { service.slow(cxt) }.fullMessage() shouldContain "unit test"
    }

    // The regression the refusal must NOT cause: a developer's own in-memory local node (ENV.local, which is
    // still `isTestInstance`) must be able to reach the client, or a real Google sign-in / mail send breaks
    // there. Handing back a client makes no network call -- the Jetty client is started lazily, on first
    // request -- so this stays hermetic.
    "a non-unit local instance is handed a client rather than refused" {
        val localCxt = KdrCxt("outLocal", KdrInstanceConfig("outLocal", ENV.local, ENV.liveSource))
        shouldNotThrowAny { OutboundHttpService().fast(localCxt).close() }
    }
}
