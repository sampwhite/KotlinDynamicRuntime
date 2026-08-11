package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxtBase

/**
 * Builds a set of JSON Schema types (and, as a convenience, reusable properties)
 * for a single [namespace], so the namespace is named just once. The types are
 * keyed by their fully qualified "namespace.Name" — i.e., the contents of a
 * `$defs` object, which is where all kd2 types implicitly live and what `$ref`
 * paths resolve into.
 */
open class SchTypesBuilder(val cxt: KdrCxtBase, val namespace: String) {
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
     * Declares one **branch** of a discriminated union: an ordinary object type that additionally states which
     * branch it is, by giving [discriminator] a `const` of [value] (issue #252).
     *
     * The `const` is the load-bearing part, not a convenience: it is what lets any validator select this
     * branch, so the union is correct with the `discriminator` keyword stripped out. Declaring it through here
     * rather than by hand is what stops a branch reaching [variantType]'s boot check without one.
     *
     * The discriminator property is declared required, since a branch nobody can select is not a branch.
     */
    fun variantBranch(
        name: String,
        discriminator: String,
        value: String,
        description: String? = null,
        build: SchTypeBuilder.() -> Unit = {},
    ) {
        type(name) {
            type = SCT.kObject
            description?.let { this.description = it }
            property(discriminator, "Which kind of entry this is.", required = true) {
                type = SCT.string
                const = value
            }
            build()
        }
    }

    /**
     * Declares the **default** branch of a discriminated union: where a discriminator value naming no branch
     * goes (issue #252).
     *
     * Deliberately *not* [variantBranch]. A catch-all is the one branch that must not declare a `const`,
     * because it has to accept a value it has never heard of — give it one and it rejects exactly the payload
     * it exists to keep readable, with a message ("'somethingElse' is not 'opaque'") that reads as nonsense to
     * whoever thought they had a fallback. Its own helper rather than a flag on the other one, so the
     * distinction is structural instead of remembered.
     *
     * The discriminator is still declared, and still required: the value has to survive, and a union's
     * branches are closed objects that would otherwise reject it as undeclared.
     */
    fun variantDefault(
        name: String,
        discriminator: String,
        description: String? = null,
        build: SchTypeBuilder.() -> Unit = {},
    ) {
        type(name) {
            type = SCT.kObject
            description?.let { this.description = it }
            // Open, and it has to be. A catch-all exists to carry a shape this reader cannot describe, so
            // declaring its fields is precisely what it cannot do -- and a branch that is closed instead
            // rejects every unrecognized entry that carries anything, which is all of them. Closed here would
            // also silently empty an entry on the way through, which is worse than refusing it: nothing says
            // it happened.
            additionalProperties = true
            property(discriminator, "Which kind of entry this is; unrecognized by this reader.", required = true) {
                type = SCT.string
            }
            build()
        }
    }

    /**
     * Declares a discriminated union over [branches] — types normally declared with [variantBranch] — selected
     * by the [on] property (issue #252).
     *
     * `oneOf`, not `anyOf`: exactly one branch matches, which is what the discriminator makes true, and it is
     * also the only form the widely-deployed implementations support. No `mapping` is emitted — it would
     * duplicate what each branch's `const` already says, and a derived value written into the document is one
     * that can drift; it is synthesized at the export boundary instead.
     *
     * [defaultBranch] names where a value matching no branch goes. Without one, an unrecognized value is a
     * failure; with one it passes through, which is what a reader that knows only some of the branches needs.
     */
    fun variantType(
        name: String,
        description: String,
        on: String,
        branches: List<String>,
        defaultBranch: String? = null,
    ) {
        val discriminator = linkedMapOf<String, Any?>(SCH.propertyName to on)
        defaultBranch?.let { discriminator[SCH.defaultMapping] = typeRefPath(it, namespace) }
        defs[qualifyTypeName(name, namespace)] = linkedMapOf(
            SCH.description to description,
            // Every branch is an object, so the union is one -- said explicitly because a validator that
            // ignores `discriminator` still reads this, and because it costs nothing to be true.
            SCH.type to SCT.kObject,
            SCH.oneOf to branches.map { linkedMapOf(SCH.dRef to typeRefPath(it, namespace)) },
            SCH.discriminator to discriminator,
        )
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
fun schemaDefs(cxt: KdrCxtBase, namespace: String, build: SchTypesBuilder.() -> Unit): Map<String, Any?> =
    SchTypesBuilder(cxt, namespace).apply(build).defs
