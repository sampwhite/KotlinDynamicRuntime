package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toOptEnum
import com.dynamicruntime.common.exception.KdrException

/** The client page's listing columns (issue #1491): the catalog, the save, and the removal. See [ClientUsageEdit]. */
internal fun SchModuleBuilder.clientUsagesEndpoints() {
    type(CUS.columnTypeName) {
        type = SCT.kObject
        description = "One of a client's listing columns (issue #1491), as its stored entry holds it, with where it is stored."
        usageRuleFields()
        property(
            CUS.readsAs, "How its value reads, from its field's schema or its declared kind: what its search filters on.", required = true,
        ) {
            options(UsageKind.entries)
        }
        property(CUS.configName, "The stored configuration holding it; absent for one the client does not store.")
        property(CUS.notEditable, "Why it cannot be changed here; absent when it can.")
        property(
            CUS.notRunning,
            "Whether it is stored but not run: dropped when its configuration loaded, which the client's issues say why.",
        ) {
            type = SCT.boolean
        }
    }

    type(CUS.fieldTypeName) {
        type = SCT.kObject
        description = "A field of a trait that a listing column may show (issue #1491)."
        property(CUS.field, $$"The field's key, which a column's `${field}` names.", required = true)
        property(CUS.label, "What a person calls it.", required = true)
        property(CUS.readsAs, "How a column showing it alone reads.", required = true) { options(UsageKind.entries) }
        property(CUS.hasChoices, "Whether it offers choices, which a column of it shows by their labels.") { type = SCT.boolean }
    }

    type(CUS.traitTypeName) {
        type = SCT.kObject
        description = "A form trait a client's listing column may read (issue #1491)."
        property(CCT.traitId, "The trait's id.", required = true)
        property(CUS.description, "What the trait is for.")
        property(CUS.fields, "Its fields a column may show: those that read as text, a number, a date, or a yes or no.", required = true) {
            type = SCT.array
            items { ref(CUS.fieldTypeName) }
        }
    }

    type(CUS.catalogTypeName) {
        type = SCT.kObject
        description = "A client's listing columns, and the traits a column may read (issue #1491)."
        property(CLD.client, "The client.", required = true)
        property(CUS.columns, "The client's columns, in the order its forms listing shows them.", required = true) {
            type = SCT.array
            items { ref(CUS.columnTypeName) }
        }
        property(
            CUS.defaults,
            "Whether the client shows the deployment's default columns, having none of its own. A save or a removal then " +
                "makes the defaults its own first, so that changing one keeps the others.",
            required = true,
        ) { type = SCT.boolean }
        property(CUS.newColumnsIn, "The stored configuration a new column is stored in: the one holding the client's definition.")
        property(CUS.traits, "The form traits the client supports, which a column may read.", required = true) {
            type = SCT.array
            items { ref(CUS.traitTypeName) }
        }
        property(
            CUS.workflowLayout,
            "How the client's forms listing draws each row's workflows, as it runs (issue #1599): `split`, a Your turn " +
                "column beside the Workflows cell, or `combined`, one cell.",
            required = true,
        ) { options(WorkflowLayout.entries) }
        property(CUS.layoutConfigName, "The stored configuration holding the client's listing settings; absent when none does.")
    }

    itemEndpoint(
        UADEP.clientUsages,
        "A client's listing columns (issue #1491): each column of its forms listing as stored, with how its value reads " +
            "and the stored configuration holding it, or why it cannot be changed here; and the form traits a column may " +
            "read, with their fields. For a client with a Shadow Sandbox, the columns its sandbox runs, drafts included. " +
            "The caller's own client, or one an administrator who sees every client names.",
        HttpMethod.GET,
        outputRef = CUS.catalogTypeName,
        inputFields = { overseenClientField(CLD.client, "The client whose columns to list; the caller's own when absent.") },
        needsClientConfig = true,
    ) { c, request ->
        ClientUsageEdit.catalog(c, overseenClient(c, request[CLD.client].toOptStr()))
    }

    type(CUS.saveResultTypeName) {
        type = SCT.kObject
        description = "What saving a listing column did (issue #1491)."
        property(CLD.client, "The client.", required = true)
        property(CIT.configName, "The stored configuration the column is in.", required = true)
        property(CUS.column, "The column as saved, its column id included when it has one.", required = true) { type = SCT.kObject }
        saveOutcomeFields()
    }

    generalEndpoint(
        UADEP.clientUsageSave,
        "Adds a column to a client's forms listing, or changes one (issue #1491). A new column is stored in the " +
            "configuration holding the client's definition, and a change where the column is stored; one the client " +
            "does not store is refused. A column is keyed by its trait and its heading, so a heading changed replaces the " +
            "entry where it stands. Its trait, and its column id, are fixed once saved: the server makes a column id from " +
            "the heading when the trait already has a column. A client showing the deployment's default columns makes " +
            "them its own first. Then as every editor's save: published and reloaded, or a draft for a client with a " +
            "Shadow Sandbox; the trial refuses a column that would add a problem.",
        HttpMethod.POST,
        outputRef = CUS.saveResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(
                CUS.column,
                "The column as it should be stored: `${CCT.traitId}`, `${CCT.label}` (its heading), `${CCT.display}` (a " +
                    "string script for its value), and optionally `${CCT.kind}`, `${CCT.substring}`, and " +
                    "`${CCT.presentation}`. A `${CCT.columnId}` given is ignored.",
                required = true,
            ) { type = SCT.kObject }
            field(CUS.previousLabel, "The heading of the column of its trait this changes; absent for a new column.")
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val wanted = usageFromRuleMap(request[CUS.column].toJsonMapOrEmpty())
        val result = ClientUsageEdit.save(c, client, request[CUS.previousLabel].toOptStr(), wanted, expectedModeOf(request))
        editorSaveResult(client, result.configName, CUS.column to result.usage?.toRuleMap(), result.mode, result.issues, null)
    }

    type(CUS.removeResultTypeName) {
        type = SCT.kObject
        description = "What removing a listing column did (issue #1491)."
        property(CLD.client, "The client.", required = true)
        property(CIT.configName, "The stored configuration the column was in.", required = true)
        property(CCT.label, "The column removed, by its heading.", required = true)
        saveOutcomeFields()
    }

    generalEndpoint(
        UADEP.clientUsageRemove,
        "Removes a column from a client's forms listing (issue #1491), from the stored configuration holding it; one the " +
            "client does not store is refused. A client showing the deployment's default columns makes the others its " +
            "own; one removing its last column of its own shows the defaults again. Then as every editor's save.",
        HttpMethod.POST,
        outputRef = CUS.removeResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(CCT.traitId, "The trait the column reads.", required = true)
            field(CCT.label, "The column's heading.", required = true)
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val label = request[CCT.label].toOptStr().orEmpty()
        val result = ClientUsageEdit.remove(c, client, request[CCT.traitId].toOptStr().orEmpty(), label, expectedModeOf(request))
        editorSaveResult(client, result.configName, CCT.label to label, result.mode, result.issues, null)
    }

    type(CUS.layoutResultTypeName) {
        type = SCT.kObject
        description = "What setting a client's workflow layout did (issue #1599)."
        property(CLD.client, "The client.", required = true)
        property(CIT.configName, "The stored configuration holding the client's listing settings.", required = true)
        property(CUS.workflowLayout, "The layout as stored.", required = true) { options(WorkflowLayout.entries) }
        saveOutcomeFields()
    }

    generalEndpoint(
        UADEP.clientUsageLayout,
        "Sets how a client's forms listing draws each row's workflows (issue #1599): `split`, a Your turn column beside the " +
            "Workflows cell, or `combined`, one cell. Stored in the configuration already holding the client's listing " +
            "settings, else the editors' own, where copy and menu edits land; a stored setting stands over one the " +
            "client's code declares. Then as every editor's save: published and reloaded, or a draft for a client with a " +
            "Shadow Sandbox.",
        HttpMethod.POST,
        outputRef = CUS.layoutResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(CUS.workflowLayout, "The layout.", required = true) { options(WorkflowLayout.entries) }
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val layout = request[CUS.workflowLayout].toOptEnum<WorkflowLayout>()
            ?: throw KdrException.mkInput("'${request[CUS.workflowLayout]}' is not a workflow layout.")
        val result = ClientListingEdit.setLayout(c, client, layout, expectedModeOf(request))
        editorSaveResult(client, result.configName, CUS.workflowLayout to result.layout.name, result.mode, result.issues, null)
    }
}
