package com.dynamicruntime.common.http.server

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.LogRequest
import com.dynamicruntime.common.http.request.RequestHandler
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
        server.handler = object : Handler.Abstract() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                RequestHandler(instanceName, request, response, callback).handleRequest()
                return true
            }
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
