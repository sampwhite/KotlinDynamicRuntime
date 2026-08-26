package com.dynamicruntime.common.http.client

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The instance's outbound HTTP clients (issue #420), reached the way services are.
 *
 * Two named clients, so "how long should this wait" becomes a decision made once, by name, rather than a
 * number copied between call sites:
 *
 * - [fast] -- short timeouts, and the **default**. A caller that does not think about timeouts should get the
 *   strict ones rather than none, which is exactly backwards from the direct `java.net.http` use this replaces.
 * - [slow] -- long timeouts, chosen explicitly, for a service known to take minutes. No caller yet; it is part
 *   of the shape rather than a present need.
 *
 * Both are created **lazily on first use** (starting a Jetty client spins up threads, and a test that never
 * calls out must not pay for that), and each is registered with [InstanceRegistry] so the JVM shutdown hook
 * stops it.
 *
 * **The unit test suite (`ENV.unit`) refuses outright.** A test that reaches a real outbound call is a test
 * that should have been given a stub, and failing loudly says so -- the network is never touched by the suite.
 * Deliberately keyed on the environment, **not** `isTestInstance`: a developer's own in-memory local node
 * (`KDR_IN_MEMORY_ONLY=true`) is a test instance too, and a real Google sign-in or mail send must still work
 * there. `ProbeSession` is not affected either: it constructs its own standalone [KdrHttpClient] rather than
 * going through this service.
 */
class OutboundHttpService : ServiceInitializer {
    override val serviceName: String = OutboundHttpService.serviceName

    @Volatile
    private var fastClient: KdrHttpClient? = null

    @Volatile
    private var slowClient: KdrHttpClient? = null

    /** The short-timeout client. The default; nearly every call should use this. */
    fun fast(cxt: KdrCxt): KdrHttpClient = client(cxt, fast = true)

    /** The long-timeout client, for a service known to take minutes. */
    fun slow(cxt: KdrCxt): KdrHttpClient = client(cxt, fast = false)

    private fun client(cxt: KdrCxt, fast: Boolean): KdrHttpClient {
        // The unit test suite must never make a real outbound call -- a test that reaches this path should have
        // been given a stub. Keyed on ENV.unit, NOT isTestInstance: the latter is also true for a developer's
        // own in-memory local node (KDR_IN_MEMORY_ONLY=true), where a real Google sign-in or mail send is
        // exactly what should happen.
        if (cxt.instanceConfig.env == ENV.unit) {
            throw KdrException(
                "Outbound HTTP is not available in the unit test environment -- stub it rather than making a " +
                    "real network call (the '$serviceName' clients refuse one under ENV.unit).",
            )
        }
        (if (fast) fastClient else slowClient)?.let { return it }
        synchronized(this) {
            (if (fast) fastClient else slowClient)?.let { return it }
            val created = if (fast) {
                KdrHttpClient("fast", HTTPC.fastConnectMs, HTTPC.fastRequestMs, HTTPC.fastIdleMs)
            } else {
                KdrHttpClient("slow", HTTPC.slowConnectMs, HTTPC.slowRequestMs, HTTPC.slowIdleMs)
            }
            // Whatever owns them stops them: the instance registry drains this on the JVM shutdown hook.
            InstanceRegistry.registerForShutdown(created)
            if (fast) fastClient = created else slowClient = created
            return created
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "OutboundHttpService"

        fun get(cxt: KdrCxt): OutboundHttpService = cxt.instanceConfig.get(serviceName) as? OutboundHttpService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
