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

    /**
     * On a workflow whose labels pull copy from a fragment file (`%{@t(...)}`): each label as the page's client sees it
     * -- the workflow's own under `label`, each task's under `tasks` by task id -- while [definition] keeps them as
     * written, for an editor. Absent when no label pulls any.
     */
    const val labels = "labels"

    /** On a workflow, and a save's result: the stored configuration holding it. */
    const val configName = "configName"

    /** On a save, and its result: the workflow's slots. */
    const val slots = "slots"

    const val paramsResultTypeName = "ClientWorkflowParamsResult"
    const val paramsTypeName = "ClientWorkflowParams"
    const val settingTypeName = "ClientWorkflowParamsSetting"
    const val removeResultTypeName = "ClientWorkflowRemoveResult"

    /**
     * On a normal workflow, and a parameter save's result (issues #1356, #1386): its parameters as the page's client or
     * sandbox runs them -- each layer (`WFP.current`, `WFP.parent`, `WFP.source`), the earlier settings
     * (`WFP.history`), and what the layers make (`WFP.inForce`, `WFP.from`). A workflow of another kind has none.
     */
    const val params = "params"

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
