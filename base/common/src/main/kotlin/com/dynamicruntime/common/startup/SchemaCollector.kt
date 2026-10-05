package com.dynamicruntime.common.startup

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.cfact.CFactSource
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.naming.componentNamespaceProblem
import com.dynamicruntime.common.naming.declaredTypeProblem
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.componentNameProblem
import com.dynamicruntime.common.gedra.GedraDataDeriver
import com.dynamicruntime.common.gedra.GedraPrepForSaveFn
import com.dynamicruntime.common.gedra.GedraStateDeriver
import com.dynamicruntime.common.gedra.GedraWriteGuard
import com.dynamicruntime.common.gedra.GedraWriteHook
import com.dynamicruntime.common.gedra.workflow.WfFunctionCreation
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.schema.SchOptionsProvider
import com.dynamicruntime.common.sql.KdrTable

/**
 * Gathers schema during startup, before it is compiled. Each
 * [ComponentDefinition.addSchema] (and, later, a startup service's `onCreate`)
 * contributes [SchModule]s here; [SchemaService] then compiles the accumulated
 * [defs] into resolved types and indexes the [endpoints] into the read-only
 * [com.dynamicruntime.common.context.KdrSchemaStore].
 *
 * Created early by the [InstanceRegistry] and stashed in the instance config under
 * [key] so any contributor reached during startup can add to it. This is kd2's
 * take on dn's `DnRawSchemaStore`; named for its job (collecting contributions)
 * rather than for the "raw" state of the data it holds.
 */
