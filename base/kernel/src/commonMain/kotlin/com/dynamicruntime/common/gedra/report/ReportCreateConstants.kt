package com.dynamicruntime.common.gedra.report

/**
 * Making a report from simple choices (issue #1492): the names of the paths read (`GET /clientAdmin/report/paths`), the
 * create's request and result (`POST /clientAdmin/report/create`), and the offer the reports listing makes. A report's
 * own fields are [RDEF]'s; these are only what the two endpoints add.
 */
@Suppress("ConstPropertyName")
object RCR {
    const val catalogTypeName = "ReportPathCatalog"
    const val groupTypeName = "ReportPathGroup"
    const val pathTypeName = "ReportPathOption"
    const val keyFieldTypeName = "ReportKeyField"
    const val createResultTypeName = "ReportCreateResult"

    /** The catalog's groups of paths, by where the value comes from. */
    const val groups = "groups"

    /** A group's source: [ReportPathGroupKind]. */
    const val source = "source"

    /** A group's trait or workflow id; absent for the form's own facts. */
    const val sourceId = "sourceId"
    const val paths = "paths"

    /** A keyed trait's key fields, in the key's order: what a column naming one entry gives a value for. */
    const val keyFields = "keyFields"
    const val field = "field"

    /** A create's column: the key values naming one entry of a keyed trait, in [keyFields]' order. */
    const val key = "key"

    /** A create's column: whether a totals report groups by it. */
    const val groupedBy = "groupedBy"

    /** A create's column: whether a form must hold a value in it to appear. */
    const val mustHaveValue = "mustHaveValue"

    /**
     * A create's column: whether the report's chart measures it (issue #1773). Named on the column, as [groupedBy] is,
     * since a column's id is made by the create; no column saying so measures the count of forms.
     */
    const val charted = "charted"

    /** The listing summary's offer: whether a report may be made here. */
    const val canCreate = "canCreate"

    /** The listing summary's offer: the sandbox a report is made from instead, for a client with one. */
    const val createFrom = "createFrom"

    /** The listing summary's offer: whether a report made here is a draft, as a sandbox's edits are. */
    const val createsDraft = "createsDraft"
}

/** Where a catalog group's values come from (issue #1492). */
@Suppress("EnumEntryName")
enum class ReportPathGroupKind {
    /** The form's own facts: when it was made, its status, its owner. */
    form,

    /** One of the traits the client's forms carry. */
    trait,

    /** One of the client's workflows: where a form stands in it, and its approvals. */
    workflow,
}
