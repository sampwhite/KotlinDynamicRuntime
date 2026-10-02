package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.http.request.SECT

/**
 * Design View (issue #972): an administrator's mode that shows, beside what a page renders, which definition each
 * part of it comes from and where that definition was declared. These are its names -- the request flag, the
 * fields of the explanation a response carries, and the endpoint that reads a definition -- kept in the kernel so
 * the webapp reads them by the names the backend writes them under.
 *
 * **Addresses.** A definition is named by an address: the config [slot] it is an entry of (the slots a stored
 * configuration is made of, [CCT]), its [key] there (the entry's primary-key value -- a trait id, a type name, a
 * workflow id), and optionally a [path] within the entry. The client is the request's. An address names the
 * *definition*, not where it is stored, so a global trait has the same address in every client.
 */
@Suppress("ConstPropertyName")
object DSV {
    /**
     * The value of the request's view flag ([com.dynamicruntime.common.endpoint.EP.view]) that asks for Design View.
     * The backend honors it only for a caller entitled to it -- a client administrator -- and otherwise answers as
     * though it were absent.
     */
    const val design = "design"

    // --- the explanation a Design View response carries ---

    /** The workflow view's Design View block: [workflow] and [types]. Absent from an ordinary response. */
    const val designBlock = "design"

    /** The workflow's own address and where it was declared. */
    const val workflow = "workflow"

    /** Each named type the view's `$defs` carries, by qualified name, to its address and where it was declared. */
    const val types = "types"

    // --- an address ---
    const val slot = "slot"
    const val key = "key"
    const val path = "path"

    // --- where a definition was declared ---

    /** One of [DesignOrigin]'s names. */
    const val origin = "origin"

    /** The configuration that declares it -- a stored bundle's name, or a source config's; absent for core code. */
    const val config = "config"

    /** Whether the definition can be edited in place: true only for one in the client's own stored configuration. */
    const val editable = "editable"

    // --- the definition read ---

    /** Reads one definition by address: its authored entry and where it was declared. Client-scoped admin. */
    const val definition = "/${SECT.clientAdmin}/design/definition"

    /** The schema namespace the Design View endpoint types live in. */
    const val namespace = "kdr.designView"
    const val definitionType = "DesignDefinition"

    /** The definition's authored entry: the slot entry as a stored configuration would hold it. */
    const val entry = "entry"

    /** For a stored definition: its bundle's latest revision number, and whether that revision is published. */
    const val version = "version"
    const val published = "published"

    // --- editing a workflow's copy (issue #984) ---

    /**
     * In the block: whether this caller may edit the workflow's copy **here** -- the workflow is the client's own
     * stored definition, and the client runs its latest revision, so an edit shows on this page once saved.
     */
    const val canEdit = "canEdit"

    /**
     * In the block, and sent back with an edit: a stamp of the workflow definition the page was drawn from. An edit
     * based on a definition that has since changed is refused rather than overwriting the change.
     */
    const val basedOn = "basedOn"

    /**
     * In the block: the layout entries the workflow alters, by type name and then field -- each with the workflow's
     * [entry], the [inherited] entry it replaces, and whether that inherited entry has [inheritedChanged] since.
     */
    const val layoutEdits = "layoutEdits"
    const val inherited = "inherited"
    const val inheritedChanged = "inheritedChanged"

    /**
     * Sets or clears the workflow's layout entry for one field of one type -- the workflow's own wording -- and
     * reloads the client so the page shows it. Client-scoped admin.
     */
    const val layoutEntryEdit = "/${SECT.clientAdmin}/design/layoutEntry"
    const val layoutEntryEditType = "DesignLayoutEntryEdit"

    // The edit's input, beside [entry] (the field's layout entry; absent to reset to the inherited one) and [basedOn].
    const val workflowId = "workflowId"
    const val typeName = "typeName"
    const val field = "field"
}

/**
 * Where a definition shown in Design View was declared (issue #972) -- which decides whether it can be edited in
 * place. Named for the reader of the inspector, not for the loader's mechanics.
 */
@Suppress("EnumEntryName")
enum class DesignOrigin {
    /** In the client's own **stored** configuration: editable in place. */
    stored,

    /** In configuration declared in **source** for this client: changing it means changing code. */
    source,

    /** A **global** definition every client sees, declared in source; a client cannot edit it in place. */
    global,
}
