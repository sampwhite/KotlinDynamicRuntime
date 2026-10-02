package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.Problem

/**
 * Every `g-` keyword, and the shape its value must take (issue #822).
 *
 * A `g-` keyword is **ours** ([SCH.gPrefix]), so the list of them is closed: a key carrying the prefix that is not
 * one of these is a mistake -- a misspelling, or a keyword from a newer version -- never a client's own
 * annotation, which keeps any other name. And each has a declared shape, so a value of the wrong type is refused
 * rather than read leniently: `"yes"` for a boolean used to fall back silently to the default, which is neither
 * strict about the type nor helpful about it. Definitions gain nothing from that leniency, and schema written at
 * runtime (client configuration, a `g-schemaDocument`) is exactly where a mistake should be caught.
 *
 * One list, read two ways: the parser refuses a problem outright (`parseSchemaTypes` throws, naming the keyword
 * and the type or property), and the repair of a client's stored definitions -- and of a component's in production
 * -- drops the one keyword and reports it, as it does its other keyword-level faults. A JSON `null` reads as the
 * keyword not being set. Keywords the parser checks more closely than their shape (`g-outerWhitespace`'s mode,
 * `g-errors`' keys, `g-layout`'s vocabulary) keep those checks; this is only the first gate.
 */
object SchGKeywords {
    private class Shape(val described: String, val accepts: (Any?) -> Boolean)

    private val boolean = Shape("true or false") { it is Boolean }
    private val text = Shape("text") { it is String }
    private val textList = Shape("a list of text") { it is List<*> && it.all { e -> e is String } }
    private val list = Shape("a list") { it is List<*> }
    private val obj = Shape("an object") { it is Map<*, *> }

    private val shapes: Map<String, Shape> = linkedMapOf(
        SCH.allowCoerce to boolean,
        SCH.emptyIsAbsent to boolean,
        SCH.visibleOnly to boolean,
        SCH.outerWhitespace to text,
        SCH.derived to Shape("true, false or an object") { it is Boolean || it is Map<*, *> },
        SCH.schemaDocument to boolean,
        SCH.options to list,
        SCH.openOptions to boolean,
        SCH.primaryKey to textList,
        SCH.optionalContents to boolean,
        SCH.optionsSource to text,
        SCH.visibleWhen to text,
        SCH.errors to obj,
        SCH.presentation to Shape("one of ${PRES.all.sorted()}") { it is String && it in PRES.all },
        SCH.layout to obj,
        SCH.merge to obj,
        SCH.appliesTo to textList,
    )

    /** Every `g-` keyword there is. */
    val keywords: Set<String> get() = shapes.keys

    /**
     * What is wrong with [keyword] set to [value] on [where] (a type or property, for the message), or null when
     * nothing is: a `g-` key that is not one of ours ([SchemaError.unknownKeyword]), or a value not of its
     * keyword's shape ([SchemaError.badValue]). Null for any key without the prefix -- not ours to judge.
     */
    fun problem(where: String, keyword: String, value: Any?): Problem? {
        if (!keyword.startsWith(SCH.gPrefix) || value == null) return null
        // A merge directive is consumed by the merge of a client's alteration of a global type (issue #985), so in a
        // type that is being judged -- a global one, a client's own, a nested part, or anything the parser sees -- it
        // would do nothing. The repair of a client's alterations lets it stand at the one place it applies.
        if (keyword == SCH.merge) return misplacedMerge(where)
        val shape = shapes[keyword]
            ?: return Problem(
                SchemaError.unknownKeyword,
                "$where carries '$keyword', which is not one of our schema keywords " +
                    "(${keywords.sorted().joinToString(", ")}) -- check its spelling.",
            )
        if (shape.accepts(value)) return null
        return Problem(
            SchemaError.badValue, "$where sets '$keyword' to ${describe(value)}; it must be ${shape.described}.",
        )
    }

    /** [SCH.merge] at [where], where no merge will apply it. */
    fun misplacedMerge(where: String): Problem = Problem(
        SchemaError.misplacedKeyword,
        "$where carries '${SCH.merge}', which says how a client's alteration of a global type merges with it; here it " +
            "would do nothing. A type a client declares in full -- a new one, or one its own configuration declares " +
            "elsewhere -- states its whole definition.",
    )

    /** Every problem among [map]'s own keys (not its children's), in key order. */
    fun problems(where: String, map: Map<String, Any?>): List<Problem> =
        map.entries.mapNotNull { (key, value) -> problem(where, key, value) }

    private fun describe(value: Any?): String = when (value) {
        is String -> "'$value'"
        is Map<*, *> -> "an object"
        is List<*> -> "a list"
        else -> value.toString()
    }
}
