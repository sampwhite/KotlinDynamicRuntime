package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.logging.KdrLogger

/**
 * Topic logger for the HTTP request layer (the server, request handler, and dispatcher).
 * Lives beside the request code it serves. See [com.dynamicruntime.common.logging.LogStartup]
 * for the placement rule.
 */
object LogRequest : KdrLogger("request")
