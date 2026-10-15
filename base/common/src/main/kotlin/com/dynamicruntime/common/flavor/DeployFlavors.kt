package com.dynamicruntime.common.flavor

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EVSRC
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.schema.PRES
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition

/*
 * Deployment flavors (issue #1218): named packages of configuration changes a deployment chooses with
 * `KDR_DEPLOY_FLAVORS` -- `reference`, say, for a reference deployment. The design is in
 * `kdr-design/thoughts-on-flavors.md` (private `sampwhite/Actions`).
 *
 * A flavor **sets values, and code reads the values**. Nothing tests a flavor but this engine: a component that
 * behaves differently under a flavor reads a config key or an environment variable the flavor set, as it would read
 * any other, so each switch also works alone and the operator view can say where its value came from. That is also
 * what keeps flavors from interacting -- a flavor is a list of changes with no logic in it.
 */

/** The flavor engine's names. A flavor's own name is a constant here too, beside the engine's. */
@Suppress("ConstPropertyName")
object FLV {
    /** The value of [deployFlavorsEnvVar] that says, explicitly, no flavors. */
    const val none = "none"

    /** The instance-config key holding the resolved [DeployFlavors]. */
    const val registryKey = "deployFlavors"

    /** The operator view of the deployment flavors. */
    const val operatorPath = "/${SECT.operator}/flavors"

    const val reportType = "DeployFlavorsReport"
    const val declaredType = "DeployFlavorInfo"
    const val valueType = "DeployFlavorValue"
    const val declared = "declared"
    const val active = "active"
    const val values = "values"
    const val name = "name"
    const val description = "description"
    const val isActive = "isActive"
    const val flavor = "flavor"
    const val component = "component"
    const val isEnvVar = "isEnvVar"
    const val value = "value"
    const val applied = "applied"
    const val overriddenBy = "overriddenBy"

    val deployFlavorsEnvVar = EnvVarDef(
        "KDR_DEPLOY_FLAVORS", group = ENVGRP.application, defaultDoc = "unset (no flavors)",
        description = "The deployment flavors this node runs with, comma-separated in order -- for example " +
            "`reference`. A flavor is a named package of configuration changes a component declares; a later flavor " +
            "wins where two set the same value. `none` says no flavors. A name no component declares refuses the " +
            "boot. The operator view `/operator/flavors` lists what each active flavor set.",
        bootstrap = true,
    )
}

/**
 * A deployment flavor's declaration (issue #1218), made by the one component that owns it: its name, which is what
 * `KDR_DEPLOY_FLAVORS` lists, and what it is for. Any component may then contribute values to it ([flavor]).
 */
class FlavorDef(val name: String, val description: String)

/**
 * What one component contributes to one flavor (issue #1218): instance-config keys to set, existing environment
 * variables to default, and fragment overlays (issue #1219). Built with [flavor], returned from
 * `ComponentDefinition.flavors`.
 */
class FlavorContribution(
    val flavor: String,
    /** Instance-config keys, each set only where the key is not already set. */
    val configValues: Map<String, Any>,
    /** Defaults for declared environment variables, each applying only where nothing else sets the variable. */
    val envVarDefaults: Map<EnvVarDef, String>,
    /**
     * Each yields fragment overlays (issue #1219). Functions, so a flavor that is not active never reads its files:
     * an omnibus file is read when its layers are made.
     */
    val fragmentLayers: List<() -> List<FragmentSource>> = emptyList(),
)

/** The DSL behind [flavor]. */
class FlavorContributionBuilder(private val flavor: String) {
    private val configValues = LinkedHashMap<String, Any>()
    private val envVarDefaults = LinkedHashMap<EnvVarDef, String>()
    private val fragmentLayers = mutableListOf<() -> List<FragmentSource>>()

    /**
     * Sets the instance-config [key] to [value] while the flavor is active, unless the deployment's own
     * configuration (or a test's boot configuration) already set it.
     */
    fun set(key: String, value: Any) {
        require(key !in configValues) { "Flavor '$flavor' sets '$key' twice in one contribution." }
        configValues[key] = value
    }

    /**
     * Defaults the existing environment variable [def] to [value] while the flavor is active. It applies only where
     * nothing else sets the variable -- the instance config, the workspace defaults file, and the process
     * environment all win. A flavor never declares a variable of its own: keeping new environment variables from
     * being invented is what flavors are for, so a new switch is a config key ([set]).
     */
    fun default(def: EnvVarDef, value: String) {
        require(def !in envVarDefaults) { "Flavor '$flavor' defaults ${def.name} twice in one contribution." }
        envVarDefaults[def] = value
    }

