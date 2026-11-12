@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.page

import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.schema.SchType

/**
 * The **page widgets reference** (issue #1792): how a page document places a widget with a fence, the keys every fence
 * has, what the checks refuse, and every widget kind with its parameters, who may place it, and where it draws. Made
 * from [PageWidgets.core] and each kind's compiled parameter type, so a kind cannot be added, or a parameter changed,
 * without appearing here.
 *
 * One Markdown literal, as the other generated references are: `$${…}` substitutes, and a single `${…}` is the page's
 * own text.
 */
fun pageWidgetsMarkdown(): String =
    // language=Markdown
    $$"""# Page widgets

A page can be written as copy: Markdown, with **widget fences** placing what Markdown cannot draw, such as a list of
cards or a form to create a client. The order of the page is the order of its document, so a client or a deployment
rearranges a page by overlaying its copy. This page describes how a fence is written, what the checks refuse, and every
kind of widget a fence may name. The list of kinds is made from the kinds themselves.

## Writing a fence

A fence is a code block whose info string is `$${PGW.infoString}`, opened in the first column of a line at the top level
of the copy (not inside a list or a quote). Its body is one JSON object:

````markdown
```$${PGW.infoString}
{
  "$${PGW.id}": "cards",
  "$${PGW.widget}": "$${PageWidgets.cardsBlock.name}",
  "$${PGW.block}": "$${HCARD.block}",
  "$${PGW.copy}": "home.cardsIntro",
  "$${PGW.jumpLabel}": "What you can do"
}
```
````

A fence opened with four backticks may hold code written with three. A fence inside another code block is that block's
text, so copy can show a fence as an example, as this page does, without drawing it.

| Key | What it means |
|---|---|
| `$${PGW.id}` | The fence's name, unique in its page: a letter or underscore, then letters, digits, and underscores. The fence is drawn with the element id `$${PGW.elementIdPrefix}<id>`, which a link can name. |
| `$${PGW.widget}` | The kind of widget to draw, one of [the kinds](#the-kinds) below. Left out, the fence draws only its `$${PGW.copy}`, which is how a page shows copy under a condition. |
| `$${PGW.copy}` | Copy drawn before the widget, as `namespace.key` of the page's own fragment file. It is drawn as any other copy is, so it may pull other keys and fill placeholders. |
| `$${PGW.cfactExpression}` | Whether the fence is drawn: a test of [facts](facts.md), judged in the browser, so it may name only the facts sent to it. Left out, the fence is always drawn. |
| `$${PGW.jumpLabel}` | Puts a link to the fence, with these words, among the page's jump links. |

Every other key is a parameter of the widget's kind, held to the kind's own list below.

Long copy belongs in its own key, named by `$${PGW.copy}`, rather than inside the JSON: copy is changed one key at a time,
so words kept in their own key can be reworded without restating the page.

## What the checks refuse

A fence in copy a component ships is checked when the server starts, and one in a client's copy when the copy is
written. A fence is refused when:

- it is never closed;
- its body is not a JSON object;
- it has no `$${PGW.id}`, an id that is not a name, or the id of an earlier fence in the same copy;
- it names a kind there is none of, or one that only copy a component ships may place;
- it gives a parameter the kind does not take, or one the kind's schema refuses;
- it names neither a `$${PGW.widget}` nor a `$${PGW.copy}`, so it would draw nothing;
- its `$${PGW.copy}` is not a `namespace.key` the page's fragment file has;
- its `$${PGW.cfactExpression}` does not parse, or names a fact the browser is never sent, which would never hold;
- one of the keys above holds something other than text.

Where copy is drawn, a fence with a problem is left out and the rest of the page is drawn.

## Surfaces

A kind says where it can be drawn. A surface meeting a fence whose kind it cannot draw leaves the fence out.

| Surface | What it is |
|---|---|
$${PageSurface.entries.joinToString("\n") { "| `${it.name}` | ${it.description} |" }}

## The kinds

| Kind | What it draws | Who may place it | Drawn on |
|---|---|---|---|
$${kindsTable()}

$${PageWidgets.coreKinds.joinToString("\n\n") { kindSection(it) }}
"""

/** A row per core kind: its name, what it draws, whose copy may place it, and its surfaces. */
private fun kindsTable(): String = PageWidgets.coreKinds.joinToString("\n") { kind ->
    "| [`${kind.name}`](#${kind.name.lowercase()}) | ${kind.description} | " +
        "${kind.placement.words.replaceFirstChar { it.uppercase() }} | ${kind.surfaces.joinToString(", ") { "`${it.name}`" }} |"
}

/** One kind's section: its heading, and its parameters from its compiled type. */
private fun kindSection(kind: PageWidgetKind): String {
    val type = PageWidgets.core.paramsType(kind)
    val params = if (type.properties.isEmpty()) {
        "It takes no parameters."
    } else {
        "| Parameter | Type | Required | What it means |\n|---|---|---|---|\n" + paramRows(type)
    }
    return "### ${kind.name}\n\n${kind.description}\n\n$params"
}

/** A row per parameter of [type]: its name, its JSON type, whether it is required, and its description. */
private fun paramRows(type: SchType): String = type.properties.values.joinToString("\n") { prop ->
    val jsonType = prop.valueType?.jsonType ?: "string"
    "| `${prop.name}` | $jsonType | ${if (prop.name in type.required) "Yes" else "No"} | ${prop.description.orEmpty()} |"
}
