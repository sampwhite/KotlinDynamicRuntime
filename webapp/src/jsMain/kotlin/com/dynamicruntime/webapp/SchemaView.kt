package com.dynamicruntime.webapp

/**
 * Pure-Kotlin (no React, no `dynamic`) model of the endpoint schema the display engine renders. It consumes
 * the catalog form emitted by the runtime's `/schema/endpoints` endpoint (see the server's SchemaService and
 * `renderEndpoint`): a list of endpoint renderings plus a shared `$defs` bag the renderings' `$ref`s bind to.
 *
 * Keeping this layer free of `dynamic`/React (it works over plain `Map`/`List`/primitives, exactly like the
 * server's schema code) follows the codebase's multiplatform rule and keeps the field-dispatch logic testable
 * on its own. The JSON->Kotlin conversion and fetching live in [SchemaCatalogApi]; the rendering in the
 * `SchemaForm` components.
 */

/** JSON Schema keys the model reads. Mirrors the server's `SCH`/`SCT`/`SFMT`/`EI` constants (the source of
 *  truth on the JVM side); redeclared here because `webapp` (Kotlin/JS) cannot depend on `:base:common`. */
@Suppress("ConstPropertyName")
object SK {
    // Schema keywords.
    const val type = "type"
    const val properties = "properties"
    const val required = "required"
    const val ref = "\$ref"
    const val format = "format"
    const val items = "items"
    const val description = "description"
    const val options = "options" // custom choice construct: a list of {label, value}
    const val label = "label"
    const val value = "value"

    // `type` values.
    const val string = "string"
    const val integer = "integer"
    const val number = "number"
    const val boolean = "boolean"
    const val array = "array"
    const val kObject = "object"

    // `format` values for date fields.
    const val date = "date"
    const val dateTime = "date-time"

    // EndpointInfo (one endpoint's rendering) keys.
    const val path = "path"
    const val method = "method"
    const val kind = "kind"
    const val namespace = "namespace"
    const val inputSchema = "inputSchema"
    const val outputSchema = "outputSchema"

    // Catalog result keys: the endpoints list and the shared `$defs` bag.
    const val endpoints = "endpoints"
    const val ref_defs = "\$defs"
}

/** Guard on nested-schema recursion (the codebase requires external/map recursion to carry an explicit depth
 *  and fail past a limit). Cycles are already terminated by ref tracking; this is belt-and-braces. */
private const val maxSchemaDepth = 50

/** The whole `/schema/endpoints` result: the rendered endpoints and the shared `$defs` their `$ref`s resolve in. */
class Catalog(val endpoints: List<EndpointInfo>, val defs: Map<String, Any?>)

/** One endpoint's catalog rendering. [inputSchema]/[outputSchema] are JSON-schema object maps with `$ref`s intact. */
class EndpointInfo(
    val path: String,
    val method: String,
    val kind: String,
    val namespace: String,
    val description: String?,
    val inputSchema: Map<String, Any?>,
    val outputSchema: Map<String, Any?>,
)

/** One choice in an option list: the stored [value] and its display [label]. */
class Opt(val label: String, val value: String)

/**
 * A normalized field of an object schema — the atom the renderer dispatches on. One subtype per widget the
 * display engine draws; [ObjectField] recurses (a nested map with its own schema), [ArrayField] repeats.
 */
sealed interface FieldSpec {
    val name: String
    val description: String?
    val required: Boolean
}

class StringField(override val name: String, override val description: String?, override val required: Boolean) : FieldSpec
class BoolField(override val name: String, override val description: String?, override val required: Boolean) : FieldSpec
class NumberField(override val name: String, override val description: String?, override val required: Boolean, val integer: Boolean) : FieldSpec
class DateField(override val name: String, override val description: String?, override val required: Boolean, val withTime: Boolean) : FieldSpec

/** A choice list. [multi] is true when the field is an array of options (multi-select). */
class OptionField(
    override val name: String,
    override val description: String?,
    override val required: Boolean,
    val options: List<Opt>,
    val multi: Boolean,
) : FieldSpec

/** A nested map with its own schema (from a `$ref`). [fields] is empty when [cyclic] (expansion stopped to
 *  break a self-/mutually-referential type, e.g. the sample's `TreeNode`); the renderer expands it on demand. */
