package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptStr

/**
 * What a client's **alteration** of an existing type is allowed to do (issue #356).
 *
 * An altered type keeps its name, so every `$ref` to it resolves to the client's version -- which is what
 * makes an alteration useful and also what makes it dangerous. It may therefore **only narrow**: accept no
 * more than the global type accepts. A client wanting a different shape rather than a smaller one **extends**
 * instead, which produces a name of its own and constrains nothing.
 *
 * The rule applies only to what takes part in **validation**. Labels, descriptions and error copy are a
 * client's own business and may change however they like.
 *
 * ### The three ways to narrow
 *
 * Deliberately a closed list, not a general subtype check. These are the three that came up in practice, and
 * a refusal is cheap to relax later where a wrongly-permitted widening is not -- a widened type reaches
 * storage, and the data it admitted is then wrong for everybody else.
 *
 *  1. **Fewer properties** -- mention only the keys wanted (see `mergeProperties`).
 *  2. **Fewer choices**, or a choice list where there was none: [SCH.options] whose values are a subset, or
 *     an options list applied to an attribute that had none -- including **closing an open list**
 *     ([SCH.openOptions]), which is the same act said in the other keyword: a list that bounded nothing
 *     starts bounding. Opening a closed one is its opposite and is refused with the widenings.
 *  3. **More required**: [SCH.required] as a superset of the base's.
 *
 * Anything else that affects validation is refused, naming the alternative.
 *
 * ### The one case that looks like narrowing and is not
 *
 * **Dropping a property the base requires widens the type**, and it is worth being explicit because it is the
 * opposite of how it reads. Removing a property narrows what a *present* value may be -- data carrying it is
 * now rejected -- but it also means data *omitting* it is accepted, where the global type rejected exactly
 * that. Such an entry then fails validation for everybody who is not this client, which is the cross-client
 * breakage the whole rule exists to prevent. Drop the requirement globally, or extend.
 */
fun narrowingProblems(typeName: String, base: Map<String, Any?>, overlay: Map<String, Any?>): List<String> {
    val problems = mutableListOf<String>()
    // Compared against the **merged result**, not against the fragment the client wrote. A declared property
    // body replaces rather than merges, so the fragment says what changed only in the simplest cases; the
    // result says what this client actually accepts, which is the question.
    compare(typeName, base, overlayType(base, overlay), problems)
    return problems
}

/**
 * Keywords a client may change freely: none of them takes part in deciding whether a value is valid.
 *
 * An allowlist rather than a list of forbidden ones, because the failure directions are not symmetric. A
 * keyword nobody thought about is refused and somebody asks why; the other way round it is silently permitted
 * to widen a type, and nothing says so until data written under it is read by another client.
 */
private val presentationKeys = setOf(
    SCH.title, SCH.description, SCH.examples, SCH.deprecated, SCH.errors, SCH.dComment,
    // A sourced choice list (issue #413) is here because it never reaches validation: the callback's answer
    // is rendered for a reader and is not parsed into a type, so a client pointing an attribute at a
    // different source cannot change what this node accepts. A client swapping a *declared* list is a
    // different matter and stays under the narrowing rule below.
    SCH.optionsSource,
    // A presentation hint (issue #540) is display-only and never consulted by validation, so a client may set
    // or change it as freely as a label. Absent from this list it would be refused as a validation change.
    SCH.presentation,
)

/** The keys that may differ by narrowing; every other validating key must match the base exactly. */
private val narrowingKeys = setOf(SCH.properties, SCH.options, SCH.required, SCH.openOptions)

private fun compare(path: String, base: Map<String, Any?>, variant: Map<String, Any?>, out: MutableList<String>) {
    checkProperties(path, base, variant, out)
    // An **open** list bounds nothing (issue #418), so no list of choices the variant declares can widen what
    // is accepted -- everything already was. So neither the contents nor the subset rule apply while the base
    // is open: a per-client suggestion list is the obvious thing to want, and refusing it would protect
    // nothing.
    if (base[SCH.openOptions] != true) {
        checkOptions(path, base[SCH.options], variant[SCH.options], out)
    }
    checkOpenOptions(path, base, variant, out)
    checkRequired(path, base, variant, out)
    for (key in base.keys + variant.keys) {
        if (key in presentationKeys || key in narrowingKeys) {
            continue
        }
        if (base[key] != variant[key]) {
            out.add(
                "'$path' changes '$key' from ${show(base[key])} to ${show(variant[key])}. That takes part in " +
                    "validation and is not one of the three ways a client may narrow a type " +
                    "(${narrowingKeys.joinToString(", ")}). Extend the type instead, which creates a name of " +
                    "its own and may do as it likes.",
            )
        }
    }
}

private fun show(value: Any?): String = if (value == null) "absent" else "'$value'"

