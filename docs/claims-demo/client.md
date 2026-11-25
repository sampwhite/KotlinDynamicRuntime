[← The claims demo](README.md) · [Contents](README.md#the-pages)

# Client

The `base` bundle's `kdr:clientDef` slot holds one entry, which says who the client is. The conference demo's [client
page](../demo-client/client.md) explains each of its keys.

<!-- kdr-doc: bundle=base pointer=/kdr:clientDef/0 -->
```json
{
  "clientId": "claimsdemo",
  "name": "Claims demo",
  "description": "A small insurer's claims: policyholders report incidents, each numbered as it is filed, adjusters assess them, and a claims manager approves the larger payments.",
  "usageType": "demo",
  "audience": "internal",
  "enabledEnvironments": ["unit", "local", "dev"],
  "preload": false,
  "staticConfig": false,
  "includedTraits": [],
  "userLabels": ["adjuster"],
  "userLabelNotes": { "adjuster": "Give this to the people who assess claims." },
  "joinRoles": [
    {
      "name": "policyholder",
      "label": "Policyholder",
      "persona": "member",
      "summary": "Report an incident, see the number it is given, and follow the claim."
    },
    {
      "name": "adjuster",
      "label": "Adjuster",
      "userLabels": ["adjuster"],
      "persona": "editor",
      "summary": "Assess the claims other visitors make, and approve payments up to $5,000."
    },
    {
      "name": "manager",
      "label": "Claims manager",
      "persona": "admin",
      "summary": "See every claim, approve the payments over $5,000, and run the claims register."
    }
  ]
}
```

**The client takes no shared trait.** Its `includedTraits` is empty, so its forms do not have the platform's
`kdr:name`, which the conference demo names its talks by. A claim is named by its incident's number instead.

**One label, and three roles to join as.** A policyholder is an ordinary user (`member`), who sees only their own
claims. An adjuster is an `editor` with the `adjuster` label: being an editor lets them see every claim, and the label
lets them assess one. A claims manager is an administrator (`admin`). It takes no label, since what it approves asks
for an administrator.

## Columns in the forms list

The `kdr:usageDef` slot says what columns the forms list has beside its own:

<!-- kdr-doc: bundle=base pointer=/kdr:usageDef -->
```json
[
  {
    "traitId": "filing",
    "columnId": "incidentNumber",
    "label": "Incident",
    "display": "${incidentNumber}",
    "substring": true
  },
  { "traitId": "incident", "columnId": "kind", "label": "Kind", "display": "${kind}", "substring": false },
  { "traitId": "incident", "columnId": "place", "label": "Where", "display": "${place}", "substring": true }
]
```

| Key | What it means |
| --- | --- |
| `traitId` | The trait the column takes its value from. |
| `label` | The column's heading. |
| `display` | What the column shows, with the trait's fields in `${` and `}`. |
| `columnId` | The column's id, which a search for it and a link to it use. Two columns here take their values from one trait, so each says its own. |
| `substring` | Whether a search finds a part of the value. A search for `003` finds `INC-20261124-003`. |

**The number comes first,** so it is what a claim is called wherever the app names it, such as the home page's
**Waiting on you**. The conference demo calls a talk by its title, and shows its id in a second column.

One more entry, in the `kdr:listingDef` slot, has the list show each claim's workflows in one column:

<!-- kdr-doc: bundle=base pointer=/kdr:listingDef -->
```json
[{ "workflowLayout": "combined" }]
```

## Menu and home page

The shipped menu leaves the Workflows page off. This entry of the `kdr:uiBlockDef` slot turns it on, for anyone signed
in:

<!-- kdr-doc: bundle=base pointer=/kdr:uiBlockDef -->
```json
[
  {
    "blockId": "homeMenu",
    "content": {
      "menu": [{ "id": "workflows", "cfactExpression": "kdr:loggedIn,kdr:app" }]
    }
  }
]
```

The demo's home page says what the demo is and where each role starts. Its words are in the `kdr:fragmentDef` slot,
which changes the platform's words one key at a time, as the conference demo's [home
page](../demo-client/client.md#home-page) explains:

<!-- kdr-doc: bundle=base pointer=/kdr:fragmentDef -->
```json
[
  {
    "fileId": "home",
    "content": {
      "home": {
        "title": "Claims demo",
        "intro": "The **Claims demo** is a small insurer's claims. A policyholder reports an incident, which is given a number as the report is filed, and adds photographs and a document as evidence. An adjuster assesses the claim and approves a payment of up to $5,000, while a larger one waits for a claims manager. The demo is shared with everyone who joins it: as an adjuster or a claims manager, you see the claims every visitor makes, and they see yours.\n\nAs a policyholder, open **My forms** from the menu, and choose **Create a form** to report an incident. As an adjuster, this page lists the claims you can assess under **Waiting on you**, and as a claims manager, so does each payment over $5,000, for you to approve. [The demo's pages](/docs/claims-demo/README.md) show the configuration behind each step."
      }
    }
  }
]
```

## What the rest of the bundle holds

| Slot | What it holds | Explained on |
| --- | --- | --- |
| `kdr:traitDef` | Five traits: `incident` and `reporter`, `filing`, `evidence`, and `assessment`. | [Reporting an incident](reporting.md#traits), [Numbering incidents](numbering.md#trait), [Evidence](evidence.md#trait), [Settlement](settlement.md#the-assessment-trait) |
| `kdr:counterDef` | The counter that numbers each incident, such as `INC-20261124-003`. | [Numbering incidents](numbering.md#counter) |
| `kdr:cfactDef` | The client's one fact, `settled`. | [Settlement](settlement.md#the-fact-of-an-approval) |

Next: [Reporting an incident](reporting.md).
