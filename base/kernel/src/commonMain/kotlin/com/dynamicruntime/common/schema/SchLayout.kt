package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.fmtD
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
 * — and the block's [fragmentFileId]; the error override and field inclusion/order are later stages. Stage 2
 * (issue #585) delivers the model to every friendly surface ([deliveredLayouts]) and parses it back on the
 * frontend ([parseDeliveredLayouts]); nothing renders it yet.
 */
class SchLayout(
    /** The fragment file the block's `${'$'}{…}` substitutions resolve against, declared once for the block. */
    val fragmentFileId: String?,
    /** The per-field overrides, in declaration order. */
    val fields: List<SchLayoutField>,
) : JsonMappable {
    /** The schema properties this layout addresses -- what the boot check holds against the type. */
    val fieldNames: List<String> = fields.map { it.field }

    /** The override for property [name], or null when this layout does not address that field. */
    fun fieldFor(name: String): SchLayoutField? = fields.firstOrNull { it.field == name }

    /**
     * The layout as its `g-layout` block again -- the wire form (issue #585). Delivery re-serializes the
     * **model** rather than shipping the authored block, so what a page receives is what the store holds: the
     * pruned form for a client that narrowed the type, never a field the client's type lacks. Round-trips
     * through [parseSchLayout]; a null override is omitted, not written as null. Built once and kept: the
     * model never changes after boot and every catalog and workflow-view request delivers it, so the wire form
     * is a property of the layout rather than work done per request (the same reason `servedDefs` is).
     */
    private val jsonMap: Map<String, Any?> by lazy {
        val out = LinkedHashMap<String, Any?>()
        fragmentFileId?.let { out[SL.fragmentFileId] = it }
        out[SL.schemaFields] = fields.map { it.toJsonMap() }
        out
    }

    override fun toJsonMap(): Map<String, Any?> = jsonMap

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
) : JsonMappable {
    /** The entry as written in a `schemaFields` list; see [SchLayout.toJsonMap]. */
    override fun toJsonMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out[SL.field] = field
        label?.let { out[SL.label] = it }
        description?.let { out[SL.description] = it }
        hint?.let { out[SL.hint] = it }
        return out
    }
}

/**
 * Declares a type's `g-layout` from the schema DSL (issue #585): `layout { field("topic", label = "Topic") }`
 * inside a `type("X") { ... }` block, or a trait's data block. Writes the same block [parseSchLayout] reads, so
 * the boot check and the delivery see a handwritten block and a built one identically. A builder rather than
 * the raw-map escape hatch because the sample's layouts are read by people, and the sets [SL.blockKeys] /
 * [SL.fieldKeys] the parser is strict about are then spelled once, here.
 */
class SchLayoutBuilder(private val fragmentFileId: String?) {
    private val fields = mutableListOf<SchLayoutField>()

    /** One field's overrides; each is optional. */
    fun field(name: String, label: String? = null, description: String? = null, hint: String? = null) {
        fields.add(SchLayoutField(name, label, description, hint))
    }

    /** The finished block, as the JSON `g-layout` value. */
    fun build(): Map<String, Any?> = SchLayout(fragmentFileId, fields.toList()).toJsonMap()
}

/** Attaches a `g-layout` to the type being built; see [SchLayoutBuilder]. Replaces one declared earlier. */
fun SchTypeBuilder.layout(fragmentFileId: String? = null, block: SchLayoutBuilder.() -> Unit) {
    data[SCH.layout] = SchLayoutBuilder(fragmentFileId).apply(block).build()
}

/**
 * The `{ typeName -> g-layout block }` to deliver beside a served `$defs` closure (issue #585): the layouts in
 * [layouts] for exactly the [typeNames] the closure carries, each in wire form ([SchLayout.toJsonMap]). A type with
 * no layout has **no entry** -- absent, not empty -- so a page reads "no layout" and "no key" the same way. The
 * shared helper both friendly surfaces call (the endpoint catalog and the workflow view), the way the delivered
 * cfacts have one shape; a pure function, so the frontend could reuse it if it ever assembles a view itself.
 */
fun deliveredLayouts(layouts: Map<String, SchLayout>, typeNames: Collection<String>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (name in typeNames) {
        layouts[name]?.let { out[name] = it.toJsonMap() }
    }
    return out
}

