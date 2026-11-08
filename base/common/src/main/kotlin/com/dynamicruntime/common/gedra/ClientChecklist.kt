package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService

/**
 * A new client's checklist (issue #1639): the few steps that make a self-serve client useful, each checked off from the
 * client's state rather than remembered, so a step done anywhere -- the Clients page, the workflow assistant, Design
 * View -- is done here too. What Home's "Getting started" card lists.
 *
 * Every step is a cheap read, since Home asks on every visit until the checklist is done or hidden: the workflows the
 * node already holds, the user cache's rows for the client, and whether the form cache holds any live form of it.
 *
 *  - **New form** ([CHKL.newForm]): the client's own creation workflow differs, as it runs, from the one it started
 *    with, so one changed and changed back again is not done: the starter (`ClientCreate.starterConfigs`), or, for a
 *    client built on a template that has a creation workflow, the template's, whose copy it holds.
 *  - **A workflow** ([CHKL.workflow]): a normal workflow among its own stored ones.
 *  - **Invite someone** ([CHKL.invite]): more than one active user.
 *  - **A first form** ([CHKL.firstForm]): at least one live form.
 *
 * The workflows are read as the client's editors change them -- from its sandbox, drafts included, when it has one
 * ([ClientStoredEdit.editedView]) -- so a workflow made as a draft counts: publishing it is the drafts card's to say.
 * Asked from inside a sandbox, it answers for the sandbox's parent, whose users and forms are the real ones.
 */
object ClientChecklist {
    fun of(cxt: KdrCxt, client: String): Map<String, Any?> {
        val owner = sandboxParentOf(client) ?: client
        val workflows = WorkflowService.get(cxt)
        // Its own: stored for the view read, which loads a parent's stored configuration under its sandbox's id -- the
        // rule the Clients page's workflow catalog lists a workflow's configuration by.
        val view = ClientStoredEdit.editedView(cxt, owner)
        val registry = workflows.forClient(view)
        fun isOwn(w: WfDeclared) = w.bundle.isStored && w.bundle.gedraId.client == view
        val steps = linkedMapOf(
            // The New form it runs, which its own replaces: a template's copy beside it is not the one.
            CHKL.newForm to newFormDone(cxt, owner, registry.creation?.takeIf(::isOwn)),
            CHKL.workflow to registry.workflows.values.any { isOwn(it) && it.def.entry == WfEntry.normal },
            CHKL.invite to (UserService.get(cxt).countActiveUsers(cxt, owner).total > 1),
            CHKL.firstForm to GedraDataService.get(cxt).hasLiveGedras(cxt, GedraDataType.formDoc, owner),
        )
        return linkedMapOf(
            CLD.client to owner,
            CHKL.steps to steps.map { (id, done) -> linkedMapOf(CHKL.id to id, CHKL.done to done) },
        )
    }

    /**
     * Whether [owner]'s New form is its own ([creation], its own stored creation workflow): different, as it runs, from the
     * one it started with -- its base's, for a client built on one that has one, else the starter template's -- so one
     * changed and then changed back is not its own. One written whole, or from another template, is.
     */
    private fun newFormDone(cxt: KdrCxt, owner: String, creation: WfDeclared?): Boolean {
        if (creation == null) return false
        val workflows = WorkflowService.get(cxt)
        val base = ClientService.get(cxt).known(owner)?.extendsFromClientId
        val fromBase = base?.let { workflows.forClient(it).creation?.def }
        val started = when {
            fromBase != null -> fromBase
            creation.def.fromTemplate == WFT.createForm -> workflows.forClient(null).workflow(WFT.createForm)?.def
            else -> null
        } ?: return true
        return asStarter(creation.def.toJsonMap()) != asStarter(started.toJsonMap())
    }

    /** A creation workflow's definition [def] less what names it, which a client's copy and its original never share. */
    private fun asStarter(def: Map<String, Any?>): Map<String, Any?> = def - setOf(WFD.workflowId, WFD.fromTemplate)
}

/** The checklist's endpoint (issue #1639); see [ClientChecklist]. */
internal fun SchModuleBuilder.clientChecklistEndpoints() {
    type(CHKL.stepTypeName) {
        type = SCT.kObject
        description = "One step of a new client's checklist (issue #1639)."
        property(
            CHKL.id,
            "Which step: `${CHKL.newForm}`, `${CHKL.workflow}`, `${CHKL.invite}`, or `${CHKL.firstForm}`.",
            required = true,
        )
        property(CHKL.done, "Whether the client's state says it is done.", required = true) { type = SCT.boolean }
    }
    type(CHKL.checklistTypeName) {
        type = SCT.kObject
        description = "A new client's checklist (issue #1639): the steps that make it useful, each checked off from its state."
        property(CLD.client, "The client -- the sandbox's parent, from inside it.", required = true)
        property(CHKL.steps, "The steps, in the order to take them.", required = true) {
            type = SCT.array
            items { ref(CHKL.stepTypeName) }
        }
    }

    itemEndpoint(
        UADEP.clientChecklist,
        "The caller's client's checklist (issue #1639): whether its New form is its own, it has a workflow of its own, " +
            "someone besides its first administrator, and a first form. From inside a sandbox, its parent's.",
        HttpMethod.GET,
        outputRef = CHKL.checklistTypeName,
        needsClientConfig = true,
    ) { c, _ -> ClientChecklist.of(c, overseenClient(c, null)) }
}
