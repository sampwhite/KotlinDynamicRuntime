[← The conference demo](README.md) · [Contents](README.md#the-pages)

# Reference

The things the demo's pages have in common, explained here once so that no page has to repeat them. Each page links
here the first time it uses one of them.

## Reading the JSON on these pages

The JSON on each page is a bundle's `slots`, as a running instance returns it from
`GET /clientAdmin/config/bundle?name=<bundle>`. A page shows a whole bundle, or one entry out of it.

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
`type`, `title`, and `required` mean what they always mean.

## Bounds and their messages

A schema can set bounds on a value: `minLength` and `maxLength` on a string, `minimum` and `maximum` on a number,
`minItems` and `maxItems` on a list. A form shows the bounds as a hint under the field, and a value outside them is
refused when the form is saved.

The message a refusal shows is the platform's unless the schema gives its own, under `g-errors`, keyed by what went
wrong: `missingRequired`, `belowMinimum`, `aboveMaximum`, `invalidOption`, and so on, or `default` for any of them.

<!-- kdr-doc: illustration -->
```json
{
  "type": "string",
  "minLength": 50,
  "g-errors": {
    "missingRequired": "Every talk needs a summary.",
    "belowMinimum": "A summary is at least 50 characters; give the audience a reason to come."
  }
}
```

## Trimming and coercion

What a person types is tidied before it is checked. Spaces at the start and end of a text are removed, so a title
pasted with a stray space is saved without it, and a number typed into a form, such as `45`, is accepted as the
number 45. A schema can say otherwise for one field: `"g-outerWhitespace": "keep"` keeps the spaces, and
`"g-allowCoerce": false` refuses a number that arrives as text. The demo's schemas say `"g-outerWhitespace": "trim"`
where trimming matters, which also trims a value that arrives by other routes than a form.

## Lists

A field whose `type` is `array` holds a list. Its `items` say what each element is: a choice from `g-options`,
which makes the field a multi-select, or an object, given by `$ref` to a type the client defines in its
`kdr:schemaDef` slot. `uniqueItems` refuses a repeated element, and `maxItems` caps the length.

## Who sees a field

A field with `g-visibleWhen` is left off a form unless the person editing it has the fact the keyword names.
`kdr:hasAdminLevel` is the fact an administrator has. This is presentation, not access control: the data still holds
the field, a read-only view of the form shows it, and the API returns it. It keeps a question off a form that is not
the right person's to answer.

## Facts and eligibility

A **fact** (in the JSON, a `cfact`) is a yes-or-no statement about a form, a user, or the request. Some are the
platform's, such as `kdr:surveyComplete` and `kdr:hasAdminLevel`. A client declares its own in the `kdr:cfactDef`
slot of a bundle, and a workflow sets them: a `computeCFactsFromData` function reads a field and asserts a fact when
the value is one it maps, and an approval task asserts its fact when a reviewer approves.

A normal workflow can list **eligibility tests** in `eligibility`. Each is a fact the form must have, with an
`explanation` shown when it does not. A form that fails a test cannot enter the workflow, and the forms list says
why. A test can be negated with `~`, as in `~kdr:needsReview`.

## Who may work on a task

A task can run a `userHasLabel` function, which asserts the fact `kdr:reviewer` for a person whose user has the
label the function is given (see `userLabels` on the [client](client.md)). With `"saveWhen": "kdr:reviewer"`, only such a person
may save the task; everyone else sees it read-only. The platform insists on a **lock** to go with a save rule, so the
trait cannot be changed by another route while the form is in the workflow.

An **approval task** has no traits to fill in. It has an `approval` with the fact it asserts, a `prompt`, and a
`button`, and it is complete once a reviewer has pressed the button. Its `display` chooses what the step shows, by
trying its branches in order against the facts of whoever is looking.

## Field layouts

A trait's fields have a default presentation: each field's `title` is its label, its `description` is the line beneath
it, and the fields appear in the order the schema declares them. A **field layout**, under `g-layout`, changes that
presentation without changing the data. A layout can relabel a field, reorder the fields, or, with `mode` set to
`authoritative`, choose which fields the form shows.

A workflow can give one of its forms a layout of its own. It does this in its `types`, keyed by the name of the
trait's data type, which is the trait's entry type with `Data` in place of `Entry`. So `QuestionnaireEntry` becomes
`QuestionnaireData`. The [talk survey](talk-survey.md#survey) does this.

A layout entry can also restate a field's `choices`, which narrows the list the form offers to those it names, with
labels of its own. A form never offers a choice the schema does not. And an entry can mark a field `required` for
this form, which the schema may leave optional.

## Copy from a file

A label or description can pull its words from a fragment file instead of holding them: `%{@t("logistics.heading")}`
takes the `heading` key of the `logistics` namespace in the file the layout's `fragmentFileId` names. The words then
live in one place, and a client's copy overlay can change them without touching the definition.

## Report paths

A report column's `path` says where its value comes from. The first part is the **source**, and what follows
depends on it.

| Source | What follows | Example |
| --- | --- | --- |
| `form` | A trait's id, then a field of its data. A trait the platform ships keeps its full id. | `form.session.track`, `form.kdr:name.name` |
| `workflow` | A workflow's id, then one of `category`, `engaged`, `eligible`, `finished`, `tasksDone`, `ctaTask`, or `lastEngagedAt`. | `workflow.review.category` |
| `user` | One of `userId`, `name`, `publicName`, `email`, `persona`, `org`, `labels`, `isEntity`, or `enabled`, about the person who owns the form. | `user.name` |
| `meta` | One of `gedraId`, `client`, `org`, `ownerId`, `createdAt`, `updatedAt`, `formStatus`, `surveyStatus`, or `cfacts`, about the form itself. | `meta.formStatus` |

A `form` path can go deeper than one field, `form.abstract.coSpeakers.name` for instance, and reaches into a list
as well as an object. A path into a list has several values, and the column's `combine` says how they are made one
for the form: the first, all of them as a list, the distinct ones, a count, or a sum, minimum, maximum, or
average for numbers. Left out, a path with one value takes the first and a path with several lists them.

A column's `rollup` is the same list of choices, applied across the forms of a group when the report is run
grouped. A column with no rollup is left out of a grouped run, and one the run groups by is shown once, as the
group's value. The demo's [Reports](reports.md) page shows `sum`, `list`, and `count`.

Paths are checked when the configuration loads. A path that names a trait, a field, or a workflow the client does
not have costs the report that holds it, not the bundle.