    /**
     * Overlays the fragment files the omnibus file `md-fragments/<id>_omnibus.md` names, while the flavor is active
     * (issue #1219) -- see `omnibusOverlay`. Read only when the flavor is active.
     */
    fun omnibusOverlay(id: String) {
        fragmentLayers.add { com.dynamicruntime.common.content.omnibusOverlay(id) }
    }

    /**
     * Fragment overlays [layers] makes, while the flavor is active (issue #1219) -- for a layer an omnibus file does
     * not suit, such as `fragmentInline`. Each must be an overlay for every client; called only when the flavor is
     * active.
     */
    fun fragments(layers: () -> List<FragmentSource>) {
        fragmentLayers.add(layers)
    }

    fun build(): FlavorContribution =
        FlavorContribution(flavor, configValues.toMap(), envVarDefaults.toMap(), fragmentLayers.toList())
}

/** A component's contribution to the flavor [name] (issue #1218). */
fun flavor(name: String, build: FlavorContributionBuilder.() -> Unit): FlavorContribution =
    FlavorContributionBuilder(name).apply(build).build()

/** One value an active flavor set, and whether it took effect -- a row of the operator view. */
class FlavorValue(
    val flavor: String,
    /** The component that contributed it. */
    val component: String,
    /** A config key, or an environment variable's name. */
    val name: String,
    val isEnvVar: Boolean,
    val value: Any,
    /**
     * Whether the value is the one in effect: false when something explicit set it, or when a later flavor set it
     * too.
     */
    val applied: Boolean,
    /** What decided the value instead, when not [applied]: the later flavor, or the explicit setting. */
    val overriddenBy: String?,
)

