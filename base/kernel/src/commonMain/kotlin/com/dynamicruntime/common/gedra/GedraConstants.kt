package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SchTypeBuilder

/*
 * Endpoint paths and wire field-names for stored gedra data, in `base/kernel` so the front end names them from
 * the same strings the backend serves them under -- a backend rename then breaks compilation here rather than
 * failing at runtime, exactly as `ADEP`/`AFLD` do for the admin console (issue #393).
 *
 * They are pure string constants and drag no `base/common` dependency. The types they describe -- `GedraDataRow`
 * and the patch classes -- stay in `base/common`: a front end needs the names, not the machinery. That is the
 * same split `AuthUserRow` (in `base/common`) and its `ADF`/`AFLD` names (here) already make.
 */

/**
 * Endpoint paths for stored gedra data, and the type names a response is shaped by.
 *
 * **Build a client's path, not the bare shared one.** Each path here also exists per client
 * (`/gedra/acme/formDoc` beside `/gedra/formDoc`, issue #387), and the *shared* copy publishes the **global**
 * schema -- a form built from it offers choices a client has removed and is refused only on save. So a UI
 * names the path through `clientPath(GEP.formDoc, client)` (the kernel helper), or takes it from the
 * `/schema/endpoints` catalog, which already answers with the caller's own client's paths. Reaching for the
 * bare constant as the path selects the global surface; see `gedraSchema`'s note for the failure it causes.
 */
@Suppress("ConstPropertyName")
object GEP {
    /**
     * The section (and schema namespace) the gedra endpoints and their types live in -- every endpoint here is
     * client-shaped, so the whole section is copied per client (issue #387). Named once so the endpoint module,
     * the per-client copier, and the search-type key ([formDocsQueryDefName]) cannot disagree about it.
     */
    const val gedraNamespace = "gedra"

    const val formDocCreate = "/gedra/formDoc/create"
    const val formDoc = "/gedra/formDoc"
    const val formDocs = "/gedra/formDocs"

    /**
     * The distinct values a text trait takes across the caller's own documents (issue #581): what a filter
     * box's type-ahead suggests. Client-shaped like the rest, so a caller reaches its own client's copy.
     */
    const val formDocValues = "/gedra/formDoc/values"

    /** The type of one suggested value -- a `{value}` wrapper so the listing envelope carries a distinct-count. */
    const val fieldValueType = "FormDocFieldValue"

    /**
     * The named input type of the forms listing (issue #538). Named rather than inline because a per-client
     * copy carries the type *name*, which resolves to that client's variant -- so a client's usage-derived
     * search parameters can ride on it, where an inline field list would be copied literally and could not
     * vary. Its stable fields (offset, user) are authored; the search fields are generated per scope.
     */
    const val formDocsQuery = "FormDocsQuery"

    /** Imports form documents from search output for a target user (issue #545). */
    const val formDocImport = "/gedra/formDoc/import"

    /** The type naming an import's result -- what was created and what was thrown away. */
    const val importResultType = "ImportResult"

    const val patch = "/gedra/patch"

    /** The resolved-workflow view a creation page renders (issue #534). */
    const val workflowView = "/gedra/workflow/view"

    /** The type naming a resolved workflow view -- open by design, like a UiBlock. */
    const val workflowViewType = "WorkflowView"

    /** Saves a workflow task's collected entries (issue #535). */
    const val workflowSave = "/gedra/workflow/save"

    /** The type naming a workflow-save result -- either a refusal with reasons, or the created gedra. */
    const val workflowSaveType = "WorkflowSaveResult"

    /** The type naming what a "delete" removed. */
    const val deletedGedra = "DeletedGedra"

    /** The type of one target in a patch: a gedra, and what is asked of it. */
    const val patchTarget = "PatchTarget"

    /** The type of a patch's targets, grouped by gedra kind. */
    const val patchTargets = "PatchTargets"

    /** The type of what a patch did to one gedra. */
    const val patchedGedra = "PatchedGedra"
}

/**
 * Field and value names for the form-document import (issue #545): its input parameters, its result envelope,
 * and the discard categories. Each name matches its value, so a frontend and the handler read one spelling.
 */
@Suppress("ConstPropertyName")
object GIF {
    /** Input: the copied data to import -- a single form document, or a `{items: [...]}` wrapper. A Map. */
    const val data = "data"

    /** Input: throw away an entry whose trait the target client does not support (default true). */
    const val forgiveUnknownTraits = "forgiveUnknownTraits"

    /** Input: throw away an entry that fails validation rather than rejecting the whole import (default false). */
    const val forgiveInvalidEntries = "forgiveInvalidEntries"

    /** Input: keep the incoming entry ids instead of minting fresh ones (default false; env-auth only). */
    const val preserveEntryIds = "preserveEntryIds"

    /** Response: the documents created, each with its assigned gedra id and the traits excluded from it. */
    const val imported = "imported"

    /** Response: what was thrown away, one row per (category, trait) with a count. */
    const val discarded = "discarded"

    /** Response (imported[]): the traits excluded from this document, by trait id. */
    const val excludedTraits = "excludedTraits"

    /** Response (discarded[]): which kind of failure this row counts. */
    const val category = "category"

