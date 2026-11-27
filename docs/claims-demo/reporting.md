[← Client](client.md) · [Contents](README.md#the-pages)

# Reporting an incident

A claim begins with a report of what happened. The workflow that takes it is the client's **creation workflow**, which
runs when someone starts a new form. Where the conference demo's creation workflow asks only for a title, and leaves
the rest to later workflows, this one asks for the whole first notice at once: what happened, when and where, and who
is reporting it. Somebody reporting an accident should not have to come back to finish.

Three pieces of configuration go into it:

- [two traits](#traits), in the `base` bundle, for the incident and for whoever reports it;
- [the creation workflow](#workflow), in the `reportIncident` bundle;
- [the workflow that corrects a report](#correcting-a-report), in the `incidentDetails` bundle.

A third trait, which holds the incident's number, has [a page of its own](numbering.md).

## Traits

The `incident` trait holds what happened:

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/0 -->
```json
{
  "traitId": "incident",
  "typeName": "client.claimsdemo.IncidentEntry",
  "description": "What happened, when, and where.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "title": "Incident",
    "properties": {
      "kind": {
        "type": "string",
        "title": "What kind of incident was it?",
        "description": "Choose the closest.",
        "g-options": [
          { "value": "vehicle", "label": "Vehicle" },
          { "value": "property", "label": "Property" },
          { "value": "injury", "label": "Injury" },
          { "value": "other", "label": "Something else" }
        ]
      },
      "occurredAt": {
        "type": "string",
        "format": "date-time",
        "title": "When did it happen?",
        "description": "If you are not sure, give the time you think it was."
      },
      "place": {
        "type": "string",
        "title": "Where did it happen?",
        "description": "An address, or the nearest crossing.",
        "g-outerWhitespace": "trim"
      },
      "whatHappened": {
        "type": "string",
        "title": "What happened?",
        "description": "In your own words.",
        "g-longText": true,
        "minLength": 20,
        "maxLength": 4000,
        "g-outerWhitespace": "trim",
        "g-errors": {
          "belowMinimum": "Say a little more: an adjuster needs at least ${min} characters to go on."
        }
      }
    },
    "required": ["kind", "occurredAt", "place", "whatHappened"]
  }
}
```

The keys of a trait, and the schema's `g-options`, are explained on the conference demo's [core details
page](../demo-client/talk-core-details.md#traits). Three things here are worth a word.

**The questions are the titles, and the help is the descriptions.** A form shows a field's `title` as its label and its
`description` beneath, so a field titled as a question can use its description to say how to answer it.

**A moment, and not only a day.** The format `date-time` gives the field a picker for a day and a time. The reporter
picks the time in the client's time zone, which the form names beside the field, and what is stored is the moment
itself.

**A message of its own.** The `whatHappened` field takes at least 20 characters, and its `g-errors` says why to a
reporter who types fewer. See [Bounds and their messages](../demo-client/reference.md#bounds-and-their-messages).

The `reporter` trait holds who is reporting it:

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/1 -->
```json
{
  "traitId": "reporter",
  "typeName": "client.claimsdemo.ReporterEntry",
  "description": "Who reports the incident.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "title": "Reported by",
    "properties": {
      "name": {
        "type": "string",
        "title": "Your name",
        "description": "The name an adjuster asks for.",
        "g-outerWhitespace": "trim"
      },
      "involvement": {
        "type": "string",
        "title": "How were you involved?",
        "description": "Your part in it.",
        "g-options": [
          { "value": "insured", "label": "It happened to me, or to something of mine" },
          { "value": "witness", "label": "I saw it happen" },
          { "value": "representative", "label": "I am reporting it for someone else" }
        ]
      }
    },
    "required": ["name", "involvement"]
  }
}
```

It is a trait of its own because the person who reports an incident is not always the one it happened to.

## Workflow

Here is the whole `reportIncident` bundle:

<!-- kdr-doc: bundle=reportIncident -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "reportIncident",
      "definition": {
        "workflowId": "reportIncident",
        "entry": "creation",
        "$comment": "The first notice of an incident. It also collects the incident's number, which the server fills as the report is saved.",
        "label": "Report an incident",
        "tasks": [
          {
            "id": "describe",
            "label": "What happened",
            "traits": [
              { "traitId": "incident", "required": true },
              { "traitId": "reporter", "required": true },
              { "traitId": "filing", "required": false }
            ],
            "saves": [{ "id": "file", "label": "File the report", "kind": "create" }],
            "functions": [
              {
                "fn": "prefillFromOwner",
                "userAttribute": "name",
                "targetTrait": "reporter",
                "targetValuePath": "name"
              }
            ]
          }
        ]
      }
    }
  ]
}
```

The keys are explained on the conference demo's [Creating a talk](../demo-client/create-talk.md#workflow). What is new
is the task's function.

**A name that is filled in already.** The `prefillFromOwner` function offers a value for a field from the account of
whoever will own the form: here the `name` on their profile, for the `name` field of the `reporter` trait. The form
marks the field **Suggested**, and the reporter can change it, as they would to report an incident for someone else. An
account with no name leaves the field empty.

**The number is collected, and not required.** The third trait, `filing`, holds the incident's number, which the server
fills as the report is saved. [Numbering incidents](numbering.md) explains why its `required` is `false`.

## Correcting a report

The client's **core details workflow** runs on a claim that already exists, and collects the same two traits again,
after the one that holds the incident's number:

<!-- kdr-doc: bundle=incidentDetails -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "incidentDetails",
      "definition": {
        "workflowId": "incidentDetails",
        "entry": "coreDetails",
        "$comment": "What every claim needs. The report asks for all of it, so a claim is complete from the start, unless a program made it without. Its number comes first, so a claim's own page opens with it.",
        "label": "Incident details",
        "tasks": [
          {
            "id": "revise",
            "label": "What happened",
            "traits": [
              { "traitId": "filing", "required": false },
              { "traitId": "incident", "required": true },
              { "traitId": "reporter", "required": true }
            ],
            "saves": [{ "id": "saveDetails", "label": "Save", "kind": "edit" }]
          }
        ]
      }
    }
  ]
}
```

It does two jobs. It is the claim's own page, where a policyholder reads what they reported, and corrects it. And it is
what makes a claim **complete**: a claim has the fact `kdr:coreDetailsComplete` once every trait the workflow requires
has an entry. A claim made by the report form always does, so it is complete from the start, and the forms list shows
it as **Valid**. A program could create a claim without the report, and such a claim could not be assessed until
somebody filled it in ([Settlement](settlement.md#workflow) asks for the fact).

## What the policyholder sees

The page is titled **Report an incident**, with two sections, **Incident** and **Reported by**, and one button, **File
the report**. The **Your name** field holds the name from their profile, marked **Suggested**. There is no section for
the number, since there is none yet to show.

Pressing **File the report** saves the claim, and the forms list opens, saying that the claim was created and naming it
by its number, as [Numbering incidents](numbering.md#what-the-policyholder-sees) shows. The claim's status there is
**Valid**, and its **Workflows** column offers **Start Evidence**. The settlement is an adjuster's to start, so it is
shown muted, with *Waiting on someone else* under it.

Next: [Numbering incidents](numbering.md).
