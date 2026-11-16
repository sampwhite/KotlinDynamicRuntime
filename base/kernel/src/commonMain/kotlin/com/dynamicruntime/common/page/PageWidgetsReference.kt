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
 *
 * Given [example], Home's document as shipped (issue #1794), the page ends with it as the worked example. The kernel
 * holds no copy, so whoever serves the page reads it from the `home` file and passes it.
 */
fun pageWidgetsMarkdown(example: String? = null): String =
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
| `$${PGW.copy}` | Copy drawn before the widget, as `namespace.key` of the page's own fragment file, unless the kind draws it itself in its own place, as a page's title does. It is drawn as any other copy is, so it may pull other keys and fill placeholders. |
| `$${PGW.cfactExpression}` | Whether the fence is drawn: a test of [facts](facts.md), judged in the browser, so it may name only the facts about the caller and the node that are sent to it. A fact about a form, a client's own included, never holds on a page. Left out, the fence is always drawn. |
| `$${PGW.jumpLabel}` | Puts a link to the fence, with these words, among the page's jump links. |

Every other key is a parameter of the widget's kind, held to the kind's own list below.

Long copy belongs in its own key, named by `$${PGW.copy}`, rather than inside the JSON: copy is changed one key at a time,
so words kept in their own key can be reworded without restating the page. The Markdown between fences may pull a key
too, with `${@t("namespace.key")}`, as any copy may.

A line that begins with `#` and a space ends a value in a fragment file, so a page's top-level heading is a
`$${PageWidgets.pageTitle.name}` fence rather than a Markdown line. A smaller heading (`##`) is written as Markdown.

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
- its `$${PGW.cfactExpression}` does not parse, or names a fact a page never sees, which would never hold;
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
$${example?.let { homeExample(it) }.orEmpty()}"""

/** The worked example: Home's document as shipped, [document], read from its copy by whoever generates the page. */
private fun homeExample(document: String): String =
    "\n## Home, a worked example\n\nThe app's home page is a page document: the `home` file's `page.home` key, as " +
        "shipped below. Each widget decides from its own data whether it draws anything, so who sees what needs no " +
        "conditions: the demos only for a visitor signed in to the public client where joining is offered, say. Its one " +
        "condition is on the walk-through's note, which only such a visitor is shown. A client or a deployment rewords the " +
        "title, the introduction, and the note by their own keys, and rearranges the page by overlaying this one.\n\n" +
        "````markdown\n${document.trim()}\n````\n"

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
    val copy = if (kind.drawsCopy) "\n\nIt draws its fence's `${PGW.copy}` itself, as described above, rather than before it." else ""
    return "### ${kind.name}\n\n${kind.description}$copy\n\n$params"
}

/** A row per parameter of [type]: its name, its JSON type, whether it is required, and its description. */
private fun paramRows(type: SchType): String = type.properties.values.joinToString("\n") { prop ->
    val jsonType = prop.valueType?.jsonType ?: "string"
    "| `${prop.name}` | $jsonType | ${if (prop.name in type.required) "Yes" else "No"} | ${prop.description.orEmpty()} |"
}
