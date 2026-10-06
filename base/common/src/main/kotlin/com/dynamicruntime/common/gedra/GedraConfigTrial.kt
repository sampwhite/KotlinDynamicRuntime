package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.gedra.workflow.WorkflowRegistry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.SchemaTrial
import com.dynamicruntime.common.uiblock.UiBlockService

/**
 * A **trial reload** (issue #843): what reloading a client would find if some of its stored configs were replaced --
 * run with the load's own checks, so a write and a load cannot come to disagree about what is valid, and without
 * publishing anything.
 *
 * Stored configuration is judged strictly when it is **written** and forgivingly once it is stored (#839): a
 * write is where a person is still at the keyboard to fix it. So the write endpoints refuse a change whose trial
 * finds anything the client's configuration did not already have.
 *
 * ### What it judges
 *
 * The client's configuration **as stored**, not as this node last loaded it: one revision per config -- its latest,
 * or its latest published, as the caller asks (`GedraConfigService.configsAt`) -- with the replaced configs swapped
 * in. A client's definition is spread across its configs, so a write is judged
 * against the others as they stand -- including ones written since the last reload, or on another node. The
 * caller holds the client's config lock (see `gedraConfigTables`), so none of them changes while it is judged.
 * Another client's configuration is never an input: a stored config depends only on source code and its own
 * client's configs.
 *
 * ### How it avoids side effects
 *
 * Every check reports through `reportConfigProblem`, and a context carrying [GCFG.trialCaptureKey] makes that
 * collect instead of refusing, logging, or recording -- so the trial sees every problem, each treated as forgiven so
 * the evaluation carries on past it. The checks run over a scratch copy of the schema collector
 * (`SchemaCollector.trialCopy`) with the client's loaded configs withdrawn and the trial's set added, and each
 * service evaluates its part with a `trialClient` that builds what a reload would build and keeps nothing.
 */
object GedraConfigTrial {

    /**
     * Refuses the change with a 400 -- its message led by [refusal] -- when a trial of [client]'s latest or
     * [published] revisions with [replacing] in place finds anything **beyond** what the client's configuration
     * already has (the issues its last load recorded, #840). A pre-existing problem elsewhere in the client does not
     * block the change -- or fixing one of two broken configs would be refused over the other -- but anything the
     * change introduces does, wherever in the client it lands.
     */
    fun requireClean(
        cxt: KdrCxt,
        client: String,
        replacing: List<GedraConfigRow>,
        published: Boolean,
        refusal: String,
    ) {
        val baseline = ClientConfigIssues.get(cxt).issuesFor(client)
        val found = trial(cxt, client, replacing, published).filterNot { issue -> baseline.any { it.sameAs(issue) } }
        if (found.isEmpty()) return
        throw KdrException.mkInput(
            "$refusal: loading client '$client' would find ${found.size} problem(s). " +
                found.joinToString(" ") { it.message },
        )
    }

    /**
     * Every problem a reload of [client] would find running its latest revisions -- or its latest [published] ones --
     * with [replacing] in place of the stored configs of the same class (see the class note); an empty [replacing]
     * judges the client as it is stored. The stored set is read on [cxt], so inside a transaction it sees what the
     * transaction wrote.
     *
     * Run under the reload lock, since it reads the collectors a reload swaps. A write calls this inside its
     * transaction, so the order is always the client's config lock, then the reload lock; a reload never takes a
     * config lock (it reads the configs), so the two cannot wait on each other.
     */
    fun trial(
        cxt: KdrCxt,
        client: String,
        replacing: List<GedraConfigRow> = emptyList(),
        published: Boolean = false,
    ): List<GedraConfigIssue> = candidate(cxt, client, replacing, published)?.issues.orEmpty()

    /**
     * What [client] would run with [replacing] in place -- the same trial as [trial], keeping what it built: the
     * schema and workflows a reload would give the client, beside the problems it found. What a publish impact report
     * judges stored data by (issue #935). Null on a node with no schema collector, where there is nothing to judge.
     */
    fun candidate(
        cxt: KdrCxt,
        client: String,
        replacing: List<GedraConfigRow> = emptyList(),
        published: Boolean = false,
    ): TrialCandidate? {
        val replaced = replacing.map { it.configId.fullId }.toSet()
        val stored = GedraConfigService.get(cxt).configsAt(cxt, client, published)
        val rows = stored.filter { it.configId.fullId !in replaced } + replacing
        return GedraConfigReload.underReloadLock { trialLocked(cxt, client, rows) }
    }

