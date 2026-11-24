[← Talk core details](talk-core-details.md) · [Contents](README.md#the-pages)

# Numbering talks

The demo numbers its talks, so that the committee and the speaker can name a talk by a short id, such as `T-007`. Two
pieces of configuration in the `base` bundle do it:

- [a counter](#counter), which gives out the ids;
- [a trait](#trait) with a field that takes its ids from the counter.

The creation workflow collects the trait, so every talk gets its id as it is submitted, and the forms list and the
roster report show it.

## Counter

A **counter** gives out ids in order: a prefix, then a count padded with zeroes. The demo's is the one entry in the
`base` bundle's `kdr:counterDef` slot:

<!-- kdr-doc: bundle=base pointer=/kdr:counterDef/0 -->
```json
{
  "counterId": "talk", "prefix": "T-", "digits": 3, "reset": "never",
  "description": "Numbers the talks, in the order they are submitted."
}
```

| Key | What it means |
| --- | --- |
| `counterId` | The counter's id. A field names the counter by it. |
| `prefix` | The text before the count. The demo's is `T-`, the same for every talk. It is a template, so it could instead include the day an id is given. |
| `digits` | How many digits the count is padded to with zeroes, from 2 to 8. A count that outgrows them takes more, so after `T-999` comes `T-1000`. |
| `reset` | When the count starts again at 1: `never`, or `daily`, on each of the client's days. A daily counter needs the day in its prefix, or its ids would repeat: `R${compactDay}-` gives `R20261117-0001` one day and `R20261118-0001` the next. |
| `description` | What the counter numbers. |
| `issue` | When a form is given its id. Left out, as the demo's is, it is `onOpen`: the form asks for the id as it opens, so the person sees it before saving. With `onSave` the id is given when the form is saved instead, so a form opened and never saved uses none up, and the form shows the field only once it holds the id. |

The demo's counter goes on for the whole call for talks, so its ids read `T-001`, `T-002`, and so on, and they are short
enough for a phone's screen.

## Trait

The trait is the last of the `base` bundle's traits:

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/5 -->
```json
{
  "description": "The talk's id, given when it is submitted.",
  "traitId": "submission",
  "typeName": "client.confdemo.SubmissionEntry",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "title": "Talk id",
    "properties": {
      "talkId": {
        "description": "The talk's id, given by the conference's counter.",
        "title": "Id",
        "g-counter": "talk",
        "type": "string"
      }
    },
    "required": ["talkId"],
    "type": "object"
  }
}
```

Its keys are a trait's, as [Talk core details](talk-core-details.md#traits) explains them.

**Its one field takes its ids from the counter.** The field `talkId` names the counter with `g-counter`, one of the
platform's additions to JSON Schema (see the [schema dialect reference](../reference/schema-dialect.md)). A field like
this is never typed. When a form opens, it asks the counter for the next id and shows it, so the speaker sees the talk's
id before saving it.

**An id is fixed once it is saved.** It cannot be changed afterward, and no other talk can be given it. A form that is
opened and never saved leaves its id unused, so the count can skip a number. A counter whose `issue` is `onSave` skips
fewer, at the cost of the preview.

**The creation workflow collects the trait.** Its task lists `submission` after the title, as
[Creating a talk](create-talk.md#task) shows, with `required: false`. The form always fills it, so it need not be
required. A program creating a talk through the API does not have to ask the counter for an id first either: when it
sends the trait with `talkId` left out, the talk is given the next id. A talk created without the trait has no id.

**The forms list and the roster show it.** The forms list has a **Talk id** column, narrow, since an id needs far less
room than a talk's title ([Talk core details](talk-core-details.md#columns-in-the-forms-list)). The **Submissions by
track** report has a **Talk id** column too ([Reports](reports.md)).

## What the speaker sees

On **Start a talk submission**, under the title, a section headed **Talk id** shows the talk's id in its **Id** field,
such as **T-007**, which the speaker cannot change. Once the talk is created, its id is in the forms list's **Talk id**
column, and a search for it there finds the talk.

Next: [Abstract](abstract.md).
