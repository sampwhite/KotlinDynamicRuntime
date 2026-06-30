package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.config.KdrConfigData
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.deepClone
import com.dynamicruntime.common.util.toT
import kotlin.reflect.KProperty

/**
 * Map-backed nullable delegate for a single schema attribute stored under [key].
 * Reading returns `null` when the key is absent; writing `null` removes it. Lets
 * a builder expose optional keywords as plain `var`s (`b.title = "..."`).
 */
class SchAttr<T>(private val data: MutableMap<String, Any?>, private val key: String) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = data[key]?.toT()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        if (value == null) data.remove(key) else data[key] = value
    }
}

/** Qualifies a bare type [name] with [namespace]. A name that already contains a
 *  '.' is treated as fully qualified and returned unchanged. */
fun qualifyTypeName(name: String, namespace: String): String =
    if (name.contains('.')) name else "$namespace.$name"

/** JSON Pointer to a type under `$defs`, e.g. "#/${'$'}defs/core.Count". A bare
 *  [name] is resolved within [namespace]; a dotted name is used as-is. */
fun typeRefPath(name: String, namespace: String): String =
    "#/${SCH.dDefs}/${qualifyTypeName(name, namespace)}"

/**
 * Builder for a single JSON Schema "type" (a schema node, usually an object
 * schema). Map-backed like the application-config builders ([KdrConfigData]), so
 * a built type is just an insertion-ordered `Map<String,Any?>` ready to be
 * rendered as JSON Schema.
 *
 * Carries the [namespace] it is declared in so that `$ref`s to sibling types can
 * be written with bare names. Required is tracked ON THE SIDE: marking a property
 * required records its name in this type's `required` array rather than as a flag
 * on the field.
 */
open class SchTypeBuilder(
    cxt: KdrCxt,
    val namespace: String,
    data: MutableMap<String, Any?> = LinkedHashMap(),
) : KdrConfigData(cxt, data) {

    var type: String? by SchAttr(data, SCH.type)
    var description: String? by SchAttr(data, SCH.description)
    var format: String? by SchAttr(data, SCH.format)

    /** Custom `allowCoerce` keyword. When unset, the parser defaults it (true for
     *  numeric types, false otherwise). */
    var allowCoerce: Boolean? by SchAttr(data, SCH.allowCoerce)

    /**
     * Makes this schema a `$ref` to another type. A bare [name] resolves within
     * this builder's [namespace]; a dotted name (e.g. "core.Count") is used as-is.
     */
    fun ref(name: String) {
        data[SCH.dRef] = typeRefPath(name, namespace)
    }

    /**
     * Adds a choice to the custom `options` construct: a [value] (the stored data)
     * and an optional display [label], which defaults to the value when redundant.
     */
    fun option(value: String, label: String = value) {
        optionsList().add(linkedMapOf(SCH.label to label, SCH.value to value))
    }

    @KdrPrivate
    fun optionsList(): MutableList<Any?> =
        data.getOrPut(SCH.options) { ArrayList<Any?>() }!!.toT()

    /**
     * Adds a property (field) subschema. A [description] is MANDATORY for fields
     * (unlike a type's description, which is optional). The field's type defaults
     * to `string` unless [build] sets a `type` or makes it a `$ref`. When
     * [required] is true the property name is also added to this type's `required`
     * array (required is tracked on the side).
     */
    fun property(
        name: String,
        description: String,
        required: Boolean = false,
        build: SchTypeBuilder.() -> Unit = {},
    ) {
        val sub = SchTypeBuilder(cxt, namespace)
        sub.description = description
        sub.apply(build)
        // Default field type to string unless the build set a type or a $ref.
        if (SCH.type !in sub.data && SCH.dRef !in sub.data) {
            sub.type = SCT.string
        }
        propertiesMap()[name] = sub.data
        if (required) this.required(name)
    }

    /**
     * Adds a previously declared, reusable [property] (see [schemaProperty]). The
     * property's schema is deep-cloned, so the original is untouched, then [mutate]
     * can adjust the clone for this use (e.g., refine its description or
     * constraints). When [required] is true its name is added to `required`.
     */
    fun property(property: SchBuilderProperty, required: Boolean = false, mutate: SchTypeBuilder.() -> Unit = {}) {
        val sub = SchTypeBuilder(cxt, namespace, property.data.deepClone())
        sub.apply(mutate)
        propertiesMap()[property.name] = sub.data
        if (required) this.required(property.name)
    }

    // Conceptually private helper; left open per the code guide and marked rather
    // than hidden.
    @KdrPrivate
    fun propertiesMap(): MutableMap<String, Any?> =
        data.getOrPut(SCH.properties) { LinkedHashMap<String, Any?>() }!!.toT()

    /** Records one or more property names in this type's `required` array. */
    fun required(vararg names: String) {
        val req: MutableList<String> = data.getOrPut(SCH.required) { ArrayList<String>() }!!.toT()
        for (n in names) if (n !in req) req.add(n)
    }

    /** Defines the element schema for an array type (`items`). */
    fun items(build: SchTypeBuilder.() -> Unit) {
        data[SCH.items] = SchTypeBuilder(cxt, namespace).apply(build).data
    }
}
