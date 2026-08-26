package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.toOptBool
import com.dynamicruntime.common.util.toOptEnum

/**
 * What a configuration defect does at startup, and what a running node can be asked about afterwards
 * (issue #303).
 *
 * ### The paradigm
 *
 * Production boots when a defect is **survivable**; everywhere else refuses. A broken piece of copy should
 * stop the author who wrote it and should not take down every endpoint that had nothing to do with it. That
 * asymmetry was established for Markdown fragments in #296 and hand-rolled again for schema drift, which is
 * the point at which it wants a shared shape rather than a third copy.
 *
 * ### Why a registry rather than an endpoint per check
 *
 * A boot log line scrolls past once, at the moment nobody is looking, so a production node that degraded
 * quietly stays degraded quietly. An endpoint per check does not fix that: it answers "are the fragments
 * alright", where the question on arriving at a node is **"is this node running degraded, and how?"** -- which
 * no single check can answer, because none of them knows about the others.
 *
 * **A clean check is reported too, and that is not padding.** "The fragment check ran, strictly, and found
 * nothing" and "the fragment check never ran" are very different facts about a node, and a report listing only
 * problems cannot tell them apart. A check silently not running is its own kind of failure.
 *
 * ### The boundary, so this is not over-applied
 *
 * Degrading is for a configuration defect **on the side** -- one fragment, one trait, something wrong while
 * the rest of the instance is fine. It is not for a security fence: `SchemaService.checkInit` refuses to start
 * a test instance outside `local`/`unit`, and that stays absolute. Nor for a misconfiguration that leaves the
 * node unable to do its job -- which is why [SqlSchemaDrift][com.dynamicruntime.common.sql.SqlSchemaDrift]
 * stays [strict] even in production. The first fails toward "one feature is wrong"; the second toward "the
 * wrong people can reach something", and no amount of uptime is worth the second.
 *
 * So the mode is **per check**, and `strict` in production is a legitimate answer for a check whose failure is
 * not survivable. A registry that flattened that into one policy would have to be wrong for one of them.
 */
@Suppress("EnumEntryName")
enum class BootCheckMode {
    /** Refuse to boot when the check finds something. */
    strict,

    /** Log what it found and serve anyway. */
    warn,

    /** Do not run the check at all. */
    off,
}

/** Names of the registered checks, and the field names their report is served under. */
@Suppress("ConstPropertyName")
object BCHK {
    /** The Markdown fragment files every loaded component declared (issue #296). */
    const val fragments = "fragments"

    /** Drift between a table declaration and the live database (issue #216). */
    const val schemaDrift = "schemaDrift"

    /** Gedra config coherence: a duplicated trait id, a namespace claimed twice (issue #299). */
    const val gedraConfig = "gedraConfig"

    // Report field names, shared with the frontend so a console reads them from the same strings.
    const val name = "name"
    const val envVar = "envVar"
    const val mode = "mode"
    const val findings = "findings"

    /** Schema type name for one check's entry in the report. */
    const val infoTypeName = "BootCheckInfo"
}

/**
 * One registered check and what it found: the [name] it is known by, the [envVar] that overrides its mode,
 * the [mode] it actually resolved to, and its [findings] -- empty when it ran and found nothing.
 *
 * A finding is recorded **at boot** and read back unchanged, because most checks cannot be re-run
 * meaningfully: a trait collision cannot change without a restart. A check that *can* usefully re-run
 * (fragments) offers that separately through its own endpoint; re-running is not the model here.
 */
