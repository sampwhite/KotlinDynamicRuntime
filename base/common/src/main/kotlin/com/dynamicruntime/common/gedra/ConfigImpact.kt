package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WorkflowRegistry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.engagedWorkflowIds
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A **publish impact report** (issue #935): what publishing one of a client's stored configurations would do to the
 * data the client already stores.
 *
 * With a Shadow Sandbox, publishing is the promotion step -- there is no deploy, and the client runs the new revision
 * at once -- but the sandbox tries a change against its own data, not the client's. The trial (#843) says whether a
 * candidate configuration is valid; this says what it does to the rows already stored, which is what makes "publish
 * is the promotion" safe.
 *
 * ### What is judged
 *
 * The **candidate** is what the client would run after the publish: for a published-only client, its published
 * revisions with the named configuration's latest in place, built by the trial and kept by nothing. A client on
 * the latest tier already runs every latest revision, so publishing changes nothing it runs and its report is always
 * empty.
 *
 * Each of the client's live rows is judged twice -- under the configuration it runs now and under the candidate --
 * and only what the candidate breaks is reported ([ImpactKind]): a row that already has a problem is not the
 * publish's doing. Stored state is compared against the candidate's workflows rather than derived anew: a trial's
 * workflows carry no functions to run (see [TrialCandidate]), so a state the candidate would merely *derive
 * differently* is not reported, only one it no longer defines.
 *
 * ### Cost
 *
 * A scan of every row, synchronously, over the caches -- as a report run reads them. Past [scanLimitEnvVar] rows
 * nothing is read: the report says it is [ImpactReport.tooLarge] rather than answer for part of the rows, and a
 * publish then asks for acknowledgement as for any finding. A batch job (#867) is the way past the limit, once a
 * client that large needs one.
 */
object ConfigImpact {
    /** The most rows one report examines; past it the report examines none. */
    val scanLimitEnvVar = EnvVarDef(
        "KDR_IMPACT_SCAN_LIMIT", group = ENVGRP.gedra, defaultDoc = "50000",
        description = "The most stored rows a publish impact report examines (issue #935). The report runs in " +
            "memory over the caches, so a client with more rows than this gets a report saying it is too large, " +
            "which a publish asks to be acknowledged like any finding -- a report covering part of the rows would " +
            "pass a harmful change. The default is the report runs' (KDR_REPORT_SCAN_LIMIT).",
    )

    const val defaultScanLimit = 50_000

    /** How many rows are read from the data service at a time. */
    private const val readBatch = 500

    /**
     * The report for publishing [client]'s configuration [name] as its latest revision stands. Not found when the
     * client has no such configuration.
     */
    fun report(cxt: KdrCxt, client: String, name: String): ImpactReport {
        val latest = GedraConfigService.get(cxt).readLatest(cxt, GedraId.of(GedraConfigType.configDoc, client, name))
            ?: throw KdrException("No configuration '$name' for client '$client'.", code = EXC.notFound)
        return reportFor(cxt, client, latest)
    }

    /**
     * The report for making [latest] -- a revision of one of [client]'s configurations -- the one it runs. A publish
     * asks on behalf of a published-only client alone, since a client on the latest tier already runs [latest]. An
     * editor's save that writes [latest] and makes it live in one step ([anyTier], issue #1040) asks before the reload
     * on either tier: what the client runs now is still the revision before it.
     */
    fun reportFor(cxt: KdrCxt, client: String, latest: GedraConfigRow, anyTier: Boolean = false): ImpactReport {
        val report = ImpactReport(client, latest.configId.baseId, latest.version)
        val publishedOnly = GedraConfigService.get(cxt).publishedOnly(cxt, client)
        if (!publishedOnly && !anyTier) return report
        val data = GedraDataService.get(cxt)
        val idsByKind = GU.entryKinds.associateWith { data.liveGedraIds(cxt, it, client).sorted() }
        val limit = cxt.getEnvVar(scanLimitEnvVar)?.trim()?.toIntOrNull() ?: defaultScanLimit
        if (idsByKind.values.sumOf { it.size } > limit) return report.also { it.tooLarge = true }
        val candidate = GedraConfigTrial.candidate(cxt, client, listOf(latest), published = publishedOnly) ?: return report

        val now = Judged(SchemaService.get(cxt).storeFor(client).types, WorkflowService.get(cxt).forClient(client))
        val next = Judged(candidate.schema.types, candidate.workflows)
        val scope = ReadScope.ofClient(client)
        for ((kind, ids) in idsByKind) {
            for (batch in ids.chunked(readBatch)) {
                val rows = data.readGedras(cxt, kind, batch, scope)
                val states = if (kind == GedraDataType.formDoc) data.readStates(cxt, rows.values.map { it.gedraId }, scope) else emptyMap()
                for (id in batch) {
                    val row = rows[id] ?: continue
                    report.scanned++
                    judgeEntries(report, row, now.union(kind), next.union(kind))
                    judgeStates(report, row, states[id].orEmpty(), now.workflows, next.workflows)
                }
            }
        }
        return report
    }

    /**
     * Refuses a publish of [latest] whose report finds anything (or could not be computed), with the report in the
     * refusal's `extraData` under [IMP.report] and its `errorCode` [IMP.refusedCode], so a caller can show it and ask
     * again with [IMP.acknowledgeImpact]. With [asSave] it is an editor's save made live at once (issue #1040): judged
     * on any tier ([reportFor]'s `anyTier`), and refused in a save's words.
     */
    fun requireNone(cxt: KdrCxt, client: String, latest: GedraConfigRow, asSave: Boolean = false) {
        val report = reportFor(cxt, client, latest, anyTier = asSave)
        if (!report.blocks) return
        val what = if (report.tooLarge) {
            "client '$client' stores more rows than an impact report examines (${scanLimitEnvVar.name}), so what it " +
                "would do to them is unknown"
        } else {
            report.findings.joinToString("; ") { it.describe() }
        }
        val (doing, again) = if (asSave) "This change" to "Save" else "Publishing configuration '${report.name}'" to "Publish"
        throw KdrException.mkInput(
            "$doing would affect data client '$client' already stores: $what. $again again acknowledging the impact " +
                "to go ahead.",
        ).also {
            it.extraData[KdrException.errorCodeKey] = IMP.refusedCode
            it.extraData[IMP.report] = report.toMap()
        }
    }

    /** One configuration's view of a client: its entry unions and workflows. */
    private class Judged(val types: Map<String, SchType>, val workflows: WorkflowRegistry) {
        fun union(kind: GedraDataType): SchType? = types["${GCFG.globalNamespace}.${GU.unionName(kind)}"]
    }

    /** A trait the candidate drops, and an entry it no longer accepts that the running configuration did. */
    private fun judgeEntries(report: ImpactReport, row: GedraDataRow, now: SchType?, next: SchType?) {
        for (entry in row.entries) {
            val traitId = entry[GE.traitId].toOptStr() ?: continue
            val knownNow = now?.variants?.isKnown(traitId) == true
            val knownNext = next?.variants?.isKnown(traitId) == true
            if (knownNow && !knownNext) {
                report.add(ImpactKind.traitGone, row, traitId = traitId)
            } else if (next != null && knownNext && validate(next, entry).isNotEmpty() &&
                (now == null || !knownNow || validate(now, entry).isEmpty())
            ) {
                // A trait newly supported counts too: its entries were never checked, and every edit now checks them.
                report.add(ImpactKind.dataInvalid, row, traitId = traitId)
            }
        }
    }

    /** A workflow the candidate drops that the form takes part in, and a recorded task it no longer defines. */
    private fun judgeStates(
        report: ImpactReport,
        row: GedraDataRow,
        states: List<Map<String, Any?>>,
        now: WorkflowRegistry,
        next: WorkflowRegistry,
    ) {
        val involved = LinkedHashSet<String>()
        row.creationWorkflowId?.workflowId?.let { involved.add(it) }
        involved.addAll(engagedWorkflowIds(states))
        states.filter { it[GE.traitId].toOptStr() == WFS.workflowState }
            .mapNotNullTo(involved) { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() }
        for (workflowId in involved) {
            if (now.workflow(workflowId) != null && next.workflow(workflowId) == null) {
                report.add(ImpactKind.workflowGone, row, workflowId = workflowId)
            }
        }
        for (state in states) {
            val data = state[GE.data].toJsonMapOrEmpty()
            val taskId = when (state[GE.traitId].toOptStr()) {
                WFS.workflowState -> data[WFS.ctaTask].toOptStr()
                WFS.workflowApproval -> data[WFS.taskId].toOptStr()
                else -> null
            } ?: continue
            val workflowId = data[WFD.workflowId].toOptStr() ?: continue
            val before = now.workflow(workflowId)?.def ?: continue
            val after = next.workflow(workflowId)?.def ?: continue
            if (taskId in before.tasksById && taskId !in after.tasksById) {
                report.add(ImpactKind.stateStranded, row, workflowId = workflowId, taskId = taskId)
            }
        }
    }
}

/**
 * Whether a publish checks its impact on stored data (issue #935): [unchecked] for a publish that cannot touch it --
 * an editor's copy or menu change, a definition's sandbox flag -- [refuse] to refuse one whose report finds anything,
 * [acknowledged] for a caller who has seen the report and goes ahead.
 */
enum class ImpactGate { unchecked, refuse, acknowledged }

/** A publish impact report (issue #935); see [ConfigImpact]. The wire names are [IMP]'s. */
class ImpactReport(val client: String, val name: String, val version: Int) {
    var scanned = 0
    var tooLarge = false
    private val byKey = LinkedHashMap<List<String?>, ImpactFinding>()

    /** The findings, in the order first met. */
    val findings: List<ImpactFinding> get() = byKey.values.toList()

    /** Whether a publish needs acknowledging: something found, or nothing could be examined. */
    val blocks: Boolean get() = tooLarge || byKey.isNotEmpty()

    fun add(kind: ImpactKind, row: GedraDataRow, traitId: String? = null, workflowId: String? = null, taskId: String? = null) {
        byKey.getOrPut(listOf(kind.name, traitId, workflowId, taskId)) { ImpactFinding(kind, traitId, workflowId, taskId) }
            .rows.add(row.gedraId.fullId)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        IMP.client to client,
        IMP.name to name,
        IMP.version to version,
        IMP.scanned to scanned,
        IMP.tooLarge to tooLarge,
        IMP.findings to findings.map { it.toMap() },
    )
}

/** Rows one publish would affect the same way: a [kind] and what it is about. */
class ImpactFinding(val kind: ImpactKind, val traitId: String?, val workflowId: String?, val taskId: String?) {
    /** The rows affected, by gedra id; a row counts once however many of its entries are. */
    val rows: MutableSet<String> = LinkedHashSet()

    fun describe(): String {
        val what = when (kind) {
            ImpactKind.traitGone -> "trait '$traitId' would no longer be supported"
            ImpactKind.dataInvalid -> "entries of trait '$traitId' would no longer validate"
            ImpactKind.workflowGone -> "workflow '$workflowId' would no longer exist"
            ImpactKind.stateStranded -> "workflow '$workflowId' would no longer define task '$taskId'"
        }
        return "${rows.size} row(s), $what"
    }

    fun toMap(): Map<String, Any?> = buildMap {
        put(IMP.kind, kind.name)
        traitId?.let { put(IMP.traitId, it) }
        workflowId?.let { put(IMP.workflowId, it) }
        taskId?.let { put(IMP.taskId, it) }
        put(IMP.count, rows.size)
        put(IMP.sampleIds, rows.take(IMP.sampleLimit))
    }
}
