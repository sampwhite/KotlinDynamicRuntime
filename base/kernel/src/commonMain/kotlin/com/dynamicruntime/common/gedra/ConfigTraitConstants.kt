package com.dynamicruntime.common.gedra

/**
 * The core config traits (issues #316, #625): the trait ids the storage slots are keyed by, and their data fields.
 * The traits themselves are declared in `base:common` (`CoreConfigTraits.kt`); the names are here so the frontend
 * reads a client's definition -- its traits' metadata, say -- by the names the backend writes (issue #906).
 */
@Suppress("ConstPropertyName")
object CCT {
    const val configName = "coreConfigTraits"

    // --- the storage slots (trait ids) ---

    /** The client definition itself; single-instance, since a bundle defines at most one client. */
    const val clientDef = "kdr:clientDef"

    /** One data-trait **declaration** this configuration contributes, keyed by trait id (issue #625). */
    const val traitDef = "kdr:traitDef"

    /** One trait-usage rule -- how a client presents a trait in a listing -- keyed by trait id (issue #625). */
    const val usageDef = "kdr:usageDef"

    /** One workflow definition, keyed by its workflow id. */
    const val workflowDef = "kdr:workflowDef"

    /** One schema type definition, keyed by the type's name. */
    const val schemaDef = "kdr:schemaDef"

    /** One Markdown-fragment overlay, keyed by the fragment file id (issue #625). */
    const val fragmentDef = "kdr:fragmentDef"

    /** One UiBlock overlay, keyed by the block id (issue #625). */
    const val uiBlockDef = "kdr:uiBlockDef"

    /** One cfact **name declaration** (declaration only, never a production), keyed by name (issue #625). */
    const val cfactDef = "kdr:cfactDef"

    /** One named report, keyed by its report id (issue #979). */
    const val reportDef = "kdr:reportDef"

    /** One customer, keyed by its customer id (issue #1107); only `hub`'s stored configuration holds any. */
    const val customerDef = "kdr:customerDef"

    /** One file ref -- a named file of the client's, by where its bytes are -- keyed by its ref id (issues #1153, #1154). */
    const val fileRefDef = "kdr:fileRefDef"

    /** The client's listing-wide settings (issue #1599); single-instance, as [clientDef] is. See `ListingDef`. */
    const val listingDef = "kdr:listingDef"

    // --- what a stored entry says of the code it stands over (issue #1634) ---

    /**
     * On a stored entry of a keyed slot: that it stands over the code layer's entry with the same key -- the template's
     * copies, the client's own code, a sandbox's code -- the one value being [overridesCodeValue]. Without it, a stored
     * entry whose key matches a code entry is refused at write, and loads as it always has, with an issue saying to
     * mark it. A plain field rather than a `$` note, which a request drops and a stored entry may not hold.
     */
    const val overrides = "overrides"

    /** The value of [overrides] for an entry that stands over the code's, whole. */
    const val overridesCodeValue = "code"

    /**
     * The value of [overrides] for an entry that holds only what it changes of the code's (issue #1657): an overlay,
     * merged over the code's entry as it is now on every read. Only a workflow or a schema type takes one
     * (`codeOverlaySlots`).
     */
    const val overridesCodeOverlayValue = "codeOverlay"

    // --- field names inside the slots' data (each matches its value) ---

    const val traitId = "traitId"
    const val typeName = "typeName"
    const val appliesTo = "appliesTo"
    const val dataSchema = "dataSchema"
    const val description = "description"

    const val label = "label"
    const val display = "display"
    const val kind = "kind"
    const val substring = "substring"
    const val presentation = "presentation"
    const val columnId = "columnId"

    const val workflowId = "workflowId"
    const val definition = "definition"

    const val reportId = "reportId"

    const val schema = "schema"

    const val fileId = "fileId"
    const val blockId = "blockId"
    const val content = "content"

    const val name = "name"
    const val group = "group"
    const val toFrontend = "toFrontend"

    const val refId = "refId"
    const val source = "source"
    const val hash = "hash"
    const val root = "root"
    const val path = "path"

    const val workflowLayout = "workflowLayout"
}
