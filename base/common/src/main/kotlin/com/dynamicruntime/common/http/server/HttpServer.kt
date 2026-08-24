package com.dynamicruntime.common.http.server

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.LogRequest
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.node.NodeUtil
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.Callback

/**
 * The HTTP server. Ported from dn's `DnServer`, freshened to Jetty 12: we handle requests with a core
 * [Handler] (`handle(Request, Response, Callback)`) rather than through the servlet container, which is
 * the faithful successor to dn's servlet-bypassing handler and keeps us off the servlet API entirely.
 *
 * Each request builds a Jetty-mode [RequestHandler] that processes it and completes the [Callback].
 * SSL/HTTPS, virtual-host proxying, and connector tuning are deferred (TODO).
 */
object HttpServer {
    /** Starts the server on the node's port and blocks until it stops. */
    fun launch(cxt: KdrCxt) {
        val nodeId = NodeUtil.extractNodeId(cxt)
        val instanceName = cxt.instanceConfig.instanceName
        val server = Server(nodeId.port)
        // The dispatcher, and always last: it answers every request that reaches it, including the terse 404
        // for an unrecognized context root, so nothing can usefully sit behind it.
        val dispatcher = object : Handler.Abstract() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                RequestHandler(instanceName, request, response, callback).handleRequest()
                return true
            }
        }
        // Front handlers (issue #419) are offered the request first and may decline by returning false, which
        // is how an edge takes traffic addressed to a backend without the dispatcher ever seeing it. Read
        // optionally for the same reason the dispatcher reads the service optionally: a node that serves no
        // endpoints is a protocol answer, not a reason to refuse to start a server.
        val front = (cxt.instanceConfig.get(RequestService.serviceName) as? RequestService)?.frontHandlers.orEmpty()
        server.handler = if (front.isEmpty()) {
            dispatcher
        } else {
            LogRequest.info(cxt) { "Offering requests to ${front.size} front handler(s) before dispatch." }
            Handler.Sequence(front + dispatcher)
        }

        try {
            server.start()
            LogRequest.info(cxt, "Started server at ${nodeId.nodeIpAddress}:${nodeId.port} on ${nodeId.hostname}.")
            server.join()
        } catch (e: Exception) {
            runCatching { server.stop() }
            throw KdrException("Could not start the HTTP server.", e)
        }
    }
}