    /** Response (discarded[]): how many entries were thrown away for this (category, trait). */
    const val count = "count"

    // Discard categories.
    /** An entry whose trait the target client does not support. */
    const val unknownTrait = "unknownTrait"

    /** An entry that failed validation against its trait's schema. */
    const val invalidEntry = "invalidEntry"
}

/** Field names for a gedra's wire shape (see `GedraDataRow.toJsonMap`). Each name matches its value. */
@Suppress("ConstPropertyName")
object GDF {
    /**
     * Whether this call may write traits the client does not support. **Defaults to false** (issue #379).
     *
     * A client's `includedTraits` says which traits its people work with, and by default that is what a write
     * is held to -- so a typo in a `traitId`, or a trait belonging to somebody else's client, is refused
     * rather than quietly stored as an unrecognized shape. Set true to write outside the client's schema
     * deliberately: importing another client's export, or storing a trait this node has not loaded a
     * definition for.
     *
     * It governs **what this call writes**, not what the gedra already holds. A document carrying an entry
     * from before is still editable without the flag, as long as this call is not itself writing an
     * unsupported trait -- otherwise one legacy entry would make a document permanently unpatchable.
     *
     * Reads are unaffected. An unrecognized entry is always carried on the way out, which is what the
     * union's open default branch is for (#301) and what lets one client read another's export at all.
     */
    const val allowAdditionalTraits = "allowAdditionalTraits"
    const val gedraId = "gedraId"

    /** Names a workflow to act on, e.g., the input of the view endpoint (issue #534). */
    const val workflowId = "workflowId"

    /** Names the workflow task a save targets (issue #535). */
    const val taskId = "taskId"

    /** Names the save option chosen within a task (issue #535). */
    const val saveId = "saveId"
    const val gedraKind = "gedraKind"
    const val client = "client"
    const val userId = "userId"
    const val org = "org"
    const val entries = "entries"

    /**
     * A stored row's **computed display values** (issue #537): a list of `{traitId, label, value, kind}` the
     * list and read endpoints attach from the caller's client's trait-usage rules, so the forms table shows
     * the columns a client declared. Derived -- neither sent nor stored.
     */
    const val displayValues = "displayValues"

    /**
     * The document's **owner** as a block of user-type information (issue #580): a `{name?, email}` map keyed
     * by [DUF], attached to a listed row for a caller who may see other users' documents. Absent -- not empty --
     * for an ordinary caller, whose rows are all their own, and on a single read.
     *
     * Its own block rather than flat `ownerName`/`ownerEmail` keys because more of this kind of information is
     * coming: who last **updated** the document (an administrator may edit another user's), and when the owner
     * created and last touched it -- as distinct from the row's [updatedAt], which any user's edit moves. Each
     * of those is a block of the same [DUF] shape (`owner`, later `updatedBy`), so a frontend learns one shape
     * rather than a widening set of `ownerX`/`updatedByX` keys.
     */
    const val owner = "owner"

    const val createdAt = "createdAt"
    const val updatedAt = "updatedAt"

    /**
     * The workflow reference a gedra was created under, when a creation workflow made it (issue #533): a
     * `WfRef` text -- bundle id and workflow id. Configuration lineage, recorded once and never rewritten;
     * absent for a gedra created any other way. A key in the gedra's `data` map, not a trait, so every trait
     * stays user-editable.
     */
    const val creationWorkflowId = "creationWorkflowId"
}

/**
 * The field names inside a document's **user-information block** (issue #580) -- [GDF.owner] today, and the
 * `updatedBy` and per-user timestamps to come, which share this shape. A `D`ocument-`U`ser `F`ield. Each name
 * matches its value; only the fields a caller may see are attached, so a reader treats every one as optional.
 */
@Suppress("ConstPropertyName")
object DUF {
    /** The user's display name, present only when the account has one that is not its email (issue #580). */
    const val name = "name"

    /** The user's email (their login id). */
    const val email = "email"
}

/**
 * The properties inside a document's user-information block ([DUF]) -- declared once, beside the field names,
 * so a block's shape lives in one place rather than re-listed at each site (issue #580). The [GDF.owner] block
 * composes it today, and the `updatedBy` block to come will too. The email is always present; the name only
 * when the account has one that is not its email, so it is optional. Both are computed, never sent -- the
 * caller marks the containing block `derived`, and that carries to these, so they are not repeated as such.
 *
 * The property *type* defaults to string (see `property`), which is what both are; a caller composes this into
 * a `kObject` property whose own presence is already gated (an ordinary caller gets no block at all).
 */
fun SchTypeBuilder.userBlockProperties() {
    property(DUF.name, "The user's display name, when it is not the email.")
    property(DUF.email, "The user's email (their login id).", required = true)
}

/** Field names for a patch's request and its answer (issue #337). Each name matches its value. */
@Suppress("ConstPropertyName")
object GPF {
    /** The request's targets, keyed by gedra kind. */
    const val targets = "targets"

    /** Within a target: the edits asked of that one gedra. */
    const val edits = "edits"

    /** In the answer: what became of each edit, keyed by the trait it named. */
    const val outcomes = "outcomes"

    /** In an outcome: whether the edit changed anything. */
    const val applied = "applied"
}
