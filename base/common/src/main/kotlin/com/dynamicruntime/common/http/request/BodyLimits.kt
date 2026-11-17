package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
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

    /**
     * How many raw bodies (issue #1836) a node holds in memory at once: each is read whole before its handler runs --
     * a part of an upload, up to its endpoint's limit -- so this, times the largest such limit, bounds what uploads
     * may take of the heap.
     */
    val rawBodyPermits = EnvVarDef(
        "KDR_RAW_BODY_PERMITS", group = ENVGRP.application, defaultDoc = "$defaultRawBodyPermits",
        description = "How many request bodies sent as raw bytes -- the parts of a large upload -- a node reads into " +
            "memory at once. Each is held whole until its handler is done, so this times the largest part an endpoint " +
            "takes (8 MiB for a file's parts) bounds what uploads may take of the heap. A request finding none free " +
            "waits up to $rawBodyWaitSeconds seconds and is then answered 429, to be sent again.",
    )

    /** [rawBodyPermits] when the deployment sets none. */
    const val defaultRawBodyPermits = 8

    /** How long a raw body waits for one of the [rawBodyPermits] before it is answered 429. */
    const val rawBodyWaitSeconds = 10L
}
