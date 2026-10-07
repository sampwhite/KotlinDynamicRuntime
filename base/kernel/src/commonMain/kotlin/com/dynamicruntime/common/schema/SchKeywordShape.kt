package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate

/**
 * The shape a keyword's value must take: how it is [described] in a refusal ("true or false"), and whether a value
 * [accepts] it. What the two keyword tables are made of -- [SchGKeywords] for our own keywords, [SchStdKeywords] for
 * the standard ones this layer reads -- so the two say a shape the same way and anything reading both tables reads
 * one kind of entry.
 */
@KdrPrivate
class SchKeywordShape(val described: String, val accepts: (Any?) -> Boolean)

/** The shapes more than one keyword takes, in either table. */
@KdrPrivate
object SchKeywordShapes {
    val boolean = SchKeywordShape("true or false") { it is Boolean }
    val text = SchKeywordShape("text") { it is String }
    val textList = SchKeywordShape("a list of text") { it is List<*> && it.all { e -> e is String } }
    val list = SchKeywordShape("a list") { it is List<*> }
    val obj = SchKeywordShape("an object") { it is Map<*, *> }
}

/** How a keyword's wrongly shaped value is named in a refusal: text quoted, a container by its kind, the rest as it prints. */
@KdrPrivate
fun describeSchemaValue(value: Any?): String = when (value) {
    is String -> "'$value'"
    is Map<*, *> -> "an object"
    is List<*> -> "a list"
    else -> value.toString()
}
