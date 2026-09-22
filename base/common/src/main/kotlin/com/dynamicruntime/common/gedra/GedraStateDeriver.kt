package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

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
/**
 * What a [GedraStateDeriver] is handed about the gedra it is deriving state for (issue #794): the [row] whose
 * data the state projects from, and [currentState] -- the state entries as they stand *before* this recompute,
 * read under the write's own lock.
 *
 * A **parameters object rather than loose arguments**, for the reason [GedraWriteContext] is one: a derivation
 * wants the universe the recompute has already assembled, and carrying it here lets a field be *added* as more
 * is collected without changing [GedraStateDeriver.derive]'s signature and every deriver with it.
 *
 * [currentState] is what lets a derived projection stay in step with an **asserted** fact beside it. The
 * per-workflow state (issue #794) is the case it was added for: a workflow a form is *engaged* with must keep
 * its entry even when nothing else would emit one, and engagement is an asserted entry this deriver cannot
 * otherwise see. Note what it is not: the previous value of a deriver's *own* derived entries is not a licence
 * to carry state forward -- derived state is recomputed from the data, and a deriver that read its own last
 * answer would be storing rather than deriving.
 */
class GedraStateContext(
    /** The gedra whose data the state is derived from. */
    val row: GedraDataRow,
    /**
     * The gedra's state entries as they stand before this recompute -- every entry, asserted and derived alike,
     * read under the lock. Empty when the gedra has no state row yet.
     */
    val currentState: List<Map<String, Any?>>,
    /**
     * The entries the derivers registered **before** this one produced in this same recompute (issue #783), in
     * registration order. What lets one derived projection build on another without lagging a write behind: the
     * survey's deriver emits the form's cfacts, and the per-workflow deriver evaluates eligibility against them
     * -- reading [currentState] instead would see the cfacts as they stood *before* this write. Registration
     * order is therefore meaningful; a deriver reads only what came earlier.
     */
    val derivedThisPass: List<Map<String, Any?>> = emptyList(),
)

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
     * The state entries this deriver computes from [state]'s row and the state around it -- each a
     * `{traitId, data}` map for a derived state trait it owns, keyed by that trait's `primaryKey`. Returns empty
     * when it has nothing to record for this gedra. The entries are stamped, validated, keyed, and written by the
     * caller (`writeState`), so this returns the same shape a caller of `writeState` would pass.
     */
    fun derive(cxt: KdrCxt, state: GedraStateContext): List<Map<String, Any?>>
}

/**
 * Folds every [GT.cfacts] entry in [entries] into **one** (issue #784), keeping every other entry as it is.
 *
 * `cfacts` is form-singleton -- a form's cfacts are one set about the form -- but it has several producers: the
 * survey's deriver contributes its facts, the per-workflow deriver its engaged workflows' singleton cfacts. Each
 * emits its own contribution rather than reading and rewriting a shared list (which would make every producer
 * responsible for every other's facts), and the union is taken here, where they all meet. Two entries would
 * otherwise collide on the unkeyed trait's one address and refuse the whole write.
 *
 * The union keeps first-seen order -- registration order, so the survey's facts lead -- and lands where the
 * first contribution stood.
 */
fun mergeCfactContributions(entries: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val isCfacts = { e: Map<String, Any?> -> e[GE.traitId].toOptStr() == GT.cfacts }
    if (entries.count(isCfacts) < 2) {
        return entries
    }
    val facts = LinkedHashSet<String>()
    entries.filter(isCfacts).forEach { e ->
        (e[GE.data].toJsonMapOrEmpty()[GT.facts] as? List<*>).orEmpty().mapNotNullTo(facts) { it.toOptStr() }
    }
    val merged = mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to facts.toList()))
    val first = entries.indexOfFirst(isCfacts)
    return entries.filterIndexed { i, e -> i == first || !isCfacts(e) }.map { if (isCfacts(it)) merged else it }
}
