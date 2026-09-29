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
    const val clientDef = "clientDef"

    /** One data-trait **declaration** this configuration contributes, keyed by trait id (issue #625). */
    const val traitDef = "traitDef"

    /** One trait-usage rule -- how a client presents a trait in a listing -- keyed by trait id (issue #625). */
    const val usageDef = "usageDef"

    /** One workflow definition, keyed by its workflow id. */
    const val workflowDef = "workflowDef"

    /** One schema type definition, keyed by the type's name. */
    const val schemaDef = "schemaDef"

    /** One Markdown-fragment overlay, keyed by the fragment file id (issue #625). */
    const val fragmentDef = "fragmentDef"

    /** One UiBlock overlay, keyed by the block id (issue #625). */
    const val uiBlockDef = "uiBlockDef"

    /** One cfact **name declaration** (declaration only, never a production), keyed by name (issue #625). */
    const val cfactDef = "cfactDef"

    // --- field names inside the slots' data (each matches its value) ---

    const val traitId = "traitId"
    const val typeName = "typeName"
    const val appliesTo = "appliesTo"
    const val primaryKey = "primaryKey"
    const val dataSchema = "dataSchema"
    const val description = "description"

    const val label = "label"
    const val display = "display"
    const val kind = "kind"
    const val substring = "substring"

    const val workflowId = "workflowId"
    const val definition = "definition"

    const val schema = "schema"

    const val fileId = "fileId"
    const val blockId = "blockId"
    const val content = "content"

    const val name = "name"
    const val group = "group"
    const val toFrontend = "toFrontend"
}
