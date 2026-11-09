package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.evalTemplate
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

/**
 * The lines that say a field's declared bounds (issue #1693), each a key of the home file's [HFRAG.fieldBoundsNs] and a
 * template over `min` and `max` (and, for [numberBetweenExclusive], `minExclusive` and `maxExclusive`), with the words
 * shipped. `text` is a string's length, `number` a number's value, `choice` how many of a list of choices, and `list`
 * how many entries of any other list or of a map. A `between` line whose ends are equal says so ("Exactly 6
 * characters", "Choose 2").
 */
@Suppress("EnumEntryName")
enum class FieldBound(val shipped: String) {
    textBetween($$"${min == max ? 'Exactly' : 'From ' ~ min ~ ' to'} ${max} ${max == 1 ? 'character' : 'characters'}"),
    textAtLeast($$"At least ${min} ${min == 1 ? 'character' : 'characters'}"),
    textAtMost($$"Up to ${max} ${max == 1 ? 'character' : 'characters'}"),
    numberBetween($$"From ${min} to ${max}"),
    numberAtLeast($$"At least ${min}"),
    numberAtMost($$"Up to ${max}"),
    numberMoreThan($$"More than ${min}"),
    numberLessThan($$"Less than ${max}"),
    numberBetweenExclusive(
        $$"${minExclusive ? 'More than' : 'From'} ${min} ${minExclusive ? 'and' : 'to'} " +
            $$"${maxExclusive ? 'less than' : 'up to'} ${max}",
    ),
    choiceBetween($$"Choose ${min == max ? '' : min ~ ' to '}${max}"),
    choiceAtLeast($$"Choose at least ${min}"),
    choiceAtMost($$"Choose up to ${max}"),
    listBetween($$"${min == max ? 'Exactly' : 'From ' ~ min ~ ' to'} ${max} ${max == 1 ? 'entry' : 'entries'}"),
    listAtLeast($$"At least ${min} ${min == 1 ? 'entry' : 'entries'}"),
    listAtMost($$"Up to ${max} ${max == 1 ? 'entry' : 'entries'}"),
}

/**
 * The words of the line under a field saying its bounds (issue #1693), by [FieldBound]: as shipped until the shell's
 * copy is read, then as [of] gives them. A data class, since the shell's facts compare by value.
 */
data class FieldBoundWords(val words: Map<FieldBound, String> = emptyMap()) {
    /** The template for [bound], the client's when it gives one. */
    fun template(bound: FieldBound): String = words[bound] ?: bound.shipped

    companion object {
        /** The words [copy] gives, each one it does not left as shipped. Pure, under `jsNodeTest`. */
        fun of(copy: Copy): FieldBoundWords =
            FieldBoundWords(FieldBound.entries.mapNotNull { b -> copy.opt(HFRAG.fieldBoundsNs, b.name)?.let { b to it } }.toMap())
    }
}

/**
 * The line a field's declared bounds give it -- "Up to 128 characters", "From 20 to 90", "Choose up to 3" -- or null
 * when it declares neither end (issue #203, in words since #1693). What a form shows beneath the field when no layout
 * `hint` replaces it. A client's line that does not evaluate falls back to the shipped one, so a wording mistake costs
 * the client its words, not the form. Pure.
 *
 * Theme: #TPLPS.
 */
fun boundHintText(vt: SchType, words: FieldBoundWords = FieldBoundWords()): String? {
    val min = vt.minBound
    val max = vt.maxBound
    if (min == null && max == null) return null
    val bound = when (vt.jsonType) {
        SCT.string -> sideOf(min, max, FieldBound.textBetween, FieldBound.textAtLeast, FieldBound.textAtMost)
        SCT.array if !vt.itemType?.options.isNullOrEmpty() ->
            sideOf(min, max, FieldBound.choiceBetween, FieldBound.choiceAtLeast, FieldBound.choiceAtMost)
        SCT.array, SCT.kObject -> sideOf(min, max, FieldBound.listBetween, FieldBound.listAtLeast, FieldBound.listAtMost)
        // An exclusive end (issue #823) does not admit the bound itself, and is only ever a number's.
        else -> when {
            min != null && max != null ->
                if (vt.minExclusive || vt.maxExclusive) FieldBound.numberBetweenExclusive else FieldBound.numberBetween
            min != null -> if (vt.minExclusive) FieldBound.numberMoreThan else FieldBound.numberAtLeast
            else -> if (vt.maxExclusive) FieldBound.numberLessThan else FieldBound.numberAtMost
        }
    }
    val data = buildMap {
        min?.let { put("min", it) }
        max?.let { put("max", it) }
        put("minExclusive", vt.minExclusive)
        put("maxExclusive", vt.maxExclusive)
    }
    return runCatching { words.template(bound).evalTemplate(data) }.getOrElse { bound.shipped.evalTemplate(data) }
}

/** [between] for both ends, else [atLeast] or [atMost] for the one given. */
private fun sideOf(min: Double?, max: Double?, between: FieldBound, atLeast: FieldBound, atMost: FieldBound): FieldBound = when {
    min != null && max != null -> between
    min != null -> atLeast
    else -> atMost
}

private external interface BoundHintProps : Props {
    var type: SchType
    var override: String?
}

/**
 * A field's declared bounds, stated the way the outline already states a choice field's options -- the range is part
 * of what the shape *is*, and reading it here beats discovering it by being rejected (issue #203). In the words of the
 * shell's copy (issue #1693). Silent when the field declares neither end.
 */
private val BoundHint = FC<BoundHintProps> { props ->
    val words = useShellFacts().fieldBoundWords
    // A layout `hint` (issue #587) replaces the derived line wholesale -- it was resolved against this field's
    // `${'$'}{min}` / `${'$'}{max}`, so it says what the author wanted in its place. A blank override is treated as "no
    // hint", so a layout can suppress the derived one by setting it empty.
    val text = props.override?.ifBlank { null } ?: if (props.override == null) boundHintText(props.type, words) else null
    if (text != null) {
        p {
            className = ClassName("type-hint")
            +text
        }
    }
}

/** Draws [BoundHint] for [vt], a layout's [override] in place of the derived line. */
internal fun ChildrenBuilder.boundHint(vt: SchType, override: String? = null) {
    BoundHint {
        type = vt
        this.override = override
    }
}
