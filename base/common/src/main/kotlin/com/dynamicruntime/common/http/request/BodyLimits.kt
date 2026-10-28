package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrInstanceConfig

/**
 * How large a request body may be (issue #1446). A web application firewall in front of a deployment commonly
 * inspects, or refuses, a body over 16 KB, so every endpoint is held to a little under that by default. The few
 * endpoints whose input is a whole configuration are marked [largeBody][com.dynamicruntime.common.endpoint.KdrEndpoint.largeBody]
 * and held to [largeMaxBytes] instead -- and the firewall has to let the same endpoints through, which is why the
 * request service publishes the list it collected at boot.
 *
 * Bytes, not characters: a firewall counts what crosses the wire, and text beyond ASCII is more bytes than characters.
 */
@Suppress("ConstPropertyName")
object BODY {
    /** The most bytes a request body may be, unless its endpoint accepts large bodies. */
    const val defaultMaxBytes = 16_000

    /** The instance-config key for the most bytes a large-body endpoint's body may be. Not an environment variable. */
    const val largeMaxBytesKey = "largeBodyMaxBytes"

    /** [largeMaxBytesKey]'s value when the deployment sets none: 128 KB. */
    const val defaultLargeMaxBytes = 128 * 1024

    /**
     * The most bytes a large-body endpoint's body may be, as the deployment configures it. Less than
     * [defaultMaxBytes] would make a large-body endpoint take less than any other, so the request service refuses
     * the start over one.
     */
    fun largeMaxBytes(config: KdrInstanceConfig): Int = (config.get(largeMaxBytesKey) as? Number)?.toInt() ?: defaultLargeMaxBytes
}
