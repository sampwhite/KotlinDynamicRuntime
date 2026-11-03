@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.util

import com.dynamicruntime.common.schema.SCH

/**
 * The **expressions reference** (issue #1560): the `${…}` language a client's copy, columns, layouts, computed values,
 * and validation messages are written in, for whoever writes one -- a person or an AI configuring a client. A Markdown
 * document served on the Documents page to anyone, signed in or not, and readable by the assistant's `read_doc`.
 *
 * The syntax is prose, stated from the grammar in `ScriptExpr.kt` and the value rules in `ScriptEval.kt`; the function
 * table is made from [scriptFunctions] itself -- each one's description, arguments, and an example a test runs -- so a
 * function cannot be added without appearing here, nor its example drift from what it does. Here beside the table, in
 * the kernel, since the table is the kernel's own; `base:common` only registers the document.
 *
 * One Markdown literal, as the other generated references are: `$${…}` substitutes, and a single `${…}` is the page's
 * own text. It sits at the margin because `trimIndent` runs after substitution, where a table's lines would defeat it.
 */
fun expressionsMarkdown(): String =
    // language=Markdown
    $$"""# Expressions

Much of what a client configures can hold an expression: a line of copy, a column in the forms list, a form's labels,
a value computed from a form's other fields, and a validation message. An expression is written between `${` and `}`,
in a small language that the server and the browser run alike, so a form shows what the server will store or send.
This page describes the language and lists its functions. The list of functions is made from the functions themselves.

Text outside `${` and `}` is kept as written, so `Hello, ${name}.` joins the words and the value.

A test of yes-or-no facts, such as a field's `$${SCH.visibleWhen}` or a workflow's eligibility test, is not an
expression. It has a syntax of its own, described in [Facts](facts.md).

## Where you write them

What an expression can read depends on where it is written.

| Where | What it can read |
|---|---|
| Copy, in a fragment file | Its other lines, as `${namespace.key}`, and what the page or mail supplies: `${user.email}`. |
| A column's `display`, in the forms list | The fields of the column's trait: `${name}`. |
| A column's `presentation` | The same fields, and the column's value as `${value}`. |
| A layout's `label` and `description` | The fields of the data being entered. |
| A layout's `hint` | The field's bounds, as `${min}` and `${max}`. |
| A validation message | Its failure's parameters, listed in [Validation messages](validation-messages.md). |
| A computed value's `script` | The object's own fields. It is a bare expression, with no `${` and `}`: `perItemAmount * itemCount`. |

## Values

| Write | For |
|---|---|
| `"Kotlin"` or `'Kotlin'` | Text. A backslash escapes the quote: `"say \"hi\""`. |
| `42`, `2.5` | A number. |
| `true`, `false` | Yes or no. |
| `null` | No value. |
| `user.name` | A value in the data, by its path: each dot goes into an object. |

A value is true when it is yes, a number other than zero, text that is not empty, or a list or object with entries in
it. No value, zero, empty text, and an empty list or object are false.

## Operators

From the loosest to the tightest binding, so `a ~ b + c` adds before it joins:

| Operator | Means |
|---|---|
| `a ? b : c` | `b` when `a` is true, else `c`. Only the branch taken is worked out. |
| `a ?: b` | `a`, or `b` when `a` is missing or null. |
| `\|\|` | Or, on truth. |
| `&&` | And, on truth. |
| `==`, `!=` | Equal or not, within one kind: numbers, text, or yes and no. Comparing with `null` asks whether a value is there. |
| `<`, `>`, `<=`, `>=` | Compare numbers. Text is not put in order. |
| `~` | Joins text: `count ~ " items"`. Each side is written as text. |
| `+`, `-` | Add and subtract. |
| `*`, `/`, `%` | Multiply, divide, and the remainder. Whole numbers give a whole number: `7 / 2` is 3. Dividing by zero is an error. |
| `!a`, `-a` | Not, and the negative. |
| `( )` | Grouping. |

The arithmetic operators and the comparisons read text that is a number as that number, so `"3" + 1` is 4. Nothing
else does: `"1" == 1` compares text with a number, which is an error, not a no.

## A missing value

A path whose value is missing or null is an error, not an empty space, so a misspelled name is noticed rather than
quietly showing nothing. Three places accept a missing value, because there you have said what it means:

- the left side of `?:`: `${user.name ?: "there"}`;
- the condition of `? :`, where a missing value is false: `${note ? note : "No note"}`;
- a comparison with `null`: `${note == null ? "No note" : note}`.

## Copy from another line

Writing `@t("namespace.key")` brings another line of copy into an expression, worked out in turn, so shared wording is
written once.

## On the server: `%{…}`

An expression written `%{…}` instead of `${…}` is the same language, worked out on the server before the page sees
anything. It is accepted in a layout's `label`, `description`, and `hint`, in a workflow's, task's, and save's labels,
and in an eligibility test's explanation.

- **It reads no data.** The server works it out with nothing but the fragment files, so it is for pulling copy:
  `%{@t("fileId.namespace.key")}`, naming the file, the namespace, and the key. In a layout, a two-part key reads the
  file the layout's `fragmentFileId` names. Anything about the person or the form stays `${…}`, for the page.
- **It is worked out for the client's own copy.** A client that rewords the line in a copy overlay sees its own wording
  everywhere the line is pulled, which is why shared wording is pulled rather than written in each place.
- **A mistake in a layout is caught early.** A layout's pull that names a key no file has is caught when the server
  starts, where a mistake in a `${…}` shows only on the page.
- **What it pulls can hold `${…}`,** which the page then fills in. Pull it only where the page supplies what it reads.

See [Copy from a file](../demo-client/reference.md#copy-from-a-file) for a layout pulling its heading.

## Functions

A function checks the kind of each argument, so `upper(42)` is an error rather than "42". An argument shown with `?`
may be left out.

$${functionsTable()}
"""

/** Every built-in function: how it is called, what it gives, and an example with what it renders to. */
private fun functionsTable(): String {
    val rows = scriptFunctions.values.map { fn ->
        val call = fn.args.withIndex().joinToString(", ", "${fn.name}(", ")") { (i, arg) -> if (i < fn.minArgs) arg else "$arg?" }
        "| `$call` | ${fn.description} | `${fn.example}` | `${fn.exampleResult}` |"
    }
    return (listOf("| Function | What it gives | Example | Result |", "|---|---|---|---|") + rows).joinToString("\n")
}
