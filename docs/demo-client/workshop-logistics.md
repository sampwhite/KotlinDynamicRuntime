[← Committee review](committee-review.md) · [Contents](README.md#the-pages)

# Workshop logistics

Workshop logistics is a normal workflow for one kind of talk only: a talk on the workshop track. Its form shows how a
workflow can take over the presentation of a trait, relabeling its fields, narrowing a choice list, and pulling a
heading from a file, without changing the trait itself.

Three pieces of configuration go into it, and this page shows all three:

- [the fact that says a talk is a workshop](#a-computed-fact), which the survey computes;
- [the logistics trait](#the-logistics-trait), in the `base` bundle;
- [the workflow](#workflow), in the `logistics` bundle.

The facts and the trait are the last additions to the `base` bundle. The [client page](client.md#what-the-bundle-holds)
lists everything it holds.

## A computed fact

The client declares two facts of its own in the `kdr:cfactDef` slot of its `base` bundle. The review's
`talkAccepted` was the first; `isWorkshop` is the other.

<!-- kdr-doc: bundle=base pointer=/kdr:cfactDef -->
```json
[
  {
    "name": "isWorkshop",
    "group": "confDemo",
    "description": "The talk's track is the workshop track; computed by the survey from the session.",
    "toFrontend": false
  },
  {
    "name": "talkAccepted",
    "group": "confDemo",
    "description": "True once the committee has accepted the talk.",
    "toFrontend": false
  }
]
```

| Key | What it means |
| --- | --- |
| `name` | The fact's name, as a test or a workflow refers to it. |
| `group` | A heading the facts are listed under, for people. |
| `description` | What the fact means. |
| `toFrontend` | Whether the fact is sent to the browser, where a form could show or hide a field by it. Neither of these is. |

A declaration says only that the fact exists. Something has to set it. For `isWorkshop` that is the
[survey](talk-survey.md), not this workflow: a talk's eligibility is judged before it enters a workflow, so the fact
has to come from something that has already run, and the survey is where the track is answered. The survey runs a
function after every save:

<!-- kdr-doc: bundle=talkSurvey pointer=/kdr:workflowDef/0/definition/functions/0 -->
```json
{
  "fn": "computeCFactsFromData",
  "trait": "session",
  "op": "isInList",
  "valuePath": "track",
  "valueMapping": [{ "value": "workshop", "cfact": "isWorkshop" }]
}
```

The function reads the `track` field of the talk's `session` entry, named by `trait` and `valuePath`. Its `op`,
`isInList`, means the value is compared against the list in `valueMapping`: when the value is `workshop`, the talk has
the fact `isWorkshop`; otherwise it does not. See [Facts and eligibility](reference.md#facts-and-eligibility).

## The logistics trait

The `logistics` trait asks what a workshop needs on the day: a room, whether attendees bring laptops, and, if they
do, what to install.

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/4 -->
```json
{
  "traitId": "logistics",
  "typeName": "client.confdemo.LogisticsEntry",
  "description": "What a workshop needs on the day.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "properties": {
      "room": {
        "type": "string",
        "title": "Room",
        "description": "The room the session is in.",
        "g-options": [
          { "value": "hall", "label": "Main hall" },
          { "value": "track1", "label": "Track room 1" },
          { "value": "track2", "label": "Track room 2" },
          { "value": "labA", "label": "Lab A" },
          { "value": "labB", "label": "Lab B" }
        ]
      },
      "needsLaptops": {
        "type": "boolean",
        "title": "Do attendees need their own laptop?",
        "description": "Whether attendees bring a laptop."
      },
      "setup": {
        "type": "string",
        "title": "What should they install beforehand?",
        "description": "What attendees install before they arrive."
      }
    },
    "if": {
      "required": ["needsLaptops"],
      "properties": { "needsLaptops": { "const": true } }
    },
    "then": { "required": ["setup"] },
    "else": { "not": { "required": ["setup"] } }
  }
}
```

**One answer changes what the form demands.** The `if`, `then`, and `else` keys are standard JSON Schema. Together
they say: when `needsLaptops` is `true`, `setup` is required; otherwise it may not be given at all. The form shows the
setup field only once the laptop question is answered yes, and a save without it is refused. This is the one shape of
condition the platform supports: a field required or forbidden by another field's value.

The room list offers every room the conference has. The workflow narrows it.

## Workflow

Here is the whole `logistics` bundle:

<!-- kdr-doc: bundle=logistics -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "logistics",
      "definition": {
        "workflowId": "logistics",
        "entry": "normal",
        "$comment": "For workshops only: the eligibility test asks for the fact the survey computes from the track.",
        "label": "Workshop logistics",
        "eligibility": [
          {
            "id": "workshopOnly",
            "test": "isWorkshop",
            "explanation": "Only a talk on the workshop track has logistics to plan."
          }
        ],
        "tasks": [
          {
            "id": "plan",
            "label": "Plan the workshop",
            "traits": [{ "traitId": "logistics", "required": true }],
            "saves": [{ "id": "savePlan", "label": "Save the plan", "kind": "edit" }]
          }
        ],
        "types": {
          "client.confdemo.LogisticsData": {
            "g-layout": {
              "$comment": "Authoritative: the labels and descriptions here replace the trait's, and the room list is narrowed to the labs.",
              "fragmentFileId": "confDemo",
              "label": "%{@t(\"logistics.heading\")}",
              "mode": "authoritative",
              "schemaFields": [
                {
                  "field": "room",
                  "label": "Lab",
                  "description": "%{@t(\"logistics.roomHelp\")}",
                  "required": true,
                  "choices": [
                    { "value": "labA", "label": "Lab A (24 seats)" },
                    { "value": "labB", "label": "Lab B (40 seats)" }
                  ]
                },
                { "field": "needsLaptops", "label": "Laptops", "description": "Say yes if attendees work on their own machines." },
                { "field": "setup", "label": "Setup instructions", "description": "Sent to attendees a week before." }
              ]
            }
          }
        }
      }
    }
  ]
}
```

**The eligibility test asks for the computed fact.** A talk has `isWorkshop` or it does not, and only one that does
may enter. The forms list shows the `explanation` to every other talk.

**The layout takes over.** Its `mode` is `authoritative`, so the form shows exactly the fields it lists, in its order,
with the `label` and `description` it gives each one in place of the trait's `title` and `description`. See
[Field layouts](reference.md#field-layouts).

**The layout narrows the room list.** Its entry for `room` restates `choices`: the two labs, with labels that say how
many seats each has. The form offers those two and no other, and a save naming the main hall is refused, though the
trait's schema would allow it. The entry also marks the room `required` for this form, which the schema does not.

**The heading and one description come from a file.** The block's `label` is `%{@t("logistics.heading")}`, and the
room's description is a pull of the same kind. Both take their words from the `confDemo` fragment file, which the
`fragmentFileId` names and which the platform ships for the demo. See [Copy from a file](reference.md#copy-from-a-file).

## What the speaker sees

In the forms list, a workshop talk shows **Workshop logistics available**. Any other talk shows *Only a talk on the
workshop track has logistics to plan.* in its place.

Once the speaker engages the workflow, the page is titled **Workshop logistics**, and so is the heading over the
fields, from the fragment file. The fields are **Lab**, a choice between *Lab A (24 seats)* and *Lab B (40 seats)*,
with the file's line under it, and **Laptops**. **Setup instructions** appears only once Laptops is answered yes. The
button is **Save the plan**.

Next: [Reports](reports.md).
