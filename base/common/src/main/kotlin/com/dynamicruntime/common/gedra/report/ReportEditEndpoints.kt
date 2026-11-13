package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.editorSaveResult
import com.dynamicruntime.common.gedra.dryRunField
import com.dynamicruntime.common.gedra.dryRunOf
import com.dynamicruntime.common.gedra.markedDryRun
import com.dynamicruntime.common.gedra.expectedModeField
import com.dynamicruntime.common.gedra.expectedModeOf
import com.dynamicruntime.common.gedra.overseenClient
import com.dynamicruntime.common.gedra.overseenClientField
import com.dynamicruntime.common.gedra.saveOutcomeFields
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toOptStr

/**
 * Making a report from simple choices, and removing one (issue #1492): the paths a report may read, the create, and the
 * removal. See [ReportEdit].
 */
internal fun SchModuleBuilder.reportEditEndpoints() {
    type(RCR.keyFieldTypeName) {
        type = SCT.kObject
        description = "A field of a keyed trait's key (issue #1492), which a column reading one entry gives a value for."
        property(RCR.field, "The key field.", required = true)
        property(RRUN.label, "What a person calls it.", required = true)
        property(RRUN.kind, "How its value reads.", required = true) { options(ReportKind.entries) }
    }

    type(RCR.pathTypeName) {
        type = SCT.kObject
        description = "A report path a column may read (issue #1492), bound against the client as its configuration load binds one."
        property(RRUN.path, "The path, as a report column names it.", required = true)
        property(RRUN.label, "What a person calls the value: a column's heading, unless changed.", required = true)
        property(RRUN.kind, "How its value reads.", required = true) { options(ReportKind.entries) }
        property(
            RRUN.multiValued,
            "Whether a form may have several values there: a keyed trait's every entry, or a list. A column then says " +
                "which to show, by its combine.",
            required = true,
        ) { type = SCT.boolean }
        property(
            RCR.keyFields,
            "For a keyed trait, its key fields in the key's order: a column showing one entry gives a value for each, as its `${RCR.key}`.",
        ) {
            type = SCT.array
            items { ref(RCR.keyFieldTypeName) }
        }
    }

    type(RCR.groupTypeName) {
        type = SCT.kObject
        description = "The report paths of one source (issue #1492): the form itself, one of its traits, or one workflow."
        property(RCR.source, "Where the values come from.", required = true) { options(ReportPathGroupKind.entries) }
        property(RCR.sourceId, "The trait or workflow; absent for the form's own facts.")
        property(RRUN.label, "What a person calls the source.", required = true)
        property(RCR.paths, "Its paths, in the order offered.", required = true) {
            type = SCT.array
            items { ref(RCR.pathTypeName) }
        }
    }

    type(RCR.catalogTypeName) {
        type = SCT.kObject
        description = "The report paths a column of a client's report may read (issue #1492), grouped by source."
        property(RRUN.client, "The client.", required = true)
        property(RCR.groups, "The sources, the form's own facts first.", required = true) {
            type = SCT.array
            items { ref(RCR.groupTypeName) }
        }
    }

    itemEndpoint(
        UADEP.reportPaths,
        "The report paths a client's report may read (issue #1492), for making one from simple choices: the form's own " +
            "facts, each form trait's fields, and each workflow's standing and approvals, each bound against the client " +
            "as its configuration load binds a report -- so a page offers only what will be accepted -- with its kind " +
            "and whether a form may have several values there. For a client with a Shadow Sandbox, what the sandbox " +
            "runs. The caller's own client, or one an administrator who sees every client names.",
        HttpMethod.GET,
        outputRef = RCR.catalogTypeName,
        inputFields = { overseenClientField(RRUN.client, "The client whose paths to list; the caller's own when absent.") },
        needsClientConfig = true,
    ) { c, request ->
        ReportEdit.catalog(c, overseenClient(c, request[RRUN.client].toOptStr()))
    }

    type(RCR.createResultTypeName) {
        type = SCT.kObject
        description = "What making or removing a report did (issue #1492)."
        property(CLD.client, "The client.", required = true)
        property(CIT.configName, "The stored configuration the report is in, or was removed from.", required = true)
        property(RRUN.reportId, "The report's id: made from its name, or the one removed.", required = true)
        saveOutcomeFields()
    }

    generalEndpoint(
        UADEP.reportCreate,
        "Makes a report from simple choices (issue #1492), in the client's `reports` configuration, made when it has " +
            "none. One-way: the report is changed or removed afterwards in the bundle editor. Its id is made from its " +
            "name, and one the client already has is refused. Each column's id is made from its heading; a column giving " +
            "`${RCR.key}` reads the one entry of a keyed trait those values name, its path reading every entry otherwise. " +
            "Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox; the " +
            "trial refuses a report that does not bind.",
        HttpMethod.POST,
        outputRef = RCR.createResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(RRUN.client)
            field(RDEF.label, "The report's name.", required = true)
            field(RDEF.description, "What the report shows, for the person choosing it.")
            field(
                RDEF.columns,
                "The columns, in the order shown. Each has `${RDEF.label}` (its heading) and `${RDEF.path}` (from the " +
                    "paths read), and optionally `${RDEF.combine}`, `${RDEF.rollup}`, `${RCR.key}` (the key values naming " +
                    "one entry of a keyed trait), `${RCR.groupedBy}` (a totals report groups by it), " +
                    "`${RCR.mustHaveValue}` (a form appears only with a value in it), and `${RCR.charted}` (the " +
                    "report's chart measures it).",
                required = true,
            ) {
                type = SCT.array
                items { type = SCT.kObject }
            }
            field(RDEF.history, "Whether the nightly job keeps a snapshot of the report's grouped run.") { type = SCT.boolean }
            field(
                RDEF.chart,
                "The chart a totals report's grouped run opens on (issue #1773), measuring the column marked " +
                    "`${RCR.charted}`, or the count of forms when none is; absent for the table alone.",
            ) { options(ReportChartKind.entries) }
            expectedModeField()
            dryRunField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[RRUN.client].toOptStr())
        val dryRun = dryRunOf(request)
        val result = ReportEdit.create(c, client, request, expectedModeOf(request), dryRun)
        editorSaveResult(c, client, result.configName, RRUN.reportId to result.reportId, result.mode, result.issues, null)
            .markedDryRun(dryRun)
    }

    generalEndpoint(
        UADEP.reportRemove,
        "Removes a report the client stores (issue #1492), from the stored configuration holding it, wherever that is. " +
            "One declared in code, or copied from the client this one is built on, is refused. Its snapshots are " +
            "kept. Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox.",
        HttpMethod.POST,
        outputRef = RCR.createResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(RRUN.client)
            field(RRUN.reportId, "The report to remove.", required = true)
            expectedModeField()
            dryRunField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[RRUN.client].toOptStr())
        val dryRun = dryRunOf(request)
        val result = ReportEdit.remove(c, client, request[RRUN.reportId].toOptStr().orEmpty(), expectedModeOf(request), dryRun)
        editorSaveResult(c, client, result.configName, RRUN.reportId to result.reportId, result.mode, result.issues, null)
            .markedDryRun(dryRun)
    }
}
