package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt

/**
 * A service is a named singleton, scoped to a running instance, that the
 * [InstanceRegistry] creates and initializes at startup and publishes into the
 * instance config under [serviceName] so other code can find it (by convention via
 * a static `get(cxt)` on the concrete service that reads that key).
 *
 * Initialization runs in three idempotent passes across all services of a tier --
 * [onCreate], then [checkInit], then [checkReady] -- so services with
 * interdependencies can rely on earlier passes having run for their peers, and a
 * service may force a dependency to initialize by calling its `checkInit` directly.
 * Each method is idempotent so those direct calls are safe. All three default to
 * no-op; a service overrides only the passes it needs.
 *
 * Services are registered as factories (a `() -> ServiceInitializer`, typically a
 * `::Ctor` reference) rather than as reflectively instantiated `Class` tokens --
 * the code guide's minimize-reflection rule. Whether a service is a *startup*
 * service (fully initialized before regular services) is decided by which list of
 * its owning [ComponentDefinition] it appears in, not by a marker type.
 */
interface ServiceInitializer {
    /** The key this service is published under in the instance config. */
    val serviceName: String

    /** Pass 1: construct-time setup; may contribute to the schema collector. */
    fun onCreate(cxt: KdrCxt) {}

    /**
     * Pass 2: idempotent initialization; may be triggered early by a dependent service. Leaves the service usable, and
     * starts nothing: a script boot (`BootStage.initialized`, issue #1289) ends here, and a script calls the service
     * directly.
     */
    fun checkInit(cxt: KdrCxt) {}

    /**
     * Pass 3: idempotent final check that the service is fully ready for use -- and where it **begins to serve**: starts
     * background work (the job scheduler), provisions data at boot (the conference demo), announces itself to the
     * deployment's other nodes, or warms what would otherwise load on first use. Only a serving node's boot
     * (`BootStage.ready`) runs it, so anything a script must not set off belongs here and not in [checkInit].
     */
    fun checkReady(cxt: KdrCxt) {}
}
