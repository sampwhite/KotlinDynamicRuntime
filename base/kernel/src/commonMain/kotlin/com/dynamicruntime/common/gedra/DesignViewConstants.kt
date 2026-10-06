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

    /**
     * On an address, when the client's own configuration alters a shared definition (issue #1013): that config,
     * as `{ origin, config }`. A definition is at most a shared declaration plus one client alteration -- within a
     * client a later declaration replaces an earlier one -- so this is one layer, not a list. In the definition read
     * it also carries the alteration's own authored entry, under [entry].
     */
    const val alteredBy = "alteredBy"

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
     * In the block, when [canEdit] is false: why, as a sentence the page shows -- so a workflow declared in source, or
     * a client that runs its published configuration, reads as such rather than as a missing control.
     */
    const val editRefusal = "editRefusal"

    /** In the block, beside [editRefusal]: which of the [DesignRefusal] reasons it is, by name. */
    const val editRefusalCode = "editRefusalCode"

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

    // --- the shared editor (issue #1029) ---

    /**
     * In the definition read of a trait or type: the client's workflows whose pages show it, each `{ workflowId,
     * label }` -- what an edit of the shared definition reaches.
     */
    const val usedBy = "usedBy"
    const val label = "label"

    /** In the definition read: by field, the workflows that override that field's copy with a variant of their own. */
    const val variantFields = "variantFields"

    /** In the definition read: whether this caller may edit the shared definition here -- see [canEdit]'s rules. */
    const val canEditShared = "canEditShared"

    /** In the definition read, when [canEditShared] is false: why, and which [DesignRefusal] it is. */
    const val sharedRefusal = "sharedRefusal"
    const val sharedRefusalCode = "sharedRefusalCode"

    /** In the definition read, and sent back with a shared edit: a stamp of the stored entry it was drawn from. */
    const val sharedBasedOn = "sharedBasedOn"

    /** Sets a field's shared copy and choices in the client's own definition (a POST). */
    const val sharedFieldEdit = "/${SECT.clientAdmin}/design/sharedField"
    const val sharedFieldEditType = "DesignSharedFieldEdit"

    /** The field's choices as they should stand: every existing value kept (relabeled or not), new ones added. */
    const val options = "options"
}

/**
 * Why Design View offers no edit of a workflow's copy (issue #1013): a closed set, so a refusal is one of a few known
 * reasons -- each with its sentence -- rather than whatever a new check happens to say.
 */
@Suppress("EnumEntryName")
enum class DesignRefusal {
    /** The workflow -- or, for the shared editor, the definition -- is declared in source: not this client's to edit here. */
    declaredInSource,

    /** The definition is declared globally, for every client (issue #1029): not one client's to edit. */
    declaredGlobally,

    /**
     * The client runs its published configuration and previews edits in its sandbox, so a change is made from there.
     * (A published-only client without a sandbox is edited here: a save publishes, as the Clients page's do.)
     */
    publishedOnly,

    /**
     * The configuration the edit would land in has somebody's unpublished changes, which a save -- it publishes, as the
     * Clients page's editors do (issue #1026) -- would take live with it. Publish or revert that configuration first.
     */
    unpublishedChanges,
}

/**
 * Where a definition shown in Design View was declared (issue #972), named for the reader of the inspector, not for
 * the loader's mechanics. What may be edited is the block's to say (`canEdit`), not the origin's.
 */
@Suppress("EnumEntryName")
enum class DesignOrigin {
    /** In the client's own **stored** configuration. */
    stored,

    /** In configuration declared in **source** for this client: changing it means changing code. */
    source,

    /** A **global** definition every client sees, declared in source; a client cannot edit it in place. */
    global,
}
