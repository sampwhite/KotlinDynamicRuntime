package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrCxt

/**
 * A source of non-endpoint content (HTML, static assets, redirects) that the
 * [RequestService] consults before it tries to match a JSON endpoint. This is the
 * first, minimal step toward the deferred content-serving layer (dn's
 * `DnContentService`): a service that can produce raw responses registers itself
 * with the dispatcher via [RequestService.addContentServer].
 *
 * Each registered server is offered the request in turn; the first to claim it by
 * writing a response (returning true) wins, and endpoint dispatch is skipped. A
 * server that does not recognize the target must return false and leave the
 * response untouched so the next server -- or the endpoint dispatcher -- can run.
 */
interface ContentServer {
    /**
     * Serves [handler]'s request if this server owns its target. Returns true iff it
     * produced a response (via the handler's send/redirect methods); false to pass.
     */
    fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean
}
