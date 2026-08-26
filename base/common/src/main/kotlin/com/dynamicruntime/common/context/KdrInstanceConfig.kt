package com.dynamicruntime.common.context

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.util.toOptBool
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
 * **"Instance-scoped" here means one per [KdrInstanceConfig], which is one per process** -- not shared across
 * the nodes of a deployment the way the encryption key is. Advancing it moves time for the node that advanced
 * it and no other, so a test spanning two nodes has to advance both. The name follows `instanceNow()` and the
 * config object it lives on; it is the narrower sense of the word.
 *
 * Held as a plain field on [KdrInstanceConfig] rather than a registered service, deliberately: every `now()`
 * reads it, it must exist wherever a context does, and a plain field is the easiest thing to find in the code
 * and to read in a debugger. Every mutator and the read are `synchronized(this)` -- production never mutates it (the
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
    /**
     * The boot role this process runs as (issue #377) -- `edge` for a `StartEdge` node, null for an ordinary
     * one. Every environment variable then gains a per-role override; see [getEnvVar].
     *
     * A **constructor parameter** rather than something set afterwards, and deliberately: it has to be settled
     * before the first environment read, and a value that can be assigned late is one that can be read early.
     */
    val bootRole: String? = null,
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

    /** The value of [def], resolved for this instance, or null when it is unset. See [resolveEnvVarByName]. */
    fun getEnvVar(def: EnvVarDef): String? = resolveEnvVarByName(def.name)

    /**
     * The raw-name resolution behind [getEnvVar]. **Private on purpose** (issue #371): ordinary reads take an
     * [EnvVarDef], so a variable nobody declared cannot be read. Only the bootstrap paths that run before a
     * declaration is convenient (`KDR_ENV` in [preBootLoadConfig], `KDR_WORKSPACE_DIR` in `AppPaths`) name a
     * variable as a string, and they read it through a declared def's [EnvVarDef.name] and `System.getenv`.
     *
     * Instance-config entries win over the real process environment, so configuration (and tests) can inject
     * or override an "environment variable" without touching the process environment.
     *
     * Under a boot role, the role-prefixed name is tried first and the plain one is the fallback, so an edge
     * and an application can run side by side on one machine wanting different values for the same variable.
     * The MORE SPECIFIC name wins outright -- both its config entry and its process variable -- before the
     * plain name is considered at all, because a value naming this role was written for this role and a
     * general one was not.
     */
    private fun resolveEnvVarByName(name: String): String? {
        for (k in envVarNamesFor(name, bootRole)) {
            ((get(k) as? String) ?: System.getenv(k))?.let { return it }
        }
        return null
    }

    /**
     * [getEnvVar] parsed as a boolean, or null when it is unset **or unrecognized** -- so a caller's `?:`
     * default covers both, and a value nobody can read is never mistaken for a deliberate `false`.
     *
     * Parsing is [toOptBool]'s loose one (`true`/`yes`/`y`/`t`/`1` and `false`/`no`/`n`/`f`/`0`), which is the
     * house rule for coercing external data. **Read every boolean variable through this**, rather than
     * spelling out a parse at the call site: doing the latter is how the codebase came to have three
     * different answers to "is this variable true" -- a strict `toBooleanStrictOrNull` that ignored `yes`,
     * a loose `toOptBool`, and an `equals("true")` that quietly read *every* other spelling, `yes` included,
     * as false. An operator cannot be expected to know which variable got which.
     */
    fun getEnvBool(def: EnvVarDef): Boolean? = getEnvVar(def)?.toOptBool()

    /**
     * Whether this is a **test instance** -- a node where test-only affordances are on: `forTestingOnly`
     * endpoints are exposed (issue #125), and email is simulated and captured by default (issue #158).
     *
     * An explicit [ACFG.isTestInstance] config entry **decides it**, either way. Otherwise, it is inferred:
     * true when the [testInstanceEnvVar] env var is set true, OR the environment is [ENV.unit], OR the
     * instance runs [ACFG.inMemoryOnly].
     *
     * The explicit form exists because the inference is a chain of ORs and so could only ever say *yes*.
     * Setting the env var false changed nothing -- a unit test is in [ENV.unit] and in memory, and either
     * alone re-asserts it -- which left the behavior of a real node untestable, since no test could obtain an
     * instance that was not a test instance. The override does not weaken anything: a node claiming to be a
     * test instance outside a `local`/`unit` environment still refuses to start (`SchemaService.checkInit`),
     * so this can turn affordances *off* in a test but never on where they do not belong.
     *
     * Resolved once and cached: the inputs are fixed by the time the instance boots, and a materialized value
     * is directly inspectable in a debugger while stepping (unlike a recomputed getter). The boot path
     * force-touches it via [warmDerived] so it is realized at a single-threaded point before any concurrent
     * request; off-boot paths (a hand-built config in a test) resolve it correctly on first access.
     */
    val isTestInstance: Boolean by lazy {
        when (val explicit = get(ACFG.isTestInstance)) {
            is Boolean -> explicit
            is String -> explicit.toBooleanStrictOrNull() ?: inferIsTestInstance()
            else -> inferIsTestInstance()
        }
    }

    /** The inferred answer, used when [ACFG.isTestInstance] says nothing. See [isTestInstance]. */
    private fun inferIsTestInstance(): Boolean =
        getEnvBool(testInstanceEnvVar) == true ||
            env == ENV.unit ||
            get(ACFG.inMemoryOnly) == true

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
        val testInstanceEnvVar = EnvVarDef(
            "KDR_TEST_INSTANCE", group = ENVGRP.application, defaultDoc = "unset (derived)",
            description = "Forces this to be a test instance, independent of environment -- exposing " +
                "`forTestingOnly` endpoints and simulating/capturing email by default. Also true implicitly " +
                "when `KDR_ENV=unit` or `inMemoryOnly` is on. A test instance outside `local`/`unit` refuses " +
                "to start, so test affordances cannot reach a real deployment.",
        )

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
        fun preBootLoadConfig(bootRole: String? = null): KdrInstanceConfig {
            // Resolved against the WORKSPACE, not the working directory (issue #380). A bare relative path
            // found the file only when the JVM happened to start in the workspace, which a Gradle `run` task
            // does not -- so a deployment's defaults were silently not applied, and a missing KDR_PORT fell
            // through to the built-in default. AppPaths already answers this for the secrets file and the H2
            // data file, and its walk-up from the working directory covers a launch started anywhere inside
            // the workspace, which its own KDoc names as the case it exists for.
            val fileDefaults = readDefaultEnvVars(AppPaths.resolve(defaultEnvVarsFileName), System::getenv)
            // Role-aware from the very first read: an edge may want its own KDR_EDGE_ENV, and the environment
            // name decides everything downstream, so it cannot be the one variable the role does not reach.
            val env = envVarNamesFor(envName.name, bootRole)
                .firstNotNullOfOrNull { System.getenv(it) ?: fileDefaults[it] }
                ?: ENV.local
            val config = KdrInstanceConfig(env, env, ENV.liveSource, bootRole)
            for ((k, v) in fileDefaults) {
                config.put(k, v)
            }
            return config
        }

        /** The environment variable naming the environment. Read at pre-boot, so via [EnvVarDef.name]. */
        val envName = EnvVarDef(
            "KDR_ENV", group = ENVGRP.application, defaultDoc = "`local`",
            description = "The environment name -- `local`, `unit`, `prod`. Drives environment-specific " +
                "behavior (whether the sample app loads, whether a database host is defaulted, and much more). " +
                "Read before the instance exists, so it is resolved at pre-boot rather than through an instance.",
        )

        /** The prefix every application environment variable carries. */
        const val envVarPrefix = "KDR_"

        /**
         * The names to try for [key], most specific first: under a boot role, `KDR_PORT` becomes
         * `KDR_EDGE_PORT` then `KDR_PORT`; with no role, just `KDR_PORT`.
         *
         * **No role means no prefixing at all**, which is what leaves every existing deployment bit-for-bit
         * unchanged -- this can only ever add a name that was not being read before.
         *
         * A key not carrying the [envVarPrefix] is left alone: it is not one of ours to namespace.
         */
        fun envVarNamesFor(key: String, bootRole: String?): List<String> {
            if (bootRole.isNullOrEmpty() || !key.startsWith(envVarPrefix)) {
                return listOf(key)
            }
            return listOf(envVarPrefix + bootRole.uppercase() + "_" + key.removePrefix(envVarPrefix), key)
        }

        /**
         * Reads the properties [file], returning only the entries whose key is NOT already a defined
         * environment variable per [getEnv] (the real environment always wins). Empty if the file is absent.
         * [getEnv] is injectable for testing; production passes `System::getenv`.
         */
        @KdrPrivate
        fun readDefaultEnvVars(file: File, getEnv: (String) -> String?): Map<String, String> {
            if (!file.isFile) {
                lastLoadReport = "no ${file.name} found at ${file.absolutePath}"
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
            lastLoadReport = "${result.size} of ${props.size} entries applied from ${file.absolutePath} " +
                "(any others are already set in the real environment, which wins)"
            return result
        }

        /**
         * What the last [readDefaultEnvVars] call did, for a launcher to log once at startup (issue #380).
         *
         * **The silence is what let the path bug hide.** An absent file and an empty one produced the same
         * empty map, so a deployment could not tell "my defaults applied" from "my defaults were never seen",
         * and the two look identical from outside until something behaves unexpectedly. Held rather than
         * logged here because this runs before logging is configured.
         */
        var lastLoadReport: String = "not loaded"
            @KdrPrivate set
    }
}
