package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

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
 * The order of the layers is the order they are handed to the collector, and a later layer's overlays win: the
 * parent's source first, then the sandbox's own source **overlays**, then the parent's stored revisions -- every
 * source layer below every stored one, as for any client. A later layer's copy and menu overlays win by that order
 * (#916); a trait or workflow is held to its first definition, as within any client.
 *
 * **Source overlays** (issue #940) are configs source code files under the sandbox: a change for the sandbox only,
 * seen on the real deployment beside the real client without touching it -- in effect a per-client feature flag,
 * promoted by moving the code into the parent's source. They author into the parent's namespace, so that move
 * renames nothing; they are taken only for a parent whose definition asks for a sandbox, and a part of one that
 * repeats the parent's source ([overlayRepeats]) is reported, since a leftover from a promotion is how one goes
 * stale. A static parent's sandbox in production is its source and its overlays, with no stored layer.
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

    /**
     * What [overlay] repeats of the parent's source configuration [parentSource] (issue #940), each named as a person
     * reads it ("trait 'x'", "copy 'home: home.brand'"): an entry the parent's source defines identically, and for
     * copy a key whose value the parent's source sets the same. Compared as stored entries, which carry no client, so
     * the overlay's and the parent's line up although they are filed under different clients. A config that cannot
     * be written as entries (one declaring state or config traits, which no client's may) contributes nothing.
     */
    fun overlayRepeats(overlay: GedraConfig, parentSource: List<GedraConfig>): List<String> {
        val mine = entriesOrNull(overlay) ?: return emptyList()
        val theirs = parentSource.mapNotNull { entriesOrNull(it) }
        fun slotOf(slot: String) = theirs.flatMap { it[slot].orEmpty() }
        val out = mutableListOf<String>()
        for ((slot, entries) in mine) {
            if (slot == CCT.clientDef) continue
            if (slot == CCT.fragmentDef) {
                val parentCopy = slotOf(slot)
                for (entry in entries) {
                    val fileId = entry[CCT.fileId].toOptStr() ?: continue
                    val parentContent = parentCopy.filter { it[CCT.fileId].toOptStr() == fileId }.map { it[CCT.content].toJsonMapOrEmpty() }
                    for ((ns, keys) in entry[CCT.content].toJsonMapOrEmpty()) {
                        for ((key, value) in keys.toJsonMapOrEmpty()) {
                            if (parentContent.any { it[ns].toJsonMapOrEmpty()[key] == value }) out.add("copy '$fileId: $ns.$key'")
                        }
                    }
                }
                continue
            }
            val parentEntries = slotOf(slot)
            for (entry in entries) {
                if (entry in parentEntries) out.add(describe(slot, entry))
            }
        }
        return out
    }

    private fun entriesOrNull(config: GedraConfig): Map<String, List<Map<String, Any?>>>? =
        try {
            gedraConfigToEntries(config)
        } catch (_: KdrException) {
            null
        }

    /** An entry of [slot] as a person names it: its kind and its id. */
    private fun describe(slot: String, entry: Map<String, Any?>): String {
        val id = listOf(CCT.traitId, CCT.workflowId, CCT.reportId, CCT.typeName, CCT.blockId, CCT.name)
            .firstNotNullOfOrNull { entry[it].toOptStr() }
        val kind = when (slot) {
            CCT.traitDef -> "trait"
            CCT.workflowDef -> "workflow"
            CCT.reportDef -> "report"
            CCT.schemaDef -> "type"
            CCT.uiBlockDef -> "block"
            CCT.cfactDef -> "cfact"
            else -> slot
        }
        return if (id != null) "$kind '$id'" else kind
    }

    private fun FragmentSource.reboundTo(sandbox: String): FragmentSource = refiled(client = client?.let { sandbox })

    private fun UiBlockSource.reboundTo(sandbox: String): UiBlockSource = refiled(client = client?.let { sandbox })
}
