package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraId
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
 * ([WorkflowPhases.live]). The clients are those of the caller's visible forms, plus the caller's own.
 */
object WorkflowAggregate {
    fun of(cxt: KdrCxt, statesByForm: Map<GedraId, List<Map<String, Any?>>>): List<Map<String, Any?>> {
        val registries = HashMap<String, WorkflowRegistry>()
        fun registry(client: String) = registries.getOrPut(client) { WorkflowService.get(cxt).forClient(client) }
        // client -> workflow id -> counts by category; a key present means the workflow is listed.
        val counts = HashMap<String, LinkedHashMap<String, IntArray>>()
        fun countsFor(client: String, id: String) =
            counts.getOrPut(client) { LinkedHashMap() }.getOrPut(id) { IntArray(WfColumnCategory.entries.size) }

        fun liveNormal(client: String, id: String): WfDeclared? =
            WorkflowPhases.live(cxt, registry(client), id)?.takeIf { it.def.entry == WfEntry.normal }

        for ((gedraId, states) in statesByForm) {
            val client = gedraId.client
            // Every live workflow the form's state names is one it "appears on", counted or not.
            states.filter { it[GE.traitId].toOptStr() == WFS.workflowState }
                .mapNotNull { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() }
                .filter { liveNormal(client, it) != null }
                .forEach { countsFor(client, it) }
            val phaseOf = { id: String -> liveNormal(client, id)?.let { WorkflowPhases.of(cxt, it.def) } }
            formWorkflowsOf(states, phaseOf).forEach { countsFor(client, it.workflowId)[it.category.ordinal]++ }
        }
        // What is being calculated in the caller's clients is listed even before any form counts toward it.
        val clients = statesByForm.keys.map { it.client }.toSet() + cxt.client
        for (client in clients) {
            registry(client).workflows.values
                .filter { it.def.entry == WfEntry.normal && WorkflowPhases.of(cxt, it.def).calculates }
                .forEach { countsFor(client, it.def.workflowId) }
        }

        return counts.entries.sortedBy { it.key }.flatMap { (client, byId) ->
            val resolve = copyResolver(cxt, client)
            val order = registry(client).workflows.keys.toList()
            byId.entries.sortedBy { order.indexOf(it.key) }.mapNotNull { (id, c) ->
                val declared = liveNormal(client, id) ?: return@mapNotNull null
                linkedMapOf<String, Any?>(
                    WCOL.client to client,
                    WFD.workflowId to id,
                    WFD.label to workflowLabel(declared.def, resolve),
                    WCOL.phase to WorkflowPhases.of(cxt, declared.def).name,
                    WAGG.eligible to c[WfColumnCategory.eligible.ordinal],
                    WAGG.engaged to c[WfColumnCategory.engaged.ordinal],
                    WAGG.finished to c[WfColumnCategory.finished.ordinal],
                )
            }
        }
    }
}
