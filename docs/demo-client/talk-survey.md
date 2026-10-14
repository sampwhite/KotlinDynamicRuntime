[← Creating a talk](create-talk.md) · [Contents](README.md#the-pages)

# Talk survey

A **survey** collects what every form needs, after the form exists. A client has at most one survey. In the demo, the
survey asks each speaker for the talk's track and one background question.

Adding a survey takes three pieces of configuration, and this page shows all three:

- [two traits](#traits) for the survey to collect, in the `base` bundle;
- [two columns](#columns-in-the-forms-list) for the forms list, also in the `base` bundle;
- [the survey itself](#survey), in the `talkSurvey` bundle.

## Traits

Before the survey can collect anything, the client needs traits for it to collect. It defines two of its own,
`session` and `questionnaire`, and keeps them in the `kdr:traitDef` slot of the `base` bundle.

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

The `questionnaire` trait holds five questions that every speaker answers. The survey asks the first; the abstract
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
        "description": "What the audience leaves knowing."
      },
      "recordingOk": {
        "type": "boolean",
        "title": "May we record the talk?",
        "description": "Whether the talk may be recorded and published."
      },
      "needs": {
        "type": "string",
        "title": "Anything we should know for the day?",
        "description": "Anything the organizers should know for the day."
      }
    }
  }
}
```

The schema requires none of the questions, so the data stays valid as the answers arrive in stages. Each workflow
that asks questions from the trait can choose which questions to show and which ones its form requires. A requirement
the workflow adds applies to its form only. The trait's schema does not change.

## Columns in the forms list

The forms list as it appears in the application shows one row per form. Some columns in the forms list
are specified by the application, but the leading columns can be configured. The client chooses the configurable columns
in the `kdr:usageDef` slot of its `base` bundle.

<!-- kdr-doc: bundle=base pointer=/kdr:usageDef -->
```json
[
  { "traitId": "kdr:name", "label": "Talk", "display": "${name}", "kind": "string", "substring": true },
  { "traitId": "session", "label": "Track", "display": "${track}", "kind": "string", "substring": false }
]
```

| Key | What it means |
| --- | --- |
| `traitId` | The trait the column takes its value from. |
| `label` | The column's heading. |
| `display` | What the column shows: a template over the trait's data, in which `${track}` is the `track` field's value. |
| `kind` | How a search treats the value: as text, a number, or a date. |
| `substring` | Whether a search may match part of the value. On the talk's name, a search for `scale` finds `Kotlin at scale`. |

In this example, the demo promotes two fields to be columns: `name`, from the built-in `kdr:name` trait, and `track`,
from its own `session` trait. A promoted value also becomes available for search.
As soon as a client declares columns of its own, the platform's default column, the form's name, disappears. So the
demo redeclares the name column too, but relabels the heading to be **Talk**.

The track column shows the stored value, such as `workshop`, rather than the choice's label.

## Survey

Here is the whole `talkSurvey` bundle:

<!-- kdr-doc: bundle=talkSurvey -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "talkSurvey",
      "definition": {
        "workflowId": "talkSurvey",
        "entry": "survey",
        "$comment": "The form's survey: what every talk needs before it is reviewed.",
        "label": "About your talk",
        "tasks": [
          {
            "id": "describe",
            "label": "Track and background",
            "traits": [
              { "traitId": "session", "required": true },
              { "traitId": "questionnaire", "required": true }
            ],
            "saves": [{ "id": "saveAbout", "label": "Save", "kind": "edit" }]
          }
        ],
        "types": {
          "client.confdemo.QuestionnaireData": {
            "g-layout": {
              "$comment": "The survey asks one of the five questions. The abstract workflow asks the rest.",
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

Most of it reads as the creation workflow does (see [Creating a talk](create-talk.md#workflow)). Three things are
new.

**The entry is `survey`.** A survey runs on a form that already exists, as often as the speaker likes. It can have more
than one task, though this one has a single task.

**The save is of kind `edit`.** It updates the form rather than creating one. A survey's saves are all `edit`.

**The workflow has its own field layout, in `types`.** The survey's form shows only the questionnaire's first question.
The layout is `authoritative`, so its list of fields is the whole list: `firstTalk`, and nothing else. The other four
questions are still part of the data. This form simply does not ask them. See [Field layouts](reference.md#field-layouts).

## What the speaker sees

The forms list shows each talk's survey status in its **Status** column. A survey is **complete** when the form has an
entry for every trait its tasks require: here, `session` and `questionnaire`.

| Status | What it means |
| --- | --- |
| **Needs Info** | A trait the survey requires has no entry yet. |
| **Invalid** | An entry's data does not pass its schema. |
| **Valid** | Every required trait has an entry, and every entry passes. |

A new talk starts as **Needs Info**, since the creation workflow collects only its name. The status is a link, and it
opens the survey ready to fill in: a page titled **About your talk**, with a **Track** choice, the question **Is this
your first conference talk?**, and a **Save** button. Once the speaker saves a track and an answer, the talk becomes
**Valid**, and the **Track** column shows the track.

A complete survey has one more effect. The platform marks the form with a fact, `kdr:surveyComplete`, and other
workflows can test for it. The committee's review, on a later page, waits for that fact before it will start.

Next: more pages are on the way. Back to [Contents](README.md#the-pages).
