package com.dynamicruntime.common.http.server

import com.dynamicruntime.common.http.request.RequestHandler
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback

/**
 * Runs the runtime's HTTP stack on a real socket, for tests that need the wire itself. Serves [instanceName]'s
 * endpoints exactly as [HttpServer] does — a core Jetty [Handler] building a Jetty-mode [RequestHandler] — but
 * on an ephemeral [port] and without blocking, so a test can start one, make requests, and stop it.
 *
 * It is the counterpart of [com.dynamicruntime.common.http.request.TestHttpClient], and exists because that
 * one deliberately has no wire: it drives a test-mode `RequestHandler` in-process, which is the right trade for
 * testing endpoints (fast, no ports) but leaves everything *below* the handler uncovered — Jetty's own
 * parsing, headers, and the response bytes as they actually go out.
 *
 * That gap is not hypothetical. Multipart uploads are parsed by Jetty and configured by us; the in-process
 * client hands parts over already decoded, so the first real upload failed on a `MultiPartConfig` that no test
 * could see. Reach for this when the thing under test is the wire; reach for `TestHttpClient` otherwise.
 *
 * Lives in the main source (not test), like `TestHttpClient`, so tests in any module can use it — Jetty is an
 * `implementation` dependency of this module and does not reach a dependent module's test classpath.
 *
 * ```
 * TestHttpServer("myInstance").use { server ->
 *     val response = client.send(HttpRequest.newBuilder(URI(server.url("/kda/health"))).build(), ...)
 * }
 * ```
 */
class TestHttpServer(val instanceName: String) : AutoCloseable {
    private val server = Server()
    private val connector = ServerConnector(server)

    /** The ephemeral port the server bound to. Assigned once started, so read it rather than assuming one. */
    val port: Int

    init {
        // Port 0: let the OS pick a free one, so concurrent test specs cannot collide over a fixed port.
        connector.port = 0
        server.addConnector(connector)
        server.handler = object : Handler.Abstract() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                RequestHandler(instanceName, request, response, callback).handleRequest()
                return true
            }
        }
        server.start()
        port = connector.localPort
    }

    /** The absolute URL of [path] on this server, e.g. `url("/kda/health")`. */
    fun url(path: String): String = "http://localhost:$port$path"

    override fun close() {
        runCatching { server.stop() }
    }
}
