package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's narrowing of a global trait as the traits editor sees it (issue #1119): three moves, and only legal ones,
 * so the editor cannot produce a widening.
 *
 *  - **Hide a field** -- in the layout only. The client's version of the trait's data type lists the fields its forms
 *    show (an authoritative `g-layout`), the default every workflow starts from; what the data accepts is unchanged. A
 *    field the data requires cannot be hidden: a form could then never be saved.
 *  - **Require a field** -- in the data: added to the type's `required`, which narrowing rule 3 admits.
 *  - **Restrict its choices** -- in the data: a subset of the global choices, which closes an open list (rule 2).
 *
 * In the kernel because both sides run it: the editor previews the narrowed type with [TraitNarrowing.toAlteration],
 * and the backend writes exactly that, as the client's alteration of the trait's data type -- a `schemaDef` entry under
 * the global type's own name. The narrowing check judges it as it judges any alteration.
 *
 * ### What it writes, and what it leaves
 *
 * The alteration merges its properties (`g-merge`), so it names only the fields whose choices it restricts, each the
 * global definition with fewer choices, and the rest stay the global trait's -- including any it gains later. It owns
 * `required`, those properties, and the layout's mode and field list; anything else a stored alteration says (copy, a
 * field's other layout entries) is carried. An empty narrowing is no alteration at all ([TraitNarrowing.isEmpty]).
 *
 * A stored alteration that does more than these moves -- drops a field from the data, restates the properties, changes
 * a field some other way -- is not editable here ([traitNarrowingOf] says why); it was written by hand.
 */
class TraitNarrowing(
    val traitId: String,
    /** The fields the client's forms leave out (in the layout); the data still accepts them. */
    val hidden: Set<String> = emptySet(),
    /** The fields the client's data requires beyond what the global trait requires. */
    val required: Set<String> = emptySet(),
    /** Per field, the global choices the client keeps, in the global order; a field not named keeps them all. */
    val choices: Map<String, List<String>> = emptyMap(),
) {
    /** Whether this narrows nothing: the client takes the trait as the platform declares it. */
    val isEmpty: Boolean get() = hidden.isEmpty() && required.isEmpty() && choices.isEmpty()

    /** The narrowing on the wire, as [traitNarrowingFromInfo] reads it. */
    fun toInfo(): Map<String, Any?> = buildMap {
        put(CIT.traitId, traitId)
        put(CIT.hidden, hidden.toList())
        put(CIT.required, required.toList())
        put(CIT.choices, choices)
    }

    /**
     * The client's alteration of the trait's data type: [base] -- the global type -- narrowed as this says, over
     * [stored], the alteration as it stands (null when there is none), carrying what this does not own. Null when this
     * is [isEmpty] and the stored alteration says nothing else: no alteration at all.
     */
    fun toAlteration(base: Map<String, Any?>, stored: Map<String, Any?>? = null): Map<String, Any?>? {
        val out = LinkedHashMap(stored.orEmpty())
        out.keys.removeAll(setOf(SCH.required, SCH.properties, SCH.merge))
        if (required.isNotEmpty()) {
            val baseRequired = base[SCH.required].toJsonListOfStrings()
            out[SCH.required] = baseRequired + baseOrder(base).filter { it in required && it !in baseRequired }
        }
        val baseProps = base[SCH.properties].toJsonMapOrEmpty()
        val restricted = LinkedHashMap<String, Any?>()
        for (field in baseOrder(base)) {
            val kept = choices[field] ?: continue
            restricted[field] = withChoices(baseProps[field].toJsonMapOrEmpty(), kept)
        }
        if (restricted.isNotEmpty()) {
            out[SCH.merge] = mapOf(SCH.properties to MCH.merge)
            out[SCH.properties] = restricted
        }
        layoutOver(base, stored?.get(SCH.layout).toJsonMapOrEmpty())?.let { out[SCH.layout] = it } ?: out.remove(SCH.layout)
        return out.ifEmpty { null }
    }

    /**
     * The layout the alteration carries: with fields hidden, an authoritative list of the rest -- in the stored list's
     * order, then the type's -- each keeping its stored entry; with none, the stored layout's copy entries alone, in
     * the mode the global layout has. Null when there is nothing to say.
     */
    private fun layoutOver(base: Map<String, Any?>, stored: Map<String, Any?>): Map<String, Any?>? {
        val storedEntries = stored[SL.schemaFields].toJsonListOfMaps()
        val byField = storedEntries.associateBy { it[SL.field].toOptStr() }
        val out = LinkedHashMap(stored)
        if (hidden.isNotEmpty()) {
            val order = (storedEntries.mapNotNull { it[SL.field].toOptStr() } + baseOrder(base)).distinct()
            out[SL.mode] = SLM.authoritative
            out[SL.schemaFields] = order.filter { it !in hidden && it in baseOrder(base) }.map { byField[it] ?: mapOf(SL.field to it) }
        } else {
            out.remove(SL.mode)
            // Only the entries that say something: one naming its field alone was there for the list.
            val copy = storedEntries.filter { it.keys.any { k -> k != SL.field } }
            if (copy.isEmpty()) out.remove(SL.schemaFields) else out[SL.schemaFields] = copy
        }
        return out.ifEmpty { null }
    }
}

/** A narrowing read off the wire ([TraitNarrowing.toInfo]). */
fun traitNarrowingFromInfo(raw: Map<String, Any?>): TraitNarrowing = TraitNarrowing(
    traitId = raw[CIT.traitId].toOptStr().orEmpty(),
    hidden = raw[CIT.hidden].toJsonListOfStrings().toSet(),
    required = raw[CIT.required].toJsonListOfStrings().toSet(),
    choices = raw[CIT.choices].toJsonMapOrEmpty().mapValues { it.value.toJsonListOfStrings() },
)

