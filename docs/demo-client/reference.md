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

## Field layouts

A trait's fields have a default presentation: each field's `title` is its label, its `description` is the line beneath
it, and the fields appear in the order the schema declares them. A **field layout**, under `g-layout`, changes that
presentation without changing the data. A layout can relabel a field, reorder the fields, or, with `mode` set to
`authoritative`, choose which fields the form shows.

A workflow can give one of its forms a layout of its own. It does this in its `types`, keyed by the name of the
trait's data type, which is the trait's entry type with `Data` in place of `Entry`. So `QuestionnaireEntry` becomes
`QuestionnaireData`. The [talk survey](talk-survey.md#survey) does this.
