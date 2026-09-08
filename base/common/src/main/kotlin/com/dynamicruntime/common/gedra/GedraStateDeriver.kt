package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt

/**
 * A component-registered computation of **derived** gedra state (issue #599): given a gedra's current data, it
 * produces the state entries a derived state trait should hold. `GedraDataService` runs the applicable derivers
 * on `createGedra` / `importGedras` and writes their entries in the **same transaction** as the data, so a
 * form records its initial state even on a plain API create or a bad import -- not only in an interactive
 * workflow step.
 *
 * Why a component registration rather than a lambda on the `stateTrait` DSL: a trait *declaration* is data
 * (loadable from a database), while a *computation* is Kotlin, so the two are kept apart (decision 1 of the
 * gedra-states design). A deriver is registered through `SchemaCollector.addStateDeriver`, the same seam a
 * component registers a `CFactSource` through.
 *
 * A deriver only ever produces [StateTraitClass.derived] state -- a projection a later batch job may safely
 * recompute and overwrite. Asserted state (an approval, a captured external fact) is written by the act that
 * asserts it, never derived.
 */
interface GedraStateDeriver {
    /** The gedra kinds this deriver produces state for; it is skipped for any other kind. Never empty. */
    val appliesTo: Set<GedraDataType>

    /**
     * When non-null, an opt-in gate: this deriver runs only on a **test instance** whose gedra's client lists
     * this feature name in [ClientDef.testFeatures]. Null means it always runs (a real, non-demo derivation).
     * The gate is what keeps a demo derivation (like `traitPresenceByYear`) off production and off clients that
     * did not ask for it, without varying the state schema.
     */
    val featureName: String? get() = null

    /**
     * The state entries this deriver computes from [row]'s current data -- each a `{traitId, data}` map for a
     * derived state trait it owns, keyed by that trait's `primaryKey`. Returns empty when it has nothing to
     * record for this gedra. The entries are stamped, validated, keyed, and written by the caller (`writeState`),
     * so this returns the same shape a caller of `writeState` would pass.
     */
    fun derive(cxt: KdrCxt, row: GedraDataRow): List<Map<String, Any?>>
}