/** The global type's fields, in its order. */
private fun baseOrder(base: Map<String, Any?>): List<String> = base[SCH.properties].toJsonMapOrEmpty().keys.toList()

/** The global choices [prop] offers -- its own, or its items' for several choices -- as value to label; null for none. */
fun baseChoicesOf(prop: Map<String, Any?>): List<Pair<String, String>>? {
    val holder = if (prop[SCH.type] == SCT.array) prop[SCH.items].toJsonMapOrEmpty() else prop
    val options = holder[SCH.options] ?: return null
    return options.toJsonListOfMaps().mapNotNull { o ->
        val value = o[SCH.value].toOptStr() ?: return@mapNotNull null
        value to (o[SCH.label].toOptStr() ?: value)
    }
}

/** [prop] keeping only the choices in [kept], its list closed: the global definition with fewer choices. */
private fun withChoices(prop: Map<String, Any?>, kept: List<String>): Map<String, Any?> {
    fun narrowed(holder: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(holder)
        out[SCH.options] = holder[SCH.options].toJsonListOfMaps().filter { it[SCH.value].toOptStr() in kept }
        out.remove(SCH.openOptions)
        return out
    }
    if (prop[SCH.type] != SCT.array) return narrowed(prop)
    return LinkedHashMap(prop).also { it[SCH.items] = narrowed(prop[SCH.items].toJsonMapOrEmpty()) }
}

/**
 * What is wrong with [narrowing] of the global type [base], in words, or nothing: each field one the type declares, no
 * field both hidden and required (by the type or by the client), and each restricted field one with choices, keeping
 * at least one of them and none it does not offer.
 */
fun traitNarrowingProblems(narrowing: TraitNarrowing, base: Map<String, Any?>): List<String> {
    val out = mutableListOf<String>()
    val props = base[SCH.properties].toJsonMapOrEmpty()
    val baseRequired = base[SCH.required].toJsonListOfStrings().toSet()
    for (field in narrowing.hidden + narrowing.required + narrowing.choices.keys) {
        if (field !in props) out.add("The trait has no field '$field'.")
    }
    for (field in narrowing.hidden) {
        if (field in baseRequired) out.add("Field '$field' is required by the trait, so a form must show it.")
        else if (field in narrowing.required) out.add("Field '$field' cannot be both required and hidden.")
    }
    for ((field, kept) in narrowing.choices) {
        val offered = baseChoicesOf(props[field].toJsonMapOrEmpty())?.map { it.first } ?: run {
            if (field in props) out.add("Field '$field' has no choices to restrict.")
            continue
        }
        if (kept.isEmpty()) out.add("Field '$field' must keep at least one choice.")
        kept.filterNot { it in offered }.forEach { out.add("Field '$field' does not offer '$it'.") }
    }
    return out.distinct()
}

/** What reading a stored alteration as a narrowing gave: the [narrowing], or why it is not one the editor can edit. */
class TraitNarrowingRead(val narrowing: TraitNarrowing?, val notEditable: String?)

/**
 * The client's stored [alteration] of the global trait [traitId]'s data type [base] as a narrowing, or why the editor
 * cannot edit it (see the class note). No alteration is an empty narrowing.
 */
fun traitNarrowingOf(traitId: String, base: Map<String, Any?>, alteration: Map<String, Any?>?): TraitNarrowingRead {
    fun no(why: String) = TraitNarrowingRead(null, why)
    if (alteration == null) return TraitNarrowingRead(TraitNarrowing(traitId), null)
    val props = base[SCH.properties].toJsonMapOrEmpty()
    val choices = LinkedHashMap<String, List<String>>()
    if (alteration.containsKey(SCH.properties)) {
        if (alteration[SCH.merge].toJsonMapOrEmpty()[SCH.properties] != MCH.merge) {
            return no("Its alteration restates the trait's fields, which this editor does not do.")
        }
        for ((field, raw) in alteration[SCH.properties].toJsonMapOrEmpty()) {
            val baseProp = props[field].toJsonMapOrEmpty()
            val prop = raw?.toJsonMapOrEmpty() ?: return no("Its alteration removes field '$field' from the data.")
            if (prop.isEmpty()) continue
            val offered = baseChoicesOf(baseProp)?.map { it.first }
                ?: return no("Its alteration changes field '$field' in a way this editor does not.")
            val kept = baseChoicesOf(prop)?.map { it.first }.orEmpty().filter { it in offered }
            if (withChoices(baseProp, kept) != prop) return no("Its alteration changes field '$field' in a way this editor does not.")
            choices[field] = kept
        }
    }
    val baseRequired = base[SCH.required].toJsonListOfStrings().toSet()
    val required = alteration[SCH.required]?.toJsonListOfStrings()?.filterNot { it in baseRequired }?.toSet().orEmpty()
    val layout = alteration[SCH.layout].toJsonMapOrEmpty()
    val hidden = when (layout[SL.mode]) {
        SLM.authoritative -> {
            val listed = layout[SL.schemaFields].toJsonListOfMaps().mapNotNull { it[SL.field].toOptStr() }.toSet()
            props.keys.filterNot { it in listed }.toSet()
        }
        null, SLM.overlay -> emptySet()
        else -> return no("Its layout reorders the trait's fields, which this editor does not do.")
    }
    return TraitNarrowingRead(TraitNarrowing(traitId, hidden, required, choices), null)
}
