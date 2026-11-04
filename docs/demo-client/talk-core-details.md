[← Creating a talk](create-talk.md) · [Contents](README.md#the-pages)

# Talk core details

A form's **core details** are what every form needs, and the client's **core details workflow** collects them after the
form exists. They are not all of a form's data: each workflow a form takes part in collects traits of its own. A client
has at most one core details workflow. In the demo, it lets each speaker change the talk's title, and asks for its
track and one background question.

Adding the core details workflow takes three pieces of configuration, and this page shows all three:

- [two traits](#traits) for the core details workflow to collect, in the `base` bundle;
- [three columns](#columns-in-the-forms-list) for the forms list, also in the `base` bundle;
- [the core details workflow itself](#core-details-workflow), in the `talkCoreDetails` bundle.

The traits and the columns are the `base` bundle's first additions beyond the client itself. The
[client page](client.md#what-the-bundle-holds) lists everything the bundle holds, as the pages add to it.

## Traits

Before the core details workflow can collect anything, the client needs traits for it to collect. It defines two of its
own, `session` and `questionnaire`, and keeps them in the `kdr:traitDef` slot of the `base` bundle.

### Session

The `session` trait says where a talk fits in the program. For now it has one field, the track.

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/0 -->
```json
{
  "traitId": "session",
  "typeName": "client.confdemo.SessionEntry",
  "description": "Where a talk fits in the program.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "properties": {
      "track": {
        "type": "string",
        "title": "Track",
        "description": "The part of the program the talk belongs to.",
        "g-options": [
          { "value": "kotlin", "label": "Kotlin" },
          { "value": "web", "label": "The web" },
          { "value": "data", "label": "Data" },
          { "value": "workshop", "label": "Workshop (hands-on)" }
        ]
      }
    },
    "required": ["track"]
  }
}
```

| Key | What it means |
| --- | --- |
| `traitId` | The trait's id. When a form has data for this trait, the entry that holds the data includes this id, so the entry can always be matched to its trait. A workflow's task also picks out the trait by this id. |
| `typeName` | The name of the trait's entry type: the client's namespace, `client.confdemo`, and the short name for the type, `SessionEntry`. |
| `description` | A note on what the trait holds. |
| `appliesTo` | What the trait can be part of. The value `formDoc` means a form. |
| `dataSchema` | The JSON Schema for the trait's data. |

The schema is standard JSON Schema with a few additions. The track is a string, and `g-options` limits it to four
choices (see [Choices](reference.md#choices)). Because the `required` list includes `track`, data for a `session` is
not valid until it has a track.

### Questionnaire

The `questionnaire` trait holds five questions that every speaker answers. The core details workflow asks the first; the abstract
workflow, on a later page, asks the other four.

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/1 -->
```json
{
  "traitId": "questionnaire",
  "typeName": "client.confdemo.QuestionnaireEntry",
  "description": "Five questions every speaker answers.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "properties": {
      "firstTalk": {
        "type": "boolean",
        "title": "Is this your first conference talk?",
        "description": "Whether this is the speaker's first talk at a conference."
      },
      "audienceLevel": {
        "type": "string",
        "title": "Who is the talk for?",
        "description": "How much the audience needs to know already.",
        "g-options": [
          { "value": "beginner", "label": "Beginners" },
          { "value": "intermediate", "label": "People with some experience" },
          { "value": "advanced", "label": "Experts" }
        ]
      },
      "takeaway": {
        "type": "string",
        "title": "What will the audience take away?",
        "description": "What the audience leaves knowing.",
        "g-longText": true
      },
      "recordingOk": {
        "type": "boolean",
        "title": "May we record the talk?",
        "description": "Whether the talk may be recorded and published."
      },
      "needs": {
        "type": "string",
        "title": "Anything we should know for the day?",
        "description": "Anything the organizers should know for the day.",
        "g-longText": true
      }
    }
  }
}
```

The schema requires none of the questions, so the data stays valid as the answers arrive in stages. Each workflow
that asks questions from the trait can choose which questions to show and which ones its form requires. A requirement
the workflow adds applies to its form only. The trait's schema does not change.

**Two answers are long text.** The `g-longText` on `takeaway` and `needs` says each one holds free text that may run
over several lines, so a form shows a box of several lines for it rather than a single line. See
[Long text](reference.md#long-text).

## Columns in the forms list

The forms list as it appears in the application shows one row per form. Some columns in the forms list
are specified by the application, but the leading columns can be configured. The client chooses the configurable columns
in the `kdr:usageDef` slot of its `base` bundle.

<!-- kdr-doc: bundle=base pointer=/kdr:usageDef -->
```json
[
  { "traitId": "kdr:name", "label": "Talk", "display": "${name}", "substring": true },
  { "traitId": "session", "label": "Track", "display": "${track}", "substring": false },
  {
    "traitId": "questionnaire", "label": "Audience", "display": "${audienceLevel}", "substring": false,
    "columnId": "audience"
  }
]
```

| Key | What it means |
| --- | --- |
| `traitId` | The trait the column takes its value from. |
| `label` | The column's heading. |
| `display` | The column's value: a template over the trait's data, in which `${track}` is the `track` field's value. The column sorts and searches by it, and shows it unless the column has a presentation. |
| `kind` | Optional, and left out of the demo's columns. How a search treats the value: as text, a number, or a date. Without it, a column that shows one field takes it from that field, so a number field is searched by a range. A column that computes its value is text unless it says otherwise. A date column with no presentation reads relative to now, such as `in 3h`. |
| `substring` | Whether a search may match part of the value. On the talk's name, a search for `scale` finds `Kotlin at scale`. |
| `columnId` | The column's name in a search, when it is not the trait's id. The Audience column is searched with `audience`. |

In this example, the demo promotes three fields to be columns: `name`, from the built-in `kdr:name` trait, `track`,
from its own `session` trait, and one answer from its `questionnaire` trait. A promoted value also becomes available
for search.
As soon as a client declares columns of its own, the platform's default column, the form's name, disappears. So the
demo redeclares the name column too, but relabels the heading to be **Talk**.

A column may also have a `presentation`, which the demo does not use. A presentation is a template that says how
the column's cells read, with the column's value available as `value`. For example, `${currency(value, "USD")}` shows
an amount as `$1,234.50`, and `${city}, ${state}` composes two fields of the trait. The column still sorts and
searches by its value, so a formatted amount never sorts as text.

One trait can give more than one column, each with its own heading. Every column of a trait after the first also
needs a `columnId`: a name of letters, digits, and underscores. A column without a `columnId` is searched by its
trait's id, so two of them from one trait would share a search. The demo's questionnaire gives only the Audience
column, but the demo still names it `audience`, so a search for it says what it looks for rather than which trait it
reads.

The Track and Audience columns each read a single field that offers choices, so each shows the choice's label, such
as **Workshop (hands-on)** or **Beginners**, and sorts in the order the choices are listed. A search on either matches
the stored value or the label. Its filter suggests the choices as soon as it is clicked, and anything else can still
be typed. A column that reads a yes-or-no field shows **Yes** or **No**, and a dash until the field is answered. Its
filter is a choice between Yes and No, and its search takes `true` or `false` (or `yes` or `no`). A column whose
`display` does more than name one field, such as
`${city}, ${state}`, works as the name column does: its filter suggests only the values the forms hold.

## Core details workflow

Here is the whole `talkCoreDetails` bundle:

<!-- kdr-doc: bundle=talkCoreDetails -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "talkCoreDetails",
      "definition": {
        "workflowId": "talkCoreDetails",
        "entry": "coreDetails",
        "$comment": "The form's core details workflow: what every talk needs before it is reviewed.",
        "label": "About your talk",
        "tasks": [
          {
            "id": "describe",
            "label": "Title, track, and background",
            "$comment": "The title comes first, so a speaker can change the one they started with.",
            "traits": [
              { "traitId": "kdr:name", "required": true },
              { "traitId": "session", "required": true },
              { "traitId": "questionnaire", "required": true }
            ],
            "saves": [{ "id": "saveAbout", "label": "Save", "kind": "edit" }]
          }
        ],
        "functions": [
          {
            "fn": "computeCFactsFromData",
            "trait": "session",
            "op": "isInList",
            "valuePath": "track",
            "valueMapping": [{ "value": "workshop", "cfact": "isWorkshop" }]
          }
        ],
        "types": {
          "client.confdemo.QuestionnaireData": {
            "g-layout": {
              "$comment": "The core details workflow asks one of the five questions. The abstract workflow asks the rest.",
              "mode": "authoritative",
              "schemaFields": [{ "field": "firstTalk" }]
            }
          }
        }
      }
    }
  ]
}
```

Most of it reads as the creation workflow does (see [Creating a talk](create-talk.md#workflow)). Five things are
new.

**The entry is `coreDetails`.** The core details workflow runs on a form that already exists, as often as the speaker
likes. It can have more than one task, though this one has a single task.

**The task starts with the built-in `kdr:name` trait.** The creation workflow asked for the title, and the
form already has an entry for it. Listing the trait again here shows that entry, ready to change, so a speaker who
started with a working title can give the real one later. A trait can be part of any number of workflows, and each
shows the same entry.

**The save is of kind `edit`.** It updates the form rather than creating one. A core details workflow's saves are all
`edit`.

**The workflow has its own field layout, in `types`.** The workflow's form shows only the questionnaire's first question.
The layout is `authoritative`, so its list of fields is the whole list: `firstTalk`, and nothing else. The other four
questions are still part of the data. This form simply does not ask them. See [Field layouts](reference.md#field-layouts).

**The workflow runs a function after every save, in `functions`.** It reads the track and, for a workshop, gives the
talk a fact that a later workflow asks for. [Workshop logistics](workshop-logistics.md#a-computed-fact) explains it,
where it matters.

## What the speaker sees

The forms list shows the status of each talk's core details in its **Status** column. A talk's core details are
**complete** when the form has an entry for every trait the workflow's tasks require: here, `kdr:name`, `session`, and
`questionnaire`.

| Status | What it means |
| --- | --- |
| **Needs Info** | A trait the core details workflow requires has no entry yet. |
| **Invalid** | An entry's data does not pass its schema. |
| **Valid** | Every required trait has an entry, and every entry passes. |

A new talk starts as **Needs Info**, since the creation workflow collects only its name. The status is a link, and it
opens the core details workflow ready to fill in: a page titled **About your talk**. Under **Talk title** is the
**Title** field, already holding the title the talk was created with, which the speaker may change. Then come a
**Track** choice, the question **Is this your first conference talk?**, and a **Save** button. Once the speaker saves
a track and an answer, the talk becomes **Valid**, and the **Track** column shows the track.

Complete core details have one more effect. The platform marks the form with a fact, `kdr:coreDetailsComplete`, and
other workflows can test for it. The committee's review, on a later page, waits for that fact before it will start.

Next: [Abstract](abstract.md).
