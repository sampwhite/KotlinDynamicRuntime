package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.client.OutboundHttpService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * The service refuses a real outbound call in the unit test environment (issue #420). A test that reaches this
 * path is a test that should have been given a stub, and failing loudly says so -- which is what keeps the
 * suite from ever touching the network (and from spinning up a Jetty client's threads thousands of times).
 *
 * `mkTestBootCxt` forces `ENV.unit`, which is what the refusal is keyed on -- deliberately not `isTestInstance`,
 * so a developer's own in-memory local node still calls out (see `OutboundHttpService`).
 */
class OutboundHttpServiceTest : StringSpec({

    "fast and slow both refuse in the unit test environment" {
        val cxt = Startup.mkTestBootCxt("outboundHttp", "outboundHttpServiceTest")
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
})
