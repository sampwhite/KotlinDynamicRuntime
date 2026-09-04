package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The **layout** for a schema type (issue #584): how a friendly form renders that type's fields, kept apart
 * from `SchType` because it varies by surface rather than by validity (`thoughts-schema-direction.md` §9, in
 * the private `sampwhite/Actions` design notes). It is authored inline in the schema document under the
 * `g-layout` keyword, but read out by its own function ([collectLayouts]) into this model, held **beside** the
 * compiled types in the schema store and **never** on `SchType`; it is stripped from the served schema
 * ([withoutLayouts]) and delivered out-of-band, so the wire schema stays a clean, documentation-grade artifact.
 *
 * This first slice (Stage 1) carries the copy-override fields — [SchLayoutField.label] / `description` / `hint`
 * — and the block's [fragmentFileId]; the error override and field inclusion/order are later stages. The model
 * is parsed and held here; nothing consumes it yet.
 */
class SchLayout(
    /** The fragment file the block's `${'$'}{…}` substitutions resolve against, declared once for the block. */
    val fragmentFileId: String?,
    /** The per-field overrides, in declaration order. */
    val fields: List<SchLayoutField>,
) {
    /** The schema properties this layout addresses -- what the boot check holds against the type. */
    val fieldNames: List<String> = fields.map { it.field }

    /**
     * This layout with every field the type does not declare dropped -- [props] being the type's property
     * names. A client that narrows a type (mentions only the properties it keeps) inherits the base's layout
     * intact, so the inherited layout can name a field the client's type no longer has; the field is moot for
     * that client, and pruning it is the sanctioned outcome rather than a refused boot. Returns `this` when
     * nothing is dropped.
     */
    fun prunedTo(props: Set<String>): SchLayout {
        val kept = fields.filter { it.field in props }
        return if (kept.size == fields.size) this else SchLayout(fragmentFileId, kept)
    }
}

/** One field's overrides in a [SchLayout] (issue #584): the schema property [field] it addresses, and the copy
 *  that shadows the schema's own `title` / `description` / bound hint when the form renders it. */
class SchLayoutField(
    val field: String,
    val label: String?,
    val description: String?,
    val hint: String?,
)

/** The vocabulary inside a `g-layout` block (issue #584). Bare rather than `g-`-prefixed: these are fields
 *  inside the value of the `g-layout` keyword, not keywords in the schema namespace, so nothing collides. The
 *  sets [blockKeys] and [fieldKeys] are what the parser refuses anything outside of, so a misspelled or
 *  not-yet-supported key fails the boot rather than silently rendering nothing. */
@Suppress("ConstPropertyName")
object SL {
    /** The per-field override list (renamed from the draft's `formFields`: it lists schema properties). */
    const val schemaFields = "schemaFields"

    /** On a [schemaFields] entry: the schema property it addresses. */
    const val field = "field"

    /** On a [schemaFields] entry: the label that shadows the schema's `title`. */
    const val label = "label"

    /** On a [schemaFields] entry: the description that shadows the schema's `description`. */
    const val description = "description"

    /** On a [schemaFields] entry: the hint that shadows the derived bound hint. */
    const val hint = "hint"

    /** On the block: the fragment file its `${'$'}{…}` substitutions resolve against. */
    const val fragmentFileId = "fragmentFileId"

    /** Every key a `g-layout` block may carry. */
    val blockKeys: Set<String> = setOf(schemaFields, fragmentFileId)

    /** Every key a [schemaFields] entry may carry. */
    val fieldKeys: Set<String> = setOf(field, label, description, hint)
}

/**
 * Parses one `g-layout` block [raw] into a [SchLayout], throwing on a structurally bad one so a mistake fails
 * the boot rather than rendering as nothing. Strict about keys: an unknown key on the block or on a field entry
 * is refused (the same stance `g-errors` takes), because the failure it guards against is a block that parses
 * clean and does nothing -- the draft's `formFields`, a `schemaFields` written as an object, a typo. A present
 * block must list at least one field for the same reason. [where] names the type for the message.
 */
fun parseSchLayout(where: String, raw: Map<String, Any?>): SchLayout {
    refuseUnknownKeys(where, "a '${SCH.layout}' block", raw.keys, SL.blockKeys)
    val entries = raw[SL.schemaFields]
    if (entries !is List<*> || entries.isEmpty()) {
        throw KdrException("$where: a '${SCH.layout}' block must list at least one '${SL.schemaFields}' entry.")
    }
    val fields = entries.toJsonListOfMaps().map { m ->
        refuseUnknownKeys(where, "a '${SL.schemaFields}' entry", m.keys, SL.fieldKeys)
        val field = m[SL.field].toOptStr()
            ?: throw KdrException("$where: a '${SL.schemaFields}' entry has no '${SL.field}'.")
        SchLayoutField(field, m[SL.label].toOptStr(), m[SL.description].toOptStr(), m[SL.hint].toOptStr())
    }
    return SchLayout(raw[SL.fragmentFileId].toOptStr(), fields)
}

private fun refuseUnknownKeys(where: String, what: String, present: Set<String>, allowed: Set<String>) {
    val unknown = present.filterNot { it in allowed }
    if (unknown.isNotEmpty()) {
        throw KdrException("$where: $what has unknown key(s) ${unknown.sorted()}; allowed: ${allowed.sorted()}.")
    }
}

/**
 * The `{ typeName -> SchLayout }` for every type in [defs] that declares a `g-layout` (issue #584). A read-only
 * pass -- [defs] is not mutated, so a body keeps its `g-layout` for the per-client overlay merge to inherit;
 * the served schema is cleaned separately by [withoutLayouts].
 *
 * Only a **named** type's own top-level `g-layout` is collected. A `g-layout` found anywhere below that -- on
 * an inline sub-object property, say -- is **refused**, not ignored: there is no name to key it by, so it could
 * be neither delivered nor checked, and the honest answer is to say so at boot ("pull the sub-object out as a
 * named type") rather than let it silently render nothing and leak into the served schema. A `g-layout` that
 * is not an object fails the boot too.
 */
fun collectLayouts(defs: Map<String, Any?>): Map<String, SchLayout> {
    val out = LinkedHashMap<String, SchLayout>()
    for ((name, body) in defs) {
        if (body !is Map<*, *>) continue
        refuseNestedLayout("Type '$name'", body, depth = 0)
        val rawLayout = body[SCH.layout] ?: continue
        if (rawLayout !is Map<*, *>) {
            throw KdrException("Type '$name': '${SCH.layout}' must be an object.")
        }
        out[name] = parseSchLayout("Type '$name'", rawLayout.toJsonMapOrEmpty())
    }
    return out
}

/** The deepest a schema body is walked looking for a stray nested `g-layout`; matches the JSON nesting cap. */
private const val maxLayoutScanDepth = 50

/** Throws on a `g-layout` key found at any depth **below** the top level of [node]; [depth] guards the walk. */
private fun refuseNestedLayout(where: String, node: Map<*, *>, depth: Int) {
    if (depth >= maxLayoutScanDepth) return
    for ((key, value) in node) {
        // The top-level key is the one collected; anything under it is not.
        if (depth == 0 && key == SCH.layout) continue
        when (value) {
            is Map<*, *> -> {
                if (value.containsKey(SCH.layout)) {
                    throw KdrException(
                        "$where: a '${SCH.layout}' under '$key' is not on a named type, so it can be neither " +
                            "delivered nor checked. Pull the sub-object out as a named type and put the layout there.",
                    )
                }
                refuseNestedLayout(where, value, depth + 1)
            }
            is List<*> -> for (element in value) {
                if (element is Map<*, *>) {
                    if (element.containsKey(SCH.layout)) {
                        throw KdrException(
                            "$where: a '${SCH.layout}' inside '$key' is not on a named type, so it can be " +
                                "neither delivered nor checked. Pull the sub-object out as a named type.",
                        )
                    }
                    refuseNestedLayout(where, element, depth + 1)
                }
            }
        }
    }
}

/**
 * [defs] with each body's top-level `g-layout` removed (issue #584) -- the served, documentation-grade schema,
 * since the layout is delivered out-of-band. Non-destructive: bodies that carry a `g-layout` are copied without
 * it, everything else is returned as-is. Top-level is the whole job: [collectLayouts] has already refused any
 * `g-layout` below it at boot, so none can be here to miss. Shape is validated by [collectLayouts] too; this
 * only strips.
 */
fun withoutLayouts(defs: Map<String, Any?>): Map<String, Any?> = defs.mapValues { (_, body) ->
    if (body is Map<*, *> && body.containsKey(SCH.layout)) body.toJsonMapOrEmpty() - SCH.layout else body
}

/**
 * The problems with [layout] against the [type] it is declared on (issue #584) -- the boot check. A layout
 * belongs on an **object** type: a union carries its fields on its branches and an array on its items, so
 * there is no single property set to render, and a layout there is refused with a message naming that rather
 * than listing every field as undeclared. On an object type, each field the layout names must be a property
 * the type declares. An unresolved type is itself a problem. [where] names the type.
 */
fun layoutFieldProblems(where: String, layout: SchLayout, type: SchType?): List<String> {
    if (type == null) {
        return listOf("$where: a '${SCH.layout}' is declared on a type that did not resolve.")
    }
    if (type.variants != null || type.jsonType == SCT.array) {
        return listOf(
            "$where: a '${SCH.layout}' belongs on an object type; this is a " +
                "${if (type.variants != null) "union" else "array"} -- put the layout on the branch or item type.",
        )
    }
    val props = type.properties.keys
    return layout.fieldNames.filterNot { it in props }
        .map { "$where: '${SCH.layout}' names field '$it', which the type does not declare." }
}
