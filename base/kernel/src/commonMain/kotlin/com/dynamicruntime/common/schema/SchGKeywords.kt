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
    private val boolean = SchKeywordShapes.boolean
    private val text = SchKeywordShapes.text
    private val textList = SchKeywordShapes.textList
    private val list = SchKeywordShapes.list
    private val obj = SchKeywordShapes.obj

    private val shapes: Map<String, SchKeywordShape> = linkedMapOf(
        SCH.allowCoerce to boolean,
        SCH.emptyIsAbsent to boolean,
        SCH.visibleOnly to boolean,
        SCH.outerWhitespace to text,
        SCH.derived to SchKeywordShape("true, false or an object", SchShapeForm.Either(SchShapeForm.Flag, SchShapeForm.Obj)),
        SCH.schemaDocument to boolean,
        SCH.options to list,
        SCH.openOptions to boolean,
        SCH.primaryKey to textList,
        SCH.optionalContents to boolean,
        SCH.optionsSource to text,
        SCH.visibleWhen to text,
        SCH.errors to obj,
        SCH.presentation to SchKeywordShape("one of ${PRES.all.sorted()}", SchShapeForm.Choice(PRES.all.sorted())),
        SCH.layout to obj,
        // The two directives below are judged before any shape ([problem] returns for them first); they are listed
        // so the unknown-keyword message names them among ours when one is misspelled.
        SCH.merge to obj,
        SCH.extends to text,
        SCH.appliesTo to textList,
    )

    /** Every `g-` keyword there is. */
    val keywords: Set<String> get() = shapes.keys

    /** Every `g-` keyword with its shape, in the order declared -- what the meta-schema is generated from (issue #1056). */
    val entries: Map<String, SchKeywordShape> get() = shapes

    /**
     * The directives: keywords read where a configuration's types are assembled -- a merge, an extension -- and
     * nowhere a type is judged, so each is refused there ([problem]) whatever its value.
     */
    val directives: Set<String> = setOf(SCH.merge, SCH.extends)

    /**
     * What is wrong with [keyword] set to [value] on [where] (a type or property, for the message), or null when
     * nothing is: a `g-` key that is not one of ours ([SchemaError.unknownKeyword]), or a value not of its
     * keyword's shape ([SchemaError.badValue]). Null for any key without the prefix -- not ours to judge.
     *
     * [directivesStand] is for the top of a body as a configuration stores it, where a directive is read by the
     * assembly: there one is held to its shape like any keyword, and its placement is not this check's to judge.
     */
    fun problem(where: String, keyword: String, value: Any?, directivesStand: Boolean = false): Problem? {
        if (!keyword.startsWith(SCH.gPrefix) || value == null) return null
        // A merge directive is consumed by the merge of a client's alteration of a global type (issue #985), so in a
        // type that is being judged -- a global one, a client's own, a nested part, or anything the parser sees -- it
        // would do nothing. The repair of a client's alterations lets it stand at the one place it applies.
        if (keyword == SCH.merge && !directivesStand) return misplacedMerge(where)
        // An extension is resolved before a type is judged (issue #990), so one met here is somewhere it cannot apply.
        if (keyword == SCH.extends && !directivesStand) return misplacedExtends(where)
        val shape = shapes[keyword]
            ?: return Problem(
                SchemaError.unknownKeyword,
                "$where carries '$keyword', which is not one of our schema keywords " +
                    "(${keywords.sorted().joinToString(", ")}) -- check its spelling.",
            )
        if (shape.accepts(value)) return null
        return Problem(
            SchemaError.badValue, "$where sets '$keyword' to ${describeSchemaValue(value)}; it must be ${shape.described}.",
        )
    }

    /**
     * [SCH.extends] at [where], where nothing resolves it: below the top of a named type, or -- when [onAlteration] --
     * at the top of an alteration, which keeps the name it alters and so cannot be another type plus a delta.
     */
    fun misplacedExtends(where: String, onAlteration: Boolean = false): Problem = Problem(
        SchemaError.badExtends,
        "$where carries '${SCH.extends}', which belongs at the top of a named type a configuration declares as new, " +
            "naming the type it extends. " + if (onAlteration) {
                "An alteration keeps the name it alters, so it cannot extend; declare a new type."
            } else {
                "To extend a type here, declare the extension as a named type of its own and refer to it."
            },
    )

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
}

