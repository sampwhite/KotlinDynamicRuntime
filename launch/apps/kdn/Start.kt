package kdn

import com.dynamicruntime.common.http.server.HttpServer

/**
 * The application launcher: boots an ordinary KDR node and serves HTTP on it.
 *
 * The boot itself lives in [bootInstance], shared with [StartEdge] -- see its KDoc for why that is shared and
 * not copied. This file is only what makes an *application* node different, which today is nothing but the
 * absence of a boot role.
 */
fun main() {
    HttpServer.launch(bootInstance("start"))
}