class SchemaCollector(
    /**
     * What this node is, so a contribution can be declared for some nodes and skipped on the rest
     * (issue #433). Defaults to an ordinary application carrying no tags, which is what a test building a
     * collector by hand wants and what every node was before presence existed.
     */
    val node: NodeProfile = NodeProfile(BOOT.app, emptySet()),
) {
    /** Merged `$defs` contents across all contributed modules, keyed by qualified type name. */
    val defs: MutableMap<String, Any?> = LinkedHashMap()

    /** Every contributed endpoint, in contribution order. */
    val endpoints: MutableList<KdrEndpoint> = mutableListOf()

    /** Every contributed table definition, in contribution order. */
    val tables: MutableList<KdrTable> = mutableListOf()

    /**
     * The Gedra config bundles components contributed, and the checks over them (issue #299).
     *
     * Its own collector rather than three more fields here: taking a config involves checking it against
     * every config already taken, which is logic rather than accumulation, and this class is deliberately the
     * latter.
     */
    val gedraConfigs: GedraConfigCollector = GedraConfigCollector()

    /**
     * Callbacks that produce a choice list when the schema is rendered, keyed by the id a
     * `g-optionsSource` names (issue #413).
     *
     * Accumulated here beside [defs] and [endpoints] because a provider is contributed the same way and at
     * the same moment they are, and because [SchemaService] then holds the compiled document and the full
     * registration set together -- which is the only point at which "every id names a provider" can be
     * asked at all.
     */
    val optionsProviders: MutableMap<String, SchOptionsProvider> = LinkedHashMap()

    /**
     * Registers an options provider under [id], refusing a second one.
     *
     * The issue leaves uniqueness to the registrant, and this is what makes that hold: last-write-wins would
     * mean one component silently answering for another's attribute, visible only as a wrong list on a page
     * nobody connected to the component that took the id.
     */
    fun addOptionsProvider(id: String, provider: SchOptionsProvider) {
        if (optionsProviders.containsKey(id)) {
            throw KdrException(
                "Two options providers are registered under '$id'. The id has to be unique across every " +
                    "component on this node -- rename one of them.",
            )
        }
        optionsProviders[id] = provider
    }

    /**
     * The cfacts components declared, keyed by name (issue #455), and [cfactSources], what decides each.
     *
     * Two maps rather than one because they come from two places: a **component** declares a name and the
     * Kotlin that makes it true, while a **client** declares only a name -- its config is data, and data has
     * nowhere to put a computation. So sources are global by construction.
     */
    val cfacts: MutableMap<String, CFactDef> = LinkedHashMap()

    /** What decides each declared cfact for a request, keyed by name; a subset of [cfacts]. */
    val cfactSources: MutableMap<String, CFactSource> = LinkedHashMap()

    /**
     * The state derivations components registered (issue #599) -- functions that compute a gedra's derived
     * state from its data, run on create/import. Kotlin, so component-contributed, like a [CFactSource]; the
     * state traits they fill are declared separately (as data) through `gedraConfig { stateTrait(...) }`.
     */
    val stateDerivers: MutableList<GedraStateDeriver> = mutableListOf()

    /** Registers a state derivation (issue #599); order is preserved but does not matter, as each owns its own traits. */
    fun addStateDeriver(deriver: GedraStateDeriver) {
        stateDerivers.add(deriver)
    }

    /**
     * The data derivations components registered (issue #712) -- functions that compute a gedra entry's derived
     * data value **on read**, so a `g-derived` data field (an expense report's total, say) is presented without
     * being stored. Kotlin, so component-contributed, like a [GedraStateDeriver]; the twin that runs on write.
     */
    val dataDerivers: MutableList<GedraDataDeriver> = mutableListOf()

    /** Registers a data derivation (issue #712); order is preserved but does not matter, as each owns its own trait. */
    fun addDataDeriver(deriver: GedraDataDeriver) {
        dataDerivers.add(deriver)
    }

    /**
     * The trait save-time functions components registered (issue #728) -- run at the entry to a create or update
     * before the write transaction, to calculate or validate a trait's data. Kotlin, so component-contributed,
     * like a [GedraDataDeriver]; the first of a growing set of trait event functions.
     */
    val prepForSaveFns: MutableList<GedraPrepForSaveFn> = mutableListOf()

    /** Registers a trait save-time function (issue #728); order is preserved but does not matter, as each owns its trait. */
    fun addPrepForSaveFn(fn: GedraPrepForSaveFn) {
        prepForSaveFns.add(fn)
    }

    /**
     * The batch-job types components registered (issue #869). Kotlin, so component-contributed, like a
     * [GedraStateDeriver]; `JobService` runs them.
     */
    val jobs: MutableList<JobDef> = mutableListOf()

    /** Registers a batch-job type (issue #869); a type registered twice fails the boot. */
    fun addJob(def: JobDef) {
        jobs.add(def)
    }

    /** The post-write hooks (issue #675), fired after every gedra data write inside its transaction. */
    val writeHooks: MutableList<GedraWriteHook> = mutableListOf()

    /** Registers a post-write hook (issue #675); order is preserved and matters -- hooks run in registration order. */
    fun addWriteHook(hook: GedraWriteHook) {
        writeHooks.add(hook)
    }

    /** The write guards (issue #857), run under a patch's lock before its edits; any may refuse it. */
    val writeGuards: MutableList<GedraWriteGuard> = mutableListOf()

    /** Registers a write guard (issue #857); each runs, in registration order, on every patch of an existing gedra. */
    fun addWriteGuard(guard: GedraWriteGuard) {
        writeGuards.add(guard)
    }

    /**
     * The workflow function kinds components registered (issue #677) -- the Kotlin `create` half of a function,
     * keyed by `fn`. `WorkflowService` organizes these by event and resolves them onto workflow definitions in a
     * second pass; the *usages* that name them are data on the definitions.
     */
    val workflowFunctions: MutableList<WfFunctionCreation> = mutableListOf()

    /** Registers a workflow function kind (issue #677), the same seam a deriver or a cfact source registers through. */
    fun addWorkflowFunction(creation: WfFunctionCreation) {
        workflowFunctions.add(creation)
    }

    /**
     * The cfacts each client's own configs declared, in arrival order (issue #455).
     *
     * A list rather than a map so a client declaring one name twice is *reported* rather than silently
     * collapsed, and held apart from [cfacts] for the reason [clientOverlays] is held apart from [defs]:
     * folding them in would give every other client a name only this one declared.
     */
    val clientCFacts: MutableMap<String, MutableList<CFactDef>> = LinkedHashMap()

    /**
     * Declares [def], optionally with the [source] that decides it, refusing a second declaration of the name.
     *
     * Refused rather than overwritten for the reason [addOptionsProvider] gives, and one more: a cfact is
     * matched by *name*, so a second declaration would not shadow the first, it would silently answer for it
     * everywhere the first is written -- visible only as something shown to the wrong people.
     *
     * A component's cfact is a global name, so it is rooted under the component's owner root (issue #952) -- core's
     * `kdr:loggedIn` -- and a client's own, always bare, can never take it. A cfact under another owner's root names
     * that root in [contributesTo], declaration by declaration: provisional code meant for core declares `kdr:` names
     * with `contributesTo = OWNR.kdrRoot`, and keeps them when it is promoted. Judged like a namespace (issue #950):
     * a problem refuses the boot outside production, and is logged and the cfact taken as declared in production.
     */
    fun addCFact(def: CFactDef, contributesTo: String? = null, source: CFactSource? = null) {
        contributor?.let { c ->
            componentNameProblem(OwnedNameKind.cfact, def.name, c.ownerRoot, contributesTo)?.let { problem ->
                reportConfigProblem(
                    c.cxt,
                    GedraConfigIssue(
                        "Component '${c.name}' declares the cfact '${def.name}': $problem", "Taking it as declared.",
                        client = GID.globalClient,
                    ),
                    gedraConfigs.issues,
                )
            }
        }
        putCFact(def, source)
    }

    /** Declares [def] with no owner check: a global config's cfacts, judged with the rest of the config. */
    private fun putCFact(def: CFactDef, source: CFactSource? = null) {
        val existing = cfacts[def.name]
        if (existing != null) {
            throw KdrException(
                "The cfact '${def.name}' is declared twice, in groups '${existing.group}' and '${def.group}'. " +
                    "A cfact name is unique across every component on this node -- rename one of them.",
            )
        }
        cfacts[def.name] = def
        if (source != null) {
            cfactSources[def.name] = source
        }
    }

    /**
     * Contributes [module] only when this node is admitted by [presence] (issue #433).
     *
     * The filter is here rather than at the call site so that "which nodes get this?" stays a **declaration**
     * next to the contribution, readable without executing it. An `if` around the call would work identically
     * and answer nothing: the point of the axis is that a reviewer can ask what a consumer node contains
     * without running a boot for every profile.
     *
     * Dropping a module drops its endpoints *and* its types, together, which is what makes this the right
     * granularity for a surface an edge should not have. A node without the auth module has no `/auth`
     * endpoints to serve and no auth types advertised in its catalog.
     */
    fun addModule(module: SchModule, presence: Presence) {
        if (presence.admits(node)) {
            addModule(module)
        }
    }

    /** Contributes [tables] only when this node is admitted by [presence] (issue #433). */
    fun addTables(tables: List<KdrTable>, presence: Presence) {
        if (presence.admits(node)) {
            addTables(tables)
        }
    }

    /**
     * The component whose contributions are being collected, while the boot has one in hand (issue #950): its
     * name, for a message, and its owner root, which every namespace it contributes is judged by.
     */
    private class Contributor(val cxt: KdrCxt, val name: String, val ownerRoot: String?)

    private var contributor: Contributor? = null

    /**
     * Runs [block] -- [component]'s schema and config contributions -- with the component in hand, so each module and
     * global config it adds is held to its owner root and to "a type is declared once" (issue #950). Outside this,
     * as in a test building a collector by hand, nothing is attributed to a component and neither is checked.
     */
    fun <T> contributingAs(cxt: KdrCxt, component: ComponentDefinition, block: () -> T): T {
        val prior = contributor
        contributor = Contributor(cxt, component.providerName, component.ownerRoot)
        try {
            return block()
        } finally {
            contributor = prior
        }
    }

    /**
     * Holds a contribution in [namespace] to its component's owner root (issue #950): the namespace itself, and every
     * type it [declared]. A problem is a source-config problem -- it refuses the boot outside production, and is
     * logged and the contribution taken as declared in production.
     */
    private fun checkOwnership(namespace: String, contributesTo: String?, declared: Collection<String>, what: String) {
        val c = contributor ?: return
        val problems = listOfNotNull(componentNamespaceProblem(namespace, c.ownerRoot, contributesTo)) +
            declared.mapNotNull { declaredTypeProblem(it, namespace) }
        for (problem in problems) {
            reportConfigProblem(
                c.cxt,
                GedraConfigIssue("Component '${c.name}' contributes $what: $problem", "Taking it as declared.", client = GID.globalClient),
                gedraConfigs.issues,
            )
        }
    }

    /**
     * Refuses a type [declared] again by [what] (issue #950). Declarations **add**: a second one of a name would
     * otherwise replace the first by load order, which nobody chose. Refused outright, as a cfact or an options
     * provider declared twice is. Only within a component's contributions, which is where the boot adds them.
     */
    private fun refuseRedeclared(declared: Collection<String>, what: String) {
        val c = contributor ?: return
        val twice = declared.firstOrNull { it in defs } ?: return
        throw KdrException(
            "The type '$twice' is declared twice: again by $what, from component '${c.name}'. A type name is unique " +
                "across every component on this node -- declarations add, and none replaces another.",
        )
    }

    /** Folds a module's types, endpoints, and options providers into the collector. */
    fun addModule(module: SchModule) {
        module.namespace?.let { ns ->
            refuseRedeclared(module.defs.keys, "the module '$ns'")
            checkOwnership(ns, module.contributesTo, module.defs.keys, "the module '$ns'")
        }
        defs.putAll(module.defs)
        endpoints.addAll(module.endpoints)
        // Through the checked add, so a duplicate is refused whichever route a provider arrives by.
        module.optionsProviders.forEach { (id, provider) -> addOptionsProvider(id, provider) }
    }

    /**
     * Definitions contributed by a **client's own** configs, keyed by client and then by qualified type name
     * (issue #356).
     *
     * Held apart from [defs] rather than merged into it, and the separation is the whole point: a client
     * altering a type declares it under the name it is altering, so folding those into the shared document
     * would change that type **for everybody**. Here they are the client's overlay, applied to a copy of the
     * document when that client's variant is built, and invisible to every other client.
     *
     * A name the global document does not have is an ordinary new type for that client; a name it does have
     * is an alteration, and is held to the narrowing rules. Both arrive the same way, which is why the
     * distinction is drawn where the overlay is applied rather than where it is declared.
     */
    val clientOverlays: MutableMap<String, MutableMap<String, Any?>> = LinkedHashMap()

    /**
     * Takes a Gedra config bundle, checking it against the ones already taken, and folds the entry types its
     * traits generated in so they compile with everything else. A config that fails a check is dropped rather
     * than folded in -- outside production the check throws before reaching here.
     *
     * **Where they are folded depends on who owns the config.** A `global` config contributes to the shared
     * document; a client's own contributes to that client's [clientOverlays]. The config's id carries the
     * owner, so nothing has to say it twice.
     *
     * Returns whether the config was taken, which the boot needs (issue #456): a config's fragment overlays
     * are collected outside this class, and folding in the overlays of a config whose checks just failed
     * would let a rejected bundle change what people read.
     */
    /**
     * A **scratch copy** for a trial (issue #843): the same collected state, in collections of its own, so a
     * trial can withdraw and add one client's configs and run the load's checks over the result without touching
     * what the node serves. Registries of code (providers, functions, derivers, hooks) are shared, not copied --
     * a trial never changes them.
     */
    fun trialCopy(): SchemaCollector = SchemaCollector(node).also { c ->
        c.defs.putAll(defs)
        c.endpoints.addAll(endpoints)
        c.tables.addAll(tables)
        c.gedraConfigs.absorbAll(gedraConfigs)
        c.optionsProviders.putAll(optionsProviders)
        c.cfacts.putAll(cfacts)
        c.cfactSources.putAll(cfactSources)
        c.workflowFunctions.addAll(workflowFunctions)
        for ((client, list) in clientCFacts) c.clientCFacts[client] = list.toMutableList()
        for ((client, overlay) in clientOverlays) c.clientOverlays[client] = LinkedHashMap(overlay)
    }

    fun addGedraConfig(cxt: KdrCxt, config: GedraConfig): Boolean {
        if (config.gedraId.client == GID.globalClient) {
            refuseRedeclared(config.defs.keys, "the config '${config.gedraId}'")
            checkOwnership(config.namespace, config.contributesTo, config.defs.keys, "the config '${config.gedraId}'")
        }
        if (!gedraConfigs.add(cxt, config)) {
            return false
        }
        val client = config.gedraId.client
        if (client == GID.globalClient) {
            defs.putAll(config.defs)
            // A global config's cfacts are declarations like a component's, and go through the same checked
            // add -- a name is unique whichever route it arrives by.
            config.cfacts.forEach { putCFact(it) }
        } else {
            clientOverlays.getOrPut(client) { LinkedHashMap() }.putAll(config.defs)
            // Collected, not checked: whether a client may take this name depends on what every other
            // contributor declared, which is not known until the registries are built.
            if (config.cfacts.isNotEmpty()) {
                clientCFacts.getOrPut(client) { mutableListOf() }.addAll(config.cfacts)
            }
        }
        return true
    }

    /**
     * Withdraws [config] -- the reverse of [addGedraConfig], for replacing a client's stored configuration on a
     * running node (issue #616). Only a non-global config is ever withdrawn: a global one is a component's,
     * declared in source, and never reloaded.
     *
     * The client's overlay map and cfact list are **rebuilt** from the configs it still holds, in the order they
     * were added, rather than having the withdrawn config's names removed (issue #1014). Two configs of one client
     * may declare one type name -- the later one's body is the one held, as [addGedraConfig]'s fold makes it -- and
     * removing by name took the other's declaration with it: withdrawing a stored config left the client without a
     * type its source config still declares.
     */
    fun removeGedraConfig(config: GedraConfig): Boolean {
        if (!gedraConfigs.remove(config)) {
            return false
        }
        val client = config.gedraId.client
        if (client != GID.globalClient) {
            val remaining = gedraConfigs.configs.filter { it.gedraId.client == client }
            val overlay = LinkedHashMap<String, Any?>()
            remaining.forEach { overlay.putAll(it.defs) }
            if (overlay.isEmpty()) clientOverlays.remove(client) else clientOverlays[client] = overlay
            val cfacts = remaining.flatMap { it.cfacts }
            if (cfacts.isEmpty()) clientCFacts.remove(client) else clientCFacts[client] = cfacts.toMutableList()
        }
        return true
    }

    /** Adds contributed table definitions (from a `tableModule`) into the collector. */
    fun addTables(tables: List<KdrTable>) {
        this.tables.addAll(tables)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Instance-config key under which the collector is published during startup. */
        const val key = "SchemaCollector"

        /** Retrieves the collector from the instance config, or null if not present. */
        fun get(cxt: KdrCxt): SchemaCollector? = cxt.instanceConfig.get(key) as? SchemaCollector
    }
}
