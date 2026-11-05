@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toJsonStr

/**
 * The **form layouts reference** (issue #1609): what a type's `g-layout` block may hold, field by field, the modes and
 * the default-value options, the form-level strings and the wording each defaults to, the two template passes, how a
 * client's layout merges with the shared one, and what the load refuses. A Markdown document served on the Documents
 * page to anyone, signed in or not, and readable by the assistant's `read_doc`.
 *
 * Made from the vocabulary the layout parser reads -- [SL]'s keys and their meanings, [SLM], [SLDM], [LAYSTR], and
 * [LayoutError] -- so it says what the parser accepts and cannot drift from it. Depends on nothing but code, as a
 * generated document must: its build id is a hash of this text.
 *
 * The page is one Markdown literal, as `validationMessagesMarkdown` is: each table made by a function, every name the
 * code owns substituted with `$${…}`, and a single `${…}` the page's own text -- the templates an author writes.
 */
fun formLayoutsMarkdown(): String =
    // language=Markdown
    $$"""# Form layouts

A type's `$${SCH.layout}` says how a form shows the type: its name and introduction, each field's label, description,
and hint, which fields show and in what order, and the wording the form uses for the type as a whole. It changes how a
form presents data, never what data is valid. This page lists everything a layout may hold. It is made from the
vocabulary the runtime's layout parser reads, so it says exactly what the parser accepts.

```json
"$${SCH.layout}": $${exampleLayoutJson()}
```

## Where a layout is written

At the top of a type declared by name in `$defs`, a trait's data type included, and nowhere else: a layout on a
nested object is refused, since it has no type name to be found by. A type has at most one. The schema served to a
page or a program leaves the layout out, and the page is sent it beside the schema, by the type's name, so a schema
reads the same whatever its forms look like. A layout on a type a client alters, or on one of the client's own types,
is the client's; see [A client's change to a shared layout](#a-clients-change-to-a-shared-layout).

## The block

$${keyTable(SL.blockMeanings)}

## A field entry

Each entry of `$${SL.schemaFields}` is about one field. The keys besides `$${SL.field}` are each optional.

$${keyTable(SL.fieldMeanings)}

The codes a message under `$${SL.errors}` is keyed by, and the parameters each may use, are in
[Validation messages](validation-messages.md), which also says which message a reader sees when both the schema and the
form give one.

### A choice

Each entry of `$${SL.choices}` is one of the schema's choices, as this form offers it. The form offers only the choices
the schema currently allows, given what has been entered, so a choice the schema's conditions withdraw is not shown even
when the layout lists it.

$${keyTable(SL.choiceMeanings)}

## Which fields show

`$${SL.mode}` decides who chooses the fields a form shows and their order.

$${valueTable(SLM.meanings)}

Every mode narrows and orders; none adds a field. A field the schema keeps from a form -- one that is derived, one a
`g-visibleWhen` hides from the caller, one a condition forbids -- stays hidden even when the layout lists it.

## A value nobody entered

`$${SL.defaultMode}` says how the form presents a value it was given for a field that nobody entered, such as a
default from the caller's account.

$${valueTable(SLDM.meanings)}

## Form strings

The wording a form uses for the type as a whole. A layout's `$${SL.strings}` overrides any of these by name; a name not
listed here is refused.

$${stringsTable()}

## Templates

A layout's copy may hold two kinds of template, filled in at different times, and one string may hold both.

- **`%{…}`, on the server, as the layout is delivered.** It pulls shared wording from a backend fragment file:
  `%{@t("fileId.namespace.key")}`, or `%{@t("namespace.key")}` in a block whose `$${SL.fragmentFileId}` names the
  file. The page receives the finished wording, so a backend file is never sent to it. A pull naming a file or a key
  that does not exist is refused when the layout is loaded; one whose key is computed is checked as the layout is
  delivered, and shown as written if it does not resolve. Fragment files are described in [Copy](copy.md).
- **`${…}`, on the page, as the form is drawn.** In a `$${SL.label}` or a `$${SL.description}`, it reads the data being
  entered: `${topic}`. In a `$${SL.hint}`, it reads the field's declared bounds, `${$${LayoutCtx.min}}` and
  `${$${LayoutCtx.max}}`, as numbers, so a hint may compute with them. In a message under `$${SL.errors}`, it reads the
  failure's parameters. The language is described in [Expressions](expressions.md).

A pull is written with `%`, never `$`: a `${@t(…)}` in a layout is refused. A message under `$${SL.errors}` takes no
`%{…}` at all, since it is filled in wherever the failure is shown.

## A client's change to a shared layout

A client that alters a shared type may write a layout on its alteration, and it merges with the shared one. What the
alteration's own `$${SL.mode}` says decides how its `$${SL.schemaFields}` merge:

- **No mode, or `$${SLM.overlay}`: a change of copy.** Each entry replaces the shared entry for its field, whole, so the
  client states only the fields it rewords, and every other entry is the shared layout's. An entry for a field the
  shared layout does not list is added at the end where the layout is `$${SLM.overlay}`, and refused where it is
  `$${SLM.reorder}` or `$${SLM.authoritative}`, since it would need a place in the order.
- **`$${SLM.reorder}` or `$${SLM.authoritative}`: a change of order or of which fields show.** The client's list is the
  order, and with `$${SLM.authoritative}` the fields too. An entry that names only its `$${SL.field}` keeps the shared
  entry for that field.

`$${SL.strings}` merge by name. Every other key of the block replaces the shared one. `"$${SCH.layout}": null` drops the
shared layout. A client whose alteration drops fields from the type keeps the shared layout without the entries for
those fields, which is not a fault.

## What the load refuses

$${errorsTable()}

A fault in a layout the platform declares stops a test or development node from starting; a production node drops the
layout and says so. A fault in a client's own layout costs only that layout, outside a unit test: it is left out, so
the type falls back to the shared layout or none, and the client's configuration issues say why. A configuration written through the
endpoints is refused when it brings a fault the client did not already have.
"""

