<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Form layouts

A type's `g-layout` says how a form shows the type: its name and introduction, each field's label, description,
and hint, which fields show and in what order, and the wording the form uses for the type as a whole. It changes how a
form presents data, never what data is valid. This page lists everything a layout may hold. It is made from the
vocabulary the runtime's layout parser reads, so it says exactly what the parser accepts.

```json
"g-layout": {
  "label":"Site visit",
  "description":"Tell us what you saw, and how it went.",
  "mode":"reorder",
  "schemaFields":[{
      "field":"topic",
      "label":"Topic",
      "hint":"Pick the closest."
    },{
      "field":"rating",
      "hint":"From ${min} to ${max}.",
      "errors":{
        "aboveMaximum":"At most ${max}."
      }
    },{
      "field":"notes",
      "lines":6
    }],
  "strings":{
    "formErrorHint":"Fix the marked fields, then save."
  }
}
```

## Where a layout is written

At the top of a type declared by name in `$defs`, a trait's data type included, and nowhere else: a layout on a
nested object is refused, since it has no type name to be found by. A type has at most one. The schema served to a
page or a program leaves the layout out, and the page is sent it beside the schema, by the type's name, so a schema
reads the same whatever its forms look like. A layout on a type a client alters, or on one of the client's own types,
is the client's; see [A client's change to a shared layout](#a-clients-change-to-a-shared-layout).

## The block

| Key | What it holds |
|---|---|
| `schemaFields` | The fields the layout addresses, in order: one entry each, described below. Optional when the block names the type or introduces it; a list that is present may not be empty. |
| `label` | The type's name, on one line: the heading over the type as a whole on a form, and, for a trait's data type, the trait's name wherever one is shown to a person. Inline Markdown. Unset, the type's schema `title` names it. |
| `description` | The introduction under the heading, in Markdown: on a workflow form, shown above the type's fields. |
| `fragmentFileId` | The fragment file a two-part backend pull, `%{@t("namespace.key")}`, resolves against. |
| `mode` | Who decides which fields the form shows, and in what order: one of the modes below. Unset is `overlay`. |
| `strings` | The form-level wording the layout overrides, by name: an object whose keys are the form strings below. |
| `$comment` | A note for whoever maintains the layout. Kept with the schema it sits in, never delivered to a page. |

## A field entry

Each entry of `schemaFields` is about one field. The keys besides `field` are each optional.

| Key | What it holds |
|---|---|
| `field` | The property of the type this entry is about. Required, and one the type declares. |
| `label` | The field's label on the form, in place of the schema's `title`. |
| `description` | The field's description on the form, in place of the schema's `description`. |
| `hint` | The hint under the field, in place of the one the form derives from its bounds (`range: 1 to 10`). May use `${min}` and `${max}`, its declared bounds. |
| `errors` | The form's message for a failure against the field, keyed by its code (and `default` for any other), in place of the built-in wording and the schema's own `g-errors`. |
| `defaultMode` | How the form presents a value supplied for the field that nobody entered, such as a default from the caller's account: one of the default-value options below. Unset is `filled`. |
| `required` | `true` when this form asks for the field, though the schema may not require it. Checked when a workflow saves, never by a general edit of the data; only adds, so `false` is refused. |
| `choices` | The choices this form offers, restated: which of the schema's choices, in what order, with the form's own copy for each. Each entry is described below. |
| `lines` | How many lines a long text field's box starts with, from 1 to 40 (3 when unset). Only on a field the schema marks long text (`g-longText`). |
| `$comment` | A note for whoever maintains the layout, never delivered to a page. |

The codes a message under `errors` is keyed by, and the parameters each may use, are in
[Validation messages](validation-messages.md), which also says which message a reader sees when both the schema and the
form give one.

### A choice

Each entry of `choices` is one of the schema's choices, as this form offers it. The form offers only the choices
the schema currently allows, given what has been entered, so a choice the schema's conditions withdraw is not shown even
when the layout lists it.

| Key | What it holds |
|---|---|
| `value` | The schema's value this choice offers. Required, and one the schema offers. |
| `label` | The choice's label on this form, in place of the schema's. |
| `description` | A description shown with the choice on this form. |

## Which fields show

`mode` decides who chooses the fields a form shows and their order.

| Value | What it does |
|---|---|
| `overlay` | The schema decides which fields show and their order; the layout only gives copy for the fields it lists. The default. |
| `reorder` | The fields the layout lists show first, in its order; every other field the type declares follows, in the schema's order. Nothing is hidden. |
| `authoritative` | Only the fields the layout lists show, in its order. The list must name every required field a person enters, or the form could not be saved. |

Every mode narrows and orders; none adds a field. A field the schema keeps from a form -- one that is derived, one a
`g-visibleWhen` hides from the caller, one a condition forbids -- stays hidden even when the layout lists it.

## A value nobody entered

`defaultMode` says how the form presents a value it was given for a field that nobody entered, such as a
default from the caller's account.

| Value | What it does |
|---|---|
| `filled` | The value is shown in the field at once, marked as one nobody entered. The default. |
| `offer` | The field stays empty, beside a button that puts the value in, as though it had been entered. |

## Form strings

The wording a form uses for the type as a whole. A layout's `strings` overrides any of these by name; a name not
listed here is refused.

| Name | What it is | Default wording |
|---|---|---|
| `formErrorSummary` | The heading over the list of what failed, which a form shows only while the page is in debug. | saving a form, "Please fix these before saving"; creating one, "Please fix these before creating the form" |
| `formErrorHint` | The line a form shows in place of that list when the page is not in debug: the fields at fault are already marked, so it points to them. | saving a form, "Fix highlighted errors in entered data before saving."; creating one, "Fix highlighted errors in entered data before creating the form." |
| `prefillSummary` | The line a workflow form shows while values nobody entered are still waiting to be saved. A template over `${count}`, the number of fields filled in. | one field, "1 field was filled in from your account — review and save to keep them."; several, "${count} fields were filled in from your account — review and save to keep them." |

## Templates

A layout's copy may hold two kinds of template, filled in at different times, and one string may hold both.

- **`%{…}`, on the server, as the layout is delivered.** It pulls shared wording from a backend fragment file:
  `%{@t("fileId.namespace.key")}`, or `%{@t("namespace.key")}` in a block whose `fragmentFileId` names the
  file. The page receives the finished wording, so a backend file is never sent to it. A pull naming a file or a key
  that does not exist is refused when the layout is loaded; one whose key is computed is checked as the layout is
  delivered, and shown as written if it does not resolve. Fragment files are described in [Copy](copy.md).
- **`${…}`, on the page, as the form is drawn.** In a `label` or a `description`, it reads the data being
  entered: `${topic}`. In a `hint`, it reads the field's declared bounds, `${min}` and
  `${max}`, as numbers, so a hint may compute with them. In a message under `errors`, it reads the
  failure's parameters. The language is described in [Expressions](expressions.md).

A pull is written with `%`, never `$`: a `${@t(…)}` in a layout is refused. A message under `errors` takes no
`%{…}` at all, since it is filled in wherever the failure is shown.

## A client's change to a shared layout

A client that alters a shared type may write a layout on its alteration, and it merges with the shared one. What the
alteration's own `mode` says decides how its `schemaFields` merge:

- **No mode, or `overlay`: a change of copy.** Each entry replaces the shared entry for its field, whole, so the
  client states only the fields it rewords, and every other entry is the shared layout's. An entry for a field the
  shared layout does not list is added at the end where the layout is `overlay`, and refused where it is
  `reorder` or `authoritative`, since it would need a place in the order.
- **`reorder` or `authoritative`: a change of order or of which fields show.** The client's list is the
  order, and with `authoritative` the fields too. An entry that names only its `field` keeps the shared
  entry for that field.

`strings` merge by name. Every other key of the block replaces the shared one. `"g-layout": null` drops the
shared layout. A client whose alteration drops fields from the type keeps the shared layout without the entries for
those fields, which is not a fault.

## What the load refuses

| Code | What is wrong |
|---|---|
| `unknownKey` | A key the block, a field entry, a choice, or `strings` does not know -- usually a typo. |
| `noFields` | A block that says nothing: no field entries, and no name, introduction, or strings. |
| `noFieldName` | A field entry with no `field`. |
| `badValue` | A value of the wrong shape, or outside its closed set: a `mode`, a `defaultMode`, a `lines`, or a `strings` value. |
| `nestedLayout` | A `g-layout` below a type's top level, where there is no type name to key it by. |
| `unresolvedType` | A layout on a type that did not resolve. |
| `notAnObjectType` | A layout on a union or an array, which has no single set of fields to lay out. |
| `undeclaredField` | A field the type does not declare. |
| `omitsRequired` | An authoritative layout leaving out a field the type may require, so the form could not be saved. |
| `choicesWithoutOptions` | A field entry's `choices` on a field the schema gives no closed list of choices. |
| `notAChoice` | A field entry's `choices` naming a value the schema does not offer. |
| `requiredUnfillable` | A field entry's `required` on a field nobody at the form could always fill in: derived, or gated by `g-visibleWhen`. |
| `linesNotLongText` | A field entry's `lines` on a field the schema does not mark long text. |
| `malformedTemplate` | Copy whose `${…}` or `%{…}` template does not parse. |
| `frontendPull` | A frontend `${@t(…)}` pull, where a layout pulls with the backend `%{@t(…)}`, or a pull where none is allowed. |
| `unknownParam` | A template parameter its context does not provide: `${max}` on a field with no maximum. |
| `backendBlock` | A backend `%{…}` block in an error message, which delivery does not resolve. |
| `badPullKey` | A backend pull whose key names no fragment file: neither `fileId.namespace.key`, nor `namespace.key` in a block with a `fragmentFileId`. |
| `pullFileMissing` | A backend pull naming a fragment file that is not declared. |
| `pullFromFrontendFile` | A backend pull naming a frontend fragment file. |
| `pullKeyMissing` | A backend pull naming a key its file does not have. |

A fault in a layout the platform declares stops a test or development node from starting; a production node drops the
layout and says so. A fault in a client's own layout costs only that layout, outside a unit test: it is left out, so
the type falls back to the shared layout or none, and the client's configuration issues say why. A configuration written through the
endpoints is refused when it brings a fault the client did not already have.
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes it from the vocabulary its layout parser reads,
so it cannot disagree with the parser. Read it on the server's **Documents** page, under **Form layouts**, where anyone
may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/form-layouts](https://dynamicruntime.org/wa/docs/form-layouts).
`./gradlew :launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit
the generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
|---|---|
| The keys of a layout, a field entry, and a choice, each with what it holds; the modes; the default-value options; the form strings and their default wording; the codes a fault is reported with | `SL`, `SLM`, `SLDM`, `LAYSTR`, and `LayoutError` in [`SchLayout.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchLayout.kt) |
| How a client's layout merges with the shared one | `layoutMergeSpec` in [`SchLayout.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchLayout.kt) |
| The reference page, made from these | [`SchLayoutReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/schema/SchLayoutReference.kt) |
