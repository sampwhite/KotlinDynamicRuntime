[← Talk survey](talk-survey.md) · [Contents](README.md#the-pages)

# Abstract

A **normal workflow** is one a form enters by choice, after it exists. A speaker opens it from the forms list, and
the form is then *engaged* with the workflow until the workflow finishes. A form can be engaged with several normal
workflows at once.

The abstract is the demo's simplest normal workflow. Any talk may enter it at any time, and it asks for the abstract
itself and the four questionnaire questions the survey left out.

Three pieces of configuration go into it, and this page shows all three:

- [a shared type](#a-shared-type), `Speaker`, in the `base` bundle;
- [the abstract trait](#the-abstract-trait), also in the `base` bundle;
- [the workflow](#workflow), in the `abstract` bundle.

## A shared type

A trait's field can hold an object. The client defines the object's type once, in the `kdr:schemaDef` slot of its
`base` bundle, and the trait refers to it. Here the type is a co-speaker, with a name and an email.

<!-- kdr-doc: bundle=base pointer=/kdr:schemaDef/0 -->
```json
{
  "typeName": "client.confdemo.Speaker",
  "schema": {
    "type": "object",
    "properties": {
      "name": { "type": "string", "title": "Name", "description": "The co-speaker's name." },
      "email": { "type": "string", "title": "Email", "description": "Where to reach them." }
    },
    "required": ["name"]
  }
}
```

| Key | What it means |
| --- | --- |
| `typeName` | The type's full name: the client's namespace and the short name, `Speaker`. |
| `schema` | The type's JSON Schema. |

## The abstract trait

The `abstract` trait holds everything the program needs from the speaker beyond the title and the track. It shows
four things the earlier traits did not: a long text with bounds and messages of its own, a number, a multi-select,
and a list of objects.

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/2 -->
```json
{
  "traitId": "abstract",
  "typeName": "client.confdemo.AbstractEntry",
  "description": "What the talk is about.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "properties": {
      "summary": {
        "type": "string",
        "title": "Summary",
        "description": "The abstract the program will print.",
        "minLength": 50,
        "maxLength": 600,
        "g-outerWhitespace": "trim",
        "g-errors": {
          "missingRequired": "Every talk needs a summary.",
          "belowMinimum": "A summary is at least 50 characters; give the audience a reason to come.",
          "aboveMaximum": "Keep the summary under 600 characters; the program has limited space."
        }
      },
      "durationMinutes": {
        "type": "integer",
        "title": "Duration (minutes)",
        "description": "How long the talk runs, in minutes.",
        "minimum": 20,
        "maximum": 90
      },
      "topics": {
        "type": "array",
        "title": "Topics",
        "description": "The topics the talk covers; choose up to three.",
        "uniqueItems": true,
        "maxItems": 3,
        "items": {
          "g-options": [
            { "value": "coroutines", "label": "Coroutines" },
            { "value": "multiplatform", "label": "Multiplatform" },
            { "value": "compose", "label": "Compose" },
            { "value": "testing", "label": "Testing" },
            { "value": "tooling", "label": "Tooling" }
          ]
        }
      },
      "coSpeakers": {
        "type": "array",
        "title": "Co-speakers",
        "description": "Anyone presenting alongside the speaker.",
        "items": { "$ref": "#/$defs/client.confdemo.Speaker" }
      },
      "programNote": {
        "type": "string",
        "title": "Organizer's note",
        "description": "Scheduling notes for the organizers; the speaker's form leaves this out.",
        "g-visibleWhen": "kdr:hasAdminLevel"
      }
    },
    "required": ["summary"]
  }
}
```

**The summary has bounds, and its own messages.** It must be between 50 and 600 characters, and `g-errors` gives the
words the form shows when it is not, in place of the platform's. See
[Bounds and their messages](reference.md#bounds-and-their-messages). Its `g-outerWhitespace` trims spaces from the
ends of whatever is saved, so a summary pasted in with a stray space is stored without it. See
[Trimming and coercion](reference.md#trimming-and-coercion).

**The duration is a number.** A form shows the range as a hint, and a value typed as text, such as `45`, is saved as
the number 45.

**The topics are a multi-select.** The field is a list whose items are choices, so the form offers the five topics
and lets the speaker pick up to three, each once. See [Lists](reference.md#lists).

**The co-speakers are a list of objects.** Each item is a `Speaker`, the shared type above. The form shows a list the
speaker can add to, with a name and an email in each entry.

**The organizer's note is left off the speaker's form.** Its `g-visibleWhen` says the field is for a caller with an
administrator's level. The speaker's form does not show it; an organizer's does. See
[Who sees a field](reference.md#who-sees-a-field).

## Workflow

Here is the whole `abstract` bundle:

<!-- kdr-doc: bundle=abstract -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "abstract",
      "definition": {
        "workflowId": "abstract",
        "entry": "normal",
        "$comment": "No eligibility tests: any talk can have its abstract written at any time.",
        "label": "Write the abstract",
        "tasks": [
          {
            "id": "write",
            "label": "The abstract and the rest of the questionnaire",
            "traits": [
              { "traitId": "abstract", "required": true },
              { "traitId": "questionnaire", "required": true }
            ],
            "saves": [{ "id": "saveAbstract", "label": "Save the abstract", "kind": "edit" }]
          }
        ],
        "types": {
          "client.confdemo.QuestionnaireData": {
            "g-layout": {
              "$comment": "The four questions the survey left out.",
              "mode": "authoritative",
              "schemaFields": [
                { "field": "audienceLevel" },
                { "field": "takeaway" },
                { "field": "recordingOk" },
                { "field": "needs" }
              ]
            }
          }
        }
      }
    }
  ]
}
```

It reads as the [survey](talk-survey.md#survey) does, with two differences.

**The entry is `normal`.** The form enters the workflow when the speaker chooses to, and stays in it. A normal workflow
may list eligibility tests that a form has to pass first; this one lists none, so every talk may enter it. The next
two pages show workflows that do.

**The questionnaire layout shows the other four questions.** The survey's layout showed only `firstTalk`; this one
shows the rest. The two forms save their own parts of the same trait, and a form never writes a field it does not
show, so neither can overwrite the other's answers.

## What the speaker sees

In the forms list, a **Workflows** column lists each talk's normal workflows and where it stands with them:
**Write the abstract available** for a talk that has not entered it. The column is a link, and so is the **Workflows**
entry in the menu, which the client turns on in its `base` bundle. Both lead to the workflow on the talk.

The page is titled **Write the abstract**. Until the talk has entered the workflow, it offers one button, **Engage**.
After that it shows the form: under **Abstract**, the fields **Summary**, **Duration (minutes)**, **Topics**, and
**Co-speakers**, each with a hint such as *characters: 50 to 600*; under **Questionnaire**, the four questions. The
speaker does not see **Organizer's note**. The button is **Save the abstract**.

A summary that is too short is refused with the schema's own words: *A summary is at least 50 characters; give the
audience a reason to come.* Once the abstract is saved, the forms list says **Write the abstract finished**.

Next: [Committee review](committee-review.md).
