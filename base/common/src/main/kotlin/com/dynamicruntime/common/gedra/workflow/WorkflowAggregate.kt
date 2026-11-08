package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraStateRow
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The workflow pages' **aggregate** (issue #792): per normal workflow, how many of the forms the caller may see are
 * eligible for it, engaged with it (under way) and finished with it -- each form's workflows sorted by the same
 * kernel rule the forms list's workflow column uses ([formWorkflowsOf]), so a count and the column cannot disagree.
 *
 * A workflow is listed when it is **being calculated** (inside its relevancy window) in a client the caller works
 * in, even with nothing counted yet, **or** when it appears on a form the caller may see -- a workflow past its
 * relevancy still stands behind its engaged forms. One outside its lifetime is never listed
 * ([WorkflowPhases.live]), nor one the caller does not see ([WorkflowAudience], issue #1384) -- an administrator of the
 * client sees every one. The clients are those of the caller's visible forms, plus the caller's own.
 *
 * Beside the three counts, each search keyword the workflow's calls to action may carry (issue #1600) is counted twice:
 * *any*, the forms whose call to action in this workflow carries it, and *mine*, those the caller may act on now -- the
 * forms list's keyword search asked of this one workflow ([matchesWorkflowKeyword]), judged by [WaitingSteps] as the
 * listing judges it, so a count and the forms it drills to cannot disagree. A client whose workflows carry no keyword
 * costs nothing more.
 */
object WorkflowAggregate {
    fun of(cxt: KdrCxt, forms: List<Pair<GedraStateRow, List<Map<String, Any?>>>>): List<Map<String, Any?>> {
        val registries = HashMap<String, WorkflowRegistry>()
        fun registry(client: String) = registries.getOrPut(client) { WorkflowService.get(cxt).forClient(client) }
        // client -> workflow id -> counts by category; a key present means the workflow is listed.
        val counts = HashMap<String, LinkedHashMap<String, IntArray>>()
        fun countsFor(client: String, id: String) =
            counts.getOrPut(client) { LinkedHashMap() }.getOrPut(id) { IntArray(WfColumnCategory.entries.size) }

        // Each workflow resolved once per client, at one moment for the whole answer: a request straddling a window's
        // edge must not count its early forms under one phase and its later forms under another.
        val now = cxt.instanceNow()
        val resolved = HashMap<Pair<String, String>, Pair<WfDeclared, WfPhase>?>()
        fun live(client: String, id: String): Pair<WfDeclared, WfPhase>? = resolved.getOrPut(client to id) {
            registry(client).workflow(id)?.takeIf { it.def.entry == WfEntry.normal && WorkflowAudience.of(cxt).sees(client, id) }
                ?.let { it to WorkflowPhases.phaseAt(cxt, client, it.def, now) }?.takeIf { it.second.exists }
        }

        // The keywords each listed workflow's calls to action may carry, read once per workflow.
        val keywordsOf = HashMap<Pair<String, String>, Set<String>>()
        fun keywords(client: String, id: String): Set<String> = keywordsOf.getOrPut(client to id) {
            live(client, id)?.first?.searchKeywords(cxt).orEmpty()
        }
        // The forms a workflow carrying a keyword shows on, each with the workflows this pass counts as engaged there:
        // weighed after the pass, their owners read in one batch.
        val toWeigh = ArrayList<Triple<GedraStateRow, List<Map<String, Any?>>, Set<String>>>()

        for ((row, states) in forms) {
            val client = row.gedraId.client
            // Every live workflow the form's state names is one it "appears on", counted or not.
            states.filter { it[GE.traitId].toOptStr() == WFS.workflowState }
                .mapNotNull { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() }
                .filter { live(client, it) != null }
                .forEach { countsFor(client, it) }
            val phaseOf = { id: String -> live(client, id)?.second }
            val shown = formWorkflowsOf(states, phaseOf)
            shown.forEach { countsFor(client, it.workflowId)[it.category.ordinal]++ }
            // Weighed only when a workflow on the form carries a keyword: it asks who the caller is to the form.
            if (shown.any { keywords(client, it.workflowId).isNotEmpty() }) {
                toWeigh.add(Triple(row, states, shown.filter { it.category == WfColumnCategory.engaged }.map { it.workflowId }.toSet()))
            }
        }
        // client -> workflow id -> keyword -> [any, mine].
        val keywordCounts = HashMap<String, HashMap<String, LinkedHashMap<String, IntArray>>>()
        val waiting = WaitingSteps(cxt)
        waiting.readOwners(toWeigh.map { it.first.ownerUserId })
        for ((row, states, engaged) in toWeigh) {
            val client = row.gedraId.client
            for (w in waiting.weights(row.gedraId, row.ownerUserId, states)) {
                val id = w.workflow.workflowId
                // Counted only as this pass sees the workflow, at its one moment: engaged here, and the caller's turn only
                // while it calculates -- the weights read the phase at their own moment, which a window's edge may split.
                val keyword = w.keyword?.takeIf { id in engaged && it in keywords(client, id) } ?: continue
                val byKeyword = keywordCounts.getOrPut(client) { HashMap() }.getOrPut(id) { LinkedHashMap() }
                val c = byKeyword.getOrPut(keyword) { IntArray(2) }
                c[0]++
                if (w.weight == WfWeight.yourTurn && live(client, id)?.second?.calculates == true) c[1]++
            }
        }
        // What is being calculated in the caller's clients is listed even before any form counts toward it.
        val clients = forms.map { it.first.gedraId.client }.toSet() + cxt.client
        for (client in clients) {
            registry(client).workflows.values
                .filter { live(client, it.def.workflowId)?.second?.calculates == true }
                .forEach { countsFor(client, it.def.workflowId) }
        }

        return counts.entries.sortedBy { it.key }.flatMap { (client, byId) ->
            val resolve = copyResolver(cxt, client)
            val order = registry(client).workflows.keys.toList()
            byId.entries.sortedBy { order.indexOf(it.key) }.mapNotNull { (id, c) ->
                val (declared, phase) = live(client, id) ?: return@mapNotNull null
                linkedMapOf<String, Any?>(
                    WCOL.client to client,
                    WFD.workflowId to id,
                    WFD.label to workflowLabel(declared.def, resolve),
                    WCOL.phase to phase.name,
                    WAGG.eligible to c[WfColumnCategory.eligible.ordinal],
                    WAGG.engaged to c[WfColumnCategory.engaged.ordinal],
                    WAGG.finished to c[WfColumnCategory.finished.ordinal],
                    WAGG.keywords to CTAK.all.filter { it in keywords(client, id) }.map { keyword ->
                        val k = keywordCounts[client]?.get(id)?.get(keyword)
                        linkedMapOf<String, Any?>(CTKF.keyword to keyword, CTKQ.any to (k?.get(0) ?: 0), CTKQ.mine to (k?.get(1) ?: 0))
                    },
                )
            }
        }
    }
}
