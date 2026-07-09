package com.dynamicruntime.common.context

import com.dynamicruntime.common.annotation.KdrPrivate
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Configuration and service registry for a running instance/application. It holds
 * the instance identity, the increasing-id generator for context logging ids, and
 * a shared key/value store that carries both resolved configuration values and the
 * live service singletons the [com.dynamicruntime.common.startup.InstanceRegistry]
 * publishes during startup. Services locate each other by reading their own key
 * from this store (the `get(cxt)` convention).
 *
 * A fuller implementation will source configuration from environment variables and
 * Kotlin config classes; that resolution is not wired up here yet.
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

    // The shared config/service store. Real `private` because it is mutated
    // concurrently and must only be reached through the accessors below.
    private val store: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    /** Reads a value (config entry or service) by key, or null if absent. */
    fun get(key: String): Any? = store[key]

    /**
     * Publishes or overwrites a value under [key]. A null [value] removes the key
     * (the backing store cannot hold nulls, and an absent key already reads as null).
     */
    fun put(key: String, value: Any?) {
        if (value == null) store.remove(key) else store[key] = value
    }

    /** Merges all non-null entries from [values] into the store. */
    fun putAll(values: Map<String, Any?>) {
        for ((k, v) in values) put(k, v)
    }

    /** A snapshot of all stored entries (config values and services). */
    fun entries(): Map<String, Any> = HashMap(store)

    /** Returns a process-unique, increasing suffix for a context's logging id. */
    fun nextLoggingId(): Long = loggingIdCounter.incrementAndGet()

    fun getEnvVar(key: String): String? {
        // Instance-config entries win over the real process environment, so configuration (and tests) can
        // inject or override an "environment variable" without touching the process environment.
        return (get(key) as? String) ?: System.getenv(key)
    }


    @Suppress("ConstPropertyName")
    companion object {
        /**
         * Optional properties file, in the working directory, supplying default environment-variable values
         * for keys not already set in the real environment. Loaded by [preBootLoadConfig].
         */
        const val defaultEnvVarsFileName = "default-environment-variables.properties"

        /** Placeholder instance config used for code and unit tests. */
        fun codeTest(): KdrInstanceConfig =
            KdrInstanceConfig("codeTest", ENV.unit, ENV.liveSource)

        /**
         * Builds the pre-boot instance config used to load deployment configuration before the application
         * boots. The environment name comes from `KDR_ENV` (default [ENV.local]); the env type is
         * [ENV.liveSource]. Every entry in [defaultEnvVarsFileName] whose key is not already a defined
         * environment variable is pushed into the config, so it serves as a default the rest of startup reads
         * through [getEnvVar].
         */
        fun preBootLoadConfig(): KdrInstanceConfig {
            val fileDefaults = readDefaultEnvVars(File(defaultEnvVarsFileName), System::getenv)
            val env = System.getenv("KDR_ENV") ?: fileDefaults["KDR_ENV"] ?: ENV.local
            val config = KdrInstanceConfig(env, env, ENV.liveSource)
            for ((k, v) in fileDefaults) {
                config.put(k, v)
            }
            return config
        }

        /**
         * Reads the properties [file], returning only the entries whose key is NOT already a defined
         * environment variable per [getEnv] (the real environment always wins). Empty if the file is absent.
         * [getEnv] is injectable for testing; production passes `System::getenv`.
         */
        @KdrPrivate
        fun readDefaultEnvVars(file: File, getEnv: (String) -> String?): Map<String, String> {
            if (!file.isFile) {
                return emptyMap()
            }
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val result = LinkedHashMap<String, String>()
            for (name in props.stringPropertyNames()) {
                if (getEnv(name) == null) {
                    result[name] = props.getProperty(name)
                }
            }
            return result
        }
    }
}