/** The example layout at the top of the page, as JSON; `SchLayoutReferenceTest` holds it to the parser. */
fun exampleLayout(): Map<String, Any?> = linkedMapOf(
    SL.label to "Site visit",
    SL.description to "Tell us what you saw, and how it went.",
    SL.mode to SLM.reorder,
    SL.schemaFields to listOf(
        linkedMapOf(SL.field to "topic", SL.label to "Topic", SL.hint to "Pick the closest."),
        linkedMapOf(
            SL.field to "rating",
            SL.hint to $$"From ${$${LayoutCtx.min}} to ${$${LayoutCtx.max}}.",
            SL.errors to mapOf(SchFailCode.aboveMaximum.name to $$"At most ${$${LayoutCtx.max}}."),
        ),
        linkedMapOf(SL.field to "notes", SL.lines to 6),
    ),
    SL.strings to mapOf(LAYSTR.formErrorHint to "Fix the marked fields, then save."),
)

private fun exampleLayoutJson(): String = exampleLayout().toJsonStr()

/** A table of keys and their meanings. */
private fun keyTable(meanings: Map<String, String>): String =
    table("| Key | What it holds |", meanings.map { (key, meaning) -> "| `$key` | $meaning |" })

/** A table of a key's values and what each does. */
private fun valueTable(meanings: Map<String, String>): String =
    table("| Value | What it does |", meanings.map { (value, meaning) -> "| `$value` | $meaning |" })

/** The form strings, what each is, and the wording each has when no layout overrides it. */
private fun stringsTable(): String = table(
    "| Name | What it is | Default wording |",
    LAYSTR.meanings.map { (name, meaning) ->
        val defaults = LAYSTR.defaults[name].orEmpty().joinToString("; ") { (where, text) -> "$where, \"$text\"" }
        "| `$name` | $meaning | $defaults |"
    },
)

/** Each code the load reports a layout fault with, and what it means. */
private fun errorsTable(): String =
    table("| Code | What is wrong |", LayoutError.entries.map { "| `${it.name}` | ${it.meaning} |" })

/** A Markdown table: its [header] row, the rule under it, then its [rows]. */
private fun table(header: String, rows: List<String>): String {
    val rule = "|" + "---|".repeat(header.count { it == '|' } - 1)
    return (listOf(header, rule) + rows).joinToString("\n")
}
