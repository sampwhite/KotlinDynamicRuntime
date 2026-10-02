package com.dynamicruntime.common.schema

import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.overlay.MergeOutcome
import com.dynamicruntime.common.overlay.MergeRule
import com.dynamicruntime.common.overlay.MergeSpec
import com.dynamicruntime.common.overlay.mergeOverlay
import com.dynamicruntime.common.util.toJsonMap

/**
 * Applies a client's overlays to a `$defs` document, producing the document that client's schema is parsed
 * from (issue #356).
 *
 * ### Why this works on the raw maps rather than on parsed types
 *
 * A `$ref` is bound to an **object pointer** during parsing -- `SchProperty.valueType`, `SchType.itemType`,
 * `SchVariants.branches` all hold resolved [SchType]s. So narrowing a type by editing the parsed graph would
 * mean rebuilding every type that reaches it, transitively, and a variant that missed one would silently keep
 * pointing at the global form in the one place nobody looked.
 *
 * Overlaying the **document** and re-parsing removes that problem rather than solving it: `$ref` resolution
 * binds to whatever is in the map it was handed, so every reference to an altered type -- nested, inside a
 * union branch, through an array's `items` -- lands on the altered version with no traversal written and none
 * to get wrong. That is the case `client-definition.md` says cannot be faked with namespacing, and it is why
 * this layer exists at all.
 *
 * ### What it costs, and why that was already accepted
 *
 * The whole document is parsed again for each client that varies something. `client-definition.md` settled
 * this: *"Whatever the code is simplest carrying... the CPU and memory involved are small enough to ignore, and
 * simplicity of code is the thing actually being bought."* A client that overlays nothing gets the global
 * document back **by identity**, so it builds no variant at all.
 */
fun overlayDefs(defs: Map<String, Any?>, overlays: Map<String, Any?>): Map<String, Any?> {
    if (overlays.isEmpty()) {
        // The same object, not a copy: a client that varies nothing shares the global document, which is what
        // lets the caller skip building a variant by comparing identity.
        return defs
    }
    val out = LinkedHashMap<String, Any?>(defs.size + overlays.size)
    for ((name, body) in defs) {
        val overlay = overlays[name]
        out[name] = if (overlay is Map<*, *> && body is Map<*, *>) {
            overlayType(body.toJsonMap(), overlay.toJsonMap())
        } else {
            // Shared by reference, never written to. A type this client did not mention is the global one.
            body
        }
    }
    // Whatever the overlays declared that the base does not have: a wholly new type, or an extension, both of
    // which are ordinary entries under a name of their own.
    for ((name, body) in overlays) {
        if (name !in defs) {
            out[name] = body
        }
    }
    return out
}

/**
 * One type body with [overlay] applied over it -- **at two levels only**, by [schemaTypeMergeSpec] (issue #985).
 *
 * Public because the narrowing check needs it: what a client may or may not do is a question about the
 * **result**, not about the fragment they wrote, since a property body they declare replaces rather than
 * merges. See `narrowingProblems`.
 *
 * A key the overlay does not mention is carried across untouched; a key it does mention **replaces**. There is
 * no deep merging, and that is the design rather than a simplification: once an overlay starts defining
 * something, that definition wins completely, so what a client wrote is what a client gets. The exceptions are
 * declared in the spec: [SCH.properties], which is the restated set (see [schemaTypeMergeSpec]), and the type's
 * field layout, [SCH.layout], which is a resource of its own and merges by field (see [layoutMergeSpec]).
 *
 * The consequence is worth stating, because it is what shapes how schema gets authored: **there is no way to
 * address just a nested part of a type**. An interior structure a client may want to narrow is therefore
 * pulled out as a named type and referenced by `$ref`, so that it can be altered directly, as a type in its
 * own right. That is a reason to name interior types, not merely a style preference.
 *
 * Nothing is ever written into [base] or anything it holds: unmentioned values are shared by reference and
 * never mutated. That is `client-definition.md`'s sharing invariant -- *"a variant may create new nodes and
 * point at old ones; it must not write into old ones"* -- as a property of how this is written rather than a
 * discipline somebody has to keep.
 *
 * Whatever the merge refused (a layout entry it had nowhere to put) is left out; [overlayTypeOutcome] says what.
 */
