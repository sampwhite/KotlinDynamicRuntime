package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toJsonListOrEmpty

/**
 * The **schema dialect reference** (issue #1347): the schema for schema ([SchMetaSchema], issue #1056) written for a
 * person, as a Markdown document served on the Documents page to anyone, signed in or not.
 *
 * Made from the same keyword tables the parser reads -- [SchStdKeywords] and [SchGKeywords], each keyword's shape and
 * meaning -- and from the rules and limits [SchMetaSchema] lists beside its document, so it says what the parser
 * does and cannot drift from it. Depends on nothing but code, as a generated document must: its build id is a hash
 * of this text.
 *
 * The page is one Markdown literal, as `validationMessagesMarkdown` is, so it reads as it will render, with each table
 * and list made by a function and every name the code owns substituted (`$${…}` in a multi-dollar string).
 */
fun schemaDialectMarkdown(): String =
    // language=Markdown
    $$"""# The schema dialect

The runtime describes data with JSON Schema, and reads a dialect of it: most of the standard keywords, some refused,
and keywords of its own that begin with `$${SCH.gPrefix}`. This page lists them all. It is made from the tables the
runtime's schema parser reads, so it says exactly what the parser accepts. The same content is served as JSON, as a
schema for schemas, at `GET $${MSCH.path}`.

A schema here is an open record. The keywords below are read and held to the shape each lists. Any other key is the
document's own and passes unread, except a key beginning `$${SCH.gPrefix}` that is not one of ours, which is refused as
a misspelling.

$${keywordSection(
        "## Standard keywords the runtime reads",
        "These keep their JSON Schema meaning, within the limits each one states.",
        SchStdKeywords.entries, SchStdKeywords.meanings,
    )}

## The runtime's own keywords

A key beginning `$${SCH.gPrefix}` belongs to the runtime, and the list of them is closed. A stock JSON Schema validator
ignores them, as it ignores any keyword it does not know. They are listed here by where each one is written. The codes
a refused value is reported with, and how `$${SCH.errors}` gives one a message of its own, are in
[Validation messages](validation-messages.md).

$${gKeywordSections()}

## Standard keywords that are refused

These are valid JSON Schema, and a schema that uses one is refused, with what to write instead.

$${refusedTable()}

## Rules the shapes do not state

The parser applies these, and the JSON form cannot express them, so it lists them beside itself. The closed list of
`$${SCH.gPrefix}` keywords and the refused keywords are covered above.

$${unstatedRules()}

## What a shape does not decide

A schema whose every keyword has the right shape is not thereby valid. The parser, and for a client's configuration the
trial load, check these as well:

$${SchMetaSchema.notChecked.joinToString("\n") { "- $it" }}
"""

/** The places a `g-` keyword is written, in the order the reference lists them, each with its heading and lead. */
private val gKeywordPlaces = listOf(
    Triple(
        SchKeywordPlace.schema, "On a schema",
        "Wherever a schema is written: a type declared by name, a field's own schema, or an array's item schema.",
    ),
    Triple(
        SchKeywordPlace.field, "On a field",
        "On a field in `${SCH.properties}`, about that field's use of its type. These may stand beside a " +
            "`${SCH.dRef}`, where other keywords may not.",
    ),
    Triple(
        SchKeywordPlace.namedType, "At the top of a named type",
        "On a type declared by name in `${SCH.dDefs}`, and refused anywhere else.",
    ),
)

/** The `g-` keywords, a section for each place they are written. */
private fun gKeywordSections(): String = gKeywordPlaces.joinToString("\n\n") { (place, heading, lead) ->
    keywordSection("### $heading", lead, SchGKeywords.entries.filterKeys { SchGKeywords.places[it] == place }, SchGKeywords.meanings)
}

/** One section listing [shapes] under [heading]: each keyword with its value's shape and its meaning from [meanings]. */
private fun keywordSection(heading: String, lead: String, shapes: Map<String, SchKeywordShape>, meanings: Map<String, String>): String {
    val rows = shapes.map { (keyword, shape) ->
        "| `$keyword` | ${shape.described.replaceFirstChar { it.uppercase() }} | ${meanings[keyword].orEmpty()} |"
    }
    return (listOf(heading, "", lead, "", "| Keyword | Value | Meaning |", "|---|---|---|") + rows).joinToString("\n")
}

/** The standard keywords refused, each with what to write instead. */
private fun refusedTable(): String =
    (listOf("| Keyword | Instead |", "|---|---|") + refusedKeywords.map { (keyword, advice) -> "| `$keyword` | $advice |" })
        .joinToString("\n")

/** The rules the JSON form cannot state, as it lists them, worded here for a person: its own detail is for a tool. */
private fun unstatedRules(): String = SchMetaSchema.notStated.flatMap { rule ->
    val keywords = rule[MSCH.keywords].toJsonListOrEmpty().map { it.toString() }
    val named = keywords.joinToString(" and ") { "`$it`" }
    when (rule[MSCH.rule]) {
        MSCH.eitherOf -> keywords.map { keyword ->
            val shape = (SchStdKeywords.entries + SchGKeywords.entries).getValue(keyword).described
            "- **`$keyword` takes more than one kind of value:** $shape. The JSON form has no way to say \"this or that\", " +
                "so it leaves the value untyped and the parser checks it."
        }
        MSCH.unsetEntry -> listOf(
            "- **An entry of $named may be null**, meaning the name is not set. That is how a client's change to a global " +
                "type removes one. The JSON form says every entry is a schema, so it would refuse the null.",
        )
        MSCH.placement -> listOf(
            "- **$named are read only at the top of a type a configuration declares**, and refused anywhere else, whatever " +
                "their value.",
        )
        else -> emptyList()
    }
}.joinToString("\n")
