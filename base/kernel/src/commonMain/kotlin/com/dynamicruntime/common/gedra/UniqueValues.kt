package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SchType

/**
 * The names a unique field is made of (issue #1851): the scopes `g-unique` takes, and the fields of the endpoint that
 * says whether a value is taken. In the kernel so a form asks by the names the backend reads. Each name matches its
 * value.
 */
@Suppress("ConstPropertyName")
object UNQ {
    /** The scope a value is unique within: the forms of one owner. The only one for now. */
    const val owner = "owner"

    /** Every scope `g-unique` takes, in the order a refusal lists them. */
    val scopes: List<String> = listOf(owner)

    // --- asking whether a value is taken ---

    /** The trait whose field is asked about. */
    const val traitId = "traitId"

    /** The field of the trait's data. */
    const val field = "field"

    /** The value, as it was typed. */
    const val value = "value"

    /** The answer: whether another of the owner's forms holds the value. */
    const val taken = "taken"

    /** The answer, when taken: the value as the other form holds it. */
    const val heldAs = "heldAs"

    /** The answer, when taken: that it is, in words for the person who typed it. */
    const val message = "message"
}

/**
 * [value] as a unique field compares it (issue #1851), so `Q3 Budget` and `q3-budget ` are one value: lowercase, each run
 * of characters that are not letters or digits one space, and trimmed. Letters and digits are Unicode's, so `Café` keeps
 * its é. Empty for a value with neither (`!!!`), which claims nothing.
 */
fun normalizeUnique(value: String): String {
    val out = StringBuilder()
    for (c in value.lowercase()) {
        if (c.isLetterOrDigit()) out.append(c) else if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
    }
    return out.toString().trim()
}

/**
 * The fields of [type] whose value must be unique, by field name to scope (issue #1851): its own properties declaring
 * `g-unique`. Only a type's own fields: one named in a nested object or an array's items is not held to it.
 */
fun uniqueFieldsOf(type: SchType): Map<String, String> =
    type.properties.mapNotNull { (name, prop) -> prop.valueType.unique?.let { name to it } }.toMap()