/** What resolving the deployment flavors decided (issue #1218), kept on the instance config for the operator view. */
class DeployFlavors(
    /** Every declared flavor, in component order. */
    val declared: List<FlavorDef>,
    /** The active flavors, in `KDR_DEPLOY_FLAVORS` order. */
    val active: List<String>,
    /** Every value the active flavors set, in the order they were considered. */
    val values: List<FlavorValue>,
    /** The active flavors' contributions, by contributing component, in flavor order and then component order. */
    private val activeContributions: List<Pair<String, FlavorContribution>> = emptyList(),
) {
    /**
     * The fragment overlays of the active flavors (issue #1219), from the components in [loaded] alone: a component
     * that did not load ships none of the files a layer of its would overlay. Each carries its flavor's rank, so the
     * merge orders them -- above the components' own layers, below every client's, and a later flavor over an
     * earlier one. Made here, after the load decisions, so a flavor that is not active reads nothing.
     *
     * A flavor's layer must be an overlay for every client: a base would add a file only that flavor declares, and
     * a client's layer is the client's configuration to make.
     */
    fun fragmentLayers(loaded: Set<String>): List<FragmentSource> =
        activeContributions.filter { it.first in loaded }.flatMap { (component, c) ->
            val rank = active.indexOf(c.flavor)
            c.fragmentLayers.flatMap { it() }.map { layer ->
                if (!layer.isOverlay || layer.client != null) {
                    throw KdrException(
                        "Refusing to start: the deployment flavor '${c.flavor}' (from '$component') contributes " +
                            "'${layer.origin}' for '${layer.fileId}', which is not an overlay for every client.",
                    )
                }
                layer.refiled(flavorRank = rank)
            }
        }

    /** The operator view's result ([defineOperatorEndpoint]). */
    fun toReport(): Map<String, Any?> = mapOf(
        FLV.active to active,
        FLV.declared to declared.map {
            mapOf(FLV.name to it.name, FLV.description to it.description, FLV.isActive to (it.name in active))
        },
        FLV.values to values.map {
            linkedMapOf(
                FLV.flavor to it.flavor, FLV.component to it.component, FLV.name to it.name,
                FLV.isEnvVar to it.isEnvVar, FLV.value to it.value.toString(), FLV.applied to it.applied,
                FLV.overriddenBy to it.overriddenBy,
            )
        },
    )

    companion object {
        /**
         * The operator view (issue #1218): every declared flavor, the active ones, and each value an active flavor
         * set with whether it took effect. Beside `/operator/env/reference`, which shows only environment variables
         * -- a variable a flavor defaulted shows there too, as from a flavor -- while a flavor's config keys show
         * only here.
         */
        fun defineOperatorEndpoint(builder: SchModuleBuilder) = with(builder) {
            type(FLV.declaredType) {
                type = SCT.kObject
                presentation = PRES.table
                property(FLV.name, "The flavor's name, as KDR_DEPLOY_FLAVORS lists it.", required = true) {
                    presentation = PRES.identifier
                }
                property(FLV.description, "What the flavor is for.", required = true)
                property(FLV.isActive, "Whether this node runs with the flavor.", required = true) { type = SCT.boolean }
            }
            type(FLV.valueType) {
                type = SCT.kObject
                presentation = PRES.table
                property(FLV.flavor, "The active flavor that set the value.", required = true)
                property(FLV.component, "The component that contributed it.", required = true)
                property(FLV.name, "The config key, or the environment variable's name.", required = true) {
                    presentation = PRES.identifier
                }
                property(FLV.isEnvVar, "Whether it defaults an environment variable rather than sets a config key.", required = true) {
                    type = SCT.boolean
                }
                property(FLV.value, "The value the flavor gives it, as text.", required = true)
                property(FLV.applied, "Whether the value is the one in effect on this node.", required = true) {
                    type = SCT.boolean
                }
                property(FLV.overriddenBy, "What decided the value instead, when it is not applied.")
            }
            type(FLV.reportType) {
                type = SCT.kObject
                property(FLV.active, "The active flavors, in the order KDR_DEPLOY_FLAVORS lists them.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(FLV.declared, "Every flavor a component declares.", required = true) {
                    type = SCT.array
                    items { ref(FLV.declaredType) }
                }
                property(FLV.values, "Each value an active flavor set.", required = true) {
                    type = SCT.array
                    items { ref(FLV.valueType) }
                }
            }
            generalEndpoint(
                FLV.operatorPath,
                "The deployment flavors this node declares and runs with, and what each active one set.",
                HttpMethod.GET,
                outputRef = FLV.reportType,
                tags = setOf(ETAG.internal),
            ) { c, _ -> get(c).toReport() }
        }

        /** The resolved flavors of the instance [cxt] runs on; none when nothing resolved them (a hand-built config). */
        fun get(cxt: KdrCxt): DeployFlavors =
            cxt.instanceConfig.get(FLV.registryKey) as? DeployFlavors ?: DeployFlavors(emptyList(), emptyList(), emptyList())

        /**
         * Resolves the deployment flavors of the instance [cxt] is building, from every one of [components], and
         * applies the active flavors' values (issue #1218). Called **once**, while the instance is built: after the
         * deployment's own configuration is in place, and before anything reads what a flavor may set -- the node's
         * profile and the components' load decisions included, so a flavor's value can decide what loads.
         *
         * Every component is asked, loaded or not: the load decisions come after this, and may depend on it.
         *
         * The boot is refused for a name `KDR_DEPLOY_FLAVORS` lists that no component declared; a flavor declared
         * twice; a contribution to an undeclared flavor; two contributions to one flavor setting the same value;
         * and a flavor defaulting a [EnvVarDef.bootstrap] variable. All but the first are checked over every
         * contribution, active or not, so a mistake in a flavor this node is not running still fails here.
         */
        fun resolve(cxt: KdrCxt, components: List<ComponentDefinition>): DeployFlavors {
            val config = cxt.instanceConfig
            val declared = LinkedHashMap<String, FlavorDef>()
            for (component in components) {
                for (def in component.flavorDefs(cxt)) {
                    if (def.name in declared) refuse("The deployment flavor '${def.name}' is declared twice.")
                    declared[def.name] = def
                }
            }
            val contributions = components.flatMap { c -> c.flavors(cxt).map { c.providerName to it } }
            checkContributions(declared.keys, contributions)

            val active = parseActive(config.getEnvVar(FLV.deployFlavorsEnvVar), declared.keys)
            val values = applyValues(config, active, contributions)
            val activeContributions = active.flatMap { name -> contributions.filter { it.second.flavor == name } }
            val result = DeployFlavors(declared.values.toList(), active, values, activeContributions)
            config.put(FLV.registryKey, result)
            LogStartup.info(
                cxt,
                if (active.isEmpty()) "Deployment flavors: none." else "Deployment flavors: ${active.joinToString(", ")}.",
            )
            return result
        }

        private fun refuse(message: String): Nothing = throw KdrException("Refusing to start: $message")

        private fun checkContributions(declared: Set<String>, contributions: List<Pair<String, FlavorContribution>>) {
            for ((component, c) in contributions) {
                if (c.flavor !in declared) {
                    refuse("Component '$component' contributes to the deployment flavor '${c.flavor}', which no component declares.")
                }
                c.envVarDefaults.keys.firstOrNull { it.bootstrap }?.let {
                    refuse(
                        "The deployment flavor '${c.flavor}' (from '$component') defaults ${it.name}, which is read " +
                            "before flavors are resolved, so the default could change nothing.",
                    )
                }
            }
            // One flavor is one package: two of its contributions setting the same value is a conflict with no
            // order to settle it, where two flavors are ordered by the variable.
            for ((flavorName, group) in contributions.groupBy { it.second.flavor }) {
                val seen = HashMap<String, String>()
                for ((component, c) in group) {
                    val names = c.configValues.keys + c.envVarDefaults.keys.map { it.name }
                    for (name in names) {
                        seen.put(name, component)?.let { first ->
                            refuse(
                                "The deployment flavor '$flavorName' sets '$name' twice: from '$first' and from '$component'.",
                            )
                        }
                    }
                }
            }
        }

        /** The active flavors named by [raw], checked against [declared]. */
        private fun parseActive(raw: String?, declared: Set<String>): List<String> {
            val names = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (names == listOf(FLV.none)) return emptyList()
            val variable = FLV.deployFlavorsEnvVar.name
            if (FLV.none in names) refuse("$variable lists '${FLV.none}' beside other flavors: $names.")
            names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
                refuse("$variable lists the flavor '$it' more than once.")
            }
            val unknown = names.filter { it !in declared }
            if (unknown.isNotEmpty()) {
                refuse(
                    "$variable names flavor(s) $unknown that no component declares. Declared: " +
                        (declared.sorted().ifEmpty { listOf("none") }).joinToString(", ") + ".",
                )
            }
            return names
        }

        /**
         * Applies the active flavors' values to [config]: a later flavor's value replaces an earlier one's, and the
         * result goes in only where nothing explicit set it. Returns what happened to each value.
         */
        private fun applyValues(
            config: KdrInstanceConfig,
            active: List<String>,
            contributions: List<Pair<String, FlavorContribution>>,
        ): List<FlavorValue> {
            class Candidate(val flavor: String, val component: String, val name: String, val isEnvVar: Boolean, val value: Any)

            val candidates = active.flatMap { name ->
                contributions.filter { it.second.flavor == name }.flatMap { (component, c) ->
                    c.configValues.map { (k, v) -> Candidate(name, component, k, false, v) } +
                        c.envVarDefaults.map { (def, v) -> Candidate(name, component, def.name, true, v) }
                }
            }
            // The last flavor to set a name is the one whose value is in contention.
            val winners = candidates.associateBy { it.isEnvVar to it.name }
            val explicitConfig = winners.values.filter { !it.isEnvVar && config.get(it.name) != null }.map { it.name }.toSet()
            for (w in winners.values.filter { !it.isEnvVar && it.name !in explicitConfig }) {
                config.put(w.name, w.value)
            }
            config.setFlavorEnvDefaults(winners.values.filter { it.isEnvVar }.associate { it.name to it.value.toString() })
            val envVarDefs = contributions.flatMap { it.second.envVarDefaults.keys }.associateBy { it.name }

            return candidates.map { c ->
                val winner = winners.getValue(c.isEnvVar to c.name)
                val explicit = when {
                    c.isEnvVar -> config.resolveEnvVar(envVarDefs.getValue(c.name)).source.takeIf { it != EVSRC.flavor }
                    c.name in explicitConfig -> EVSRC.config
                    else -> null
                }
                val overriddenBy = when {
                    winner !== c -> "flavor '${winner.flavor}'"
                    explicit == EVSRC.config -> "instance config"
                    explicit == EVSRC.processEnv -> "the environment"
                    else -> null
                }
                FlavorValue(c.flavor, c.component, c.name, c.isEnvVar, c.value, applied = overriddenBy == null, overriddenBy)
            }
        }
    }
}
