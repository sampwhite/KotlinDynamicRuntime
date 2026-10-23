package com.dynamicruntime.common.gedra

/**
 * The wire names of a client's workflows on the client page (issue #1120): the templates a workflow can be made from,
 * the client's workflows, and the save that makes one or changes its slots. A workflow's own keys are `WFD`'s.
 */
@Suppress("ConstPropertyName")
object CWF {
    const val catalogTypeName = "ClientWorkflowCatalog"
    const val templateTypeName = "ClientWorkflowTemplate"
    const val workflowTypeName = "ClientWorkflowInfo"
    const val traitTypeName = "ClientWorkflowTrait"
    const val saveResultTypeName = "ClientWorkflowSaveResult"

    /** On the catalog: the deployment's templates, each with why it cannot be used now, if it cannot. */
    const val templates = "templates"

    /** On the catalog: the client's workflows, each with its template, or why its slots cannot be changed here. */
    const val workflows = "workflows"

    /** On the catalog: the traits a workflow of the client's may collect. */
    const val traits = "traits"

    /** On a template or a workflow: its definition, as it runs -- what the page explains the fixed parts from. */
    const val definition = "definition"

    /** On a template: why a workflow cannot be made from it now (a plan's limit, a kind the client has); absent when it can. */
    const val refusal = "refusal"

    /** On a workflow: why its slots cannot be changed here; absent when they can. */
    const val notEditable = "notEditable"

    /** On a workflow, and a save's result: the stored configuration holding it. */
    const val configName = "configName"

    /** On a save, and its result: the workflow's slots. */
    const val slots = "slots"

    const val windowsResultTypeName = "ClientWorkflowWindowsResult"
    const val removeResultTypeName = "ClientWorkflowRemoveResult"

    /**
     * On a workflow: why its time windows cannot be changed here (issue #1121), for a normal workflow only -- one the
     * client does not store as its own. Absent when they can, and for a workflow of another kind, which has none.
     */
    const val windowsNotEditable = "windowsNotEditable"

    /** On a windows save, and its result: the workflow's time windows, `WFD.lifetime` and its neighbors. */
    const val windows = "windows"

    /** On the catalog: the client's time zone, which a window's bounds are entered and shown in. */
    const val timeZone = "timeZone"

    /** On a trait: its description. */
    const val description = "description"

    /**
     * The prefix of the stored configuration the editor makes for a new workflow: one per workflow, named for it
     * (`workflow_<workflowId>`), so each is published, judged, and, later, carried on its own.
     */
    const val configPrefix = "workflow_"
}
