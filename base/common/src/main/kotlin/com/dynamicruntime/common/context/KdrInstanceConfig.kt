package com.dynamicruntime.common.context

import com.dynamicruntime.common.annotation.KdrPrivate
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The instance-scoped, runtime-mutable clock behind `KdrCxt.instanceNow()` (issue #160). Its default state is
 * real time -- no frozen base, zero offset -- so `instanceNow()` equals `Clock.System.now()` and nothing
 * behaves differently. A `forTestingOnly` endpoint (`/test/clock`) mutates it to advance or freeze time, which
 * is what makes expiry / rate-limit behavior testable without real waits.
 *
 * Held as a plain field on [KdrInstanceConfig] rather than a registered service, deliberately: every `now()`
 * reads it, it must exist on every instance, and a plain field is the easiest thing to find in the code and to
 * read in a debugger. Every mutator and the read are `synchronized(this)` -- production never mutates it (the
 * endpoint that does is absent outside a test instance), so the lock is uncontended there; a test mutates from
 * a request thread while `now()` reads from others, which the lock makes coherent.
 */
class InstanceClock {
    private var frozenBase: Instant? = null
    private var offset: Duration = Duration.ZERO

    /** The instance's current time: the frozen base (or the live wall clock) shifted by the accumulated offset. */
    fun instanceNow(): Instant = synchronized(this) { (frozenBase ?: Clock.System.now()) + offset }

    /** Advance the clock by [delta] (negative rewinds), keeping any freeze -- the way to step a frozen clock. */
    fun advanceBy(delta: Duration): Unit = synchronized(this) { offset += delta }

    /** Make `instanceNow()` read [target] now. **Not** a freeze: an unfrozen clock keeps ticking from there. */
    fun setAbsolute(target: Instant): Unit = synchronized(this) {
        offset = target - (frozenBase ?: Clock.System.now())
    }

    /** Pin the clock at its current value, so it no longer advances with the wall clock. */
    fun freeze(): Unit = synchronized(this) {
        frozenBase = (frozenBase ?: Clock.System.now()) + offset
        offset = Duration.ZERO
    }

    /** Resume ticking from the current value -- seamless, no jump. A no-op when not frozen. */
    fun unfreeze(): Unit = synchronized(this) {
        val base = frozenBase ?: return@synchronized
        offset = base + offset - Clock.System.now()
        frozenBase = null
    }

    /** Back to real time: drop any freeze and offset. */
    fun reset(): Unit = synchronized(this) {
        frozenBase = null
        offset = Duration.ZERO
    }
}

/**
 * Configuration and service registry for a running instance/application. It holds
 * the instance identity, the increasing-id generator for context logging ids, and
 * a shared key/value store that carries both resolved configuration values and the
 * live service singletons the [com.dynamicruntime.common.startup.InstanceRegistry]
 * publishes during startup. Services locate each other by reading their own key
 * from this store (the `get(cxt)` convention).
 *
 * Configuration keeps its natural map nesting: a key containing `.` is a path into
 * nested maps, so `get("node.internalIpAddressFilter")` reads the "node" entry as a
 * map and then its "internalIpAddressFilter" entry (and [put] builds that nesting).
 * This lets a deployment configure a whole entity (e.g., a database connection) as a
 * sub-map and read either the map or an individual field, rather than forcing every
 * setting into a single flat namespace. A key with no `.` is a plain top-level entry
 * (service singletons and simple config are always stored flat -- their names have no
 * dots). The store is populated during single-threaded startup, so the nested maps
 * need not themselves be concurrent.
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

    /** This instance's time source (issue #160), read by `KdrCxt.instanceNow()`; real time until a test travels it. */
    val clock: InstanceClock = InstanceClock()

    /**
     * Reads a value (config entry or service) by [key], or null if absent. A key containing `.` is a path
     * into nested maps: each `.`-separated segment reads deeper, so "node.internalIpAddressFilter" reads the
     * "node" map's "internalIpAddressFilter" entry. Returns null if any segment along the path is missing or
     * is not a map (so an unset nested key reads as null, just like a flat one).
     */
    fun get(key: String): Any? {
        if ('.' !in key) {
            return store[key]
        }
        val parts = key.split('.')
        var current: Any? = store[parts[0]]
        for (i in 1 until parts.size) {
            val map = current as? Map<*, *> ?: return null
            current = map[parts[i]]
        }
        return current
    }

    /**
     * Publishes or overwrites a value under [key]. A key containing `.` is a nested path: intermediate maps
     * are created (or reused/copied) as needed, so "node.instance.authConfigKey" sets "authConfigKey" inside
     * the "instance" map inside the "node" map, merging into any existing maps rather than replacing them. A
     * null [value] removes the (possibly nested) key -- the backing store cannot hold nulls, and an absent key
     * already reads as null.
     */
    fun put(key: String, value: Any?) {
        if ('.' !in key) {
            if (value == null) store.remove(key) else store[key] = value
            return
        }
        val parts = key.split('.')
        if (value == null) {
            removeNested(parts)
            return
        }
        var current: MutableMap<String, Any> = store
        for (i in 0 until parts.size - 1) {
            current = childMap(current, parts[i])
        }
        current[parts.last()] = value
    }

    /**
     * The mutable child map under [name] in [parent], created when absent (or copied into a mutable map when
     * an existing read-only map is found). A new map replaces a non-map value in the way.
     */
    private fun childMap(parent: MutableMap<String, Any>, name: String): MutableMap<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return when (val existing = parent[name]) {
            is MutableMap<*, *> -> existing as MutableMap<String, Any>
            is Map<*, *> -> LinkedHashMap(existing as Map<String, Any>).also { parent[name] = it }
            else -> LinkedHashMap<String, Any>().also { parent[name] = it }
        }
    }

    /** Removes the leaf of a dotted [parts] path, without creating intermediate maps. */
    private fun removeNested(parts: List<String>) {
        var current: Any? = store[parts[0]]
        for (i in 1 until parts.size - 1) {
            current = (current as? Map<*, *>)?.get(parts[i]) ?: return
        }
        (current as? MutableMap<*, *>)?.remove(parts.last())
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

    /**
     * Whether this is a **test instance** -- a node where test-only affordances are on: `forTestingOnly`
     * endpoints are exposed (issue #125), and email is simulated and captured by default (issue #158). True
     * when the [testInstanceEnvVar] env var is set true, OR the environment is [ENV.unit], OR the instance runs
     * [ACFG.inMemoryOnly]. A node that is a test instance but not in a `local`/`unit` environment refuses to
     * start (see `SchemaService.checkInit`), so test affordances can never reach a real environment.
     *
     * Resolved once and cached: the inputs are fixed by the time the instance boots, and a materialized value
     * is directly inspectable in a debugger while stepping (unlike a recomputed getter). The boot path
     * force-touches it via [warmDerived] so it is realized at a single-threaded point before any concurrent
     * request; off-boot paths (a hand-built config in a test) resolve it correctly on first access.
     */
    val isTestInstance: Boolean by lazy {
        getEnvVar(testInstanceEnvVar)?.toBooleanStrictOrNull() == true ||
            env == ENV.unit ||
            get(ACFG.inMemoryOnly) == true
    }

    /**
     * Force-materializes the derived, lazily computed config values (today [isTestInstance]) at a
     * single-threaded boot point, so they are realized before any concurrent request and are already populated
     * when inspecting the config in a debugger. Idempotent; safe to call more than once.
     */
    fun warmDerived() {
        isTestInstance
    }


    @Suppress("ConstPropertyName")
    companion object {
        /** Env var that forces this to be a test instance regardless of environment (see [isTestInstance]). */
        const val testInstanceEnvVar = "KDR_TEST_INSTANCE"

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
