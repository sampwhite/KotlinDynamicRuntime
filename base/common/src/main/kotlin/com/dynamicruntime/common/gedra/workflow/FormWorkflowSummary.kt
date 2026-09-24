package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The forms listing's **workflow summary** (issue #791): every workflow a caller should see in the workflow
 * column, over **every form they may see** rather than the page on screen -- which is what decides whether the
 * column is drawn at all, and what gives each row the labels and reasons its own state entries only name by id.
 *
 * A workflow is named when some form's state has an entry for it and the caller should see it there: it is live
 * (not unknown, not outside its lifetime -- [WorkflowPhases.live]) and shown for that form ([WfPhase.isShown]:
 * open for engagement, or the form is engaged with it). The same test the page's [formWorkflowsOf] applies per
 * row, so a summary never names a workflow no row will show.
 *
 * Labels and explanations go through the backend pass **in the workflow's own client**, so a cross-client
 * administrator reads each client's copy as that client's users do.
 */
object FormWorkflowSummary {
    fun of(cxt: KdrCxt, statesByForm: Map<GedraId, List<Map<String, Any?>>>): Map<String, Any?> {
        // Client, then workflow id, in first-seen order; resolved below once per client.
        val named = LinkedHashMap<String, LinkedHashMap<String, Pair<WfDeclared, WfPhase>>>()
        val registries = HashMap<String, WorkflowRegistry>()
        for ((gedraId, states) in statesByForm) {
            val client = gedraId.client
            val registry = registries.getOrPut(client) { WorkflowService.get(cxt).forClient(client) }
            val engaged = engagedWorkflowIds(states).toSet()
            for (entry in states) {
                if (entry[GE.traitId].toOptStr() != WFS.workflowState) continue
                val id = entry[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() ?: continue
                if (named[client]?.containsKey(id) == true) continue
                val declared = WorkflowPhases.live(cxt, registry, id)?.takeIf { it.def.entry == WfEntry.normal } ?: continue
                val phase = WorkflowPhases.of(cxt, declared.def)
                if (!phase.isShown(id in engaged)) continue
                named.getOrPut(client) { LinkedHashMap() }[id] = declared to phase
            }
        }
        // Clients in name order: the forms arrive in no order the summary should depend on (the id query is not
        // sorted), and an order that moved between identical calls would move the response's content hash too.
        val workflows = named.entries.sortedBy { it.key }.flatMap { (client, byId) ->
            val resolve = copyResolver(cxt, client)
            // Registry order within a client, so the summary does not depend on which form happened to come first.
            val order = registries.getValue(client).workflows.keys.toList()
            byId.entries.sortedBy { order.indexOf(it.key) }.map { (id, pair) ->
                val (declared, phase) = pair
                linkedMapOf<String, Any?>(
                    WCOL.client to client,
                    WFD.workflowId to id,
                    WFD.label to workflowLabel(declared.def, resolve),
                    WCOL.phase to phase.name,
                    WCOL.explanations to declared.def.eligibility.associate { it.id to resolve(it.explanation) },
                    WCOL.lastTask to declared.def.tasks.last().id,
                )
            }
        }
        return mapOf(WCOL.workflows to workflows)
    }
}

/**
 * Resolves a workflow's copy -- a label, an explanation -- through the backend pass **in [client]'s own copy**
 * (issues #791, #792): fragment overlays are per client, so a cross-client administrator reads each client's
 * workflows as that client's users do. A pull that cannot resolve falls back to the raw text rather than failing
 * the listing it decorates.
 */
fun copyResolver(cxt: KdrCxt, client: String): (String) -> String {
    val clientCxt = if (client == cxt.client) cxt else cxt.mkSubContext("workflowCopy", client)
    val fragments = MarkdownFragmentService.get(clientCxt)
    return { text ->
        try {
            fragments.backendPass(clientCxt, text)
        } catch (_: KdrException) {
            text
        }
    }
}

/** A workflow's name for a listing: its label resolved by [resolve], or its id when it declares none. */
fun workflowLabel(def: WfDef, resolve: (String) -> String): String =
    def.label.takeIf { it.isNotBlank() }?.let(resolve) ?: def.workflowId
