[← Creating a talk](create-talk.md)

# The talk survey

A **survey** collects what every form needs, after the form exists. A client has at most one survey. In the demo, the
survey asks each speaker for the talk's track and one background question.

A survey is also what the forms list reports on. Each form shows a status that says whether its survey is done, and the
speaker can open the survey from there.

This page covers three pieces of configuration:

- [the two traits the survey collects](#the-traits), from the `base` bundle;
- [the listing columns](#the-listing-columns) that show a form's name and track, also from the `base` bundle;
- [the survey itself](#the-survey), in the `talkSurvey` bundle.

## The traits

A survey collects traits. The client defines two of its own, `session` and `questionnaire`, in the `kdr:traitDef`
slot of its `base` bundle.

### The session

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

| Field | What it means |
| --- | --- |
| `traitId` | The trait's id. A form's entry for the trait is filed under it, and a workflow's task names it. |
| `typeName` | The name of the trait's entry type: the client's namespace, `client.confdemo`, and the type's own name. |
| `description` | A note on what the trait holds. |
| `appliesTo` | What can carry the trait. The value `formDoc` means a form. |
| `dataSchema` | The JSON Schema for the trait's data. |

The schema is standard JSON Schema with a few additions. The track is a string, and `g-options` limits it to four
choices; see [Choices](README.md#choices). Because `required` names it, a `session` entry is not valid without a track.

### The questionnaire

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

No question is required by the schema. Each workflow that asks a question decides whether its own form requires an
answer, so the data stays valid while the answers arrive in stages.

## The listing columns

The forms list shows one row per form. The client chooses the row's columns in the `kdr:usageDef` slot of its `base`
bundle.

<!-- kdr-doc: bundle=base pointer=/kdr:usageDef -->
```json
[
  { "traitId": "kdr:name", "label": "Talk", "display": "${name}", "kind": "string", "substring": true },
  { "traitId": "session", "label": "Track", "display": "${track}", "kind": "string", "substring": false }
]
```

| Field | What it means |
| --- | --- |
| `traitId` | The trait the column reads. |
| `label` | The column's heading. |
| `display` | What the column shows: a template over the trait's data, in which `${track}` is the `track` field's value. |
| `kind` | How the value is read when the list is searched. |
| `substring` | Whether a search may match part of the value. On the talk's name, a search for `scale` finds `Kotlin at scale`. |

A client that declares any columns replaces the platform's default, which is a single column for the form's name. That
is why the demo declares the name column too, under its own heading, **Talk**.

The track column shows the stored value, such as `workshop`, rather than the choice's label.

## The survey

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

Most of it reads as the creation workflow does; see [Creating a talk](create-talk.md#the-workflow). Three things
are new.

**The entry is `survey`.** A survey runs on a form that already exists, as often as the speaker likes. It can have more
than one task, though this one has a single task.

**The save is of kind `edit`.** It updates the form rather than creating one. A survey's saves are all `edit`.

**The workflow has its own field layout, in `types`.** The survey's form shows only the questionnaire's first question.
The layout is `authoritative`, so its list of fields is the whole list: `firstTalk`, and nothing else. The other four
questions stay in the data, unasked here. See [Field layouts](README.md#field-layouts).

## When a survey is complete

A survey is **complete** when the form has an entry for every trait its tasks require: here, `session` and
`questionnaire`. The forms list shows the form's survey status in its **Status** column:

| Status | What it means |
| --- | --- |
| **Needs Info** | A trait the survey requires has no entry yet. |
| **Invalid** | An entry's data does not pass its schema. |
| **Valid** | Every required trait has an entry, and every entry passes. |

A new talk starts as **Needs Info**, since the creation workflow collects only its name. The status is a link that
opens the survey, ready to fill in. Once the speaker saves a track and an answer, the talk becomes **Valid** and the
**Track** column shows the track.

A complete survey also gives the form a fact, `kdr:surveyComplete`, that other workflows can test. The committee's
review, on a later page, waits for it.