class BootCheckResult(
    val name: String,
    val envVar: String,
    val mode: BootCheckMode,
    val findings: List<String>,
) {
    fun toInfo(): Map<String, Any?> = linkedMapOf(
        BCHK.name to name,
        BCHK.envVar to envVar,
        BCHK.mode to mode.name,
        BCHK.findings to findings,
    )

    companion object {
        /** The shape of the [toInfo] dump, kept with the class so the two cannot drift apart. */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(BCHK.infoTypeName) {
                type = SCT.kObject
                description = "One boot check, the mode it ran in, and what it found."
                property(BCHK.name, "The check's name.", required = true)
                property(BCHK.envVar, "The environment variable that overrides this check's mode.", required = true)
                property(BCHK.mode, "What a finding did at startup.", required = true) {
                    options(BootCheckMode.entries)
                }
                property(
                    BCHK.findings,
                    "What the check found; empty means it ran and found nothing, which is a different " +
                        "fact from the check being absent here altogether.",
                    required = true,
                ) {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
        }
    }
}

/**
 * What every boot check found, published into the instance config so one operator endpoint can report them
 * together.
 *
 * Findings **accumulate** under a name rather than replacing, because a check does not necessarily run once:
 * schema drift is checked per table as each is reconciled, so its entry is built up across many calls. The
 * first call is also what records that the check ran at all -- a check with no findings still has an entry,
 * which is the distinction the whole report exists to preserve.
 */
class BootCheckRegistry {
    private val checks = LinkedHashMap<String, BootCheckResult>()

    /** Records that [name] ran in [mode], adding [findings] to anything already recorded under that name. */
    @Synchronized
    fun record(name: String, envVar: String, mode: BootCheckMode, findings: List<String> = emptyList()) {
        val existing = checks[name]
        checks[name] = BootCheckResult(
            name = name,
            envVar = envVar,
            mode = mode,
            findings = (existing?.findings ?: emptyList()) + findings,
        )
    }

    /** Every check that registered, in the order they first ran. */
    @Synchronized
    fun results(): List<BootCheckResult> = checks.values.toList()

    @Suppress("ConstPropertyName")
    companion object {
        /** Instance-config key under which the registry is published. */
        const val key = "BootCheckRegistry"

        /**
         * The registry for this instance, creating it on first use.
         *
         * Created on demand rather than by a startup service, because a check can run before any service
         * ordering would guarantee one existed -- schema drift runs while tables are being reconciled, which
         * is early. A registry that had to be installed first would turn "this check ran too soon" into a
         * boot failure over bookkeeping.
         */
        @Synchronized
        fun get(cxt: KdrCxt): BootCheckRegistry {
            val existing = cxt.instanceConfig.get(key) as? BootCheckRegistry
            if (existing != null) {
                return existing
            }
            val created = BootCheckRegistry()
            cxt.instanceConfig.put(key, created)
            return created
        }
    }
}

/**
 * The mode a check runs in: an [override] when the operator gave one, else [strictOutsideProd]'s answer.
 *
 * **This is the part that was written twice, differently.** `MarkdownFragmentService.fragmentCheckMode` read
 * three spellings from an environment variable and `SqlSchemaDrift.isDriftAllowed` read a boolean, and each
 * carried its own notion of what production means. The *spellings* legitimately differ -- see
 * [modeOverride] and [allowOverride] -- but the default did not, and that is what lives here.
 *
 * [prodMode] is the check's own policy for production, not a shared one: [BootCheckMode.warn] for a defect on
 * the side, [BootCheckMode.strict] for one that leaves the node unable to do its job.
 */
fun bootCheckMode(cxt: KdrCxt, override: BootCheckMode?, prodMode: BootCheckMode): BootCheckMode =
    override ?: strictOutsideProd(cxt, prodMode)

/** Strict everywhere except production, where the check's own [prodMode] decides. */
private fun strictOutsideProd(cxt: KdrCxt, prodMode: BootCheckMode): BootCheckMode =
    if (cxt.instanceConfig.env == ENV.prod) prodMode else BootCheckMode.strict

/**
 * A mode named outright: `strict`, `warn` or `off`. Null when [envVar] is unset or spells none of them, which
 * leaves the environment default in place rather than guessing at what was meant.
 */
fun modeOverride(cxt: KdrCxt, envVar: String): BootCheckMode? =
    cxt.getEnvVar(envVar)?.trim()?.lowercase()?.toOptEnum<BootCheckMode>()

/**
 * An **escape hatch** rather than a mode: `true` means "let it through", which is [BootCheckMode.warn], and
 * `false` means the check applies, which is [BootCheckMode.strict].
 *
 * The second spelling exists because it is the honest reading of a variable named for what it *permits* --
 * `KDR_ALLOW_SCHEMA_DRIFT=true` says allow, and mapping that onto a mode word would be a worse name for the
 * same thing. Note it cannot say `off`: an operator who wants a check not to run at all wants a check that has
 * a mode variable, and giving an allow-flag a third state would make `true` ambiguous between "let it through"
 * and "do not look".
 */
fun allowOverride(cxt: KdrCxt, envVar: String): BootCheckMode? =
    cxt.getEnvVar(envVar)?.toOptBool()?.let { if (it) BootCheckMode.warn else BootCheckMode.strict }