class ObjectField(
    override val name: String,
    override val description: String?,
    override val required: Boolean,
    val typeName: String?,
    val fields: List<FieldSpec>,
    val cyclic: Boolean,
) : FieldSpec

/** A repeated field: a list of [element]s (non-choice arrays; a choice array becomes a multi [OptionField]). */
class ArrayField(
    override val name: String,
    override val description: String?,
    override val required: Boolean,
    val element: FieldSpec,
) : FieldSpec

/** Fallback for a schema node the engine does not yet model, so an unknown shape degrades to a text field. */
class UnknownField(override val name: String, override val description: String?, override val required: Boolean) : FieldSpec

/** The bare `ns.Type` name a `$ref` pointer (`#/$defs/ns.Type`) targets. */
fun refTargetName(ref: String): String = ref.substringAfterLast("/")

/**
 * Normalizes an object schema's `properties` into ordered [FieldSpec]s, resolving `$ref`s against [defs] and
 * marking each field required per the object's sibling `required` array. [seen] tracks the `$ref` names on the
 * current ancestor path so a self-/mutually-referential type terminates instead of expanding forever.
 */
fun toFields(objectSchema: Map<String, Any?>, defs: Map<String, Any?>, depth: Int = 0, seen: Set<String> = emptySet()): List<FieldSpec> {
    if (depth > maxSchemaDepth) error("Schema nesting exceeded $maxSchemaDepth levels.")
    val properties = objectSchema[SK.properties].asMap()
    val required = objectSchema[SK.required].asList().mapNotNull { it as? String }.toSet()
    return properties.map { (name, node) ->
        toField(name, node.asMap(), name in required, defs, depth, seen)
    }
}

/** Dispatches one property [node] to its [FieldSpec] by shape (`$ref` / options / type + format). */
private fun toField(name: String, node: Map<String, Any?>, required: Boolean, defs: Map<String, Any?>, depth: Int, seen: Set<String>): FieldSpec {
    val desc = node[SK.description] as? String

    val ref = node[SK.ref] as? String
    if (ref != null) {
        val typeName = refTargetName(ref)
        if (typeName in seen) {
            return ObjectField(name, desc, required, typeName, emptyList(), cyclic = true)
        }
        val target = defs[typeName].asMap()
        val fields = toFields(target, defs, depth + 1, seen + typeName)
        return ObjectField(name, desc, required, typeName, fields, cyclic = false)
    }

    val options = node[SK.options].asList()
    if (options.isNotEmpty()) {
        return OptionField(name, desc, required, options.toOpts(), multi = false)
    }

    return when (node[SK.type] as? String) {
        SK.boolean -> BoolField(name, desc, required)
        SK.integer -> NumberField(name, desc, required, integer = true)
        SK.number -> NumberField(name, desc, required, integer = false)
        SK.array -> {
            val items = node[SK.items].asMap()
            val itemOptions = items[SK.options].asList()
            if (itemOptions.isNotEmpty()) {
                OptionField(name, desc, required, itemOptions.toOpts(), multi = true)
            } else {
                ArrayField(name, desc, required, toField("$name[]", items, false, defs, depth + 1, seen))
            }
        }
        SK.string -> when (node[SK.format] as? String) {
            SK.date -> DateField(name, desc, required, withTime = false)
            SK.dateTime -> DateField(name, desc, required, withTime = true)
            else -> StringField(name, desc, required)
        }
        null -> UnknownField(name, desc, required)
        else -> StringField(name, desc, required)
    }
}

/** Maps raw `{label, value}` option nodes to [Opt]s, tolerating a bare-value node by using it for both. */
private fun List<Any?>.toOpts(): List<Opt> = map { raw ->
    val m = raw.asMap()
    val value = m[SK.value]?.toString() ?: raw?.toString() ?: ""
    Opt(label = m[SK.label]?.toString() ?: value, value = value)
}

// --- casting helpers -------------------------------------------------------------------------------------
// `webapp` is Kotlin/JS and cannot depend on `:base:common`, so the JVM `toJsonMap()`/`toT()` coercers are not
// on the classpath; these local, null-tolerant equivalents keep the JSON walk free of scattered casts.

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()
