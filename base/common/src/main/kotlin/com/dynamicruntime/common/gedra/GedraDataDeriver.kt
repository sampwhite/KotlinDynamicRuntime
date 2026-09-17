package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A component-registered computation of a **derived data value** on a gedra's entry (issue #712): given one
 * trait entry's current data, it produces the derived fields that entry should present -- an acme expense
 * report's `totalAmount`, say, from the per-item amount and the item count beside it.
 *
 * The data twin of [GedraStateDeriver], and the same seam (`SchemaCollector.addDataDeriver`, the way a
 * component registers a `CFactSource` or a state deriver). The difference is *when* it runs. A state deriver
 * runs **on write**, in the data write's transaction, because state is stored beside the data. A data deriver
 * runs **on read**: a `g-derived` data field (`SchProperty.derived`) is one the caller never supplies and the
 * form draws no control for, so there is nothing to store -- the value simply follows from the two fields it is
 * computed from, and recomputing it whenever the form is read keeps it in step with an edit to either of them,
 * with no column, no migration, and no way for a stored copy to go stale.
 *
 * Why a component registration rather than a lambda on the trait DSL: a trait *declaration* is data (loadable
 * from a database), while a *computation* is Kotlin, so the two are kept apart -- the same split the state
 * deriver's own note gives (decision 1 of the gedra-states design). The trait declares the field `g-derived`;
 * the code that fills it is registered here and bound to the trait by [traitId].
 *
 * It fills a value for *display*, never for validation or completeness: [deriveEntryData] overlays the result
 * onto the entries a read surface presents, and leaves the stored entries a form's status is judged from
 * untouched. So a derived field cannot make a form invalid or count toward its requiredness -- which is right,
 * because a person never entered it.
 */
interface GedraDataDeriver {
    /** The gedra kinds whose entries this deriver fills; it is skipped for any other kind. Never empty. */
    val appliesTo: Set<GedraDataType>

    /**
     * The trait id whose entries this deriver fills -- the binding the trait's `g-derived` field's note calls
     * "code bound to the trait". A deriver fills the fields of exactly one trait; a second trait wanting a
     * derived value registers its own.
     */
    val traitId: String

    /**
     * When non-null, an opt-in gate identical to [GedraStateDeriver.featureName]: this deriver runs only for a
     * gedra whose client lists this feature name in [ClientDef.testFeatures]. Null means it always runs, which
     * is the ordinary case for a real derivation -- a value that genuinely follows from a form's own data,
     * shown to every client that carries the trait.
     */
    val featureName: String? get() = null

    /**
     * The derived data fields to overlay onto an entry of [traitId], computed from that entry's current [data].
     * Returns empty when it has nothing to add -- a field it needs is absent, say -- and the entry is then
     * presented unchanged, exactly as the fixture's `filledOut` leaves an under-filled entry alone. The returned
     * fields are merged over [data], so a deriver may also correct a stored value, though the usual case adds
     * one that was never stored.
     */
    fun derive(cxt: KdrCxt, data: Map<String, Any?>): Map<String, Any?>
}

/**
 * Overlays the applicable [GedraDataDeriver]s' output onto [entries] and returns the result -- the entries a
 * read surface should *present*, with each trait's `g-derived` data fields computed on read (issue #712). The
 * read-side twin of [computeDisplayValues]: it takes the same stored entries and enriches them for display,
 * failing no read and mutating nothing stored.
 *
 * Only the derivers that apply to [kind] and whose opt-in feature is enabled for **[gedraClient]** -- the
 * gedra's own owning client, not necessarily [cxt]'s -- are run (the same gate [GedraStateDeriver] uses, keyed
 * off the gedra as its write path is). Passing the gedra's client matters for an `allClients` caller reading
 * across clients: a deriver a client opted into must run for that client's forms and stay off another's,
 * whoever is looking. With none applicable, [entries] is returned unchanged, so an ordinary form pays nothing.
 * Each entry gets the fields of every deriver bound to its trait, merged over its `data`; an entry whose trait
 * has no deriver, or which names no trait, passes through untouched.
 *
 * A deriver that throws does not fail the read (issue #712 review): its fields are left underived and the fault
 * logged, the same presentation-must-not-fail-the-read stance [computeDisplayValues] takes -- one form's bad
 * data must never 500 a whole listing, a single read, or a survey view.
 */
fun deriveEntryData(
    cxt: KdrCxt,
    kind: GedraDataType,
    entries: List<Map<String, Any?>>,
    gedraClient: String,
): List<Map<String, Any?>> {
    val byTrait = SchemaService.get(cxt).dataDerivers()
        .filter { kind in it.appliesTo && gedraFeatureEnabled(cxt, gedraClient, it.featureName) }
        .groupBy { it.traitId }
    if (byTrait.isEmpty()) {
        return entries
    }
    return entries.map { entry ->
        val derivers = entry[GE.traitId].toOptStr()?.let { byTrait[it] } ?: return@map entry
        val data = entry[GE.data].toJsonMapOrEmpty()
        var overlaid = data
        for (deriver in derivers) {
            val derived = try {
                deriver.derive(cxt, overlaid)
            } catch (e: Throwable) {
                LogGedra.warn(cxt) {
                    "Data deriver for trait '${deriver.traitId}' threw; leaving its fields underived: ${e.message}"
                }
                emptyMap()
            }
            if (derived.isNotEmpty()) {
                overlaid = overlaid + derived
            }
        }
        if (overlaid === data) entry else entry + (GE.data to overlaid)
    }
}

/**
 * Whether a gedra deriver's opt-in [featureName] is on for [client] (issue #599): a null feature always runs; a
 * named one runs only when [client] lists it in [ClientDef.testFeatures]. Shared by the state and data derivers
 * so the gate is one rule -- the test-instance half lives at the boundary (`ClientService.present` strips
 * `testFeatures` from a non-test node), so this is simply a membership test. [client] is the **gedra's** client:
 * the write path passes its owner-bound `cxt.client`, the read path the row's own client, so the gate keys off
 * the form rather than off whoever triggered the derivation.
 */
fun gedraFeatureEnabled(cxt: KdrCxt, client: String, featureName: String?): Boolean {
    if (featureName == null) {
        return true
    }
    val present = ClientService.get(cxt).present(client) ?: return false
    return featureName in present.testFeatures
}
