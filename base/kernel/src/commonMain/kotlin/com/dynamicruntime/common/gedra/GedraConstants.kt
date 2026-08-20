package com.dynamicruntime.common.gedra

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
    const val formDocCreate = "/gedra/formDoc/create"
    const val formDoc = "/gedra/formDoc"
    const val formDocs = "/gedra/formDocs"

    const val patch = "/gedra/patch"

    /** The type naming what a "delete" removed. */
    const val deletedGedra = "DeletedGedra"

    /** The type of one target in a patch: a gedra, and what is asked of it. */
    const val patchTarget = "PatchTarget"

    /** The type of a patch's targets, grouped by gedra kind. */
    const val patchTargets = "PatchTargets"

    /** The type of what a patch did to one gedra. */
    const val patchedGedra = "PatchedGedra"
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
    const val gedraKind = "gedraKind"
    const val client = "client"
    const val userId = "userId"
    const val org = "org"
    const val entries = "entries"
    const val createdAt = "createdAt"
    const val updatedAt = "updatedAt"
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
