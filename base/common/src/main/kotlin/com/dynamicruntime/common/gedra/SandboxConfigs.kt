package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.uiblock.UiBlockSource

/**
 * How a **sandbox** client's configuration is made from its parent's (issue #928, the Shadow Sandbox #925).
 *
 * A sandbox owns no configuration of its own. It runs its parent's: the parent's source configuration and the
 * parent's **latest** stored revisions, whatever tier the parent consumes at, each copied under the sandbox's id
 * ([sandboxOf]). The copies then go through the very collector, client checks and reload phases every client's
 * configuration does, so the schema variant, endpoint copies, cfacts, overlays, and workflows a sandbox serves are
 * built by the existing machinery rather than a second path beside it.
 *
 * A copy keeps what it rebinds to a minimum: its id's client, the client of its fragment and UiBlock overlays
 * (the two nested pieces that name a client of their own), and -- in the one config that defines the parent --
 * the definition, which becomes [deriveDef]'s. Everything else is scoped by the config's id, and so moves with
 * it. The namespace is the parent's, deliberately: the sandbox shows what the parent will run, and a sandbox
 * shares its parent's namespaces rather than claiming any (see `GedraConfigCollector`), which also keeps a colon
 * out of every type name.
 *
 * The order of the layers is the order they are handed to the collector, and a later layer's definitions win:
 * the parent's source first, then the sandbox's own source overlays (issue #940, none yet), then the parent's
 * stored revisions -- every source layer below every stored one, as for any client.
 *
 * **A parent that extends a template** (issue #945): the parent's copies of the template are not carried over --
 * they are the loader's, not the parent's source, so they are left out with its stored configs. The derived
 * definition keeps `extendsFromClientId`, and the sandbox is extended like any client, its copy of the template
 * made against **its** configuration. That matters because the sandbox runs the parent's drafts: a draft redefining
 * a template trait the published configuration does not would otherwise sit beside a carried copy still holding it.
 */
object SandboxConfigs {
    /**
     * The definition a sandbox of [parent] runs under: the parent's, with what makes it a different client
     * changed. It is a `dev`, `internal` client whatever the parent is -- somewhere to try things, never a
     * customer's production -- and it drops the parent's routing (a domain prefix or custom domain routes to the
     * parent, never to two clients), its static lock (a sandbox's configuration is never its own to lock), and
     * its own `sandbox` flag (a sandbox has none). What its people see and may use -- included traits, user
     * labels, web resources, test features, environments -- stays the parent's.
     */
    fun deriveDef(parent: ClientDef): ClientDef = parent.copy(
        clientId = sandboxOf(parent.clientId),
        name = "${parent.name.ifEmpty { parent.clientId }} (sandbox)",
        description = "The sandbox of '${parent.clientId}': its latest configuration, with users and data of its own.",
        usageType = ClientUsageType.dev,
        audience = ClientAudience.internal,
        domainPrefix = null,
        customDomain = null,
        preload = false,
        staticConfig = false,
        sandbox = false,
    )

    /** [config], one of the parent's, as the sandbox's copy of it: see the class note for what is rebound. */
    fun rebind(config: GedraConfig): GedraConfig {
        val sandbox = sandboxOf(config.gedraId.client)
        return GedraConfig(
            gedraId = GedraId.of(config.gedraId.kind, sandbox, config.gedraId.baseId, config.gedraId.suffix),
            namespace = config.namespace,
            traits = config.traits,
            stateTraits = config.stateTraits,
            configTraits = config.configTraits,
            defs = config.defs,
            client = config.client?.let { deriveDef(it) },
            cfacts = config.cfacts,
            fragments = config.fragments.map { it.reboundTo(sandbox) },
            uiBlocks = config.uiBlocks.map { it.reboundTo(sandbox) },
            workflows = config.workflows,
            usages = config.usages,
            reports = config.reports,
            unreadReports = config.unreadReports,
            origin = config.origin,
            inheritedFrom = config.inheritedFrom,
        )
    }

    /**
     * The sandbox's configuration, in layer order: [parentSource] (the parent's source configs), then
     * [sandboxSource] (the sandbox's own source overlays, issue #940), then [parentStored] (the parent's latest
     * stored revisions). The parent's are rebound; the sandbox's own are already filed under it.
     */
    fun configsFor(
        parentSource: List<GedraConfig>,
        parentStored: List<GedraConfig>,
        sandboxSource: List<GedraConfig> = emptyList(),
    ): List<GedraConfig> = parentSource.map { rebind(it) } + sandboxSource + parentStored.map { rebind(it) }

    /**
     * Whether [parent] has a sandbox by the definition it runs -- the one in [configs] its own configuration
     * declares. A client with no definition there (retired, or never defined) has none.
     */
    fun hasSandbox(configs: GedraConfigCollector, parent: String): Boolean =
        configs.configs.firstOrNull { it.gedraId.client == parent && it.client != null }?.client?.sandbox == true

    /**
     * The parents in [configs] that have a sandbox by the definition they run. A sandbox itself never has one: its
     * definition is derived with the flag off.
     */
    fun parentsWithSandboxes(configs: GedraConfigCollector): List<String> =
        configs.configs.mapNotNull { it.client }.filter { it.sandbox && !isSandboxClient(it.clientId) }
            .map { it.clientId }.distinct()

    private fun FragmentSource.reboundTo(sandbox: String): FragmentSource = refiled(client = client?.let { sandbox })

    private fun UiBlockSource.reboundTo(sandbox: String): UiBlockSource = refiled(client = client?.let { sandbox })
}
