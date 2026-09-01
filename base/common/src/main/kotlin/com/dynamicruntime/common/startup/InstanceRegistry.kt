package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockSource
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
 * Typical use is via a module boot helper (e.g., kdn's `Startup`): pass the components to
 * [getOrCreateInstanceConfig] to build the instance, then [createCxt] for a context bound to it. The component
 * set is a **parameter, not VM-global state** (issue #524) -- so a test builds an instance from exactly the
 * components it names, and one test's fixture component cannot leak into another's instance. Ported from dn's
 * `InstanceRegistry`, reflection-free (services are factories, not `Class` tokens).
 */
object InstanceRegistry {
    private val instanceConfigs = HashMap<String, KdrInstanceConfig>()
    private var shutdownHookInstalled = false

    // Resources to release when the JVM shuts down -- today the outbound HTTP clients (issue #420), which hold
    // threads. Drained by [ShutdownThread]; empty in a test that never creates one, so the suite pays nothing.
    private val shutdownCloseables = mutableListOf<AutoCloseable>()

    /**
     * Registers an [AutoCloseable] to be closed on the JVM shutdown hook, installing the hook on first use. For
     * a long-lived resource that holds threads and is created lazily, so nothing but its owner has to remember
     * to stop it (issue #420).
     *
     * The hook is installed here, lazily, rather than at a fixed boot step (issue #524): its only job is to
     * drain these closeables, so a run that registers none -- a unit test with no outbound client -- installs
     * no hook at all.
     */
    fun registerForShutdown(closeable: AutoCloseable) {
        synchronized(shutdownCloseables) {
            if (!shutdownHookInstalled) {
                shutdownHookInstalled = true
                Runtime.getRuntime().addShutdownHook(ShutdownThread())
            }
            shutdownCloseables.add(closeable)
        }
    }

    /** Closes everything [registerForShutdown] collected. Called once by [ShutdownThread]; failures are swallowed. */
    fun runShutdownCloseables() {
        val toClose = synchronized(shutdownCloseables) { shutdownCloseables.toList() }
        toClose.forEach { runCatching { it.close() } }
    }

    /**
     * Returns the instance config for [instanceName], creating and fully initializing
     * it (schema gathered and compiled, services created and initialized) on first
     * request from [components]. Subsequent calls return the cached config without re-initializing.
     *
     * [components] is used **only when the instance is first created** (issue #524) -- like [overlay], it is
     * ignored on a cache hit, because the instance is already built. So a reused [instanceName] returns the
     * earlier instance and its earlier component set, which is why the house rule is a unique name per test.
     */
    fun getOrCreateInstanceConfig(
        instanceName: String,
        components: List<ComponentDefinition>,
        overlay: Map<String, Any?> = emptyMap(),
    ): KdrInstanceConfig {
        synchronized(instanceConfigs) {
            instanceConfigs[instanceName]?.let { return it }

            val env = (overlay[ACFG.env] as? String) ?: System.getenv(KdrInstanceConfig.envName.name) ?: ENV.local
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

            val sortedComponents = components.sortedBy { it.loadPriority() }
            // Decided once, then reused: `isLoaded` is a predicate and was being asked twice, which quietly
            // made it a place where an effect would run twice too.
            //
            // Presence first, then isLoaded: the declaration decides, and the predicate may only narrow
            // further. A component excluded by role or tag is never asked, so its `isLoaded` cannot resurrect
            // it -- which is what makes the declarations answerable by reading them.
            val loaded = sortedComponents.filter { it.presence(cxt).admits(node) && it.isLoaded(cxt) }

            // Components contribute their own instance config before anything reads it (issue #386) -- ahead
            // of schema collection and of every service, including the startup tier that fixes node identity.
            for (component in loaded) {
                component.applyInstanceConfig(cxt)
            }
            // Fragment layers are collected alongside schema and for the same reason: a component knows what
            // it ships, and nothing else can find out (the classpath is not enumerable here).
            val fragmentSources = mutableListOf<FragmentSource>()
            val uiBlockSources = mutableListOf<UiBlockSource>()
            for (component in loaded) {
                component.addSchema(cxt, collector)
                fragmentSources.addAll(component.fragments(cxt))
                uiBlockSources.addAll(component.uiBlocks(cxt))
                // Same loop, so every config is present before any service binds -- which is what lets
                // SchemaService compile them, and #301 assemble over them, with nothing left to arrive.
                for (config in component.gedraConfigs(cxt)) {
                    // A config's fragment overlays travel with the config, so they are taken only when it is
                    // (issue #456) -- a bundle whose checks just failed must not still change what its
                    // client's people read.
                    if (collector.addGedraConfig(cxt, config)) {
                        fragmentSources.addAll(config.fragments)
                        uiBlockSources.addAll(config.uiBlocks)
                    }
                }
            }
            // Kept as declared, duplicates and all. Two components declaring the same file would once have
            // been collapsed by `.distinct()` on a list of file-id strings, and the equivalent for layers is a
            // trap: a layer's *content* is part of what it is, and no key over its other fields can see that
            // -- so two inline layers a component writes for one file, sharing the origin it naturally names
            // after itself, would be read as one statement and the second silently dropped.
            //
            // Nothing needs the dedupe. Folding a layer in twice is idempotent (the same keys are put over
            // themselves), the check derives its file list with its own `distinct()`, and a repeated resource
            // read happens once per boot.
            config.put(FRAG.registryKey, fragmentSources.toList())
            config.put(UIB.registryKey, uiBlockSources.toList())

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
