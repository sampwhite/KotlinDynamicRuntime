package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfWindows
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
        property(CWF.windowsNotEditable, "For a normal workflow, why its time windows cannot be changed here; absent when they can, and for any other kind.")
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
        property(CWF.timeZone, "The client's time zone, which a workflow's time windows are entered and shown in.")
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

    // --- a normal workflow's time windows (issue #1121) ---
    type(CWF.windowsResultTypeName) {
        type = SCT.kObject
        description = "What setting a workflow's time windows did (issue #1121)."
        property(CLD.client, "The client.", required = true)
        property(CWF.configName, "The stored configuration the workflow is in.", required = true)
        property(CWF.windows, "The workflow's time windows now.", required = true) { type = SCT.kObject }
        saveOutcomeFields()
        draftImpactField()
    }

    generalEndpoint(
        UADEP.clientWorkflowWindows,
        "Sets the time windows of one of a client's normal workflows (issue #1121) -- `${WFD.lifetime}`, " +
            "`${WFD.relevancy}` and `${WFD.engagement}`, each a half-open `{start, end}` of moments, either bound " +
            "optional -- where the workflow is stored: into its overlay for one made from a template, into its " +
            "definition otherwise. Then as every editor's save. A live change that would, now, freeze or hide forms " +
            "engaged with the workflow is refused with the impact report unless the impact is acknowledged. Refused for " +
            "a sandbox, and for a workflow the client does not store.",
        HttpMethod.POST,
        outputRef = CWF.windowsResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(WFD.workflowId, "The workflow.", required = true)
            field(CWF.windows, "Its time windows: absent or empty, always open.") { type = SCT.kObject }
            field(
                IMP.acknowledgeImpact,
                "Save although forms engaged with the workflow would be frozen or hidden; without it, such a save is " +
                    "refused with the impact report.",
            ) { type = SCT.boolean }
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val gate = if (request[IMP.acknowledgeImpact] == true) ImpactGate.acknowledged else ImpactGate.refuse
        val windows = try {
            WfWindows.fromInfo(request[CWF.windows].toJsonMapOrEmpty())
        } catch (e: KdrException) {
            throw KdrException.mkInput("The windows do not read: ${e.message}")
        }
        val result = ClientWorkflowEdit.saveWindows(c, client, request[WFD.workflowId].toOptStr().orEmpty(), windows, expectedModeOf(request), gate)
        editorSaveResult(client, result.configName, CWF.windows to result.windows.toInfo(), result.mode, result.issues, result.draftImpact)
    }

    // --- removing a workflow made from a template (issue #1346) ---
    type(CWF.removeResultTypeName) {
        type = SCT.kObject
        description = "What removing a workflow made from a template did (issue #1346)."
        property(CLD.client, "The client.", required = true)
        property(CWF.configName, "The stored configuration the workflow was in.", required = true)
        property(WFD.workflowId, "The workflow removed.", required = true)
        saveOutcomeFields()
        draftImpactField()
    }

    generalEndpoint(
        UADEP.clientWorkflowRemove,
        "Removes one of a client's workflows made from a template (issue #1346), from the stored configuration holding " +
            "it. Then as every editor's save. A live removal of a workflow forms take part in is refused with the impact " +
            "report unless the impact is acknowledged. Refused for a sandbox, and for a workflow written whole or not " +
            "stored by the client.",
        HttpMethod.POST,
        outputRef = CWF.removeResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(WFD.workflowId, "The workflow to remove.", required = true)
            field(
                IMP.acknowledgeImpact,
                "Remove it although forms take part in it; without it, such a removal is refused with the impact report.",
            ) { type = SCT.boolean }
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val gate = if (request[IMP.acknowledgeImpact] == true) ImpactGate.acknowledged else ImpactGate.refuse
        val workflowId = request[WFD.workflowId].toOptStr().orEmpty()
        val result = ClientWorkflowEdit.remove(c, client, workflowId, expectedModeOf(request), gate)
        editorSaveResult(client, result.configName, WFD.workflowId to workflowId, result.mode, result.issues, result.draftImpact)
    }
}
