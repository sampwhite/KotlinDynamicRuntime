package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.toOptStr

/**
 * The **standard** keywords this layer reads, and the shape each one's value must take (issue #1053).
 *
 * What [SchGKeywords] is for our own keywords, for JSON Schema's. The parser used to read these leniently, and a
 * value of the wrong shape was mostly not refused but read as though the keyword were absent: `type: "strng"` was
 * kept and then constrained nothing, `required: "name"` required nothing, `properties: []` made an open object, and
 * `additionalProperties: "no"` fell to the default. A typo turned validation off, and nothing said so.
 *
 * **Only keywords we interpret.** A keyword this layer does not read stays the document's own -- a stock
 * validator's document must still parse -- so the list here is of what the parser consults, not of what JSON
 * Schema defines. And only a keyword's **shape**: whether it applies to the type it sits on, and what it means
 * there, are the parser's own checks (a `pattern` on an integer, a `oneOf` with no discriminator).
 *
 * **Some of what is refused is legal JSON Schema that this layer does not read**: a list of types, a tuple of
 * `items`, `true` or `false` standing for a schema. Those are said to be what they are -- valid, and not supported
 * here -- rather than worded as the typo the rest are.
 *
 * One list, read three ways: the parser refuses a problem outright (`parseSchemaTypes` throws, naming the
 * keyword and the type or property); the repair of a client's stored definitions drops the keyword -- or only the
 * part of it at fault ([salvaged]) -- and reports it, so the fault costs only itself; and the schema for schema
 * ([SchMetaSchema], issue #1056) is generated from it, which is why a shape here is data ([SchShapeForm]) and not
 * a predicate.
 *
 * **What a repair changes about a stored definition.** For most of these, nothing: the value was read as absent,
 * and absent is what dropping it leaves (`required` keeps exactly the names it was read as holding). Three were not
 * read as absent but made the whole type fail to compile, and so be dropped whole: a bound that is not a number, a
 * `$ref` that is a number or a flag, and a type that is no type beside `g-options`. Such a type now loads without
 * the keyword instead -- the smaller cost, by the rule the rest of the repair follows -- and the client's issue list
 * says what was dropped.
 *
 * A JSON `null` reads as the keyword not being set.
 */
object SchStdKeywords {
    private val types = listOf(SCT.string, SCT.number, SCT.integer, SCT.boolean, SCT.array, SCT.kObject, SCT.kNull)

    private val text = SchKeywordShapes.text

    // A bound has always been read as a number or as text that spells one, so that is what it may be: refusing
    // `"5"` now would be a rule nobody was told about, and reading it as no bound would loosen a stored schema.
    private val number = SchKeywordShape("a number", SchShapeForm.NumberLike)

    private val shapes: Map<String, SchKeywordShape> = linkedMapOf(
        SCH.type to SchKeywordShape("one of ${types.joinToString(", ")}", SchShapeForm.Choice(types)),
        SCH.format to text,
        SCH.title to text,
        SCH.description to text,
        SCH.dRef to text,
        SCH.required to SchKeywordShape("a list of property names", SchShapeForm.ListOf(SchShapeForm.Text)),
        // A null is a property not set, as a null keyword is: an alteration merged into a global type removes one
        // that way (issue #985), and the parser has always read past it.
        SCH.properties to SchKeywordShape("an object of schema objects", SchShapeForm.NodeMap),
        SCH.items to SchKeywordShape("a schema object", SchShapeForm.Node),
        // True or false for a record; a schema for a map, whose values it describes (issue #1055).
        SCH.additionalProperties to
            SchKeywordShape("true, false or a schema object", SchShapeForm.Either(SchShapeForm.Flag, SchShapeForm.Node)),
        // A branch that is not an object was skipped in silence, as a property whose schema was not one used to be
        // (issue #1056): a union quietly a branch short.
        SCH.oneOf to SchKeywordShape("a list of schema objects", SchShapeForm.Nodes),
        SCH.minimum to number,
        SCH.maximum to number,
        SCH.minLength to number,
        SCH.maxLength to number,
        SCH.minItems to number,
        SCH.maxItems to number,
        SCH.minProperties to number,
        SCH.maxProperties to number,
    )

