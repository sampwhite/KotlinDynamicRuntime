package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup

/** Names and modes for the Gedra config checks (issue #299). */
@Suppress("ConstPropertyName")
object GCFG {
    /**
     * The namespace component-contributed definitions live in, owned by [GID.globalClient].
     *
     * Reserved as *an owner claiming a namespace* rather than as one name special-cased, because that is the
     * shape the rule has to have when clients define their own config: a namespace belongs to exactly one
     * owner, and a config owned by client C may only reach into namespaces owned by C or by `global`. This is
     * simply the first claim.
     */
    const val globalNamespace = "globalconfig"

    /** Overrides what a config problem does at startup; see [gedraConfigCheckMode]. */
    val checkEnvVar = EnvVarDef(
        "KDR_GEDRA_CONFIG_CHECK", group = ENVGRP.gedra, defaultDoc = "strict everywhere but `prod`",
        description = "What a Gedra config problem does at startup: `strict` (refuse to boot), `warn`, or " +
            "`off`. Unset means strict everywhere except `prod`.",
    )

    /** Refuse to boot. */
    const val strict = "strict"

    /** Log at error, degrade, and carry on. */
    const val warn = "warn"

    /** Do not check at all. */
    const val off = "off"
}

/**
 * What a Gedra config problem does at startup: an explicit [GCFG.checkEnvVar] decides it, and otherwise it is
 * [GCFG.strict] everywhere except [ENV.prod], where it is [GCFG.warn].
 *
 * The paradigm #296 established for Markdown fragments, applied to the same class of problem: **a
 * configuration defect degrades in production and refuses everywhere else.** Silence while the author is
 * still at the keyboard is how a defect reaches production; refusing to boot a production node over one bad
 * trait takes down every endpoint that had nothing to do with it.
 *
 * The boundary matters and is narrow. This degrades because a duplicated trait is a defect *on the side* —
 * one trait is wrong and the rest of the instance is fine. A security fence never degrades (see
 * `SchemaService.checkInit` refusing a test instance outside `local`/`unit`), and neither does a
 * misconfiguration that leaves the node unable to do its job (see `SqlSchemaDrift`, which refuses in
 * production too, because a table that cannot be written to is the second case rather than the first).
 *
 * Keyed on the **environment**, never on `isTestInstance`: that flag is inferred from in-memory-ness and the
 * unit environment, so an ordinary local run against a real database is not a test instance, and keying on it
 * would hand a developer production behavior on their own machine.
 *
 * This is the third hand-rolled mode resolver, after `MarkdownFragmentService.fragmentCheckMode` and
 * `SqlSchemaDrift.isDriftAllowed` — which is what #303 exists to retire.
 */
fun gedraConfigCheckMode(cxt: KdrCxt): String {
    val explicit = cxt.getEnvVar(GCFG.checkEnvVar)?.trim()?.lowercase()
    if (explicit == GCFG.strict || explicit == GCFG.warn || explicit == GCFG.off) {
        return explicit
    }
    return if (cxt.instanceConfig.env == ENV.prod) GCFG.warn else GCFG.strict
}

/** A problem found while collecting configs, and what was done about it. */
class GedraConfigIssue(
    /** What is wrong, as a complete sentence naming both sides. */
    val message: String,
    /** What was kept, and what was dropped, when the instance carried on regardless. */
    val degradedTo: String,
)

/**
 * Does to [problem] what [mode] says to do: refuse the boot, or log it, record it and carry on.
 *
 * The one place that turns a check's finding into a consequence, so the two sets of config checks -- the ones
 * over configs as they arrive, and `checkClientDefs` over the clients they declared -- cannot come to differ
 * about what "strict" and "warn" mean. Only *what is dropped* differs between them, and that is each caller's
 * to decide: this is told what happened, not what to keep.
 *
 * Logged at **error** rather than warn, whichever check found it. A dropped definition is not a caveat; it is
 * something the deployment believes it has and does not.
 */
fun reportConfigProblem(
    cxt: KdrCxt,
    mode: String,
    problem: GedraConfigIssue,
    issues: MutableList<GedraConfigIssue>,
) {
    if (mode == GCFG.strict) {
        throw KdrException(
            "${problem.message} Fix it, or set ${GCFG.checkEnvVar}=${GCFG.warn} to start anyway " +
                "(which is the default in ${ENV.prod}).",
        )
    }
    LogStartup.error(cxt, "${problem.message} ${problem.degradedTo}")
    issues.add(problem)
}

