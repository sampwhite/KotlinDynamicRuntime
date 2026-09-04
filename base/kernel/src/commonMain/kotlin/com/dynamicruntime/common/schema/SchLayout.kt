package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The **layout** for a schema type (issue #584): how a friendly form renders that type's fields, kept apart
 * from `SchType` because it varies by surface rather than by validity (`thoughts-schema-direction.md` §9). It is
 * authored inline in the schema document under the `g-layout` keyword, but read out by its own function
 * ([collectLayouts]) into this model, held **beside** the compiled types in the schema store and **never** on
 * `SchType`; it is stripped from the served schema ([withoutLayouts]) and delivered out-of-band, so the wire
 * schema stays a clean, documentation-grade artifact.
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
    val fieldNames: List<String> get() = fields.map { it.field }
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
 *  inside the value of the `g-layout` keyword, not keywords in the schema namespace, so nothing collides. */
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

    /** On a [schemaFields] entry: the per-failure-code error override (shaped in a later stage). */
    const val error = "error"

    /** On the block: the fragment file its `${'$'}{…}` substitutions resolve against. */
    const val fragmentFileId = "fragmentFileId"
}

/**
 * Parses one `g-layout` block [raw] into a [SchLayout], throwing on a structurally bad one so a mistake fails
 * the boot rather than rendering as nothing. [where] names the type for the message.
 */
fun parseSchLayout(where: String, raw: Map<String, Any?>): SchLayout {
    val fields = raw[SL.schemaFields].toJsonListOrEmpty().map { entry ->
        val m = entry.toJsonMapOrEmpty()
        val field = m[SL.field].toOptStr()
            ?: throw KdrException("$where: a '${SL.schemaFields}' entry has no '${SL.field}'.")
        SchLayoutField(field, m[SL.label].toOptStr(), m[SL.description].toOptStr(), m[SL.hint].toOptStr())
    }
    return SchLayout(raw[SL.fragmentFileId].toOptStr(), fields)
}

/**
 * The `{ typeName -> SchLayout }` for every type in [defs] that declares a `g-layout` (issue #584). A read-only
 * pass -- [defs] is not mutated, so a body keeps its `g-layout` for the per-client overlay merge to inherit;
 * the served schema is cleaned separately by [withoutLayouts]. A `g-layout` that is not an object fails the boot.
 */
fun collectLayouts(defs: Map<String, Any?>): Map<String, SchLayout> {
    val out = LinkedHashMap<String, SchLayout>()
    for ((name, body) in defs) {
        val rawLayout = (body as? Map<*, *>)?.get(SCH.layout) ?: continue
        if (rawLayout !is Map<*, *>) {
            throw KdrException("Type '$name': '${SCH.layout}' must be an object.")
        }
        out[name] = parseSchLayout("Type '$name'", rawLayout.toJsonMapOrEmpty())
    }
    return out
}

/**
 * [defs] with each body's top-level `g-layout` removed (issue #584) -- the served, documentation-grade schema,
 * since the layout is delivered out-of-band. Non-destructive: bodies that carry a `g-layout` are copied without
 * it, everything else is returned as-is.
 */
fun withoutLayouts(defs: Map<String, Any?>): Map<String, Any?> = defs.mapValues { (_, body) ->
    if (body is Map<*, *> && body.containsKey(SCH.layout)) body.toJsonMapOrEmpty() - SCH.layout else body
}

/**
 * The field references in [layout] that [type] does not declare (issue #584) -- the boot check that a layout
 * cannot name a property that is not there. An unresolved type is itself a problem. [where] names the type.
 */
fun layoutFieldProblems(where: String, layout: SchLayout, type: SchType?): List<String> {
    if (type == null) {
        return listOf("$where: a '${SCH.layout}' is declared on a type that did not resolve.")
    }
    val props = type.properties.keys
    return layout.fieldNames.filterNot { it in props }
        .map { "$where: '${SCH.layout}' names field '$it', which the type does not declare." }
}