/**
 * A client may **close** an open list and may not **open** a closed one (issue #418).
 *
 * The asymmetry is the same one the whole file is about, wearing a different keyword. An open list accepts
 * anything, so a client that closes it accepts a subset of what the base did -- which is narrowing rule 2
 * said the other way round, and no more dangerous than trimming a closed list's choices. Opening a closed one
 * is the reverse: values the base rejects become storable here and are then wrong for everybody else, which
 * is the cross-client breakage the rule exists to prevent.
 *
 * **Note what this rule does not address.** Closing a list, like trimming one, can leave data already stored
 * under the looser version failing validation against the tighter one. That is a real problem and a different
 * one: it is about a definition changing over *time*, where everything here is about two definitions
 * coexisting. Nothing in this file would catch it, and permitting rule 2 at all already accepts it.
 *
 * There has to *be* a closed list to open. A base with no `${SCH.options}` at all bounds nothing, so a variant
 * that adds an **open** list to it accepts exactly what the base did (any value) -- that is not opening a
 * closed list but applying suggestions where there were none, which narrows no less than applying a closed
 * list would (rule 2). So this fires only when the base carries a list this variant flips open.
 */
private fun checkOpenOptions(
    path: String,
    base: Map<String, Any?>,
    variant: Map<String, Any?>,
    out: MutableList<String>,
) {
    if (base[SCH.options] != null && base[SCH.openOptions] != true && variant[SCH.openOptions] == true) {
        out.add(
            "'$path' opens a choice list the type closes ('${SCH.openOptions}'), which widens what it " +
                "accepts: values the type rejects would be storable for this client and invalid to " +
                "everybody else. Closing an open list is allowed; opening a closed one is not. Extend the " +
                "type instead, which creates a name of its own and may do as it likes.",
        )
    }
}

/** Fewer properties, each still narrowing; never more, and never one the base requires. */
private fun checkProperties(
    path: String,
    base: Map<String, Any?>,
    variant: Map<String, Any?>,
    out: MutableList<String>,
) {
    val baseProps = (base[SCH.properties] as? Map<*, *>)?.toJsonMap() ?: return
    val declared = (variant[SCH.properties] as? Map<*, *>)?.toJsonMap() ?: return
    val added = declared.keys.filterNot { it in baseProps }
    if (added.isNotEmpty()) {
        out.add(
            "'$path' adds the propert${if (added.size == 1) "y" else "ies"} " +
                "${added.joinToString(", ") { "'$it'" }}, which widens what the type accepts. A client adds " +
                "fields by extending the type, not by altering it.",
        )
    }
    // Dropping a property the base requires widens rather than narrows; see the class note.
    val baseRequired = (base[SCH.required] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList()
    val droppedAndRequired = baseRequired.filterNot { it in declared.keys }
    if (droppedAndRequired.isNotEmpty()) {
        out.add(
            "'$path' drops ${droppedAndRequired.joinToString(", ") { "'$it'" }}, which the type requires. " +
                "Removing a required property widens the type rather than narrowing it: data omitting it " +
                "would be accepted here and rejected everywhere else, so what this client stores would be " +
                "invalid to everybody else.",
        )
    }
    for ((name, body) in declared) {
        val baseBody = (baseProps[name] as? Map<*, *>)?.toJsonMap() ?: continue
        val declaredBody = (body as? Map<*, *>)?.toJsonMap() ?: continue
        compare("$path.$name", baseBody, declaredBody, out)
    }
}

/** A shorter choice list, or a choice list where the base had none. Labels are presentation and are ignored. */
private fun checkOptions(path: String, baseValue: Any?, value: Any?, out: MutableList<String>) {
    val baseOptions = optionValues(baseValue) ?: return // the base offered no choices: applying some narrows
    val declared = optionValues(value) ?: run {
        out.add("'$path' removes its choice list, which widens what the type accepts.")
        return
    }
    val added = declared.filterNot { it in baseOptions }
    if (added.isNotEmpty()) {
        out.add(
            "'$path' offers the choice${if (added.size == 1) "" else "s"} " +
                "${added.joinToString(", ") { "'$it'" }}, which the type does not. A client may shorten a " +
                "choice list or apply one where there was none; adding a choice widens what is accepted.",
        )
    }
}

/**
 * The **values** of a choice list, or null when there is no list.
 *
 * An option is either a bare value or a `{label, value}` pair, and only the value takes part in validation --
 * which is what lets a client relabel every choice while narrowing none of them.
 */
private fun optionValues(value: Any?): List<String>? {
    val list = value as? List<*> ?: return null
    return list.mapNotNull { option ->
        if (option is Map<*, *>) option.toJsonMap()[SCH.value].toOptStr() else option.toOptStr()
    }
}

/** More required, never fewer, and never a property this type does not have. */
private fun checkRequired(
    path: String,
    base: Map<String, Any?>,
    variant: Map<String, Any?>,
    out: MutableList<String>,
) {
    val declared = (variant[SCH.required] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList()
    val baseRequired = (base[SCH.required] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList()
    val dropped = baseRequired.filterNot { it in declared }
    if (dropped.isNotEmpty()) {
        out.add(
            "'$path' no longer requires ${dropped.joinToString(", ") { "'$it'" }}. Making a required " +
                "property optional widens the type: this client would store entries that are invalid " +
                "everywhere else.",
        )
    }
    val props = (variant[SCH.properties] as? Map<*, *>)?.toJsonMap() ?: emptyMap()
    val unknown = declared.filterNot { it in props }
    if (props.isNotEmpty() && unknown.isNotEmpty()) {
        out.add("'$path' requires ${unknown.joinToString(", ") { "'$it'" }}, which the type does not declare.")
    }
}