/**
 * Gathers the [GedraConfig]s components contribute, and refuses the ones that would make the set incoherent
 * (issue #299).
 *
 * Checked as each config arrives rather than in one pass afterward, for a reason worth keeping: the arriving
 * config is the one being rejected, and the one already held is the one being kept — so "first contributor
 * wins" falls out of arrival order instead of being imposed, and it is stable across restarts because
 * component load order is (`loadPriority`, then registration).
 *
 * What happens to a problem depends on where this runs; see [gedraConfigCheckMode]. Outside production a
 * problem refuses the boot. In production, it is logged at error, the loser is dropped, and the instance
 * carries on — which is only survivable because the manufactured union declares a default branch (#301), so
 * entries carrying a dropped trait fall through as unrecognized rather than failing validation. Removing that
 * default branch would quietly make this check unsafe.
 */
class GedraConfigCollector {
    private val configsById = LinkedHashMap<String, GedraConfig>()
    private val traitOwners = LinkedHashMap<String, GedraTrait>()
    private val traitConfigs = LinkedHashMap<String, GedraConfig>()
    private val namespaceOwners = linkedMapOf(GCFG.globalNamespace to GID.globalClient)

    /** Problems found, in the order they were found. Empty unless something degraded. */
    val issues: MutableList<GedraConfigIssue> = mutableListOf()

    /** The configs kept, in contribution order. */
    val configs: List<GedraConfig> get() = configsById.values.toList()

    /** Every trait kept, keyed by its globally unique id. */
    val traits: Map<String, GedraTrait> get() = traitOwners.toMap()

    /**
     * The traits [client] can see: its own, and `global`'s. Nothing else — that is the visibility rule, and
     * this is the one place that has to know it, since assembling a client's view is the only thing that ever
     * asks the question.
     *
     * Every config is `global` today, so this returns everything. It is written as the question rather than
     * as the answer because the day a second owner exists, the answer changes and the question does not.
     */
    fun traitsFor(client: String): List<GedraTrait> = traitConfigs.entries
        .filter { (_, config) -> config.gedraId.client == GID.globalClient || config.gedraId.client == client }
        .mapNotNull { (traitId, _) -> traitOwners[traitId] }

    /**
     * The traits [client] **owns** -- declared in a config of its own, rather than seen from `global`.
     *
     * The other half of [traitsFor], and kept apart from it because the two answer different questions: that
     * one is what a client can *see* (for `$ref` resolution), this is part of what it may *use*. See
     * `supportedTraits`, which is where they are combined.
     */
    fun traitsOwnedBy(client: String): List<GedraTrait> = traitConfigs.entries
        .filter { (_, config) -> config.gedraId.client == client }
        .mapNotNull { (traitId, _) -> traitOwners[traitId] }

    /** The `$defs` the kept configs generated, merged, for compiling with everything else. */
    fun defs(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (config in configsById.values) {
            out.putAll(config.defs)
        }
        return out
    }

    /**
     * Takes a config, or refuses it. Returns whether it was kept, so a caller can tell a degraded boot from a
     * clean one without reading [issues].
     */
    fun add(cxt: KdrCxt, config: GedraConfig): Boolean {
        val mode = gedraConfigCheckMode(cxt)
        if (mode == GCFG.off) {
            keep(config)
            return true
        }
        val problem = firstProblem(config)
        if (problem == null) {
            keep(config)
            return true
        }
        reportConfigProblem(cxt, mode, problem, issues)
        return false
    }

    private fun keep(config: GedraConfig) {
        configsById[config.gedraId.fullId] = config
        namespaceOwners.putIfAbsent(config.namespace, config.gedraId.client)
        for ((traitId, trait) in config.traits) {
            traitOwners[traitId] = trait
            traitConfigs[traitId] = config
        }
    }

    /**
     * The first thing wrong with [config], or null. First rather than all, because a config is taken or left
     * whole: reporting the other four problems with something already being rejected buries the one that has
     * to be fixed first.
     */
    private fun firstProblem(config: GedraConfig): GedraConfigIssue? {
        configsById[config.gedraId.fullId]?.let {
            return GedraConfigIssue(
                "Gedra config '${config.gedraId}' is contributed twice.",
                "Keeping the first; the second is dropped, along with its ${config.traits.size} trait(s).",
            )
        }
        val owner = namespaceOwners[config.namespace]
        if (owner != null && owner != config.gedraId.client) {
            return GedraConfigIssue(
                "Gedra config '${config.gedraId}' declares its types in namespace '${config.namespace}', " +
                    "which belongs to '$owner'. A namespace has exactly one owner, and reaching into " +
                    "somebody else's is how one owner's definitions become visible to another.",
                "Dropping '${config.gedraId}'; '$owner' keeps the namespace.",
            )
        }
        for (traitId in config.traits.keys) {
            val held = traitConfigs[traitId] ?: continue
            return GedraConfigIssue(
                "Trait '$traitId' is declared by both '${held.gedraId}' and '${config.gedraId}'. A trait id " +
                    "is unique across every namespace and every gedra kind, which is what lets stored data " +
                    "carry a bare trait id and nothing else.",
                "Keeping '${held.gedraId}''s; dropping '${config.gedraId}' and its ${config.traits.size} " +
                    "trait(s).",
            )
        }
        return null
    }
}