/**
 * The frontend's read of a delivered `layouts` map (issue #585) -- the inverse of [deliveredLayouts], through
 * the same strict [parseSchLayout] the boot ran, so a page holds the same model the store did. An absent or
 * non-object map (an older node) parses to empty, which reads as "no layouts" rather than a fault: the schema
 * renders without one. A malformed entry, though, is a fault -- the backend built it from a parsed model, so a
 * bad one is a wiring bug worth surfacing, not a case to render around.
 */
fun parseDeliveredLayouts(raw: Any?): Map<String, SchLayout> {
    val map = raw.toJsonMapOrEmpty()
    val out = LinkedHashMap<String, SchLayout>()
    for ((name, block) in map) {
        out[name] = parseSchLayout("Type '$name'", block.toJsonMapOrEmpty())
    }
    return out
}

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

/**
 * The template parameters a layout `${'$'}{…}` may reference, **per context** (issue #587, `thoughts-schema-direction.md`
 * §10). Only the injected-param contexts live here: their vocabulary is fixed and known at boot, so a placeholder
 * naming a param the field's context does not provide is caught like a mistyped key. The field's **own data**
 * (what a `label` / `description` resolves against) is not here -- it is dynamic, so no boot check can enumerate it.
 *
 * The **bounds** context is the first and, in this stage, only one: a numeric/bounded field's `min` / `max`, for a
 * `hint` that replaces the derived `range: X to Y`. A name appears only when the field actually declares that
 * bound, so `${'$'}{max}` on a field with no maximum is a boot failure.
 */
@Suppress("ConstPropertyName")
object LayoutCtx {
    /** The declared lower bound (`minimum` / `minLength` / `minItems`), when the field has one. */
    const val min = "min"

    /** The declared upper bound (`maximum` / `maxLength` / `maxItems`), when the field has one. */
    const val max = "max"
}

/** The bounds-context param names [type] provides: [LayoutCtx.min] when it declares a minimum, [LayoutCtx.max]
 *  when it declares a maximum (issue #587). The boot check holds a `hint` template's references against this. */
fun boundsContextNames(type: SchType): Set<String> = buildSet {
    if (type.minBound != null) add(LayoutCtx.min)
    if (type.maxBound != null) add(LayoutCtx.max)
}

/** The bounds-context data [type] supplies to a `hint` template (issue #587): each provided bound as its
 *  formatted number, keyed by [LayoutCtx.min] / [LayoutCtx.max]. The values a `${'$'}{min}` / `${'$'}{max}` resolves to;
 *  the same names [boundsContextNames] validates, so the boot check and the render agree on the vocabulary. */
fun boundsContextData(type: SchType): Map<String, Any?> = buildMap {
    type.minBound?.let { put(LayoutCtx.min, it.fmtD()) }
    type.maxBound?.let { put(LayoutCtx.max, it.fmtD()) }
}

/**
 * The problems with a layout's `hint` templates against the fields they annotate (issue #587) -- the boot check
 * for §10's injected-param placeholders. For each field carrying a `hint`: a malformed template fails, and a
 * `${'$'}{…}` referencing a bounds param the field does not provide (`${'$'}{max}` on a field with no maximum) fails, the
 * same way a mistyped key would. Only the `hint` is checked here: a `label` / `description` resolves against the
 * field's own dynamic data, which no boot check can enumerate, and a `@t` fragment pull is a fragment-existence
 * concern rather than a param one (it is not a data path). [type] is the object the layout annotates; a field the
 * type does not declare is already reported by [layoutFieldProblems], so it is skipped here.
 */
fun layoutHintProblems(where: String, layout: SchLayout, type: SchType?): List<String> {
    if (type == null) return emptyList()
    val problems = mutableListOf<String>()
    for (field in layout.fields) {
        val hint = field.hint ?: continue
        val prop = type.properties[field.field] ?: continue
        val allowed = boundsContextNames(prop.valueType)
        val analysis = hint.analyzeTemplate()
        for (issue in analysis.issues) {
            problems.add("$where: the '${SCH.layout}' hint for '${field.field}' is a malformed template: ${issue.message}")
        }
        for (path in analysis.paths.required + analysis.paths.optional) {
            val name = path.substringBefore('.')
            if (name !in allowed) {
                problems.add(
                    "$where: the '${SCH.layout}' hint for '${field.field}' references '${'$'}{$path}', but this field's " +
                        "bounds context provides ${if (allowed.isEmpty()) "no params (it declares no minimum or maximum)" else allowed.sorted().toString()}.",
                )
            }
        }
    }
    return problems
}
