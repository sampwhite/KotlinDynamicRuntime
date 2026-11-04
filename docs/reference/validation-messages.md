<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Validation messages

When the runtime refuses a value, it says why, with a code and a message. This page lists every code, what each means
and which keywords raise it, and how you can give a code a message of your own, on a schema or on one form. It is made
from the validator's own list of codes, so it says exactly what the validator reports.

## What a refusal says

A request whose input is refused answers with a 400, and its error lists every failure under `failures`, one per
refused value. A form shows the same failures beside its fields. Each failure holds these:

| Field | What it holds |
|---|---|
| `path` | Where the value is, as a dotted path: `entries[0].data.summary`. |
| `code` | Why it was refused: one of the codes below. A program branches on this. |
| `message` | The platform's own words for the failure, written for whoever is reading the data. |
| `userMessage` | The schema's own words for the failure, from its `g-errors`, when it gives some. A form shows these in place of the platform's. |
| `options` | For a choice refused, the choices there are, each a value and its label. |
| `formRequirement` | Present and true when the data is valid but the form asks for more: a field its layout requires, or a choice it does not offer. |

## The codes

The bounds share two codes across types, since a field has one type: on text a lower bound can only be its length, and
on a list only how many items it has. A parameter in parentheses is offered only when the field declares that bound.

| Code | What it means | Raised by | A message may use |
|---|---|---|---|
| `missingRequired` | A field the data requires, or this form asks for, was not supplied. | `required` | `${field}` |
| `wrongType` | The value is not of the field's type, such as text where a number belongs. | `type` | `${field}`, `${value}` |
| `badValue` | The value was read and found wrong: text that is not a date, a number, or a yes or no where one is expected, a character that does not show, or spaces at the ends of text that refuses them. | `type`, `format`, `g-visibleOnly`, `g-outerWhitespace`, `g-schemaDocument` | `${field}`, `${value}` |
| `invalidOption` | The value is not one of the field's choices. | `g-options` | `${field}`, `${value}`, `${options}` |
| `additionalProperty` | The data holds a field its type does not declare. | `additionalProperties` | `${field}` |
| `notAllowed` | The field is declared, but a rule about another field says it may not be supplied now. | `if` | `${field}`, `${value}` |
| `belowMinimum` | The value is under the field's lower bound: a number too small, text too short, or a list or object with too few entries. | `minimum`, `exclusiveMinimum`, `minLength`, `minItems`, `minProperties` | `${field}`, `${value}`, (`${min}`) |
| `aboveMaximum` | The value is over the field's upper bound: a number too large, text too long, or a list or object with too many entries. | `maximum`, `exclusiveMaximum`, `maxLength`, `maxItems`, `maxProperties` | `${field}`, `${value}`, (`${max}`) |
| `patternMismatch` | Text does not match the field's pattern. | `pattern` | `${field}`, `${value}` |
| `duplicateItem` | A list holds the same item twice, or two items with the same key. | `uniqueItems`, `g-primaryKey` | `${field}`, `${value}` |
| `default` | Not a code: the message for any code a message list does not name. | Any of the above | `${field}` |

## The parameters

A message names a parameter as `${name}`, an [expression](expressions.md), and it is replaced by the failure's own value
when the message is shown. A message that names a parameter its code does not offer is caught when the schema is loaded,
so a mistyped name never reaches a reader: the platform's own schemas refuse to load, and a client's message is left
out, with an issue on the client saying why.

| Parameter | What it is |
|---|---|
| `${field}` | The field's name, as the data has it. Every message may use it, a `default` one included. |
| `${value}` | The value refused, as it was given. Absent for a field that is missing, or one the type does not declare. |
| `${options}` | The field's choices, by their labels, joined into one line. |
| `${min}` | The field's lower bound: its least number, length, or count. |
| `${max}` | The field's upper bound: its greatest number, length, or count. |

## Writing a message of your own

A message can be given in two places, keyed the same way: by code, or by `default` for every code the list
does not name.

- **On the schema**, under a field's `g-errors`. It applies wherever the field is checked: a form shows it, and a
  refused request carries it as `userMessage`.
- **On one form**, under `errors` on the field's entry in that form's `g-layout`. It applies only on that
  form, and a request never carries it.

```json
{
  "type": "string",
  "minLength": 50,
  "g-errors": {
    "missingRequired": "Every talk needs a summary.",
    "belowMinimum": "A summary is at least ${min} characters."
  }
}
```

**Which message a reader sees.** On a form, the form's own message for the code, then its `default`; then
the schema's message for the code, then its `default`; then the platform's. A request's refusal always
carries the platform's message in `message`, and the schema's in `userMessage` when there is
one, so a program can show either.

**What a message may hold.** Plain text, and the parameters above. Neither kind may pull its wording from a fragment
file, as a layout's labels can, since a message is filled in wherever the failure is shown. A message whose parameter
has no value this time, such as `${value}` for a failure with nothing to show, is shown as written
rather than blank.
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes it from the validator's own list of codes, so it
cannot disagree with the validator. Read it on the server's **Documents** page, under **Validation messages**, where
anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/validation-messages](https://dynamicruntime.org/wa/docs/validation-messages). `./gradlew
:launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit the
generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The codes, each with what it means and the keywords that raise it | `SchFailCode` in [`SchValidator.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchValidator.kt) |
| The parameters a message for each code may use | `errorContextNames` in [`SchLayout.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchLayout.kt) |
| The reference page, made from these | [`SchMessageReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/schema/SchMessageReference.kt) |
