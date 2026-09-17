package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.SchemaService

/**
 * A component-registered function bound to a **trait**, run at the entry to a create or update **before the
 * write transaction is started** (issue #728): given one trait entry's supplied data, it returns the data to
 * store -- transformed, for a *calculation* -- or throws a `KdrException.mkInput` to reject the write, for a
 * *validation*. The write-time, trait-scoped sibling of [GedraDataDeriver] (which computes on read) and
 * [GedraStateDeriver] / [GedraWriteHook] (which run inside the write transaction).
 *
 * Why registered here rather than as a lambda on the trait declaration: a trait *declaration* is data (loadable
 * from a database), while a *computation* is Kotlin, so the two are kept apart -- the same split every other
 * gedra computation seam makes (decision 1 of the gedra-states design). A component registers one through
 * `SchemaCollector.addPrepForSaveFn`, keyed to its trait by [traitId]; the trait itself declares only the field
 * shapes the function reads and writes. This is why the feature is only available to source-defined traits: a
 * client that extends the schema owns keeping its extension consistent with what the function assumes.
 *
 * ### When it runs, and what it sees
 *
 * It sees the **supplied** trait data as it will be stored for this write, before anything is minted or locked:
 *  - On a **create**, and on an **addOrReplace** edit, that is the whole of the entry's data -- a calculation
 *    over it, or a cross-field validation of it, is exact.
 *  - On an **addOrMerge** edit it is only the fragment the caller sent, **not** the merged result (which does
 *    not exist until under the write lock). So a function must be safe on a fragment: validate a field only when
 *    that field is present (a single-field rule is naturally so), and leave a calculation that needs the *merged*
 *    data to the read-time [GedraDataDeriver] or the post-write [GedraWriteHook], which do see it. Running before
 *    the transaction is the point -- a validation fails fast, with no lock taken and nothing minted -- and this
 *    is the price of it.
 *
 * ### The first of a growing set of save events
 *
 * The issue frames this as one of a *growing set* of trait event functions. It is deliberately built as the one
 * concrete `onPrepForSave` event rather than a general event registry: a second event is what would first
 * exercise an event enum and a per-event dispatch, and an untested generality is the kind that is quietly wrong
 * until the second case lands -- the same "wait for the second hook" call [GedraWriteHook]'s own note makes. A
 * second event registers its own interface and list the same way, and the two generalize into an event map then.
 *
 * The other **variety** the issue names -- a function shared with the frontend so it can run during
 * validation/coercion -- is out of scope here (this is the pure-backend approach) but shapes the interface: it
 * takes only [KdrCxt] and plain data, nothing JVM-only, so a future shared function is a narrowing of this
 * rather than a different shape.
 */
interface GedraPrepForSaveFn {
    /** The gedra kinds this function runs for; it is skipped for any other kind. Never empty. */
    val appliesTo: Set<GedraDataType>

    /** The trait id whose entries this function prepares -- the binding to "code bound to the trait." */
    val traitId: String

    /**
     * When non-null, an opt-in gate identical to [GedraDataDeriver.featureName]: this function runs only for a
     * gedra whose client lists this feature name in [ClientDef.testFeatures]. Null means it always runs, the
     * ordinary case for a real calculation or validation.
     */
    val featureName: String? get() = null

    /**
     * Given the trait entry's supplied [data], returns the data to store -- unchanged for a pure validation,
     * transformed for a calculation. Throw `KdrException.mkInput` to reject the whole write with a 400 the
     * caller sees. Called before the write transaction, so a throw leaves nothing minted and no lock taken.
     *
     * **It must not alter the trait's primary-key fields.** The key *addresses* the entry -- it is what an
     * `addOrMerge`/`addOrReplace`/`deleteOrNoOp` edit names, and what keys entries apart within a gedra -- and it
     * is checked for uniqueness against the *supplied* data, before this runs. A function that changed a key
     * would move the entry out from under that check and under an edit's own addressing, so keying belongs to
     * the caller's data and this event transforms only the rest. (For a single-instance trait there is no key,
     * and the point is moot.)
     */
    fun prepForSave(cxt: KdrCxt, data: Map<String, Any?>): Map<String, Any?>
}

/**
 * Runs every applicable [GedraPrepForSaveFn] over one trait entry's [data] and returns the data to store
 * (issue #728), or throws the first function's `KdrException` to reject the write. Only functions that apply to
 * [kind], are bound to [traitId], and whose opt-in feature is enabled for **[gedraClient]** (the gedra's own
 * client, so an `allClients` caller writing to another client gets that client's functions) are run. Returns
 * [data] unchanged when none is bound, so an ordinary trait pays only a registry lookup.
 *
 * Unlike the read-time [deriveEntryData], a throw here is **not** swallowed: a save-time validation must reach
 * the caller as a 400, and a calculation's own programming error should surface rather than store bad data.
 */
fun prepForSaveData(
    cxt: KdrCxt,
    kind: GedraDataType,
    traitId: String,
    data: Map<String, Any?>,
    gedraClient: String,
): Map<String, Any?> {
    var prepared = data
    for (fn in SchemaService.get(cxt).prepForSaveFns()) {
        if (fn.traitId == traitId && kind in fn.appliesTo && gedraFeatureEnabled(cxt, gedraClient, fn.featureName)) {
            prepared = fn.prepForSave(cxt, prepared)
        }
    }
    return prepared
}
