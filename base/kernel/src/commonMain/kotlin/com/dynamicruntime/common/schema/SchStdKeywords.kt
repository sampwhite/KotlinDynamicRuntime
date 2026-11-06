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
@Suppress("ConstPropertyName")
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
     * What each keyword in the table means here, in a sentence (issue #1347): beside its shape in the schema for
     * schema and on the schema dialect reference. Every keyword the table holds has one, as does every keyword read
     * more closely ([closeReads], issue #1606), and a test holds the keys to those two tables and to every keyword the
     * parser reads off a node, so a keyword added without a meaning fails rather than publishing a blank.
     */
    val meanings: Map<String, String> = linkedMapOf(
        SCH.type to "The type of the value. One type only: a list of types is not supported.",
        SCH.format to "A named format a string must take, such as 'date-time' or 'email'.",
        SCH.title to "A short name for the type or property, for a reader.",
        SCH.description to "What the type or property is, for a reader.",
        SCH.dRef to "A reference to a named type, which this schema then is.",
        SCH.required to "The properties an object must have.",
        SCH.properties to "An object's properties, each a schema by name.",
        SCH.items to "The schema every item of an array takes.",
        SCH.additionalProperties to "On a record, whether properties it does not declare are allowed; as a schema, " +
            "the object is a map whose every value takes that schema.",
        SCH.oneOf to "Branches of which the value takes exactly one, told apart by a '${SCH.discriminator}', which it " +
            "must have.",
        SCH.minimum to "The smallest number allowed, inclusive.",
        SCH.maximum to "The largest number allowed, inclusive.",
        SCH.minLength to "The fewest characters a string may have.",
        SCH.maxLength to "The most characters a string may have.",
        SCH.minItems to "The fewest items an array may have.",
        SCH.maxItems to "The most items an array may have.",
        SCH.minProperties to "The fewest properties an object may have.",
        SCH.maxProperties to "The most properties an object may have.",
        // The keywords read more closely than a shape ([closeReads], issue #1606).
        SCH.pattern to "A regular expression a plain string must match. The match is unanchored, as JSON Schema has " +
            "it, so '[0-9]' accepts any value holding a digit; write '^...$' to match the whole value. Only the part " +
            "of ECMA-262's syntax that the server and the browser read alike is accepted: a construct they read " +
            "differently, such as an inline flag, is refused, and there are no flags. Refused on any type but a " +
            "plain string, and on a date or binary format.",
        SCH.const to "The one value allowed. Most often on a union branch's discriminator property, saying which " +
            "branch it is. Refused on a date or binary format, which is never compared against it.",
        SCH.default to "The value a required property takes when it is missing, so a missing one passes. A null is " +
            "no default.",
        SCH.uniqueItems to "On an array, whether no two items may be equal, compared as JSON values: 1 equals 1.0, " +
            "and two objects are equal whatever the order of their keys.",
        SCH.exclusiveMinimum to "On a number, the bound a value must be above, not equal to. The bound itself, as " +
            "JSON Schema 2020-12 has it: draft 4's true or false beside '${SCH.minimum}' is refused. Beside " +
            "'${SCH.minimum}', the stricter of the two applies. Read on a number or an integer only.",
        SCH.exclusiveMaximum to "On a number, the bound a value must be below, not equal to. The bound itself, as " +
            "JSON Schema 2020-12 has it: draft 4's true or false beside '${SCH.maximum}' is refused. Beside " +
            "'${SCH.maximum}', the stricter of the two applies. Read on a number or an integer only.",
        SCH.kIf to "Makes '${SCH.kThen}' or '${SCH.kElse}' apply by one property's value: " +
            "'{\"${SCH.properties}\": {\"kind\": {\"${SCH.const}\": \"talk\"}}}'. Only that shape is read, one " +
            "property compared with a '${SCH.const}'; anything more general is refused rather than half-applied.",
        SCH.kThen to "What applies when the '${SCH.kIf}' holds: '${SCH.required}' names properties that must be " +
            "present, and '${SCH.not}': {'${SCH.required}': [...]} names properties that must be absent. Nothing " +
            "else may be in it. '${SCH.kThen}' and '${SCH.kElse}' are the only places '${SCH.not}' is read.",
        SCH.kElse to "What applies when the '${SCH.kIf}' does not hold, written as '${SCH.kThen}' is.",
        SCH.discriminator to "Beside a '${SCH.oneOf}', which must have one: '${SCH.propertyName}' names the " +
            "property whose value says which branch a value takes, and every branch declares that property with a " +
            "'${SCH.const}' of its own. '${SCH.defaultMapping}', a '${SCH.dRef}' to a branch, takes a value naming " +
            "no branch; without it, such a value is a failure.",
    )

    /**
     * The standard keywords the parser reads **more closely than a shape** (issue #1606), each with the value it
     * takes, in words. What each accepts turns on the type it sits on or the keywords beside it -- a `pattern` on an
     * integer, an `if` that tests two properties, a `oneOf` with no `discriminator` -- so the parser judges them,
     * with its own wording, and this table does not. Kept beside [entries] so the schema dialect reference lists
     * them, and the schema for schema names them (`MSCH.closeRead`), rather than leaving a reader of the shape
     * table to conclude they are not supported. Each has a meaning in [meanings].
     */
    val closeReads: Map<String, String> = linkedMapOf(
        SCH.pattern to "text, a regular expression",
        SCH.const to "any value",
        SCH.default to "any value",
        SCH.uniqueItems to "true or false",
        SCH.exclusiveMinimum to "a number",
        SCH.exclusiveMaximum to "a number",
        SCH.kIf to "a schema object testing one property with a '${SCH.const}'",
        SCH.kThen to "an object holding '${SCH.required}', '${SCH.not}', or both",
        SCH.kElse to "an object holding '${SCH.required}', '${SCH.not}', or both",
        SCH.discriminator to "an object holding '${SCH.propertyName}', and '${SCH.defaultMapping}' if wanted",
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
    fun salvaged(keyword: String, value: Any?): Any? = when (keyword) {
        SCH.properties if value is Map<*, *> -> value.filterValues { it == null || it is Map<*, *> }
        SCH.oneOf if value is List<*> -> value.filterIsInstance<Map<*, *>>()
        SCH.required if value is List<*> -> value.mapNotNull { it.toOptStr() }
        else -> null
    }

    private fun message(where: String, keyword: String, value: Any?, shape: SchKeywordShape): String = when (keyword) {
        // The part at fault, named: "sets 'properties' to an object" would say nothing.
        SCH.properties if value is Map<*, *> -> {
            val (name, body) = value.entries.first { it.value != null && it.value !is Map<*, *> }
            "$where declares property '$name' as ${describeSchemaValue(body)}; a property's schema must be an object." +
                    (if (body is Boolean) " $booleanSchema" else "")
        }
        SCH.required if value is List<*> -> {
            val bad = value.filter { it !is String }.joinToString(", ") { describeSchemaValue(it) }
            "$where lists $bad in '${SCH.required}'; each entry must be a property's name, as text."
        }
        SCH.oneOf if value is List<*> -> {
            val bad = value.filter { it !is Map<*, *> }.joinToString(", ") { describeSchemaValue(it) }
            "$where lists $bad in '${SCH.oneOf}'; each branch must be a schema object." +
                    (if (value.any { it is Boolean }) " $booleanSchema" else "")
        }
        // The rest of these are legal JSON Schema, and not read here: said, rather than left to look like a typo.
        SCH.type if value is List<*> ->
            "$where sets '${SCH.type}' to a list; a list of types is not supported. Declare the one type it is: a " +
                    "property that may be absent is simply not required."
        SCH.items if value is List<*> ->
            "$where sets '${SCH.items}' to a list; a schema per position is not supported. Declare the one schema " +
                    "every item takes."
        SCH.items if value is Boolean ->
            "$where sets '${SCH.items}' to $value; it must be a schema object. $booleanSchema"
        else -> "$where sets '$keyword' to ${describeSchemaValue(value)}; it must be ${shape.described}."
    }

    /** What to write in place of JSON Schema's `true` / `false` schemas, which this layer does not read. */
    private const val booleanSchema =
        "True or false standing for a schema is valid JSON Schema and is not supported here: an empty object " +
            "accepts anything."
}