fun overlayType(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> =
    overlayTypeOutcome("", base, overlay).value

/**
 * [overlayType] for the type [typeName], with what the merge refused, each problem located in the type
 * (`acme.Q.g-layout.schemaFields[notes]`) -- what a client's alteration is reported by when part of it cannot apply.
 */
fun overlayTypeOutcome(typeName: String, base: Map<String, Any?>, overlay: Map<String, Any?>): MergeOutcome {
    val outcome = mergeOverlay(schemaTypeMergeSpec, base, overlay, typeName)
    return MergeOutcome(withoutStrandedLayoutEntries(outcome.value, overlay), outcome.problems)
}

/**
 * [merged] with each **inherited** layout entry for a field the merged type no longer declares left out (issue
 * #985). An alteration that narrows a type away from a field and also changes its layout gets a layout merged from
 * global's, which still has an entry for that field -- and that entry is the sanctioned outcome of the narrowing,
 * exactly as it is for a layout the alteration does not touch (pruned on delivery), not a fault of the client's. An
 * entry the alteration wrote itself is kept, so a client naming a field its type lacks is still told.
 */
private fun withoutStrandedLayoutEntries(merged: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
    val layout = merged[SCH.layout] as? Map<*, *> ?: return merged
    val ownLayout = overlay[SCH.layout] as? Map<*, *> ?: return merged
    val properties = (merged[SCH.properties] as? Map<*, *>)?.keys ?: return merged
    val written = (ownLayout[SL.schemaFields] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get(SL.field) }.toSet()
    val fields = layout[SL.schemaFields] as? List<*> ?: return merged
    val kept = fields.filter { e ->
        val field = (e as? Map<*, *>)?.get(SL.field)
        field == null || field in properties || field in written
    }
    if (kept.size == fields.size) return merged
    return merged + (SCH.layout to (layout.toJsonMap() + (SL.schemaFields to kept)))
}

/**
 * How a schema type's body merges with an alteration of it (issues #356, #985) -- the whole authoring model of an
 * alteration, in three lines:
 *
 *  - **Every key replaces** unless named below: once an overlay defines something, that definition wins.
 *  - **[SCH.properties] is the set the alteration mentions** ([MergeRule.Restate]). Two rules, and they are the
 *    whole of it. *Mentioning keys is how the set is reduced*: a property the overlay does not name is gone, which
 *    forces a client altering a type to state the complete set it offers -- found in practice to be the right
 *    thing, since somebody reading a client's definition sees every property their users will see. The cost,
 *    accepted: a property cannot be slipped into every client at once by adding it to the underlying type. And *an
 *    empty body inherits; a non-empty one replaces*: `{"name": {}}` keeps the global definition of `name`. **The
 *    order is the overlay's**, because `properties` order is the payload order and the default presentation order;
 *    a client reordering the set has reordered the form, though order and copy belong in the type's field layout
 *    (issue #834). An alteration that does not mention `properties` keeps the base's, entire.
 *  - **[SCH.layout] is a resource of its own** ([MergeRule.Resource]), merged by [layoutMergeSpec]: an alteration
 *    states only the layout entries it changes, or restates the list by setting a mode. `g-layout: null` drops the
 *    inherited layout.
 *
 * **An alteration may merge `properties` instead** (issue #985), by saying so in [SCH.merge]:
 * `"g-merge": { "properties": "merge" }`. Then it names only the properties it changes -- each named body replacing
 * the base's whole, `{}` keeping it, `null` removing it -- and the rest are the base's, including any the base gains
 * later. The default stays the restated set, which a reader of the alteration can take in at once; the merge is for
 * the author (often an AI) who would rather state a delta. Either way the narrowing check judges the merged result.
 */
val schemaTypeMergeSpec: MergeSpec = MergeSpec(
    rules = mapOf(
        SCH.properties to MergeRule.Restate,
        SCH.layout to MergeRule.Resource { base, overlay -> layoutMergeSpec(base, overlay) },
    ),
    directiveKey = SCH.merge,
    choices = mapOf(SCH.properties to mapOf(MCH.restate to MergeRule.Restate, MCH.merge to MergeRule.Merge)),
)
