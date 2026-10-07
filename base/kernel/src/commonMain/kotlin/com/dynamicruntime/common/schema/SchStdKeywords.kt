package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.toOptDoubleResult

/**
 * The **standard** keywords this layer reads, and the shape each one's value must take (issue #1053).
 *
 * What [SchGKeywords] is for our own keywords, for JSON Schema's. The parser used to read these leniently, and a
 * value of the wrong shape was not refused but read as though the keyword were absent: `type: "strng"` was kept and
 * then constrained nothing, `required: "name"` required nothing, `properties: []` made an open object, and
 * `additionalProperties: "no"` fell to the default. A typo turned validation off, and nothing said so.
 *
 * **Only keywords we interpret.** A keyword this layer does not read stays the document's own -- a stock
 * validator's document must still parse -- so the list here is of what the parser consults, not of what JSON
 * Schema defines. And only a keyword's **shape**: whether it applies to the type it sits on, and what it means
 * there, are the parser's own checks (a `pattern` on an integer, a `oneOf` with no discriminator).
 *
 * One list, read two ways, as [SchGKeywords] is: the parser refuses a problem outright (`parseSchemaTypes`
 * throws, naming the keyword and the type or property), and the repair of a client's stored definitions drops the
 * keyword and reports it. The drop is chosen to **reproduce how the value was read before** -- which was as absent
 * -- so a stored definition means after the repair exactly what it meant, with the fault now on the client's issue
 * list. Where only part of a value is at fault, only that part goes ([salvaged]).
 *
 * A JSON `null` reads as the keyword not being set.
 */
object SchStdKeywords {
    private class Shape(val described: String, val accepts: (Any?) -> Boolean)

    private val types = listOf(SCT.string, SCT.number, SCT.integer, SCT.boolean, SCT.array, SCT.kObject, SCT.kNull)

    private val text = Shape("text") { it is String }
    private val boolean = Shape("true or false") { it is Boolean }
    private val obj = Shape("a schema object") { it is Map<*, *> }

    // A bound has always been read as a number or as text that spells one, so that is what it may be: refusing
    // `"5"` now would be a rule nobody was told about, and reading it as no bound would loosen a stored schema.
    private val number = Shape("a number") { it is Number || it is String && it.isNotBlank() && it.toOptDoubleResult() is Parsed.Ok }

    private val shapes: Map<String, Shape> = linkedMapOf(
        SCH.type to Shape("one of ${types.joinToString(", ")}") { it is String && it in types },
        SCH.format to text,
        SCH.title to text,
        SCH.description to text,
        SCH.dRef to text,
        SCH.required to Shape("a list of property names") { it is List<*> && it.all { e -> e is String } },
        // A null is a property not set, as a null keyword is: an alteration merged into a global type removes one
        // that way (issue #985), and the parser has always read past it.
        SCH.properties to Shape("an object of schema objects") { it is Map<*, *> && it.values.all { v -> v == null || v is Map<*, *> } },
        SCH.items to obj,
        SCH.additionalProperties to boolean,
        SCH.oneOf to Shape("a list") { it is List<*> },
        SCH.minimum to number,
        SCH.maximum to number,
        SCH.minLength to number,
        SCH.maxLength to number,
        SCH.minItems to number,
        SCH.maxItems to number,
        SCH.minProperties to number,
        SCH.maxProperties to number,
    )

    /** Every standard keyword whose shape is checked. */
    val keywords: Set<String> get() = shapes.keys

    /**
     * What is wrong with [keyword] set to [value] on [where] (a type or property, for the message), or null when
     * nothing is: a value not of its keyword's shape ([SchemaError.badValue]). Null for a keyword that is not one
     * of [keywords] -- not ours to judge.
     */
    fun problem(where: String, keyword: String, value: Any?): Problem? {
        if (value == null) return null
        val shape = shapes[keyword] ?: return null
        if (shape.accepts(value)) return null
        return Problem(SchemaError.badValue, message(where, keyword, value, shape))
    }

    /** Every problem among [map]'s own keys (not its children's), in key order. */
    fun problems(where: String, map: Map<String, Any?>): List<Problem> =
        map.entries.mapNotNull { (key, value) -> problem(where, key, value) }

    /**
     * What is left of a wrongly shaped [value] of [keyword] when only **part** of it is at fault, or null when the
     * whole keyword goes: a `properties` object keeps the properties whose schema is an object, and a `required`
     * list its names. Each is what the lenient reading kept of it, so the repair that stores this changes nothing
     * about what the definition means.
     */
    fun salvaged(keyword: String, value: Any?): Any? = when {
        keyword == SCH.properties && value is Map<*, *> -> value.filterValues { it == null || it is Map<*, *> }
        keyword == SCH.required && value is List<*> -> value.filterIsInstance<String>()
        else -> null
    }

    private fun message(where: String, keyword: String, value: Any?, shape: Shape): String = when {
        // The part at fault, named: "sets 'properties' to an object" would say nothing.
        keyword == SCH.properties && value is Map<*, *> -> {
            val (name, body) = value.entries.first { it.value != null && it.value !is Map<*, *> }
            "$where declares property '$name' as ${describeSchemaValue(body)}; a property's schema must be an object."
        }
        keyword == SCH.required && value is List<*> -> {
            val bad = value.filter { it !is String }.joinToString(", ") { describeSchemaValue(it) }
            "$where lists $bad in '${SCH.required}'; each entry must be a property's name, as text."
        }
        // Legal JSON Schema, and not read here: said, rather than left to look like a typo.
        keyword == SCH.type && value is List<*> ->
            "$where sets '${SCH.type}' to a list; a list of types is not supported. Declare the one type it is: a " +
                "property that may be absent is simply not required."
        else -> "$where sets '$keyword' to ${describeSchemaValue(value)}; it must be ${shape.described}."
    }
}
