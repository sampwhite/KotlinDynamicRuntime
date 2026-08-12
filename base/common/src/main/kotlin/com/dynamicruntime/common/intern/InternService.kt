package com.dynamicruntime.common.intern

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.ServiceInitializer
import java.util.concurrent.ConcurrentHashMap

/**
 * The running instance's intern caches, and the one place that knows to settle them at the end of boot
 * (issue #280).
 *
 * It exists for two reasons beyond bookkeeping. The first is the boot sweep: a cache loaded from the database
 * during startup is entirely pending, so every lookup takes the slow path until enough of them have done so to
 * trigger a rebuild. Settling once when loading is over means the first real request already reads a settled
 * map. The second is scope. A cache has to belong to an instance rather than to the process — see
 * [InternCache] for why — and holding them here is what makes that concrete: they are reached through the
 * instance config like any other service, so two instances in one test run cannot answer each other's
 * existence questions.
 *
 * ### Ordering
 *
 * A regular service, so its [checkReady] runs after every service's [ServiceInitializer.onCreate] and
 * [ServiceInitializer.checkInit] — register and load a cache no later than `checkInit` and the sweep covers
 * it. A service that loads during its own `checkReady` is on its own, and should call [InternCache.internAll]
 * (which settles as it goes) or [rebuildAll] directly. Neither is a hardship: a rebuild is idempotent and
 * costs nothing when there is nothing pending.
 */
class InternService : ServiceInitializer {
    override val serviceName: String = Companion.serviceName

    private val caches = ConcurrentHashMap<String, InternCache<*>>()

    /**
     * Registers [cache] under its own name and returns it, so a caller can register and keep in one
     * expression. Registering the same instance twice is harmless; two different caches claiming one name is
     * a wiring mistake and says so.
     */
    fun <T : Internable> register(cache: InternCache<T>): InternCache<T> {
        val prior = caches.putIfAbsent(cache.name, cache)
        if (prior != null && prior !== cache) {
            throw KdrException(
                "Two different intern caches are registered as '${cache.name}'. " +
                    "A cache name identifies it within an instance, so it has to be unique.",
            )
        }
        return cache
    }

    /** The registered cache names, sorted — for diagnostics and for tests that assert the wiring. */
    fun cacheNames(): List<String> = caches.keys.sorted()

    /** A registered cache by name, or null. The caller knows the value type; this is for diagnostics. */
    fun cache(name: String): InternCache<*>? = caches[name]

    /** Settles every registered cache. Idempotent, and cheap for a cache with nothing pending. */
    fun rebuildAll() {
        for (cache in caches.values) {
            cache.rebuild()
        }
    }

    /** Pass 3: everything that was going to load has loaded, so settle the caches before traffic arrives. */
    override fun checkReady(cxt: KdrCxt) {
        rebuildAll()
    }

    companion object {
        const val serviceName: String = "InternService"

        /** Retrieves the intern service from the instance config, or null if absent. */
        fun get(cxt: KdrCxt): InternService? = cxt.instanceConfig.get(serviceName) as? InternService
    }
}
