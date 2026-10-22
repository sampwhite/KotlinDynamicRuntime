package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.templateSlotsFromInfo
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** The client page's workflows made from templates (issue #1120): the catalog, and the save. See [ClientWorkflowEdit]. */
internal fun SchModuleBuilder.clientWorkflowsEndpoints() {
    type(CWF.templateTypeName) {
        type = SCT.kObject
        description = "A workflow template (issue #1120): a global workflow a client's workflow can be made from."
        property(CWF.definition, "The template's definition: its id, kind, label, tasks, and rules.", required = true) {
            type = SCT.kObject
        }
        property(CWF.refusal, "Why a workflow cannot be made from it for this client now; absent when it can.")
    }

    type(CWF.workflowTypeName) {
        type = SCT.kObject
        description = "One of a client's workflows (issue #1120), as it runs."
        property(CWF.definition, "Its definition as it runs; one made from a template names it in `fromTemplate`.", required = true) {
            type = SCT.kObject
        }
        property(CWF.configName, "The stored configuration holding it; absent for one declared in source code.")
        property(CWF.notEditable, "Why its slots cannot be changed here; absent when they can.")
    }

    type(CWF.traitTypeName) {
        type = SCT.kObject
        description = "A trait a client's workflow may collect (issue #1120)."
        property(CCT.traitId, "The trait's id.", required = true)
        property(CWF.description, "What the trait is for.")
    }

    type(CWF.catalogTypeName) {
        type = SCT.kObject
        description = "A client's workflows and the templates a workflow can be made from (issue #1120)."
        property(CLD.client, "The client.", required = true)
        property(CWF.templates, "The deployment's workflow templates.", required = true) {
            type = SCT.array
            items { ref(CWF.templateTypeName) }
        }
        property(CWF.workflows, "The client's workflows, in the order they are declared.", required = true) {
            type = SCT.array
            items { ref(CWF.workflowTypeName) }
        }
        property(CWF.traits, "The traits the client's workflows may collect.", required = true) {
            type = SCT.array
            items { ref(CWF.traitTypeName) }
        }
    }

    itemEndpoint(
        UADEP.clientWorkflows,
        "A client's workflows (issue #1120): the templates a workflow can be made from -- each with why it cannot be " +
            "now, when it cannot -- the client's own, each with its template or why its slots cannot be changed here, " +
            "and the traits its workflows may collect. The caller's own client, or one an administrator who sees every " +
            "client names.",
        HttpMethod.GET,
        outputRef = CWF.catalogTypeName,
        inputFields = { overseenClientField(CLD.client, "The client whose workflows to list; the caller's own when absent.") },
        needsClientConfig = true,
    ) { c, request ->
        ClientWorkflowEdit.catalog(c, overseenClient(c, request[CLD.client].toOptStr()))
    }

    type(CWF.saveResultTypeName) {
        type = SCT.kObject
        description = "What saving a workflow made from a template did (issue #1120)."
        property(CLD.client, "The client.", required = true)
        property(CWF.configName, "The stored configuration the workflow is in.", required = true)
        property(CWF.slots, "The workflow's slots now, its id given.", required = true) { type = SCT.kObject }
        saveOutcomeFields()
        draftImpactField()
    }

    generalEndpoint(
        UADEP.clientWorkflowSave,
        "Makes a workflow from a template for a client, or changes the slots of one made so (issue #1120): its name, " +
            "and per data-entry task its label and the traits it collects. Stored as the workflow's overlay of the " +
            "template, in a configuration of its own for a new workflow, or where the workflow is. Then as every " +
            "editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox, whose impact on the " +
            "live client's stored forms is reported for information. A live change leaving stored forms affected is " +
            "refused with the impact report unless the impact is acknowledged. Refused for a sandbox.",
        HttpMethod.POST,
        outputRef = CWF.saveResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(
                CWF.slots,
                "The workflow's slots: `${WFD.workflowId}` (absent for a new workflow), `${WFD.fromTemplate}`, " +
                    "`${WFD.label}`, and `${WFD.tasks}`, each with its `${WFD.id}`, `${WFD.label}` and `${WFD.traits}`.",
                required = true,
            ) { type = SCT.kObject }
            field(
                IMP.acknowledgeImpact,
                "Save although forms the client stores would be affected; without it, such a save is refused with the " +
                    "impact report.",
            ) { type = SCT.boolean }
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val gate = if (request[IMP.acknowledgeImpact] == true) ImpactGate.acknowledged else ImpactGate.refuse
        val result = ClientWorkflowEdit.save(c, client, templateSlotsFromInfo(request[CWF.slots].toJsonMapOrEmpty()), expectedModeOf(request), gate)
        editorSaveResult(client, result.configName, CWF.slots to result.slots.toInfo(), result.mode, result.issues, result.draftImpact)
    }
}
