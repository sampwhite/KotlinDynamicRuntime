package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsFrom
import com.dynamicruntime.common.gedra.workflow.templateSlotsFromInfo
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfStrings
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
        property(
            CWF.labels,
            "When its labels pull copy from a fragment file: each as the page's client sees it, the workflow's under " +
                "`${WFD.label}` and each task's under `${WFD.tasks}` by task id. Absent when none pulls any.",
        ) { type = SCT.kObject }
        property(
            CWF.params,
            "For a normal workflow, its parameters as the page's client or sandbox runs them (issue #1356): its time " +
                "windows, set here whoever declared the workflow. Absent for any other kind.",
        ) { ref(CWF.paramsTypeName) }
    }

    // --- a workflow's parameters (issue #1356) ---
    type(CWF.settingTypeName) {
        type = SCT.kObject
        description = "One setting of a workflow's parameters: the values, when they were set, and by whom."
        property(
            WFP.params,
            "The values the scope set itself: `${WFP.windows}`, a normal workflow's time windows, each a half-open " +
                "`{start, end}` of moments, either bound optional. A parameter absent here was not set by this setting.",
            required = true,
        ) { type = SCT.kObject }
        property(WFP.setAt, "When they were set; absent for values nobody has set.") { dateTime() }
        property(WFP.setBy, "The user who set them, by id.") { type = SCT.integer }
        property(WFP.setByName, "The user who set them, by name.")
    }
    type(CWF.paramsTypeName) {
        type = SCT.kObject
        description = "A workflow's parameters in a client or sandbox (issues #1356, #1386), with their earlier settings."
        property(
            WFP.inForce,
            "The parameters in force, each the scope's own, else -- on a sandbox -- its client's, else the default the " +
                "client's source code declares. A parameter set nowhere is absent, and is no limit.",
            required = true,
        ) { type = SCT.kObject }
        property(
            WFP.from,
            "Where each parameter in force that is not the scope's own comes from, by key: " +
                "${WfParamsFrom.entries.joinToString(" or ") { "`${it.name}`" }}. Absent when every one is its own.",
        ) { type = SCT.kObject }
        property(WFP.current, "The scope's own setting; absent while it has set nothing.") { ref(CWF.settingTypeName) }
        property(WFP.history, "The scope's earlier settings, newest first, at most ${WFP.historyKept}.", required = true) {
            type = SCT.array
            items { ref(CWF.settingTypeName) }
        }
        property(WFP.parent, "On a sandbox: its client's own setting, which it follows for any parameter it does not set; absent while the client has none.") {
            ref(CWF.settingTypeName)
        }
        property(WFP.source, "The defaults the client's source code declares, which it follows for any parameter nobody set; absent when there are none.") {
            type = SCT.kObject
        }
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

    // --- a normal workflow's parameters, its time windows first (issues #1121, #1356) ---
    type(CWF.paramsResultTypeName) {
        type = SCT.kObject
        description = "What setting a workflow's parameters did (issue #1356): the parameters as the client or sandbox now runs them."
        property(CLD.client, "The client or sandbox they were set in.", required = true)
        property(CWF.params, "The workflow's parameters now, with their earlier settings.", required = true) { ref(CWF.paramsTypeName) }
    }

    generalEndpoint(
        UADEP.clientWorkflowParams,
        "Changes the parameters of one of a client's normal workflows (issues #1356, #1386) -- `${WFP.windows}`: its " +
            "time windows, `${WFD.lifetime}`, `${WFD.relevancy}` and `${WFD.engagement}`, each a half-open `{start, end}` " +
            "of moments, either bound optional -- in the client or sandbox named, whoever declared the workflow. Each " +
            "parameter in `${WFP.params}` becomes the scope's own, and each named in `${WFP.unset}` follows its fallback " +
            "again; any other is left as it is. In force at once, with no draft and no publish; what they replace is " +
            "kept among the earlier settings. A change that would, now, freeze or hide forms engaged with the workflow " +
            "is refused with the impact report unless the impact is acknowledged.",
        HttpMethod.POST,
        outputRef = CWF.paramsResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(WFD.workflowId, "The workflow.", required = true)
            field(WFP.params, "The parameters to set here; one set empty is no limits.") { type = SCT.kObject }
            field(WFP.unset, "The parameters to stop setting here, by key, so each follows its fallback again.") {
                type = SCT.array
                items { type = SCT.string }
            }
            field(
                IMP.acknowledgeImpact,
                "Save although forms engaged with the workflow would be frozen or hidden; without it, such a save is " +
                    "refused with the impact report.",
            ) { type = SCT.boolean }
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val gate = if (request[IMP.acknowledgeImpact] == true) ImpactGate.acknowledged else ImpactGate.refuse
        val params = try {
            WfParams.fromInfo(request[WFP.params])
        } catch (e: KdrException) {
            throw KdrException.mkInput("The parameters do not read: ${e.message}")
        }
        val unset = request[WFP.unset].toJsonListOfStrings().toSet()
        val resolved = ClientWorkflowEdit.saveParams(c, client, request[WFD.workflowId].toOptStr().orEmpty(), params, unset, gate)
        mapOf(CLD.client to client, CWF.params to ClientWorkflowEdit.paramsInfo(c, resolved))
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
