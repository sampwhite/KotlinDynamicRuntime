[← Client](client.md) · [Contents](README.md#the-pages)

# Creating a talk

A **creation workflow** runs when someone starts a new form. In the demo, a speaker starts a talk submission, and the
creation workflow asks for one thing: the talk's title. It also collects the talk's id, which the form fills in by
itself, and [Numbering talks](numbering.md) explains it.

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
        "$comment": "Runs once, when a speaker starts a submission. It asks for the title, and collects the talk's id, which the form fills from the counter; the rest comes later.",
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
| `traits` | The traits the task collects. Here there are two. The first, `kdr:name`, is the shared trait the client includes in its `base` bundle (see [Client](client.md)), and its `required: true` means the task is not complete until the form has a name. The second, `submission`, holds the talk's id, and is not required, since the form fills it by itself (see [Numbering talks](numbering.md)). |
| `saves` | The task's buttons. Each has a `label`, the button's text, and a `kind`, what the button does. A save of kind `create` creates the form. |

A creation workflow has exactly one task, and that task has exactly one save, of kind `create`. A definition that
breaks this rule is refused when it is written.

## The trait's name

The `kdr:name` trait belongs to the platform, which calls it **Name**, and so does every client that takes it as it
is. The demo calls it **Talk title** instead, with a line under the name saying what it is for, and calls its one field
**Title**. It also keeps a speaker from using one title for two talks. It does this with an entry in the
`kdr:schemaDef` slot of its `base` bundle:

<!-- kdr-doc: bundle=base pointer=/kdr:schemaDef/1 -->
```json
{
  "typeName": "kdr.core.NameData",
  "schema": {
    "g-merge": { "properties": "merge" },
    "properties": {
      "name": { "type": "string", "description": "What to call it.", "maxLength": 128, "g-unique": "owner" }
    },
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
names one of the platform's types changes it for this client alone. This one changes two things: the field layout,
`g-layout`, which is how its forms show the trait, and the rule for its one field. (The slot also holds the client's own
types, as [Abstract](abstract.md#a-shared-type) shows.)

The layout has three keys:

| Key | What it means |
| --- | --- |
| `label` | The trait's name: the heading of its section on a form, and how the forms list's **Contains** column and the workflow editor name it. |
| `description` | A line under the heading, in Markdown. This one opens with an icon, explained in [Images](reference.md#images). |
| `schemaFields` | The layout's entries for the trait's fields. This one gives the `name` field the label **Title**. A field the list leaves out is shown as the schema has it. |

See [Field layouts](reference.md#field-layouts) for the rest of what a layout can say.

**A title is unique for each speaker.** The `name` field has `"g-unique": "owner"`, one of the platform's additions to
JSON Schema (see the [schema dialect reference](../reference/schema-dialect.md)). No two forms of one owner may have the
same value in it, and an owner is the person a form belongs to, which here is the speaker. Two speakers may use the same
title. Titles are compared without regard to case or punctuation, so "Kotlin for Two" and "kotlin for two!" are the same
title. A title needs at least one letter or digit, since one made only of punctuation would have nothing to compare.

A client may only make one of the platform's types stricter, so it may add `g-unique` to a field, and never take one away.
The `g-merge` key says that the field listed under `properties` replaces the platform's field of the same name, and that
the type keeps its other fields. A field is written out in full, so the rest of the `name` field is what the platform
declares.

A talk's title and its id (see [Numbering talks](numbering.md)) are both different for every talk of a speaker's, in
different ways. The counter chooses the id, and it never changes. The speaker chooses the title, and may change it, which
frees the old one to be used again. Deleting a talk frees its title too.

On the Clients page, a trait's **Adapt…** edits this same entry: its **Name** and **Introduction** are the `label` and the
`description`, and its **Unique** column is `g-unique`.

## What the speaker sees

The speaker sees a page titled **Start a talk submission**, with two sections, **Talk title** and **Talk id**, and one
button, **Create the talk**.
Under the first heading is the line from the layout, with its icon, and then one field, **Title**. The second section
shows the talk's id, which the speaker cannot change (see [Numbering talks](numbering.md#what-the-speaker-sees)).

The field's description, *What to call it.*, comes from the trait's own schema, which every client shares, since the
layout gives the field a label and nothing else.

A speaker who types a title one of their other talks already has is told so as they leave the field, in a message under
it: *Title 'kotlin for two!' is already used by another of your forms, as 'Kotlin for Two'. Choose a different one.* The
message goes as soon as they change the title. If they press **Create the talk** without changing it, the talk is not
created, and a message under the button says why.

Next: [Talk core details](talk-core-details.md).
