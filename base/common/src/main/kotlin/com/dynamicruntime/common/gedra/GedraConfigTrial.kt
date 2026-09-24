package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.UiBlockService

/**
 * A **trial reload** (issue #843): what reloading a client would find if a candidate configuration replaced the
 * stored one of the same id -- run with the load's own checks, so a write and a load cannot come to disagree about
 * what is valid, and without publishing anything.
 *
 * Stored configuration is judged strictly when it is **written** and forgivingly once it is stored (#839): a
 * write is where a person is still at the keyboard to fix it. So the write endpoints refuse a candidate whose
 * trial finds anything the client's configuration did not already have.
 *
 * ### How it avoids side effects
 *
 * Every check reports through `reportConfigProblem`, and a context carrying [GCFG.trialCaptureKey] makes that
 * collect instead of refusing, logging or recording -- so the trial sees every problem, each treated as forgiven so
 * the evaluation carries on past it. The checks run over a scratch copy of the schema collector
 * (`SchemaCollector.trialCopy`) with the client's loaded configs withdrawn and the candidate set added, and each
 * service evaluates its part with a `trialClient` that builds what a reload would build and keeps nothing.
 */
object GedraConfigTrial {

    /**
     * Refuses [candidate] with a 400 that lists what its trial found **beyond** what the client's configuration
     * already has (the issues its last load recorded, #840). A pre-existing problem elsewhere in the client does
     * not block this write -- or fixing one of two broken configs would be refused over the other -- but anything
     * this write introduces does, wherever in the client it lands.
     */
    fun requireClean(cxt: KdrCxt, candidate: GedraConfig) {
        val client = candidate.gedraId.client
        val baseline = ClientConfigIssues.get(cxt).issuesFor(client)
        val found = trial(cxt, candidate).filterNot { issue -> baseline.any { it.sameAs(issue) } }
        if (found.isEmpty()) return
        throw KdrException.mkInput(
            "Configuration '${candidate.gedraId}' was not stored: loading it would find ${found.size} problem(s) " +
                "in client '$client'. " + found.joinToString(" ") { it.message },
        )
    }

    /** Every problem a reload of [candidate]'s client would find with [candidate] in place (see the class note). */
    fun trial(cxt: KdrCxt, candidate: GedraConfig): List<GedraConfigIssue> {
        val client = candidate.gedraId.client
        val collector = SchemaCollector.get(cxt) ?: return emptyList()
        val capture = mutableListOf<GedraConfigIssue>()
        val tcxt = cxt.mkSubContext("configTrial", client).also { it.locals[GCFG.trialCaptureKey] = capture }
        val loader = GedraConfigLoadService.get(tcxt)
        val previous = loader.loadedFor(client)
        val fresh = previous.filter { it.gedraId.fullId != candidate.gedraId.fullId } + candidate

        // Phase one's checks, as the reload runs them: the extends rule against the source clients alone, then
        // admission into a scratch copy of the collectors.
        val loadedIds = loader.allLoadedIds()
        val sourceClients = collector.gedraConfigs.configs
            .filter { it.gedraId.fullId !in loadedIds }.mapNotNull { it.client }.associateBy { it.clientId }
        val scratch = collector.trialCopy()
        previous.forEach { scratch.removeGedraConfig(it) }
        val taken = mutableListOf<GedraConfig>()
        val ignored = mutableListOf<GedraConfigIssue>()
        for (config in fresh) {
            val problem = loader.extendsProblem(config, sourceClients)
            if (problem != null) {
                reportConfigProblem(tcxt, problem, ignored)
                continue
            }
            if (scratch.addGedraConfig(tcxt, config)) taken.add(config)
        }

        // Phase two's, in the reload's order: the schema, the client's definition, the overlays, the workflows.
        val schema = SchemaService.get(tcxt).trialClient(tcxt, scratch, client)
        val def = checkClientDefs(tcxt, scratch.gedraConfigs, setOf(client), ClientService.get(tcxt).clients)
            .clients[client]
        val fragments = MarkdownFragmentService.get(tcxt)
            .trialClient(tcxt, client, previous.flatMap { it.fragments }, taken.flatMap { it.fragments })
        val uiBlocks = previous.flatMap { it.uiBlocks } to taken.flatMap { it.uiBlocks }
        UiBlockService.get(tcxt).trialClient(tcxt, client, uiBlocks.first, uiBlocks.second, schema.cfactNames)
        WorkflowService.get(tcxt)
            .trialClient(tcxt, scratch, client, def, fragments, schema.cfactNames, schema.droppedTypes)
        return capture.toList()
    }
}
