package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt

/**
 * A reusable, standalone property (field) declaration: a [name] plus a built
 * schema [data] map (which always carries a `description`). Declare once with
 * [schemaProperty] and add it to any number of types via
 * `SchTypeBuilder.property(prop, ...)`, which deep-clones it for each use.
 */
class SchProperty(val name: String, val data: Map<String, Any?>)

/**
 * Declares a reusable property. A [description] is mandatory (as for inline
 * fields). The type defaults to `string` unless [build] sets a `type` or makes it
 * a `$ref`. [namespace] is used to resolve any bare `$ref` written in [build].
 */
fun schemaProperty(
    cxt: KdrCxt,
    namespace: String,
    name: String,
    description: String,
    build: SchTypeBuilder.() -> Unit = {},
): SchProperty {
    val sub = SchTypeBuilder(cxt, namespace)
    sub.description = description
    sub.apply(build)
    if (SCH.type !in sub.data && SCH.dRef !in sub.data) {
        sub.type = SCT.string
    }
    return SchProperty(name, sub.data)
}
