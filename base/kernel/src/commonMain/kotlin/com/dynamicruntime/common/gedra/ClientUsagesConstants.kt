package com.dynamicruntime.common.gedra

/**
 * The wire names of a client's listing columns on the client page (issue #1491): the catalog of its columns and the
 * traits a column may read, the save, and the removal. A column's own keys are its stored entry's, `CCT`'s
 * ([toRuleMap]).
 */
@Suppress("ConstPropertyName")
object CUS {
    const val catalogTypeName = "ClientUsageCatalog"
    const val columnTypeName = "ClientUsageColumn"
    const val traitTypeName = "ClientUsageTrait"
    const val fieldTypeName = "ClientUsageField"
    const val saveResultTypeName = "ClientUsageSaveResult"
    const val removeResultTypeName = "ClientUsageRemoveResult"
    const val layoutResultTypeName = "ClientUsageLayoutResult"

    /**
     * On the catalog, and a layout save (issue #1599): how the client's forms listing draws each row's workflows, a
     * `WorkflowLayout` name -- what the client (for one with a sandbox, its sandbox) runs.
     */
    const val workflowLayout = "workflowLayout"

    /** On the catalog (issue #1599): the stored configuration holding the client's listing settings; absent when none does. */
    const val layoutConfigName = "layoutConfigName"

    /** On the catalog: the client's columns, in the order the listing shows them. */
    const val columns = "columns"

    /** On the catalog: the traits a column may read -- the form traits the client supports -- each with its fields. */
    const val traits = "traits"

    /** On a trait: its fields a column may show, each with how a lone `${field}` column of it reads. */
    const val fields = "fields"

    /** On a field: its key, what a column's `${field}` names. */
    const val field = "field"

    /** On a field: what a person calls it -- its layout's label, else its title, else its key in words. */
    const val label = "label"

    /** On a trait: what it is for. */
    const val description = "description"

    /** On a field, and a column: how it reads (`UsageKind`), from the field's schema or the column's declared kind. */
    const val readsAs = "readsAs"

    /** On a field: whether it offers choices, which a column of it shows by their labels and suggests in its search. */
    const val hasChoices = "hasChoices"

    /** On a column: the stored configuration holding it; absent for one the client does not store. */
    const val configName = "configName"

    /** On a column: why it cannot be changed here; absent when it can. */
    const val notEditable = "notEditable"

    /**
     * On the catalog: whether the client shows the deployment's default columns, having none of its own. A save or a
     * removal then makes the defaults its own first, so that changing one keeps the others.
     */
    const val defaults = "defaults"

    /** On a column: a stored column the client does not run -- dropped when its configuration loaded; its issues say why. */
    const val notRunning = "notRunning"

    /** On the catalog: the stored configuration a new column is stored in, the one holding the client's definition. */
    const val newColumnsIn = "newColumnsIn"

    /** On a save, and its result: the column. */
    const val column = "column"

    /** On a save: the label of the column it changes, which may differ from the column's own when its heading is renamed. */
    const val previousLabel = "previousLabel"
}
