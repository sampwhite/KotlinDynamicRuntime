package com.dynamicruntime.common.schema

import com.dynamicruntime.common.endpoint.EP

/**
 * The **validation messages reference** (issue #1559): every code a refused value is reported with, what it means,
 * which keywords raise it, and what a message written for it may say -- and where such a message is written and which
 * one a reader sees. A Markdown document served on the Documents page to anyone, signed in or not, and readable by the
 * assistant's `read_doc`.
 *
 * Made from the validator's own data -- each [SchFailCode]'s meaning and keywords, and [errorContextNames], the
 * parameters the boot check holds a message to -- so it says what the validator does and cannot drift from it.
 * Depends on nothing but code, as a generated document must: its build id is a hash of this text.
 *
 * The page is one Markdown literal, so it reads as it will render (IntelliJ treats it as Markdown), with each table made
 * by a function and every name the code owns substituted, so the compiler holds the page to the code. A multi-dollar
 * string: `$${…}` substitutes, and a single `${…}` is the page's own text -- the parameters a message author writes.
 * It sits at the margin because `trimIndent` runs after substitution, where a table's lines would defeat it.
 */
fun validationMessagesMarkdown(): String =
    // language=Markdown
    $$"""# Validation messages

When the runtime refuses a value, it says why, with a code and a message. This page lists every code, what each means
and which keywords raise it, and how you can give a code a message of your own, on a schema or on one form. It is made
from the validator's own list of codes, so it says exactly what the validator reports.

## What a refusal says

A request whose input is refused answers with a 400, and its error lists every failure under `$${EP.failures}`, one per
refused value. A form shows the same failures beside its fields. Each failure holds these:

$${refusalFieldsTable()}

## The codes

The bounds share two codes across types, since a field has one type: on text a lower bound can only be its length, and
on a list only how many items it has. A parameter in parentheses is offered only when the field declares that bound.

$${codesTable()}

## The parameters

A message names a parameter as `${name}`, and it is replaced by the failure's own value when the message is shown. A
message that names a parameter its code does not offer is caught when the schema is loaded, so a mistyped name never
reaches a reader: the platform's own schemas refuse to load, and a client's message is left out, with an issue on the
client saying why.

$${parametersTable()}

## Writing a message of your own

A message can be given in two places, keyed the same way: by code, or by `$${SCH.errorDefault}` for every code the list
does not name.

- **On the schema**, under a field's `$${SCH.errors}`. It applies wherever the field is checked: a form shows it, and a
  refused request carries it as `$${EP.failureUserMessage}`.
- **On one form**, under `$${SL.errors}` on the field's entry in that form's `$${SCH.layout}`. It applies only on that
  form, and a request never carries it.

```json
{
  "type": "string",
  "minLength": 50,
  "$${SCH.errors}": {
    "$${SchFailCode.missingRequired.name}": "Every talk needs a summary.",
    "$${SchFailCode.belowMinimum.name}": "A summary is at least ${$${LayoutCtx.min}} characters."
  }
}
```

**Which message a reader sees.** On a form, the form's own message for the code, then its `$${SCH.errorDefault}`; then
the schema's message for the code, then its `$${SCH.errorDefault}`; then the platform's. A request's refusal always
carries the platform's message in `$${EP.failureMessage}`, and the schema's in `$${EP.failureUserMessage}` when there is
one, so a program can show either.

**What a message may hold.** Plain text, and the parameters above. Neither kind may pull its wording from a fragment
file, as a layout's labels can, since a message is filled in wherever the failure is shown. A message whose parameter
has no value this time, such as `${$${LayoutErrCtx.value}}` for a failure with nothing to show, is shown as written
rather than blank.
"""

/** What each failure in a refusal holds. */
private fun refusalFieldsTable(): String = table(
    "| Field | What it holds |",
    listOf(
        EP.failurePath to "Where the value is, as a dotted path: `entries[0].data.summary`.",
        EP.failureCode to "Why it was refused: one of the codes below. A program branches on this.",
        EP.failureMessage to "The platform's own words for the failure, written for whoever is reading the data.",
        EP.failureUserMessage to "The schema's own words for the failure, from its `${SCH.errors}`, when it gives some. A form " +
            "shows these in place of the platform's.",
        EP.failureOptions to "For a choice refused, the choices there are, each a value and its label.",
        EP.failureFormRequirement to "Present and true when the data is valid but the form asks for more: a field its layout " +
            "requires, or a choice it does not offer.",
    ).map { (field, what) -> "| `$field` | $what |" },
)

/** Every code, with what it means, what raises it, and the parameters a message for it may use; then `default`. */
private fun codesTable(): String = table(
    "| Code | What it means | Raised by | A message may use |",
    SchFailCode.entries.map { code ->
        "| `${code.name}` | ${code.meaning} | ${code.raisedBy.joinToString(", ") { "`$it`" }} | ${parametersOf(code)} |"
    } + ("| `${SCH.errorDefault}` | Not a code: the message for any code a message list does not name. | Any of the above | " +
        "${parametersOf(null)} |"),
)

/** Each parameter a message may name, and what it is. */
private fun parametersTable(): String =
    table("| Parameter | What it is |", parameterMeanings.map { (name, what) -> "| `\${$name}` | $what |" })

/** A Markdown table: its [header] row, the rule under it, then its [rows]. */
private fun table(header: String, rows: List<String>): String {
    val rule = "|" + "---|".repeat(header.count { it == '|' } - 1)
    return (listOf(header, rule) + rows).joinToString("\n")
}

/** The parameters a message for [code] may use, any bound in parentheses; [code] null is the `default` message's. */
private fun parametersOf(code: SchFailCode?): String {
    val always = errorContextNames(code, hasMin = false, hasMax = false)
    val bounds = errorContextNames(code, hasMin = true, hasMax = true) - always
    return (always.map { "`\${$it}`" } + bounds.map { "(`\${$it}`)" }).joinToString(", ")
}

/** Each parameter a message may name, and what it is, in the order the codes offer them. */
private val parameterMeanings = listOf(
    LayoutErrCtx.field to "The field's name, as the data has it. Every message may use it, a `${SCH.errorDefault}` one included.",
    LayoutErrCtx.value to "The value refused, as it was given. Absent for a field that is missing, or one the type does not declare.",
    LayoutErrCtx.options to "The field's choices, by their labels, joined into one line.",
    LayoutCtx.min to "The field's lower bound: its least number, length, or count.",
    LayoutCtx.max to "The field's upper bound: its greatest number, length, or count.",
)
