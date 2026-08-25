package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.exception.KdrException
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

    /** Folds a module's types, endpoints, and options providers into the collector. */
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

    fun addModule(module: SchModule) {
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
     */
    fun addGedraConfig(cxt: KdrCxt, config: GedraConfig) {
        if (!gedraConfigs.add(cxt, config)) {
            return
        }
        val client = config.gedraId.client
        if (client == GID.globalClient) {
            defs.putAll(config.defs)
        } else {
            clientOverlays.getOrPut(client) { LinkedHashMap() }.putAll(config.defs)
        }
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
