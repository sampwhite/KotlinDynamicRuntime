package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt

/**
 * Builds a set of JSON Schema types (and, as a convenience, reusable properties)
 * for a single [namespace], so the namespace is named just once. The types are
 * keyed by their fully qualified "namespace.Name" — i.e., the contents of a
 * `$defs` object, which is where all kd2 types implicitly live and what `$ref`
 * paths resolve into.
 */
open class SchTypesBuilder(val cxt: KdrCxt, val namespace: String) {
    /** Types keyed by fully qualified name (the `$defs` contents). */
    val defs: MutableMap<String, Any?> = LinkedHashMap()

    /**
     * Declares a type. A bare [name] is qualified with this builder's namespace
     * (so `type("Count")` in namespace "core" is keyed as "core.Count"); a dotted
     * name is used as-is.
     */
    fun type(name: String, build: SchTypeBuilder.() -> Unit) {
        defs[qualifyTypeName(name, namespace)] = SchTypeBuilder(cxt, namespace).apply(build).data
    }

    /**
     * Convenience: declares a reusable [SchBuilderProperty] in this scope's namespace,
     * so the namespace is named once for both types and properties. Equivalent to
     * the standalone [schemaProperty]. Override the namespace per call with
     * [namespace] (e.g., for a property whose bare `$ref`s resolve elsewhere).
     */
    fun property(
        name: String,
        description: String,
        namespace: String = this.namespace,
        build: SchTypeBuilder.() -> Unit = {},
    ): SchBuilderProperty = schemaProperty(cxt, namespace, name, description, build)
}

/**
 * Builds the `$defs` contents (types keyed by qualified name) for [namespace] via
 * a Kotlin DSL. Wrap with `mapOf(SCH.dDefs to result)` for a standalone schema
 * document; `result.values` gives the bare array of type schemas.
 */
fun schemaDefs(cxt: KdrCxt, namespace: String, build: SchTypesBuilder.() -> Unit): Map<String, Any?> =
    SchTypesBuilder(cxt, namespace).apply(build).defs
