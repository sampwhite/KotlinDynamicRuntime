package com.dynamicruntime.common.context

import com.dynamicruntime.common.annotation.KdrPrivate
import java.util.concurrent.atomic.AtomicLong

/**
 * Stubbed placeholder for the configuration of the running instance/application.
 * A real implementation will expose application configuration sourced from
 * environment variables and Kotlin config classes. For now it provides the
 * instance identity and the increasing-id generator used to make context
 * logging ids unique.
 */
class KdrInstanceConfig(
    /** Name identifying this running instance. */
    val instanceName: String,
    /** Environment name, e.g. [ENV.unit] or [ENV.dev]. */
    val env: String,
    /** Environment type, e.g. [ENV.deployed] or [ENV.liveSource]. */
    val envType: String,
) {
    // Conceptually private: the counter must only be advanced through
    // nextLoggingId(). Left open per the code guide; marked rather than hidden.
    @KdrPrivate
    val loggingIdCounter: AtomicLong = AtomicLong(0)

    /** Returns a process-unique, increasing suffix for a context's logging id. */
    fun nextLoggingId(): Long = loggingIdCounter.incrementAndGet()

    fun getEnvVar(key: String): String? {
        // Eventually we will look to instance config data first.
        return System.getenv(key)
    }


    companion object {
        /** Placeholder instance config used for code and unit tests. */
        fun codeTest(): KdrInstanceConfig =
            KdrInstanceConfig("codeTest", ENV.unit, ENV.liveSource)
    }
}
