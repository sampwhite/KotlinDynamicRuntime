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
        "label": "Submit a talk",
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

## What the speaker sees

The speaker sees a page titled **Submit a talk**, with one field and one button, **Create the talk**.

The field is the `name` field of the `kdr:name` trait. Its label, **Name**, and its description, *What to call it.*,
come from the trait's own schema, which every client shares.

Next: [Talk survey](talk-survey.md).
