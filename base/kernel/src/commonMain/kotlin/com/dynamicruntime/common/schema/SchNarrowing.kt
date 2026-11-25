package com.dynamicruntime.common.schema

import com.dynamicruntime.common.filekind.FileKind
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.overlay.OverlayMergeError
import com.dynamicruntime.common.table.DTAB
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.ProblemCode
import com.dynamicruntime.common.util.ProblemLocation
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptDoubleOrNull
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
 *  1. **Fewer properties** -- mention only the keys wanted, or, where the alteration merges its properties
 *     (`g-merge`), remove one with `null` (see [schemaTypeMergeSpec]).
 *  2. **Fewer choices**, or a choice list where there was none: [SCH.options] whose values are a subset, or
 *     an options list applied to an attribute that had none -- including **closing an open list**
 *     ([SCH.openOptions]), which is the same act said in the other keyword: a list that bounded nothing
 *     starts bounding. Opening a closed one is its opposite and is refused with the widenings. An array's
 *     [SCH.items] are judged by these same rules (issue #1119), so a list of several choices narrows as one does.
 *  3. **More required**: [SCH.required] as a superset of the base's.
 *
 * A field made unique ([SCH.unique], issue #1851) narrows, since fewer values may be stored; one the type holds unique
 * stays so, in the scope it names.
 *
 * A file field's kinds and largest size ([SCH.fileKinds], [SCH.fileMaxSize], issue #1834) narrow as a choice list does:
 * fewer kinds, or smaller files of each kind it keeps -- judged by the limit in force, not by the keyword alone. A
 * table field's rows (issue #1882) narrow as an array's items do: an inline row type by these same rules, a `$ref` one
 * not at all, and [SCH.fileMaxRows] only downward.
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
 *
 * Theme: #NARRW.
 */
fun narrowingProblems(typeName: String, base: Map<String, Any?>, overlay: Map<String, Any?>): List<Problem> {
    val problems = mutableListOf<Problem>()
    // Compared against the **merged result**, not against the fragment the client wrote. A declared property
    // body replaces rather than merges, so the fragment says what changed only in the simplest cases; the
    // result says what this client actually accepts, which is the question.
    val outcome = overlayTypeOutcome(typeName, base, overlay)
    // Whatever else the merge refused fails the alteration too, since the result is then not what its author wrote:
    // a merge directive the type does not offer (issue #985), a key changed, added, or removed (issue #1521), or the
    // type's `g-onWrite` functions removed. Each only where the alteration mentions the part -- one that leaves a key
    // out keeps the base's. A layout entry the merge refused is the layout's own fault, reported and dropped with the
    // layout ([isLayoutProblem]).
    for (p in outcome.problems.filterNot { isLayoutProblem(typeName, it) }) {
        problems.add(
            when (p.code) {
                OverlayMergeError.unknownChoice -> p
                OverlayMergeError.fixedValue -> Problem(
                    p.code,
                    "Type '$typeName': its '${SCH.primaryKey}' tells stored values apart, so an alteration may restate it " +
                        "but not change, add, or remove it.",
                    p.location,
                )
                else -> Problem(p.code, "Type '$typeName', at '${p.location?.path ?: typeName}': ${p.message}", p.location)
            },
        )
    }
    compare(typeName, typeName, base, outcome.value, problems, typeLevel = true)
    return problems
}

/**
 * Whether [problem], from merging an alteration of [typeName], lies in the type's field layout (`g-layout`): the
 * layout's own fault, which drops the client's layout rather than the alteration. Every other merge problem fails the
 * alteration ([narrowingProblems]) -- a merge directive the type does not offer among them, even one inside the layout,
 * since the alteration then means something its author did not say.
 */
fun isLayoutProblem(typeName: String, problem: Problem): Boolean {
    if (problem.code == OverlayMergeError.unknownChoice) return false
    val layoutPath = childPath(typeName, SCH.layout)
    val at = problem.location?.path ?: return false
    return at == layoutPath || at.startsWith("$layoutPath.") || at.startsWith("$layoutPath[")
}

/**
 * How a client's alteration fails to narrow a type (issue #909): the codes of [narrowingProblems]. Each problem is
 * located at the keyword in the type, as a schema path (`acme.Q.properties.topic.options`).
 *
 * Theme: #NARRW.
 */
@Suppress("EnumEntryName")
enum class NarrowingError : ProblemCode {
    /** A keyword that takes part in validation, changed in a way that is none of the three narrowings. */
    changesKeyword,

    /** A property the type does not have. */
    addsProperty,

    /** A property the type requires, dropped -- which widens, though it reads as narrowing. */
    dropsRequiredProperty,

    /** A required property made optional. */
    relaxesRequired,

    /** A property required that the altered type does not declare. */
    requiresUndeclared,

    /** A choice list removed. */
    removesChoices,

    /** A choice the type does not offer. */
    addsChoice,

    /** A closed choice list opened. */
    opensChoices,

    /** A file field's kinds widened (issue #1834): a kind the type's field does not take, or the list removed. */
    addsFileKind,

    /** A file field made to hold a larger file of some kind than the type's does (issue #1834). */
    raisesFileMaxSize,

    /** A table field made to hold more rows than the type's does (issue #1882). */
    raisesFileMaxRows,

    /** A field the type holds unique made not to be, or unique in another scope (issue #1851). */
    dropsUnique,
}

private fun narrowed(code: NarrowingError, at: String, message: String) =
    Problem(code, message, ProblemLocation(path = at))

/**
 * Keywords a client may change freely: none of them takes part in deciding whether a value is valid.
 *
 * An allowlist rather than a list of forbidden ones, because the failure directions are not symmetric. A
 * keyword nobody thought about is refused and somebody asks why; the other way round it is silently permitted
 * to widen a type, and nothing says so until data written under it is read by another client.
 *
 * Theme: #NARRW.
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
    // Long text (issue #1550) never reaches validation either: a client may mark a platform trait's plain string long
    // text for its own forms, or say it is not, and accepts exactly what it did. The parser still refuses it off a
    // plain string.
    SCH.longText,
    // Field visibility (issue #545) is evaluated on the frontend (issue #564) and never reaches validation -- a
    // caller the gate hides a field from still has it in the compiled schema, and the trait-data write path
    // (issue #830) or the handler enforces the real condition -- so a client may set or change it freely. Hiding
    // a field cannot widen what a type accepts.
    SCH.visibleWhen,
    // A layout (issue #584) is how a friendly form renders a type's fields; it is never read into `SchType`
    // and takes no part in validation, so a client may alter it -- merged with the base's by field (issue #985).
    // Absent from this list, a client's `g-layout` alteration would be refused as a validation change -- and, in
    // warn mode, take the client's whole alteration of that type down with it.
    SCH.layout,
)

/**
 * The keys that may differ by narrowing; every other validating key must match the base exactly.
 *
 * Theme: #NARRW.
 */
private val narrowingKeys = setOf(
    SCH.properties, SCH.options, SCH.required, SCH.openOptions, SCH.fileKinds, SCH.fileMaxSize, SCH.fileMaxRows, SCH.unique,
)

/**
 * Keys whose merge rule already says what an alteration may do with them, and refuses the rest as a merge problem
 * ([narrowingProblems] reports it): `g-onWrite`, which an alteration may only add to (issue #1501), and `g-primaryKey`,
 * which it may only restate (issue #1521). Compared here they would be refused outright -- added functions included.
 * Only on the type's own body: inside a property's, which an alteration replaces whole, they are compared as any key.
 */
private val mergeRuledKeys = setOf(SCH.onWrite, SCH.primaryKey)

/**
 * [path] names the place in a message (`'acme.Q.topic'`); [at] is the same place as a schema location path. [typeLevel]
 * is the type's own body, the one level a merge rule reaches ([mergeRuledKeys]); a property's body is replaced whole.
 */
private fun compare(
    path: String,
    at: String,
    base: Map<String, Any?>,
    variant: Map<String, Any?>,
    out: MutableList<Problem>,
    typeLevel: Boolean = false,
) {
    checkProperties(path, at, base, variant, out)
    // An **open** list bounds nothing (issue #418), so no list of choices the variant declares can widen what
    // is accepted -- everything already was. So neither the contents nor the subset rule apply while the base
    // is open: a per-client suggestion list is the obvious thing to want, and refusing it would protect
    // nothing.
    if (base[SCH.openOptions] != true) {
        checkOptions(path, at, base[SCH.options], variant[SCH.options], out)
    }
    checkOpenOptions(path, at, base, variant, out)
    checkRequired(path, at, base, variant, out)
    checkFileField(path, at, base, variant, out)
    checkFileMaxRows(path, at, base, variant, out)
    checkUnique(path, at, base, variant, out)
    // An array's elements are judged by the same rules (issue #1119): elements accepting less make an array that
    // accepts less, so a list of several choices may be narrowed as a single choice is.
    val baseItems = (base[SCH.items] as? Map<*, *>)?.toJsonMap()
    val variantItems = (variant[SCH.items] as? Map<*, *>)?.toJsonMap()
    val itemsCompared = baseItems != null && variantItems != null
    if (itemsCompared) compare("$path[]", "$at.${SCH.items}", baseItems, variantItems, out)
    // A table's rows the same way (issue #1882), when both declare them inline: a `$ref` is the shared type, which an
    // alteration may only restate, so it is compared as any keyword is.
    val baseRows = (base[SCH.fileRows] as? Map<*, *>)?.toJsonMap()?.takeIf { SCH.dRef !in it }
    val variantRows = (variant[SCH.fileRows] as? Map<*, *>)?.toJsonMap()?.takeIf { SCH.dRef !in it }
    val rowsCompared = baseRows != null && variantRows != null
    if (rowsCompared) compare("$path rows", "$at.${SCH.fileRows}", baseRows, variantRows, out)
    for (key in base.keys + variant.keys) {
        val judgedElsewhere = key in presentationKeys || key in narrowingKeys || (typeLevel && key in mergeRuledKeys)
        if (judgedElsewhere || (key == SCH.items && itemsCompared) || (key == SCH.fileRows && rowsCompared)) {
            continue
        }
        if (!sameSchemaValue(base[key], variant[key])) {
            out += narrowed(
                NarrowingError.changesKeyword, "$at.$key",
                "'$path' changes '$key' from ${show(base[key])} to ${show(variant[key])}. That takes part in " +
                    "validation and is not one of the three ways a client may narrow a type " +
                    "(${narrowingKeys.joinToString(", ")}). Extend the type instead, which creates a name of " +
                    "its own and may do as it likes.",
            )
        }
    }
}

/**
 * Whether two values of a schema document say the same, a number compared by what it is rather than how it was read: one
 * declared in code is an `Int`, and the same one read back from a stored configuration a `Long`.
 */
fun sameSchemaValue(a: Any?, b: Any?): Boolean = when (a) {
    is Number if b is Number -> a.toDouble() == b.toDouble()
    is Map<*, *> if b is Map<*, *> -> a.keys == b.keys && a.keys.all { sameSchemaValue(a[it], b[it]) }
    is List<*> if b is List<*> -> a.size == b.size && a.indices.all { sameSchemaValue(a[it], b[it]) }
    else -> a == b
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
 *
 * Theme: #NARRW.
 */
private fun checkOpenOptions(
    path: String,
    at: String,
    base: Map<String, Any?>,
    variant: Map<String, Any?>,
    out: MutableList<Problem>,
) {
    if (base[SCH.options] != null && base[SCH.openOptions] != true && variant[SCH.openOptions] == true) {
        out += narrowed(
            NarrowingError.opensChoices, "$at.${SCH.openOptions}",
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
    at: String,
    base: Map<String, Any?>,
    variant: Map<String, Any?>,
    out: MutableList<Problem>,
) {
    val baseProps = (base[SCH.properties] as? Map<*, *>)?.toJsonMap() ?: return
    val declared = (variant[SCH.properties] as? Map<*, *>)?.toJsonMap() ?: return
    val added = declared.keys.filterNot { it in baseProps }
    if (added.isNotEmpty()) {
        out += narrowed(
            NarrowingError.addsProperty, "$at.${SCH.properties}.${added.first()}",
            "'$path' adds the propert${if (added.size == 1) "y" else "ies"} " +
                "${added.joinToString(", ") { "'$it'" }}, which widens what the type accepts. A client adds " +
                "fields by extending the type, not by altering it.",
        )
    }
    // Dropping a property the base requires widens rather than narrows; see the class note.
    val baseRequired = (base[SCH.required] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList()
    val droppedAndRequired = baseRequired.filterNot { it in declared.keys }
    if (droppedAndRequired.isNotEmpty()) {
        out += narrowed(
            NarrowingError.dropsRequiredProperty, "$at.${SCH.properties}",
            "'$path' drops ${droppedAndRequired.joinToString(", ") { "'$it'" }}, which the type requires. " +
                "Removing a required property widens the type rather than narrowing it: data omitting it " +
                "would be accepted here and rejected everywhere else, so what this client stores would be " +
                "invalid to everybody else.",
        )
    }
    for ((name, body) in declared) {
        val baseBody = (baseProps[name] as? Map<*, *>)?.toJsonMap() ?: continue
        val declaredBody = (body as? Map<*, *>)?.toJsonMap() ?: continue
        compare("$path.$name", "$at.${SCH.properties}.$name", baseBody, declaredBody, out)
    }
}

/**
 * A file field narrows by taking fewer kinds or smaller files (issue #1834), and widens by the reverse: a kind the base
 * does not take, the kinds removed, or a larger file of some kind than the base allows.
 *
 * **Sizes are judged per kind, by what is in force**: the smaller of the field's `g-fileMaxSize` and the kind's own
 * largest ([fileSizeLimit]'s rule). So narrowing `[png, mp4]` at 100 MB to `[png]` needs no size at all -- a PNG's own
 * 20 MB is below 100 MB -- where comparing the keyword alone would call dropping it a widening. A size is read as the
 * parser reads it, a number or text spelling one.
 *
 * Themes: #DFILE, #NARRW.
 */
private fun checkFileField(path: String, at: String, base: Map<String, Any?>, variant: Map<String, Any?>, out: MutableList<Problem>) {
    val baseKinds = (base[SCH.fileKinds] as? List<*>)?.mapNotNull { it.toOptStr() } ?: return
    val kinds = (variant[SCH.fileKinds] as? List<*>)?.mapNotNull { it.toOptStr() }
    val added = kinds?.filterNot { it in baseKinds } ?: baseKinds
    if (kinds == null || added.isNotEmpty()) {
        out += narrowed(
            NarrowingError.addsFileKind, "$at.${SCH.fileKinds}",
            "'$path' " + (if (kinds == null) "removes its '${SCH.fileKinds}'" else "takes ${added.joinToString(", ") { "'$it'" }}") +
                ", which widens the kinds of file the type takes. A client may take fewer kinds, never more.",
        )
        return
    }
    val baseMax = base[SCH.fileMaxSize].toOptDoubleOrNull()
    val max = variant[SCH.fileMaxSize].toOptDoubleOrNull()
    // A kind the registry does not know is the parser's to refuse; here it has no limit to compare.
    val raised = kinds.mapNotNull { FileKinds.byId(it) }.firstOrNull { kind ->
        limitInForce(max, kind) > limitInForce(baseMax, kind)
    } ?: return
    out += narrowed(
        NarrowingError.raisesFileMaxSize, "$at.${SCH.fileMaxSize}",
        "'$path' allows a ${raised.label} file of up to ${limitInForce(max, raised).toLong()} bytes, above the type's " +
            "${limitInForce(baseMax, raised).toLong()}. A client may lower a file field's largest size, never raise it.",
    )
}

/**
 * A table field narrows by holding fewer rows (issue #1882): its `g-fileMaxRows` may be lowered, and set where the
 * base left the default, never raised -- each read as the parser reads it, unset being [DTAB.defaultMaxRows].
 *
 * Themes: #DTABL, #NARRW.
 */
private fun checkFileMaxRows(path: String, at: String, base: Map<String, Any?>, variant: Map<String, Any?>, out: MutableList<Problem>) {
    if (base[SCH.fileRows] == null) return
    val baseMax = base[SCH.fileMaxRows].toOptDoubleOrNull() ?: DTAB.defaultMaxRows.toDouble()
    val max = variant[SCH.fileMaxRows].toOptDoubleOrNull() ?: DTAB.defaultMaxRows.toDouble()
    if (max <= baseMax) return
    out += narrowed(
        NarrowingError.raisesFileMaxRows, "$at.${SCH.fileMaxRows}",
        "'$path' allows a table of up to ${max.toLong()} rows, above the type's ${baseMax.toLong()}. A client may lower " +
            "a table field's most rows, never raise it.",
    )
}

/**
 * A field may be made unique (issue #1851), and one the type holds unique is kept so, in the scope it names.
 *
 * Theme: #NARRW.
 */
private fun checkUnique(path: String, at: String, base: Map<String, Any?>, variant: Map<String, Any?>, out: MutableList<Problem>) {
    val scope = base[SCH.unique] ?: return
    if (variant[SCH.unique] == scope) return
    out += narrowed(
        NarrowingError.dropsUnique, "$at.${SCH.unique}",
        "'$path' changes '${SCH.unique}' from ${show(scope)} to ${show(variant[SCH.unique])}, which lets two forms share a " +
            "value the type keeps unique. A client may make a field unique, not undo it.",
    )
}

/** The largest file of [kind] a field with largest size [max] (null for none) holds. */
private fun limitInForce(max: Double?, kind: FileKind): Double = minOf(max ?: kind.maxSize.toDouble(), kind.maxSize.toDouble())

/** A shorter choice list, or a choice list where the base had none. Labels are presentation and are ignored. */
private fun checkOptions(path: String, at: String, baseValue: Any?, value: Any?, out: MutableList<Problem>) {
    val baseOptions = optionValues(baseValue) ?: return // the base offered no choices: applying some narrows
    val declared = optionValues(value) ?: run {
        out += narrowed(
            NarrowingError.removesChoices, "$at.${SCH.options}",
            "'$path' removes its choice list, which widens what the type accepts.",
        )
        return
    }
    val added = declared.filterNot { it in baseOptions }
    if (added.isNotEmpty()) {
        out += narrowed(
            NarrowingError.addsChoice, "$at.${SCH.options}",
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
    at: String,
    base: Map<String, Any?>,
    variant: Map<String, Any?>,
    out: MutableList<Problem>,
) {
    val declared = (variant[SCH.required] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList()
    val baseRequired = (base[SCH.required] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList()
    val dropped = baseRequired.filterNot { it in declared }
    if (dropped.isNotEmpty()) {
        out += narrowed(
            NarrowingError.relaxesRequired, "$at.${SCH.required}",
            "'$path' no longer requires ${dropped.joinToString(", ") { "'$it'" }}. Making a required " +
                "property optional widens the type: this client would store entries that are invalid " +
                "everywhere else.",
        )
    }
    val props = (variant[SCH.properties] as? Map<*, *>)?.toJsonMap() ?: emptyMap()
    val unknown = declared.filterNot { it in props }
    if (props.isNotEmpty() && unknown.isNotEmpty()) {
        out += narrowed(
            NarrowingError.requiresUndeclared, "$at.${SCH.required}",
            "'$path' requires ${unknown.joinToString(", ") { "'$it'" }}, which the type does not declare.",
        )
    }
}