    private fun trialLocked(cxt: KdrCxt, client: String, rows: List<GedraConfigRow>): TrialCandidate? {
        val collector = SchemaCollector.get(cxt) ?: return null
        val capture = mutableListOf<GedraConfigIssue>()
        val tcxt = cxt.mkSubContext("configTrial", client).also { it.locals[GCFG.trialCaptureKey] = capture }
        val loader = GedraConfigLoadService.get(tcxt)
        val previous = loader.loadedFor(client)

        // Phase one's checks, as the reload runs them: each row reassembled (one that will not costs only itself),
        // the extends rule against the source clients alone, then admission into a scratch copy of the collectors.
        val loadedIds = loader.allLoadedIds()
        val sourceClients = collector.gedraConfigs.configs
            .filter { it.gedraId.fullId !in loadedIds }.mapNotNull { it.client }.associateBy { it.clientId }
        val scratch = collector.trialCopy()
        previous.forEach { scratch.removeGedraConfig(it) }
        val taken = mutableListOf<GedraConfig>()
        val ignored = mutableListOf<GedraConfigIssue>()
        for (row in rows) {
            val config = try {
                loader.toConfig(tcxt, row)
            } catch (e: KdrException) {
                reportConfigProblem(tcxt, loader.unloadableIssue(row.configId.fullId, row.client, e), ignored)
                continue
            }
            val problem = loader.storedConfigProblem(config, sourceClients)
            if (problem != null) {
                reportConfigProblem(tcxt, problem, ignored)
                continue
            }
            loader.unknownSlotsIssue(row)?.let { reportConfigProblem(tcxt, it, ignored) }
            loader.unreadReportIssues(config).forEach { reportConfigProblem(tcxt, it, ignored) }
            if (scratch.addGedraConfig(tcxt, config)) taken.add(config)
        }
        // The template's copy, remade as the reload remakes it (issue #945): a trial keeping the copy the client runs
        // now would refuse a revision that newly redefines a template trait, as a second definition beside the copy.
        taken.addAll(loader.addTemplateClones(tcxt, scratch, client, boot = false))

        // Phase two's, in the reload's order: the client's definition, the schema, the overlays, the workflows, the reports.
        // The client's definition before its schema, as a reload now orders them (issue #819): a variant is built only
        // for a client that would be present.
        val def = checkClientDefs(tcxt, scratch.gedraConfigs, setOf(client), ClientService.get(tcxt).clients)
            .clients[client]
        val present = def?.takeIf { it.isEnabledIn(tcxt.instanceConfig.env) }
        val schema = SchemaService.get(tcxt).trialClient(tcxt, scratch, client, present)
        val fragments = MarkdownFragmentService.get(tcxt)
            .trialClient(tcxt, client, previous.flatMap { it.fragments }, taken.flatMap { it.fragments })
        val uiBlocks = previous.flatMap { it.uiBlocks } to taken.flatMap { it.uiBlocks }
        UiBlockService.get(tcxt).trialClient(tcxt, client, uiBlocks.first, uiBlocks.second, schema.cfactNames)
        val workflows = WorkflowService.get(tcxt)
            .trialClient(tcxt, scratch, client, def, fragments, schema.cfactNames, schema.droppedTypes, schema.store)
        // Reports last, as a reload orders them (issue #980): a report's workflow paths bind to the workflows kept.
        ReportService.get(tcxt).trialClient(tcxt, scratch, client, def, schema.types, schema.droppedTypes, workflows)
        return TrialCandidate(capture.toList(), present != null, schema, workflows)
    }
}

/**
 * What a trial built for a client (issue #935): the [issues] it found, whether the client would be [present] at all,
 * and the [schema] and [workflows] a reload would give it. Built over a scratch collector and kept by nothing, so
 * holding one changes nothing the node runs. The workflows' functions are left unassigned, as every trial leaves
 * them (`resolveWorkflowFunctions`), so they describe the candidate's shape and cannot be run.
 */
class TrialCandidate(
    val issues: List<GedraConfigIssue>,
    val present: Boolean,
    val schema: SchemaTrial,
    val workflows: WorkflowRegistry,
)
