[← Client](client.md) · [Contents](README.md#the-pages)

# Creating a talk

A **creation workflow** runs when someone starts a new form. In the demo, a speaker starts a talk submission, and the
creation workflow asks for one thing: the talk's title.

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
        "$comment": "Runs once, when a speaker starts a submission. It asks only for the title; the rest comes later.",
        "label": "Start a talk submission",
        "tasks": [
          {
            "id": "nameTalk",
            "$comment": "A creation workflow has exactly one task, with exactly one save.",
            "label": "Name your talk",
            "traits": [{ "traitId": "kdr:name", "required": true }],
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
| `entry` | How the workflow starts. A `creation` workflow runs when a form is created. The other two kinds, `survey` and `normal`, run on a form that already exists, and later pages cover them. |
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
  "traits": [{ "traitId": "kdr:name", "required": true }],
  "saves": [{ "id": "create", "label": "Create the talk", "kind": "create" }]
}
```

| Key | What it means |
| --- | --- |
| `id` | The task's id, unique within the workflow. |
| `label` | The task's name. A workflow with several tasks lists them by name. A workflow with one task, like this one, does not show it. |
| `traits` | The traits the task collects. Here there is one, `kdr:name`, the shared trait the client includes in its `base` bundle (see [Client](client.md)). Its `required: true` means the task is not complete until the form has a name. |
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

## What the speaker sees

The speaker sees a page titled **Start a talk submission**, with one section, **Talk title**, and one button,
**Create the talk**.
Under the heading is the line from the layout, with its icon, and then one field, **Title**.

The field's description, *What to call it.*, comes from the trait's own schema, which every client shares, since the
layout gives the field a label and nothing else.

Next: [Talk survey](talk-survey.md).
