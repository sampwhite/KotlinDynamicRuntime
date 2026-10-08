package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.toOptDoubleResult

/**
 * What a keyword's value may be, as **data** (issue #1056): the one statement of a keyword's shape, which the check
 * of a value ([accepts]), the walk of a schema body ([SchMetaSchema.structureFailures]) and the published
 * meta-schema ([SchMetaSchema.defs]) are each read from. Before it a shape was a predicate, which a value could be
 * tested against and nothing else could be made from.
 *
 * A form says a value's own shape and **where schema nodes sit inside it** ([Node], [NodeMap], [Nodes]), and no
 * more: what a keyword means on the type it sits on, and anything the parser checks more closely than a shape, stay
 * the parser's.
 */
@KdrPrivate
sealed class SchShapeForm {
    /** True or false. */
    object Flag : SchShapeForm()

    /** Text. */
    object Text : SchShapeForm()

    /** A number, or text that spells one -- how a bound has always been read. */
    object NumberLike : SchShapeForm()

    /** An object whose content is the keyword's own business (`g-errors`, `g-layout`), not a schema. */
    object Obj : SchShapeForm()

    /** A schema node: an object this layer reads as a schema (`items`). */
    object Node : SchShapeForm()

    /** An object of name to schema node (`properties`); a null entry is a name not set. */
    object NodeMap : SchShapeForm()

    /** A list of schema nodes (`oneOf`). */
    object Nodes : SchShapeForm()

    /** One of [values], as text. */
    class Choice(val values: List<String>) : SchShapeForm()

    /** A list, each entry of form [of]; any entries at all when [of] is null. */
    class ListOf(val of: SchShapeForm?) : SchShapeForm()

    /** A value of either form -- which this dialect has no keyword to say of a schema it describes. */
    class Either(val first: SchShapeForm, val second: SchShapeForm) : SchShapeForm()
}

/**
 * Whether [value] is of this form. **Its own shape only**: a schema node is accepted for being an object, and what
 * is inside it is judged where that node is judged -- by the parser as it descends, or by the walk.
 */
@KdrPrivate
fun SchShapeForm.accepts(value: Any?): Boolean = when (this) {
    SchShapeForm.Flag -> value is Boolean
    SchShapeForm.Text -> value is String
    SchShapeForm.NumberLike -> value is Number || value is String && value.isNotBlank() && value.toOptDoubleResult() is Parsed.Ok
    SchShapeForm.Obj, SchShapeForm.Node -> value is Map<*, *>
    SchShapeForm.NodeMap -> value is Map<*, *> && value.values.all { it == null || it is Map<*, *> }
    SchShapeForm.Nodes -> value is List<*> && value.all { it is Map<*, *> }
    is SchShapeForm.Choice -> value is String && value in values
    is SchShapeForm.ListOf -> value is List<*> && (of == null || value.all { of.accepts(it) })
    is SchShapeForm.Either -> first.accepts(value) || second.accepts(value)
}

/**
 * The schema nodes inside [value], a value of this form, each with its place below the keyword: `""` for the
 * value itself, `.name` for a named one, `[2]` for a listed one. Empty for a form that holds none, and for a value
 * that is not of the form -- there is nothing in a wrongly shaped value to descend into.
 */
@KdrPrivate
fun SchShapeForm.nodesIn(value: Any?): List<Pair<String, Map<*, *>>> = when (this) {
    SchShapeForm.Node -> listOfNotNull((value as? Map<*, *>)?.let { "" to it })
    SchShapeForm.NodeMap -> (value as? Map<*, *>).orEmpty().mapNotNull { (name, node) -> (node as? Map<*, *>)?.let { ".$name" to it } }
    SchShapeForm.Nodes -> (value as? List<*>).orEmpty().mapIndexedNotNull { i, node -> (node as? Map<*, *>)?.let { "[$i]" to it } }
    is SchShapeForm.Either -> first.nodesIn(value) + second.nodesIn(value)
    else -> emptyList()
}

/**
 * The shape a keyword's value must take: how it is [described] in a refusal ("true or false"), and its [form].
 * What the two keyword tables are made of -- [SchGKeywords] for our own keywords, [SchStdKeywords] for the standard
 * ones this layer reads -- so the two say a shape the same way and anything reading both tables reads one kind of
 * entry.
 */
@KdrPrivate
class SchKeywordShape(val described: String, val form: SchShapeForm) {
    /** Whether [value] is of this shape. */
    fun accepts(value: Any?): Boolean = form.accepts(value)
}

/** The shapes more than one keyword takes, in either table. */
@KdrPrivate
object SchKeywordShapes {
    val boolean = SchKeywordShape("true or false", SchShapeForm.Flag)
    val text = SchKeywordShape("text", SchShapeForm.Text)
    val textList = SchKeywordShape("a list of text", SchShapeForm.ListOf(SchShapeForm.Text))
    val list = SchKeywordShape("a list", SchShapeForm.ListOf(null))
    val obj = SchKeywordShape("an object", SchShapeForm.Obj)
}

/** How a keyword's wrongly shaped value is named in a refusal: text quoted, a container by its kind, the rest as it prints. */
@KdrPrivate
fun describeSchemaValue(value: Any?): String = when (value) {
    is String -> "'$value'"
    is Map<*, *> -> "an object"
    is List<*> -> "a list"
    else -> value.toString()
}
