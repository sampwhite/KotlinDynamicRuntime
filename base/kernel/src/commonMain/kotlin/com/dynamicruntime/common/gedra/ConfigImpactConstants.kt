package com.dynamicruntime.common.gedra

/**
 * The names of a **publish impact report** (issue #935): what publishing one of a client's stored configurations
 * would do to the data the client already stores. The report is computed in `base:common` (`ConfigImpact`); the
 * names are here, in the kernel, so the web app reads a report -- from the report endpoint, or from a refused
 * publish's `extraData` -- by the names the backend writes it under.
 */
@Suppress("ConstPropertyName")
object IMP {
    // --- type names (in the config endpoints' namespaces) ---
    const val reportType = "ConfigImpactReport"
    const val findingType = "ConfigImpactFinding"

    // --- the report's fields (each matches its value) ---
    /** The client whose data the report is about: always the configuration's owner, never a sandbox. */
    const val client = "client"

    /** The stored configuration whose publishing is judged. */
    const val name = "name"

    /** The version of that configuration's latest revision, the one a publish would make live. */
    const val version = "version"

    /** How many stored rows were examined. */
    const val scanned = "scanned"

    /**
     * True when the client stores more rows than one report reads (`KDR_IMPACT_SCAN_LIMIT`): nothing was examined,
     * and a publish asks for acknowledgement all the same, since nothing says it is harmless.
     */
    const val tooLarge = "tooLarge"

    /** The findings, one per kind and subject; empty when publishing changes nothing stored data relies on. */
    const val findings = "findings"

    // --- a finding's fields ---
    /** Which [ImpactKind] this is, by name. */
    const val kind = "kind"

    /** The trait a trait finding is about. */
    const val traitId = "traitId"

    /** The workflow a workflow finding is about. */
    const val workflowId = "workflowId"

    /** The task a stranded-state finding is about. */
    const val taskId = "taskId"

    /** How many rows the finding applies to. */
    const val count = "count"

    /** A few of those rows' gedra ids (at most [sampleLimit]), so an administrator can go and look. */
    const val sampleIds = "sampleIds"

    /** The most sample ids a finding carries. */
    const val sampleLimit = 5

    // --- the publish side ---
    /**
     * On a publish request: go ahead although the report finds something. Without it, a publish whose report is not
     * empty is refused, and the refusal carries the report.
     */
    const val acknowledgeImpact = "acknowledgeImpact"

    /** The `errorCode` of a publish refused over its impact; the report is under [report] in its `extraData`. */
    const val refusedCode = "publishImpact"

    /** The `extraData` key a refused publish carries its report under. */
    const val report = "impactReport"
}

/**
 * What a publish would do to a stored row (issue #935). Each finding counts rows that are fine under the
 * configuration the client runs now and would not be under the candidate -- a row that already has the problem is
 * not the publish's doing, and is not reported.
 */
enum class ImpactKind {
    /** An entry's trait is one the client would no longer support: its entries stop being validated or offered. */
    traitGone,

    /**
     * An entry no longer validates against its trait: a field removed or narrowed, or a required one added. Every
     * later edit of the form is refused until the entry is fixed, since an edit checks the whole document.
     */
    dataInvalid,

    /** A form created by, or taking part in, a workflow the client would no longer have. */
    workflowGone,

    /** A form whose recorded state names a task its workflow would no longer define. */
    stateStranded,
}
