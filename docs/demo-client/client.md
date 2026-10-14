[← The conference demo](README.md) · [Contents](README.md#the-pages)

# Client

The `base` bundle's `kdr:clientDef` slot holds one entry. It says who the client is and what it is made of. The
bundle's other slots hold the client's own traits and the columns of the forms list, and the [survey](talk-survey.md),
which is the first thing to use them, explains them.

<!-- kdr-doc: bundle=base pointer=/kdr:clientDef/0 -->
```json
{
  "clientId": "confdemo",
  "name": "Conference demo",
  "description": "A demo client: a conference's call for talks. Documented in docs/demo-client.",
  "usageType": "demo",
  "audience": "internal",
  "enabledEnvironments": ["unit", "local", "dev"],
  "preload": false,
  "staticConfig": false,
  "includedTraits": ["kdr:name"],
  "userLabels": ["committee"]
}
```

| Key | What it means |
| --- | --- |
| `clientId` | The client's id. Every form and every user that belongs to the client has this id stored on it, so there is never any doubt about which client something belongs to. |
| `name` | The name people see. |
| `description` | A note for whoever maintains the client. Its users never see it. |
| `usageType` | What the client is for. The value `demo` marks a client that exists to show the product. |
| `audience` | Whose client it is. The value `internal` means it belongs to the platform, not to a customer. |
| `enabledEnvironments` | Where the client runs. Every client runs in `unit` and `local`, so that tests and local development can reach it. |
| `preload`, `staticConfig` | Two switches for production clients, both off here. |
| `includedTraits` | The shared traits the client uses. Here there is one, `kdr:name`, the trait that gives a form its name. |
| `userLabels` | Labels an administrator can put on a user. A workflow can ask whether a user has one; [Committee review](committee-review.md) asks for `committee`. |

## Traits

A **trait** is a named part of a form's data, with its own schema. A form is made of traits.

Some traits, such as `kdr:name`, are defined by the platform and shared by every client, and a client lists the ones
it uses in `includedTraits`. A client can also define traits of its own, with whatever fields it needs. The pages on
the demo's workflows show several, each where the workflow that collects it is introduced. The first two appear in
[Talk survey](talk-survey.md).

Next: [Creating a talk](create-talk.md).
