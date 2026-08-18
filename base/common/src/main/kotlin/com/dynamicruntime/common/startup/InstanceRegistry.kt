package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.logging.LogStartup

/**
 * VM global registry that assembles running instances from components. Startup is
 * assumed single-threaded per instance, but the registry guards its shared maps so
 * concurrent callers see a consistent view; the maps use real `private` and
 * synchronization because enforcement genuinely matters here (a guide-sanctioned
 * exception to the minimize-`private` rule).
 *
 * Typical use is via a module boot helper (e.g., kdn's `Startup`): [register] the
 * components, then [getOrCreateInstanceConfig] to build the instance, then
 * [createCxt] for a context bound to it. Ported from dn's `InstanceRegistry`,
 * reflection-free (services are factories, not `Class` tokens).
 */
object InstanceRegistry {
    // VM-global: registered once, shared by every instance. Different instances may
    // later choose which of these is active.
    private val componentDefinitions = LinkedHashMap<String, ComponentDefinition>()
    private val instanceConfigs = HashMap<String, KdrInstanceConfig>()
    private var shutdownHookInstalled = false

    /**
     * Registers component definitions (idempotent by [ComponentDefinition.providerName])
     * and installs the JVM shutdown hook on first call. Call during VM startup.
     */
    fun register(components: List<ComponentDefinition>) {
        synchronized(componentDefinitions) {
            if (!shutdownHookInstalled) {
                shutdownHookInstalled = true
                Runtime.getRuntime().addShutdownHook(ShutdownThread())
            }
            for (component in components) {
                componentDefinitions.putIfAbsent(component.providerName, component)
            }
        }
    }

    /**
     * Returns the instance config for [instanceName], creating and fully initializing
     * it (schema gathered and compiled, services created and initialized) on first
     * request. Subsequent calls return the cached config without re-initializing.
     */
    fun getOrCreateInstanceConfig(
        instanceName: String,
        overlay: Map<String, Any?> = emptyMap(),
    ): KdrInstanceConfig {
        synchronized(instanceConfigs) {
            instanceConfigs[instanceName]?.let { return it }

            val env = (overlay[ACFG.env] as? String) ?: System.getenv("KDR_ENV") ?: ENV.local
            // The boot role rides in on the overlay (issue #377): the launcher put it there, and it has to
            // reach the instance config every request later reads its environment through.
            val config = KdrInstanceConfig(instanceName, env, ENV.liveSource, overlay[ACFG.bootRole] as? String)
            config.putAll(overlay)
            // Materialize lazily derived config (isTestInstance) now that env/overlay are settled and boot is
            // still single-threaded -- so it is computed before any request and visible when debugging.
            config.warmDerived()

            val cxt = KdrCxt("startup", config)
            LogStartup.info(cxt, "Initializing instance '$instanceName'.")

            // Components contribute schema into the collector; a startup service later compiles it.
            val collector = SchemaCollector()
            config.put(SchemaCollector.key, collector)

            val components = componentDefinitions.values.sortedBy { it.loadPriority() }
            // Fragment files are collected alongside schema and for the same reason: a component knows what it
            // ships, and nothing else can find out (the classpath is not enumerable here).
            val fragmentFiles = mutableListOf<String>()
            for (component in components) {
                if (component.isLoaded(cxt)) {
                    component.addSchema(cxt, collector)
                    fragmentFiles.addAll(component.fragmentFiles(cxt))
                    // Same loop, so every config is present before any service binds -- which is what lets
                    // SchemaService compile them, and #301 assemble over them, with nothing left to arrive.
                    for (config in component.gedraConfigs(cxt)) {
                        collector.addGedraConfig(cxt, config)
                    }
                }
            }
            config.put(FRAG.registryKey, fragmentFiles.distinct())

            val startupFactories = mutableListOf<() -> ServiceInitializer>()
            val serviceFactories = mutableListOf<() -> ServiceInitializer>()
            for (component in components) {
                if (component.isLoaded(cxt) && component.isActive(cxt)) {
                    startupFactories.addAll(component.startupServices(cxt))
                    serviceFactories.addAll(component.services(cxt))
                }
            }

            bindAndInitServices(cxt, startupFactories)
            bindAndInitServices(cxt, serviceFactories)

            instanceConfigs[instanceName] = config
            return config
        }
    }

    /**
     * Creates each service from its factory and publishes it into the instance config
     * under its [ServiceInitializer.serviceName] (a later factory with the same name
     * replaces an earlier one -- useful for tests), then runs the three idempotent
     * lifecycle passes across the registered set.
     */
    fun bindAndInitServices(cxt: KdrCxt, factories: List<() -> ServiceInitializer>) {
        val config = cxt.instanceConfig
        val names = mutableListOf<String>()
        for (factory in factories) {
            val service = factory()
            val name = service.serviceName
            if (config.get(name) == null) {
                names.add(name)
            }
            config.put(name, service)
        }

        // Resolve the finally registered service per name, so a replacement wins.
        val services = names.map { config.get(it) as ServiceInitializer }
        for (service in services) service.onCreate(cxt)
        for (service in services) service.checkInit(cxt)
        for (service in services) service.checkReady(cxt)
    }

    /** Creates a top-level context bound to the given instance config. */
    fun createCxt(cxtName: String, config: KdrInstanceConfig): KdrCxt = KdrCxt(cxtName, config)
}
