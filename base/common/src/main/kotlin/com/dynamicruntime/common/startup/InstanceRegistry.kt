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

    // Resources to release when the JVM shuts down -- today the outbound HTTP clients (issue #420), which hold
    // threads. Drained by [ShutdownThread]; empty in a test that never creates one, so the suite pays nothing.
    private val shutdownCloseables = mutableListOf<AutoCloseable>()

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
     * Registers an [AutoCloseable] to be closed on the JVM shutdown hook. For a long-lived resource that holds
     * threads and is created lazily, so nothing but its owner has to remember to stop it (issue #420).
     */
    fun registerForShutdown(closeable: AutoCloseable) {
        synchronized(shutdownCloseables) { shutdownCloseables.add(closeable) }
    }

    /** Closes everything [registerForShutdown] collected. Called once by [ShutdownThread]; failures are swallowed. */
    fun runShutdownCloseables() {
        val toClose = synchronized(shutdownCloseables) { shutdownCloseables.toList() }
        toClose.forEach { runCatching { it.close() } }
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

            // What this node is, read once and threaded through every presence decision below (issue #433) --
            // the component gate, the service entries, and the schema the collector accepts.
            val node = NodeProfile.of(config)
            LogStartup.info(cxt, "Boot profile: $node.")

            // Components contribute schema into the collector; a startup service later compiles it.
            val collector = SchemaCollector(node)
            config.put(SchemaCollector.key, collector)

            val components = componentDefinitions.values.sortedBy { it.loadPriority() }
            // Decided once, then reused: `isLoaded` is a predicate and was being asked twice, which quietly
            // made it a place where an effect would run twice too.
            //
            // Presence first, then isLoaded: the declaration decides, and the predicate may only narrow
            // further. A component excluded by role or tag is never asked, so its `isLoaded` cannot resurrect
            // it -- which is what makes the declarations answerable by reading them.
            val loaded = components.filter { it.presence(cxt).admits(node) && it.isLoaded(cxt) }

            // Components contribute their own instance config before anything reads it (issue #386) -- ahead
            // of schema collection and of every service, including the startup tier that fixes node identity.
            for (component in loaded) {
                component.applyInstanceConfig(cxt)
            }
            // Fragment files are collected alongside schema and for the same reason: a component knows what it
            // ships, and nothing else can find out (the classpath is not enumerable here).
            val fragmentFiles = mutableListOf<String>()
            for (component in loaded) {
                component.addSchema(cxt, collector)
                fragmentFiles.addAll(component.fragmentFiles(cxt))
                // Same loop, so every config is present before any service binds -- which is what lets
                // SchemaService compile them, and #301 assemble over them, with nothing left to arrive.
                for (config in component.gedraConfigs(cxt)) {
                    collector.addGedraConfig(cxt, config)
                }
            }
            config.put(FRAG.registryKey, fragmentFiles.distinct())

            val startupEntries = mutableListOf<ServiceEntry>()
            val serviceEntries = mutableListOf<ServiceEntry>()
            for (component in loaded.filter { it.isActive(cxt) }) {
                startupEntries.addAll(component.startupServices(cxt))
                serviceEntries.addAll(component.services(cxt))
            }

            bindAndInitServices(cxt, node, startupEntries)
            bindAndInitServices(cxt, node, serviceEntries)

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
    fun bindAndInitServices(cxt: KdrCxt, node: NodeProfile, entries: List<ServiceEntry>) {
        val config = cxt.instanceConfig
        val names = mutableListOf<String>()
        for (entry in entries) {
            // Filtered before the factory runs, which is the reason presence is an entry rather than data on
            // the service: the registry has to construct one to read its `serviceName`, so a service excluded
            // here is never built at all rather than built and discarded.
            if (!entry.presence.admits(node)) {
                continue
            }
            val service = entry.factory()
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