    /**
     * What is wrong with [keyword] set to [value] on [where] (a type or property, for the message), or null when
     * nothing is: a value not of its keyword's shape ([SchemaError.badValue]). Null for a keyword this table does
     * not hold -- not ours to judge.
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

    /** Every standard keyword this layer holds to a shape, with it, in the order declared (issue #1056). */
    val entries: Map<String, SchKeywordShape> get() = shapes

    /**
     * The schema nodes [keyword]'s [value] holds, each with its place below the keyword ([SchShapeForm.nodesIn]):
     * where a walk of a schema body goes next. Empty for a keyword that holds none, or that this table does not.
     */
    fun nodesIn(keyword: String, value: Any?): List<Pair<String, Map<*, *>>> = shapes[keyword]?.form?.nodesIn(value).orEmpty()

    /** Whether [value] is **itself** a schema node under [keyword] (`items`, a map's value schema), not a holder of several. */
    fun isNode(keyword: String, value: Any?): Boolean = nodesIn(keyword, value).any { (below, _) -> below.isEmpty() }

    /**
     * How a fault inside the schema [keyword] holds is placed, for a message that names the type or property
     * holding it: "Type 'x' (in its item schema) sets ...". A fault there is not the holder's own (issue #1055), and
     * saying it of the holder points its author at the wrong line. One wording, read by the parser and by the
     * repair of a stored definition.
     */
    fun nodePlace(keyword: String): String = when (keyword) {
        SCH.items -> "in its item schema"
        SCH.additionalProperties -> "in its value schema"
        else -> "in its '$keyword' schema"
    }

    /**
     * What is left of a wrongly shaped [value] of [keyword] when only **part** of it is at fault, or null when the
     * whole keyword goes. Each is exactly what the lenient reading made of it: a `properties` object keeps the
     * properties whose schema is an object, a `oneOf` list the branches that are objects, and a `required` list the
     * names its entries spell -- a number or a flag was read as its text -- without the entries that spell none.
     */
    fun salvaged(keyword: String, value: Any?): Any? = when {
        keyword == SCH.properties && value is Map<*, *> -> value.filterValues { it == null || it is Map<*, *> }
        keyword == SCH.oneOf && value is List<*> -> value.filter { it is Map<*, *> }
        keyword == SCH.required && value is List<*> -> value.mapNotNull { it.toOptStr() }
        else -> null
    }

    private fun message(where: String, keyword: String, value: Any?, shape: SchKeywordShape): String = when {
        // The part at fault, named: "sets 'properties' to an object" would say nothing.
        keyword == SCH.properties && value is Map<*, *> -> {
            val (name, body) = value.entries.first { it.value != null && it.value !is Map<*, *> }
            "$where declares property '$name' as ${describeSchemaValue(body)}; a property's schema must be an object." +
                (if (body is Boolean) " $booleanSchema" else "")
        }
        keyword == SCH.required && value is List<*> -> {
            val bad = value.filter { it !is String }.joinToString(", ") { describeSchemaValue(it) }
            "$where lists $bad in '${SCH.required}'; each entry must be a property's name, as text."
        }
        keyword == SCH.oneOf && value is List<*> -> {
            val bad = value.filter { it !is Map<*, *> }.joinToString(", ") { describeSchemaValue(it) }
            "$where lists $bad in '${SCH.oneOf}'; each branch must be a schema object." +
                (if (value.any { it is Boolean }) " $booleanSchema" else "")
        }
        // The rest of these are legal JSON Schema, and not read here: said, rather than left to look like a typo.
        keyword == SCH.type && value is List<*> ->
            "$where sets '${SCH.type}' to a list; a list of types is not supported. Declare the one type it is: a " +
                "property that may be absent is simply not required."
        keyword == SCH.items && value is List<*> ->
            "$where sets '${SCH.items}' to a list; a schema per position is not supported. Declare the one schema " +
                "every item takes."
        keyword == SCH.items && value is Boolean ->
            "$where sets '${SCH.items}' to $value; it must be a schema object. $booleanSchema"
        else -> "$where sets '$keyword' to ${describeSchemaValue(value)}; it must be ${shape.described}."
    }

    /** What to write in place of JSON Schema's `true` / `false` schemas, which this layer does not read. */
    private const val booleanSchema =
        "True or false standing for a schema is valid JSON Schema and is not supported here: an empty object " +
            "accepts anything."
}
