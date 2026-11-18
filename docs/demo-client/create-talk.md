[← Client](client.md) · [Contents](README.md#the-pages)

# Creating a talk

A **creation workflow** runs when someone starts a new form. In the demo, a speaker starts a talk submission, and the
creation workflow asks for one thing: the talk's title. It also shows the talk's id, such as `T-007`, which the
conference gives every talk. The page explains the workflow first, then how the demo [names the platform's title
trait](#the-traits-name), and then [where the id comes from](#the-talks-id).

Here is the whole `createTalk` bundle:

<!-- kdr-doc: bundle=createTalk -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "createTalk",
      "definition": {
        "workflowId": "createTalk",
        "entry": "creation",
        "$comment": "Runs once, when a speaker starts a submission. It asks for the title, and shows the talk's id; the rest comes later.",
        "label": "Start a talk submission",
        "tasks": [
          {
            "id": "nameTalk",
            "$comment": "A creation workflow has exactly one task, with exactly one save.",
            "label": "Name your talk",
            "traits": [{ "traitId": "kdr:name", "required": true }, { "traitId": "submission", "required": false }],
            "saves": [{ "id": "create", "label": "Create the talk", "kind": "create" }]
          }
        ]
      }
    }
  ]
}
```

The bundle has one slot, `kdr:workflowDef`, with one entry. The entry has the workflow's id in `workflowId`, and the
workflow itself in `definition`.

## Workflow

| Key | What it means |
| --- | --- |
| `workflowId` | The workflow's id. It is unique within the client, and the entry repeats it. |
| `entry` | How the workflow starts. A `creation` workflow runs when a form is created. The other two kinds, `coreDetails` and `normal`, run on a form that already exists, and later pages cover them. |
| `$comment` | A note for whoever maintains the workflow. See [Comments](reference.md#comments). |
| `label` | The page's title, above the form. |
| `tasks` | The workflow's steps, in order. |

## Task

A **task** is one step of a workflow: the traits it collects, and the buttons that save them.

<!-- kdr-doc: bundle=createTalk pointer=/kdr:workflowDef/0/definition/tasks/0 -->
```json
{
  "id": "nameTalk",
  "$comment": "A creation workflow has exactly one task, with exactly one save.",
  "label": "Name your talk",
  "traits": [{ "traitId": "kdr:name", "required": true }, { "traitId": "submission", "required": false }],
  "saves": [{ "id": "create", "label": "Create the talk", "kind": "create" }]
}
```

| Key | What it means |
| --- | --- |
| `id` | The task's id, unique within the workflow. |
| `label` | The task's name. A workflow with several tasks lists them by name. A workflow with one task, like this one, does not show it. |
| `traits` | The traits the task collects. Here there are two. The first, `kdr:name`, is the shared trait the client includes in its `base` bundle (see [Client](client.md)), and its `required: true` means the task is not complete until the form has a name. The second, `submission`, holds the talk's id (see [The talk's id](#the-talks-id)). |
| `saves` | The task's buttons. Each has a `label`, the button's text, and a `kind`, what the button does. A save of kind `create` creates the form. |

A creation workflow has exactly one task, and that task has exactly one save, of kind `create`. A definition that
breaks this rule is refused when it is written.

## The trait's name

The `kdr:name` trait belongs to the platform, which calls it **Name**, and so does every client that takes it as it
is. The demo calls it **Talk title** instead, with a line under the name saying what it is for, and calls its one field
**Title**. It does this with an entry in the `kdr:schemaDef` slot of its `base` bundle:

<!-- kdr-doc: bundle=base pointer=/kdr:schemaDef/1 -->
```json
{
  "typeName": "kdr.core.NameData",
  "schema": {
    "g-layout": {
      "$comment": "The platform's name trait, as the demo's forms show it.",
      "label": "Talk title",
      "description": "![A presentation screen](img:kdr:talkIcon){.float-left .icon width=20}The title the conference program prints, and the name the committee knows your talk by.",
      "schemaFields": [{ "field": "name", "label": "Title" }]
    }
  }
}
```

The entry's `typeName` is the trait's data type: the platform's namespace, `kdr.core`, then `NameData`. An entry that
names one of the platform's types changes it for this client alone, and this one changes only its field layout,
`g-layout`, so how its forms show it. The trait's data stays exactly as every client has it. (The slot also holds the
client's own types, as [Abstract](abstract.md#a-shared-type) shows.)

| Key | What it means |
| --- | --- |
| `label` | The trait's name: the heading of its section on a form, and how the forms list's **Contains** column and the workflow editor name it. |
| `description` | A line under the heading, in Markdown. This one opens with an icon, explained in [Images](reference.md#images). |
| `schemaFields` | The layout's entries for the trait's fields. This one gives the `name` field the label **Title**. A field the list leaves out is shown as the schema has it. |

See [Field layouts](reference.md#field-layouts) for the rest of what a layout can say. On the Clients page, a trait's
**Adapt…** edits this same entry: its **Name** and **Introduction** are the `label` and the `description`.

## The talk's id

The demo numbers its talks, so that the committee and the speaker can name a talk by a short id. Two pieces of
configuration in the `base` bundle do it: a **counter**, which gives out the ids, and a trait with a field that takes its
ids from the counter.

The counter is the one entry in the bundle's `kdr:counterDef` slot:

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
| `prefix` | The text before the count. It is a template, so it can include the day an id is given, such as `R${compactDay}-` for `R20261117-`. |
| `digits` | How many digits the count is padded to with zeroes, from 2 to 8. A count that outgrows them takes more, so after `T-999` comes `T-1000`. |
| `reset` | When the count starts again at 1: `never`, or `daily`, on each of the client's days. A daily counter needs the day in its prefix, or its ids would repeat. |
| `description` | What the counter numbers. |

The demo's counter goes on for the whole call for talks, so its ids read `T-001`, `T-002`, and so on, and they are short
enough for a phone's screen.

The trait is the last of the bundle's traits:

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/5 -->
```json
{
  "description": "The talk's id, given when it is submitted.",
  "traitId": "submission",
  "typeName": "client.confdemo.SubmissionEntry",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "title": "Submission",
    "properties": {
      "talkId": {
        "description": "The talk's id, given by the conference's counter.",
        "title": "Talk id",
        "g-counter": "talk",
        "type": "string"
      }
    },
    "required": ["talkId"],
    "type": "object"
  }
}
```

Its one field, `talkId`, names the counter with `g-counter`, one of the platform's additions to JSON Schema (see the
[schema dialect reference](../reference/schema-dialect.md)). A field like this is never typed. When the form opens, it
asks the counter for the next id and shows it, so the speaker sees the talk's id before saving it. The id is the talk's
for good once it is saved: it cannot be changed. A form that is opened and never saved leaves its id unused, so the count
can skip a number.

The task does not require the trait, since the form always fills it. That also means a program creating a talk through
the API does not have to ask the counter for an id first. A talk created that way has no id.

[Talk core details](talk-core-details.md#columns-in-the-forms-list) shows the id as a column of the forms list.

## What the speaker sees

The speaker sees a page titled **Start a talk submission**, with two sections, **Talk title** and **Submission**, and one
button, **Create the talk**.
Under the first heading is the line from the layout, with its icon, and then one field, **Title**. The second section
shows **Talk id**, the talk's id, such as **T-007**, which the speaker cannot change.

The field's description, *What to call it.*, comes from the trait's own schema, which every client shares, since the
layout gives the field a label and nothing else.

Next: [Talk core details](talk-core-details.md).
