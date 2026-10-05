package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.checkMode
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.util.Parsed

/**
 * One report a scope holds (issue #980): bound, with the config bundle that declared it -- which says where it came
 * from (source or stored) and whether it is a client's copy of its template's.
 */
class ReportDeclared(val bundle: GedraConfig, val bound: BoundReport) {
    val reportId: String get() = bound.reportId

    /** Whether this is a client's copy of a report its template declares (issue #945). */
    val fromTemplate: Boolean get() = bundle.inheritedFrom != null

    override fun toString(): String = "${bundle.gedraId}/$reportId"
}

/**
 * The reports one scope may run, keyed by report id (issue #980): the global ones, and for a client its own beside
 * them. The shape `WorkflowRegistry` has, for the same reason -- a request needs one answer to "which reports are
 * there here" -- though a report never shadows another: a global id is rooted and a client's bare (#979).
 */
class ReportRegistry(
    /** The client this is for, or null for the global registry. */
    val client: String?,
    /** Every report this scope may run, by id, global ones first, each in declaration order. */
    val reports: Map<String, ReportDeclared>,
) {
    /** The report named, or null. */
    fun report(id: String): ReportDeclared? = reports[id]

    override fun toString(): String = "${client ?: GID.globalClient}: ${reports.keys}"
}

/** The global registry and each client's; a client absent from [byClient] sees [global]. */
class ReportRegistries(val global: ReportRegistry, val byClient: Map<String, ReportRegistry>) {
    fun forClient(client: String?): ReportRegistry = if (client == null) global else byClient[client] ?: global

    companion object {
        val empty: ReportRegistries = ReportRegistries(ReportRegistry(null, emptyMap()), emptyMap())
    }
}

/**
 * Builds the global report registry and each present client's from the collected bundles (issue #980), binding
 * every report against its scope ([bindReport]) and dropping what does not bind.
 *
 * **Each report is judged in the scope that declares it.** A global report is bound against the global traits,
 * types and workflows once, and every client sees it as bound there; a client that lacks a trait it names gets blank
 * cells, as a client with no forms gets no rows -- not a problem with the report. A client's own reports are bound
 * against that client: its supported traits, its variant's types, its workflow registry.
 *
 * **What a problem costs.** It goes to [reportConfigProblem], under the mode of the bundle holding the report (issue
 * #839): refused for source config outside production and for stored config only in unit tests; otherwise logged,
 * recorded on the client's issues, and the report alone is dropped. Two configs of one scope declaring one report
 * id keep the first and report the second, as two declaring one workflow do. Under `off` nothing is reported, but a
 * report that does not bind is still left out: there is nothing to run.
 */
fun buildReportRegistries(
    cxt: KdrCxt,
    configs: GedraConfigCollector,
    /** The clients present on this node, by id: the ones that get a registry of their own. */
    clients: Map<String, ClientDef>,
    /** What a scope's reports are bound against; null asks for the global scope. */
    scopeOf: (client: String?) -> ReportScope,
    issues: MutableList<GedraConfigIssue>,
    /** Build only this client's registry, inheriting [runningGlobal] (a reload or a trial); null builds every scope. */
    onlyClient: String? = null,
    /** The global registry the node runs now; with [onlyClient], what the client inherits. */
    runningGlobal: ReportRegistry? = null,
): ReportRegistries {
    fun problem(scope: String?, bundle: GedraConfig, reportId: String, what: String) = bundle.issue(
        "Report '$reportId' in '${bundle.gedraId}' $what",
        "Dropping it from ${scope?.let { "client '$it'" } ?: "the global registry"}.",
        GCEL.report, reportId,
    )

    fun assemble(scope: String?): Map<String, ReportDeclared> {
        val owner = scope ?: GID.globalClient
        val bundles = configs.configs.filter { it.gedraId.client == owner }
        if (bundles.none { it.reports.isNotEmpty() }) return emptyMap()
        val reportScope = scopeOf(scope)
        val out = LinkedHashMap<String, ReportDeclared>()
        for (bundle in bundles) {
            val off = bundle.checkMode(cxt) == BootCheckMode.off
            for (report in bundle.reports.values) {
                val held = out[report.reportId]
                if (held != null) {
                    if (!off) {
                        reportConfigProblem(
                            cxt,
                            problem(
                                scope, bundle, report.reportId,
                                "is declared a second time in this scope, beside the one in '${held.bundle.gedraId}'. " +
                                    "A report id names one definition within its client; keeping the first.",
                            ),
                            issues,
                        )
                    }
                    continue
                }
                when (val bound = bindReport(report, reportScope)) {
                    is Parsed.Ok -> out[report.reportId] = ReportDeclared(bundle, bound.value)
                    is Parsed.Failed -> {
                        val why = bound.problems.first().message
                        if (off) {
                            LogStartup.debug(cxt) { "Report '${report.reportId}' in '${bundle.gedraId}' does not bind, and is left out: $why" }
                        } else {
                            reportConfigProblem(cxt, problem(scope, bundle, report.reportId, "does not hold up: $why"), issues)
                        }
                    }
                }
            }
        }
        return out
    }

    val global = runningGlobal.takeIf { onlyClient != null } ?: ReportRegistry(null, assemble(null))
    val byClient = LinkedHashMap<String, ReportRegistry>()
    for (client in clients.keys) {
        if (onlyClient != null && client != onlyClient) continue
        val own = assemble(client)
        if (own.isNotEmpty()) byClient[client] = ReportRegistry(client, global.reports + own)
    }
    return ReportRegistries(global, byClient)
}
