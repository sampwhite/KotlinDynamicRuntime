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
        SCH.onWrite to SchKeywordShape("a list of function objects", SchShapeForm.ListOf(SchShapeForm.Obj)),
        SCH.schemaDocument to boolean,
        SCH.options to list,
        SCH.openOptions to boolean,
        SCH.primaryKey to textList,
        SCH.optionalContents to boolean,
        SCH.optionsSource to text,
        SCH.visibleWhen to text,
        SCH.sensitive to boolean,
        SCH.errors to obj,
        SCH.presentation to SchKeywordShape("one of ${PRES.all.sorted()}", SchShapeForm.Choice(PRES.all.sorted())),
        SCH.layout to obj,
        // The two directives below are judged before any shape ([problem] returns for them first); they are listed
        // so the unknown-keyword message names them among ours when one is misspelled.
        SCH.merge to obj,
        SCH.extends to text,
        SCH.appliesTo to textList,
    )

    /**
     * What each `g-` keyword means, in a sentence (issue #1347): beside its shape in the schema for schema and on the
     * schema dialect reference. A test holds this to the same keys as the shapes, so a keyword added without a
     * meaning fails rather than publishing a blank.
     */
    val meanings: Map<String, String> = linkedMapOf(
        SCH.allowCoerce to "Whether a value may be converted to the property's type when it is validated, such as " +
            "the text '5' to a number.",
        SCH.emptyIsAbsent to "Whether an empty or null value means the property was not supplied at all.",
        SCH.visibleOnly to "Whether a string may hold only characters with a visible rendering, and the ordinary space.",
        SCH.outerWhitespace to "What to do with leading and trailing whitespace on a string: 'trim' it, 'reject' a " +
            "value that has any, or 'keep' it.",
        SCH.derived to "Marks a property the caller does not supply, because something else produces it.",
        SCH.onWrite to "The functions run on the object's data each time it is written, such as one computing a " +
            "derived property from the others. Each names its kind in 'fn'. Always a list, even of one.",
        SCH.schemaDocument to "Marks a property whose value is itself a schema, checked by parsing it.",
        SCH.options to "The choices a property offers, each a label and a value.",
        SCH.openOptions to "Whether the options are suggestions rather than a bound, so another value is allowed.",
        SCH.primaryKey to "The fields, in order, that identify one element of an array of this type.",
        SCH.optionalContents to "Marks an object property whose value may be a fragment: each field is checked, " +
            "but not that the required ones are present.",
        SCH.optionsSource to "Names a registered source that supplies the property's options when the schema is served.",
        SCH.visibleWhen to "A cfact expression deciding whether the property is shown to a caller, and whether they " +
            "may change it.",
        SCH.sensitive to "Marks a property whose value is a secret, such as a password or a code, so it is never logged.",
        SCH.errors to "Messages to show for a failure of this field, keyed by the failure.",
        SCH.presentation to "How a read-only page should display the value: as a status, a table, an identifier, or " +
            "a nested table beneath its row (detail). On a type, or on a field beside its reference. It does not " +
            "affect validation.",
        SCH.layout to "How a form lays out the type's fields. Served apart from the schema, never read as part of it.",
        SCH.merge to "On a client's alteration of a global type, how a part of it merges with the type it alters.",
        SCH.extends to "On a named type a configuration declares, the type it extends: that type plus this one's body.",
        SCH.appliesTo to "The kinds of form a trait's entry type applies to.",
    )

    /**
     * Where each `g-` keyword is written (issue #1347), for the schema dialect reference, which lists them by place. The
     * parser is what holds a keyword to its place; this says it for a reader, and a test holds it to the same keys as
     * the shapes.
     */
    val places: Map<String, SchKeywordPlace> = linkedMapOf(
        SCH.allowCoerce to SchKeywordPlace.schema,
        SCH.emptyIsAbsent to SchKeywordPlace.schema,
        SCH.visibleOnly to SchKeywordPlace.schema,
        SCH.outerWhitespace to SchKeywordPlace.schema,
        SCH.derived to SchKeywordPlace.schema,
        SCH.onWrite to SchKeywordPlace.namedType,
        SCH.schemaDocument to SchKeywordPlace.schema,
        SCH.options to SchKeywordPlace.schema,
        SCH.openOptions to SchKeywordPlace.schema,
        SCH.primaryKey to SchKeywordPlace.schema,
        // Read on the field before its `$ref` is followed, so it is this use's, not the shared type's.
        SCH.optionalContents to SchKeywordPlace.field,
        SCH.optionsSource to SchKeywordPlace.field,
        SCH.visibleWhen to SchKeywordPlace.field,
        SCH.sensitive to SchKeywordPlace.field,
        SCH.errors to SchKeywordPlace.schema,
        // Read on a type and on a field beside its `$ref`; listed with the schema keywords, its meaning saying both.
        SCH.presentation to SchKeywordPlace.schema,
        SCH.layout to SchKeywordPlace.namedType,
        SCH.merge to SchKeywordPlace.namedType,
        SCH.extends to SchKeywordPlace.namedType,
        SCH.appliesTo to SchKeywordPlace.namedType,
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

