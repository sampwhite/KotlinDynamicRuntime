package com.dynamicruntime.common.node

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.server.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.server.Server

/**
 * Where the HTTP server listens (issue #1245): `KDR_BIND_ADDRESS`, and the one question asked of it -- whether only
 * this machine can reach the server directly, which is what lets a public test deployment assume env auth.
 */
class BindAddressTest : StringSpec({

    fun config(address: String?): KdrInstanceConfig = KdrInstanceConfig("bind1245", ENV.unit, ENV.liveSource).also {
        if (address != null) it.put(NodeUtil.bindAddress.name, address)
    }

    "only a loopback address, written as one, binds to this machine alone" {
        for (address in listOf("127.0.0.1", "127.0.0.2", "::1", "[::1]", "localhost", " 127.0.0.1 ")) {
            NodeUtil.bindsLoopbackOnly(config(address)) shouldBe true
        }
        // Unset (every interface), every interface by name, a real interface, and a name -- whatever it resolves to
        // today, what a name resolves to can change.
        for (address in listOf(null, "", "0.0.0.0", "::", "10.0.0.5", "example.org")) {
            NodeUtil.bindsLoopbackOnly(config(address)) shouldBe false
        }
    }

    "the server listens where it is told, and everywhere when told nothing" {
        val server = Server()
        HttpServer.connector(server, 7123, "127.0.0.1").let { it.host to it.port } shouldBe ("127.0.0.1" to 7123)
        HttpServer.connector(server, 7123, null).host shouldBe null
    }
})
