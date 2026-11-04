<!-- kdr-generated: begin -- copied from its generator by `./gradlew :base:common:refDocsDump`; edit the generator, not this -->
# Expressions

Much of what a client configures can hold an expression: a line of copy, a column in the forms list, a form's labels,
a value computed from a form's other fields, and a validation message. An expression is written between `${` and `}`,
in a small language that the server and the browser run alike, so a form shows what the server will store or send.
This page describes the language and lists its functions. The list of functions is made from the functions themselves.

Text outside `${` and `}` is kept as written, so `Hello, ${name}.` joins the words and the value.

A test of yes-or-no facts, such as a field's `g-visibleWhen` or a workflow's eligibility test, is not an
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

| Function | What it gives | Example | Result |
|---|---|---|---|
| `upper(text)` | The text in capital letters. | `upper("ada")` | `ADA` |
| `lower(text)` | The text in small letters. | `lower("ADA")` | `ada` |
| `trim(text)` | The text without the spaces at its ends. | `trim("  ada ")` | `ada` |
| `contains(text, part)` | Whether the text holds the part, exactly as written. | `contains("ada@acme.test", "@")` | `true` |
| `count(value)` | How many characters text has, or how many entries a list or an object has. | `count("talk")` | `4` |
| `abs(number)` | The number without its sign. | `abs(-3)` | `3` |
| `formatDate(date, style?)` | A date and time as text. With no style, the system's own form, in UTC. With a style: "relative" for how long ago or ahead it is (12m ago, in 3h, or the day from 48 hours on), "moment" for the date and time in the page's time zone, labeled, or "day" for the day there. | `formatDate("2026-10-17T21:48:00Z")` | `2026-10-17T21:48:00.000Z` |
| `formatDay(date)` | The day a date and time falls on in the page's time zone, written yyyy-MM-dd. | `formatDay("2026-10-17T21:48:00Z")` | `2026-10-17` |
| `formatNumber(number, decimals?)` | A number as a person reads it: grouped by thousands, and to that many decimals when a count is given. | `formatNumber(1234.5, 2)` | `1,234.50` |
| `round(number, decimals?)` | A number rounded, as a number: to a whole number, or to that many decimals when a count is given. | `round(2.567, 2)` | `2.57` |
| `currency(number, code)` | An amount of money, written for its three-letter ISO 4217 currency code. | `currency(1234.5, "USD")` | `$1,234.50` |
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes its list of functions from the functions
themselves, so it cannot disagree with them. Read it on the server's **Documents** page, under **Expressions**, where
anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/expressions](https://dynamicruntime.org/wa/docs/expressions). `./gradlew
:base:common:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit the
generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The functions, each with what it gives, its arguments, and an example | `scriptFunctions` in [`ScriptFunc.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptFunc.kt) |
| The grammar the page's syntax states | [`ScriptExpr.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptExpr.kt) |
| The value rules the page's operators state | [`ScriptEval.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptEval.kt) |
| The reference page, made from these | [`ScriptReference.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptReference.kt) |
