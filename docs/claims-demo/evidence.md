[← Numbering incidents](numbering.md) · [Contents](README.md#the-pages)

# Evidence

A claim often needs something to show what happened: photographs of the damage, a police report, an estimate. The demo
collects them in a **normal workflow** of its own, since they seldom exist at the moment the incident is reported.

Two pieces of configuration go into it:

- [the evidence trait](#trait), in the `base` bundle, whose fields hold files;
- [the workflow](#workflow), in the `evidence` bundle.

## Trait

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/3 -->
```json
{
  "traitId": "evidence",
  "typeName": "client.claimsdemo.EvidenceEntry",
  "description": "What shows what happened.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "title": "Evidence",
    "properties": {
      "photos": {
        "type": "array",
        "title": "Photographs",
        "description": "Of the damage, or of the place.",
        "maxItems": 4,
        "items": {
          "type": "string",
          "format": "gedra-file",
          "g-fileKinds": ["png", "jpeg", "webp"],
          "g-fileMaxSize": 5242880
        }
      },
      "document": {
        "type": "string",
        "format": "gedra-file",
        "title": "Document",
        "description": "A police report, an estimate, or a receipt.",
        "g-fileKinds": ["pdf"]
      },
      "notes": {
        "type": "string",
        "title": "What do these show?",
        "description": "For whoever assesses the claim.",
        "g-longText": true
      }
    }
  }
}
```

Two of its fields hold files, and a file field is a string with the format `gedra-file`. What is stored in the field is
the id of the file, which the app keeps for the client.

| Key | What it means |
| --- | --- |
| `format` | The value `gedra-file` makes the field hold a file. |
| `g-fileKinds` | The kinds of file it takes. A file's kind is told by its contents, so something else renamed `photo.png` is refused. |
| `g-fileMaxSize` | The largest file it takes, in bytes. Left out, as on `document`, it is the largest the app takes of that kind. |

**A list of files.** The `photos` field is an array whose `items` is a file field, so it holds up to four photographs
(`maxItems`). Each one is uploaded, shown, and removed on its own.

**Nothing is required.** Some claims have photographs, some have a document, and some have only a note. The trait
leaves it to whoever assesses the claim to say whether there is enough.

## Workflow

Here is the whole `evidence` bundle:

<!-- kdr-doc: bundle=evidence -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "evidence",
      "definition": {
        "workflowId": "evidence",
        "entry": "normal",
        "$comment": "No eligibility tests: evidence can be added to any claim, at any time.",
        "label": "Evidence",
        "tasks": [
          {
            "id": "attach",
            "label": "Add the evidence",
            "traits": [{ "traitId": "evidence", "required": true }],
            "saves": [{ "id": "saveEvidence", "label": "Save the evidence", "kind": "edit" }]
          }
        ]
      }
    }
  ]
}
```

It has one task and no eligibility tests, so evidence can be added to any claim, and added to later. It has no rule
about who may save it either, so it is the policyholder's step, and an adjuster can add to it too, such as with their
own photographs of the damage.

## What the policyholder sees

In the forms list, the claim's **Workflows** column offers **Start Evidence**, which opens the workflow, and the
**Start** button there begins it. The page has one section, **Evidence**, with **Photographs**, **Document**, and
**What do these show?**, and one button, **Save the evidence**. Choosing **+ Add** under **Photographs** makes room for
one. Each file is uploaded as it is chosen, and the form says which kinds it takes and how large. A photograph is shown
on the form, and every file's name is a link that opens it.

Next: [Settlement](settlement.md).
