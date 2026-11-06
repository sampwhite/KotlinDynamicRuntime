package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.gedra.workflow.WfEntry

/**
 * The definitions a configuration holds, by the keys one configuration's definition stands over another's (issues
 * #945, #1634): traits by trait id, types by qualified name, cfacts by name, workflows by id -- and a creation or core
 * details workflow by its kind too, since a client has one of each -- reports by report id, file refs by ref id, and the
 * columns of the forms list as one set ([usages]).
 *
 * One statement of those rules for both places a definition stands over another: a client's own configuration over its
 * template's copies (`ClientExtension.clones`), and a stored entry marked `"overrides": "code"` over the client's code
 * (`CodeOverrides`). Each cuts what is stood over out of the configuration below ([cutBy]), whole, so the client holds
 * each definition once. Copy and interface are layered key by key instead, so they are never here.
 */
class ConfigKeys(
    val traits: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val cfacts: Set<String> = emptySet(),
    /** Each workflow's id, to its kind. */
    val workflows: Map<String, WfEntry> = emptyMap(),
    /** Whether the columns of the forms list are held: a configuration that says any says all of them. */
    val usages: Boolean = false,
    val reports: Set<String> = emptySet(),
    val fileRefs: Set<String> = emptySet(),
) {
    /** The creation and core details kinds among [workflows]: each stands over the other's of its kind, whatever its id. */
    val singletonKinds: Set<WfEntry> get() = workflows.values.filter { it in WfEntry.singletons }.toSet()

    val isEmpty: Boolean
        get() = traits.isEmpty() && types.isEmpty() && cfacts.isEmpty() && workflows.isEmpty() && !usages &&
            reports.isEmpty() && fileRefs.isEmpty()

    operator fun plus(other: ConfigKeys): ConfigKeys = ConfigKeys(
        traits + other.traits, types + other.types, cfacts + other.cfacts, workflows + other.workflows,
        usages || other.usages, reports + other.reports, fileRefs + other.fileRefs,
    )

    /** These, less what [other] holds by key (a workflow by its id). */
    operator fun minus(other: ConfigKeys): ConfigKeys = ConfigKeys(
        traits - other.traits, types - other.types, cfacts - other.cfacts, workflows - other.workflows.keys,
        usages && !other.usages, reports - other.reports, fileRefs - other.fileRefs,
    )

    /**
     * What of these stands over something [below] holds: the same key, a creation or core details workflow over the
     * one of its kind whatever its id, and columns over columns.
     */
    fun over(below: ConfigKeys): ConfigKeys {
        val kinds = below.singletonKinds
        return ConfigKeys(
            traits intersect below.traits,
            types intersect below.types,
            cfacts intersect below.cfacts,
            workflows.filter { (id, kind) -> id in below.workflows || kind in kinds },
            usages && below.usages,
            reports intersect below.reports,
            fileRefs intersect below.fileRefs,
        )
    }

    /** These without the file refs: what cuts a configuration whose refs a registry overrides by id instead. */
    fun withoutFileRefs(): ConfigKeys = ConfigKeys(traits, types, cfacts, workflows, usages, reports)

    /**
     * Each definition held, as a person names it: "trait 'note'", "workflow 'review'", "the columns of the forms list".
     * A trait's generated types are left out when [types] holds only them, since naming the trait says it.
     */
    fun described(generatedOf: (String) -> Set<String> = { emptySet() }): List<String> = buildList {
        traits.sorted().forEach { add("trait '$it'") }
        val generated = traits.flatMap(generatedOf).toSet()
        (types - generated).sorted().forEach { add("type '$it'") }
        cfacts.sorted().forEach { add("cfact '$it'") }
        workflows.keys.sorted().forEach { add("workflow '$it'") }
        if (usages) add("the columns of the forms list")
        reports.sorted().forEach { add("report '$it'") }
        fileRefs.sorted().forEach { add("file ref '$it'") }
    }

    override fun equals(other: Any?): Boolean =
        other is ConfigKeys && traits == other.traits && types == other.types && cfacts == other.cfacts &&
            workflows == other.workflows && usages == other.usages && reports == other.reports && fileRefs == other.fileRefs

    override fun hashCode(): Int = listOf(traits, types, cfacts, workflows, usages, reports, fileRefs).hashCode()

    override fun toString(): String = described().joinToString(", ")

    companion object {
        val none = ConfigKeys()

        /** Everything [config] holds. */
        fun of(config: GedraConfig): ConfigKeys = ConfigKeys(
            traits = config.traits.keys,
            types = config.defs.keys,
            cfacts = config.cfacts.map { it.name }.toSet(),
            workflows = config.workflows.values.associate { it.workflowId to it.entry },
            usages = config.usages.isNotEmpty(),
            reports = config.reports.keys,
            fileRefs = config.fileRefs.map { it.refId }.toSet(),
        )

        /** Everything [configs] hold together. */
        fun of(configs: Collection<GedraConfig>): ConfigKeys = configs.fold(none) { acc, c -> acc + of(c) }
    }
}

/**
 * This configuration without what [keys] stand over (issues #945, #1634): each definition [keys] hold by key, each
 * creation or core details workflow of a kind they hold, its columns when they hold columns, and the default parameters
 * of every workflow it loses -- a default is for the workflow it was declared beside. What remains is the
 * configuration's, unchanged, so a copy and the client's own configuration never both hold one definition.
 */
fun GedraConfig.cutBy(keys: ConfigKeys): GedraConfig {
    if (keys.isEmpty) return this
    val kinds = keys.singletonKinds
    val kept = workflows.filterValues { it.workflowId !in keys.workflows && it.entry !in kinds }
    val lost = workflows.keys - kept.keys
    return copy(
        traits = traits.filterKeys { it !in keys.traits },
        defs = defs.filterKeys { it !in keys.types },
        cfacts = cfacts.filter { it.name !in keys.cfacts },
        workflows = kept,
        usages = if (keys.usages) emptyList() else usages,
        reports = reports.filterKeys { it !in keys.reports },
        workflowParams = workflowParams.filterKeys { it !in lost },
        fileRefs = fileRefs.filter { it.refId !in keys.fileRefs },
    )
}
