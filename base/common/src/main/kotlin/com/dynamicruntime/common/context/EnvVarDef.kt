package com.dynamicruntime.common.context

import com.dynamicruntime.common.exception.KdrException

/** The groups an [EnvVarDef] renders under -- the sections `environment-variables.md` divides into today. */
@Suppress("ConstPropertyName")
object ENVGRP {
    const val application = "Application"
    const val database = "Database"
    const val logging = "Logging"
    const val node = "Node identity"
    const val caching = "Caching"
    const val content = "Content"
    const val gedra = "Gedra"
    const val edge = "Edge"
}

/**
 * One environment variable, declared once (issue #371): its name, the group it renders under, its documented
 * default, and the "why" that used to live in `environment-variables.md` and in scattered KDoc.
 *
 * **Declaring one registers it** ([EnvVarRegistry]) and reading one goes through [KdrInstanceConfig.getEnvVar],
 * which takes an `EnvVarDef` rather than a raw string. Together those make the declaration *the* reference,
 * complete by construction: a variable nobody declared cannot be read, and a variable that is read is in the
 * registry, so it cannot silently go undocumented (which is exactly how `KDR_TRUST_ENV_AUTH_HEADER` shipped
 * undocumented and got past a merged PR).
 *
 * [defaultDoc] is a *description* of the default, not a value the read path returns — the real default is
 * usually computed at the call site (e.g. "secure everywhere but local/unit"), which no single value could
 * capture. The resolved value on a running node is a separate concern, surfaced by the operator view.
 */
class EnvVarDef(
    /** The variable's name, always `KDR_`-prefixed (e.g. `"KDR_ADMIN_EMAIL_DOMAIN"`). */
    val name: String,
    /** The section this renders under in the reference (e.g. `"Application"`, `"Database"`, `"Logging"`). */
    val group: String,
    /** A phrase describing the default when the variable is unset (e.g. `"unset"`, `"on for a test instance"`). */
    val defaultDoc: String,
    /** The documentation: what the variable does and why, in prose. */
    val description: String,
) {
    init {
        EnvVarRegistry.register(this)
    }

    /** The name, so a def logs and interpolates as the variable it stands for. */
    override fun toString(): String = name
}

/** Where a resolved [EnvVarResolution.value] came from -- one field of the operator view (issue #371). */
@Suppress("ConstPropertyName")
object EVSRC {
    /** An instance-config entry: deployment configuration, or a value a test injected. Wins over the process env. */
    const val config = "config"

    /** The real process environment (`System.getenv`). */
    const val processEnv = "processEnv"

    /** Neither supplied it, so the documented default applies -- see [EnvVarDef.defaultDoc]. */
    const val unset = "unset"
}

/**
 * How one [EnvVarDef] resolved on **this** node (issue #371): the [value] in effect, the exact [matchedName]
 * that supplied it (the role-prefixed key or the plain one), and its [source]. This is the fact a documented
 * default cannot be -- `"true, from KDR_ENV=local"` rather than `"on for local/dev"` -- and what the operator
 * view reports. Produced by [KdrInstanceConfig.resolveEnvVar], which is also what [KdrInstanceConfig.getEnvVar]
 * reads through, so the reported value and the value a request actually gets cannot disagree.
 */
class EnvVarResolution(
    /** The value in effect, or null when the variable is unset (the documented default applies then). */
    val value: String?,
    /** The exact key that supplied [value] -- the role-prefixed name or the plain one -- or null when unset. */
    val matchedName: String?,
    /** Where [value] came from: one of [EVSRC]. */
    val source: String,
)

/**
 * The VM-global index of every declared [EnvVarDef] (issue #371). Populated by declaration — an `EnvVarDef`
 * registers itself as it is constructed — so the set is complete for whatever has been class-loaded. The
 * runtime reference view (a later slice) enumerates it; a duplicate name is refused here, since two spellings
 * of one variable is the drift this change exists to delete.
 */
object EnvVarRegistry {
    private val byName = LinkedHashMap<String, EnvVarDef>()

    fun register(def: EnvVarDef) {
        synchronized(byName) {
            val existing = byName[def.name]
            if (existing != null && existing !== def) {
                throw KdrException("Environment variable '${def.name}' is declared more than once.")
            }
            byName[def.name] = def
        }
    }

    /** Every declared variable, in declaration order. */
    fun all(): List<EnvVarDef> = synchronized(byName) { byName.values.toList() }

    /** The declaration for [name], or null when nothing declares it. */
    fun forName(name: String): EnvVarDef? = synchronized(byName) { byName[name] }
}
