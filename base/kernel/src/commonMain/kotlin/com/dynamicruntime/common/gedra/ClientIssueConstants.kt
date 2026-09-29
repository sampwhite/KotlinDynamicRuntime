package com.dynamicruntime.common.gedra

/**
 * Wire field names of a client's configuration issue -- a `GedraConfigIssue`, in `base:common` -- as the client and
 * config endpoints return it (issue #840). In the kernel so the frontend reads an issue by the same names the
 * backend writes it under: the Clients page shows what a check forgave, or why a client was dropped (issue #905).
 */
@Suppress("ConstPropertyName")
object GCI {
    const val message = "message"
    const val degradedTo = "degradedTo"
    const val client = "client"
    const val storedConfigId = "storedConfigId"
    const val elementKind = "elementKind"
    const val elementId = "elementId"

    /** `source` or `stored` -- a [GedraConfigOrigin] name. */
    const val origin = "origin"
}
