[← The conference demo](README.md) · [Contents](README.md#the-pages)

# Reference

The things the demo's pages have in common, explained here once so that no page has to repeat them. Each page links
here the first time it uses one of them.

## Reading the JSON on these pages

The JSON on each page is a bundle's `slots`. On a test instance that ran the conf-demo simulation, a running instance
returns it from `GET /clientAdmin/config/bundle?name=<bundle>`. Where the demo is declared in code, as on a reference
deployment, the bundle editor shows the same JSON under **From code**. A page shows a whole bundle, or one entry out of
it.

The instance may list the keys in a different order from the page. The values are the same.

Two words come up on every page, and they mean different things. A definition is made of **keys**, such as `traitId`
and `label`, and those are what the tables on each page explain. A trait's data is made of **fields**, such as `track`,
and those are what a form shows and what a field layout arranges.

## Comments

A workflow, a task, an eligibility test, a report, and a layout entry can each have a `$comment` key. It is a note
for whoever maintains the definition. It changes nothing about what the definition does, and it is stored and returned
along with everything else in the definition, so it is there the next time someone reads it.

<!-- kdr-doc: illustration -->
```json
{
  "id": "nameTalk",
  "$comment": "A creation workflow has exactly one task, with exactly one save.",
  "label": "Name your talk"
}
```

A `$comment` anywhere else is refused, and the error says where comments are allowed. The
[creation workflow](create-talk.md) has two of them.

## Choices

A string field can offer a fixed list of choices with `g-options`. Each choice has a `value`, which is what the form
stores, and a `label`, which is what people see.

<!-- kdr-doc: illustration -->
```json
{
  "type": "string",
  "g-options": [
    { "value": "kotlin", "label": "Kotlin" },
    { "value": "web", "label": "The web" }
  ]
}
```

A value that is not one of the choices is refused when the form is saved.

Keywords that start with `g-` are the platform's additions to JSON Schema. Standard JSON Schema keywords such as
`type`, `title`, and `required` mean what they always mean. A few standard keywords are not supported. The
[schema dialect reference](../reference/schema-dialect.md) lists every keyword the platform reads, with what each one
means, and the standard keywords it refuses. A running server shows the whole reference to anyone, signed in or not:
on its Documents page, choose Reference.

## Bounds and their messages

A schema can set bounds on a value: `minLength` and `maxLength` on a string, `minimum` and `maximum` on a number,
`minItems` and `maxItems` on a list. A form shows the bounds as a hint under the field, and a value outside them is
refused when the form is saved.

The message a refusal shows is the platform's unless the schema gives its own, under `g-errors`, keyed by what went
wrong: `missingRequired`, `belowMinimum`, `aboveMaximum`, `invalidOption`, and so on, or `default` for any of them.
[Validation messages](../reference/validation-messages.md) lists every code and what a message for it may say.

<!-- kdr-doc: illustration -->
```json
{
  "type": "string",
  "minLength": 50,
  "g-errors": {
    "missingRequired": "Every talk needs a summary.",
    "belowMinimum": "A summary is at least ${min} characters; give the audience a reason to come."
  }
}
```

A message can name the failure's own values, which are filled in when it is shown: `${min}` and `${max}` for the
field's bounds, `${value}` for the value refused, `${options}` for a field's choices, and `${field}` for the field's
name. A message that says `${min}` rather than `50` stays right when the bound changes. Each code offers only the
values that make sense for it, and a message naming one its code does not offer is refused when the schema is loaded.

A form can also give a message of its own. A field's entry in a form's layout (see [Field layouts](#field-layouts))
can have `errors`, keyed in the same way, which that form shows in place of the schema's. It suits a requirement the
form adds, since only the form knows why it asks. A request made outside any form never shows the form's messages.

## Trimming and coercion

What a person types is tidied before it is checked. Spaces at the start and end of a text are removed, so a title
pasted with a stray space is saved without it, and a number typed into a form, such as `45`, is accepted as the
number 45. A schema can say otherwise for one field: `"g-outerWhitespace": "keep"` keeps the spaces, and
`"g-allowCoerce": false` refuses a number that arrives as text. The demo's schemas say `"g-outerWhitespace": "trim"`
where trimming matters, which also trims a value that arrives by other routes than a form.

## Long text

A text field with `"g-longText": true` holds free text that may run over several lines, such as an answer of a
sentence or two, or a note. Every form shows it as a box of several lines rather than a single line, and you can drag
the box taller. A form that shows a saved answer without letting you edit it keeps the answer's line breaks. Long text
changes nothing about what may be saved, so bounds and trimming apply to it as to any other text. Only a plain text
field can be long text: not a date, not a file, and not a field with choices. A layout can say how many lines the box
starts with (see [Field layouts](#field-layouts)).

## Lists

A field whose `type` is `array` holds a list. Its `items` say what each element is: a choice from `g-options`,
which makes the field a multi-select, or an object, given by `$ref` to a type the client defines in its
`kdr:schemaDef` slot. `uniqueItems` refuses a repeated element, and `maxItems` caps the length.

A form shows a list of objects one entry at a time, each under a heading of its own: **Entry 1**, **Entry 2**, and so
on, unless a layout names the entries (see [Field layouts](#field-layouts)).

## Who sees a field

A field with `g-visibleWhen` is left off a form unless the person editing it has the fact the keyword names.
`kdr:hasAdminLevel` is the fact an administrator has. This is presentation, not access control: the data still holds
the field, a read-only view of the form shows it, and the API returns it. It keeps a question off a form that is not
the right person's to answer.

## Facts and eligibility

A **fact** (in the JSON, a `cfact`) is a yes-or-no statement about a form, a user, or the request. Some are the
platform's, such as `kdr:coreDetailsComplete` and `kdr:hasAdminLevel`. A client declares its own in the `kdr:cfactDef`
slot of a bundle, and a workflow sets them: a `computeCFactsFromData` function reads a field and asserts a fact when
the value is one it maps, and an approval task asserts its fact when a reviewer approves.

A normal workflow can list **eligibility tests** in `eligibility`. Each is a fact the form must have, with an
`explanation` shown when it does not. A form that fails a test cannot enter the workflow, and the forms list says
why. A test can be negated with `~`, as in `~kdr:needsReview`.

The [facts reference](../reference/facts.md) lists the platform's facts, says which facts each kind of test sees, and
shows how to join facts in one test.

## Who may work on a task

A task can run a `userHasLabel` function, which asserts the fact `kdr:reviewer` for a person whose user has the
label the function is given (see `userLabels` on the [client](client.md)). With `"saveWhen": "kdr:reviewer"`, only such a person
may save the task; everyone else sees it read-only. The platform insists on a **lock** to go with a save rule, so the
trait cannot be changed by another route while the form is in the workflow.

A test about who may work on a task can also name `kdr:isOwner`, which is true for the person who owns the form. It is
worked out from the form's owner whenever the form is read, and never stored. A task that is the owner's to do, such as
a revision a reviewer asked for, says `"saveWhen": "kdr:isOwner"`.

An **approval task** has no traits to fill in. It has an `approval` with the fact it asserts, a `prompt`, and a
`button`, and it is complete once someone allowed to approve it has pressed the button. The approval's `approveWhen`
says who that is. Left out, it is a reviewer who does not own the form, `kdr:reviewer,~kdr:isOwner`, so nobody
approves their own form. A step that is the owner's own to confirm says `"approveWhen": "kdr:isOwner"`. Its `display`
chooses what the step shows, by trying its branches in order against the facts of whoever is looking.

A task can also say **who it waits on** from the form's own data, with a `ctaFilterByValue` function. The function
reads one field, such as a site's region, and picks a filter for each value it lists, with an `otherwise` for every
other value. A filter is a fact test that may name only facts about the person looking, such as the ones a
`userHasLabel` function asserts, and `kdr:isOwner`. While the task is the workflow's current step, a person has to pass both the task's
`saveWhen` and its filter to save it, and a reviewer has to pass the filter to approve it. Anyone else sees the filter's
`label`, such as "Waiting on a Northwest reviewer", where the step's buttons would be. The demo's workflows do not use
one.

A task can say what kind of step it is with a **search keyword**, `keyword`. There is one so far, `review`, for a
step that waits on someone to look the form over, and an approval task carries it without saying so. While a task
carrying it is its workflow's next step, the forms list shows the form as **Needs Review** (the fact
`kdr:needsReview`, which another workflow's eligibility test can name), and the workflow has the fact `kdr:ctaReview`
for its `display` branches. The keyword is the only way a form comes to need review.

Once the button is pressed, a text branch can name who pressed it as `${approvedByName}`. That is the name on the
approver's profile, or their email when their profile has none. Since the person reading the step may be the form's
owner, who may not know the approver, a branch can keep an email off the page by testing for its `@`:
`Accepted by ${contains(approvedByName, "@") ? "the committee" : approvedByName}.` A step with no text branch reads
"Approved by" and the name, and leaves an email out.

## Field layouts

A trait's fields have a default presentation: each field's `title` is its label, its `description` is the line beneath
it, and the fields appear in the order the schema declares them. A **field layout**, under `g-layout`, changes that
presentation without changing the data. A layout can relabel a field, reorder the fields, or, with `mode` set to
`authoritative`, choose which fields the form shows.

A workflow can give one of its forms a layout of its own. It does this in its `types`, keyed by the name of the
trait's data type, which is the trait's entry type with `Data` in place of `Entry`. So `QuestionnaireEntry` becomes
`QuestionnaireData`. The [talk's core details workflow](talk-core-details.md#core-details-workflow) does this.

A layout entry can also restate a field's `choices`, which narrows the list the form offers to those it names, with
labels of its own. A form never offers a choice the schema does not. And an entry can mark a field `required` for
this form, which the schema may leave optional.

A layout entry can also say how many `lines` a long text field's box starts with, which is three when no layout says.
Whether a field is [long text](#long-text) is the schema's to say, with `g-longText`, since it is a fact about the
data rather than about one form. The [abstract's summary](abstract.md) is one.

A layout entry for a [list](#lists) can say what the form calls each of its entries, with an `entryLabel` such as
`Co-speaker ${n}`. The form puts the entry's number, counting from 1, in place of `${n}`, so the entries read
**Co-speaker 1**, **Co-speaker 2**, and so on. Without one, they read **Entry 1**, **Entry 2**. The
[abstract's co-speakers](abstract.md#the-abstract-trait) have one.

A layout can also name the trait and introduce it. Its `label` is the trait's name, the heading of its section on a
form, and its `description` is a line under that heading. A client can give a trait a layout for every form it shows,
with an entry for the trait's data type in the `kdr:schemaDef` slot of its `base` bundle, even for a trait the platform
defines. [Creating a talk](create-talk.md#the-traits-name) does this for `kdr:name`.

## Images

A label or description is Markdown, and it can show an image. `![A presentation screen](img:kdr:talkIcon)` shows the
image named `kdr:talkIcon`, one the deployment ships, which every client can use. A client's own image, one its
administrator uploads on the Clients page, is named with `ref:` instead: `![Our logo](ref:logo)`. The text in the
brackets is what a reader sees where the image cannot be drawn.

What follows in braces places the image. `{.float-left .icon width=20}` puts a small one at the start of its line, at
the height of the text, which is how the demo opens the talk's introduction. Without `.icon`, `.float-left` floats a
larger picture beside the text that follows it. The [copy reference](../reference/copy.md) lists every role, and the
rest of the Markdown a label, a description, or a line of copy can hold.

## Copy from a file

A label, description, or line of copy can hold an expression between `${` and `}`, described in
[Expressions](../reference/expressions.md) with its functions. A label or description can also pull its words from a
fragment file instead of holding them: `%{@t("logistics.heading")}` takes the `heading` key of the `logistics` namespace
in the file the layout's `fragmentFileId` names. The words then live in one place, and a client's copy overlay can
change them without touching the definition.

## Report paths

A report column's `path` says where its value comes from. The first part is the **source**, and what follows
depends on it.

| Source | What follows | Example |
| --- | --- | --- |
| `form` | A trait's id, then a field of its data. A trait the platform ships keeps its full id. | `form.session.track`, `form.kdr:name.name` |
| `workflow` | A workflow's id, then one of `category`, `engaged`, `eligible`, `finished`, `tasksDone`, `ctaTask`, or `lastEngagedAt`. | `workflow.review.category` |
| `user` | One of `userId`, `name`, `publicName`, `email`, `persona`, `org`, `labels`, `isEntity`, or `enabled`, about the person who owns the form. | `user.name` |
| `meta` | One of `gedraId`, `client`, `org`, `ownerId`, `createdAt`, `updatedAt`, `formStatus`, `coreDetailsStatus`, or `cfacts`, about the form itself. | `meta.formStatus` |

A `form` path can go deeper than one field, `form.abstract.coSpeakers.name` for instance, and reaches into a list
as well as an object. A path into a list has several values, and the column's `combine` says how they are made one
for the form: the first, all of them as a list, the distinct ones, a count, or a sum, minimum, maximum, or
average for numbers. Left out, a path with one value takes the first and a path with several lists them.

A column's `rollup` is the same list of choices, applied across the forms of a group when the report is run
grouped. A column with no rollup is left out of a grouped run, and one the run groups by is shown once, as the
group's value. The demo's [Reports](reports.md) page shows `sum`, `list`, and `count`.

Paths are checked when the configuration loads. A path that names a trait, a field, or a workflow the client does
not have costs the report that holds it, not the bundle. The [report paths reference](../reference/report-paths.md)
lists every attribute a path can end in, with its kind, and the selectors a keyed trait takes.
