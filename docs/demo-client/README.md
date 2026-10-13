# The conference demo

The conference demo, `confdemo`, is a small conference's call for talks: speakers submit the talks they want to give.
Nothing in it is special-cased in code. Everything it does comes from its configuration.

These pages walk through that configuration. Each one shows a piece of the JSON and explains it, starting with the
simplest piece.

## Try it

You can run the demo on any test instance of KDR.

1. Open the [Simulations page](#page=simulations) and run **conf-demo**.
2. Choose **Sign in as** next to the speaker.
3. Type a title in the form that opens, and choose **Create the talk**.

The talk appears in the speaker's list of forms.

> **Note:** The Simulations page exists only on a test instance. The link above works when you read this page in the
> application, not on GitHub.

To change the demo without changing the original, give the simulation a suffix. With the suffix `mine`, it creates a
separate client, `confdemomine`, that you can edit freely.

## How a client is stored

A client's configuration is stored in **bundles**. A bundle is a named piece of configuration that is written, read,
and published on its own.

Inside a bundle, the configuration is sorted into **slots**. Each slot holds one kind of definition, as a list of
entries:

<!-- kdr-doc: illustration -->
```json
{
  "kdr:clientDef": [
    { "clientId": "confdemo", "name": "Conference demo" }
  ],
  "kdr:workflowDef": [
    { "workflowId": "createTalk", "definition": { "entry": "creation" } }
  ]
}
```

Here the slots are `kdr:clientDef` and `kdr:workflowDef`. Their entries are shortened placeholders; the sections and
pages that follow show each one in full.

The demo keeps the client in one bundle, and each workflow in a bundle of its own:

| Bundle | What it holds | Where it is explained |
| --- | --- | --- |
| `base` | The client itself | [The client](#the-client), below |
| `createTalk` | The workflow that creates a talk | [Creating a talk](create-talk.md) |

The JSON on these pages is each bundle's `slots`, as a running instance returns it from
`GET /clientAdmin/config/bundle?name=<bundle>`. The instance may list the keys in a different order; the values are the
same.

## The client

The `base` bundle has one entry, in the `kdr:clientDef` slot. It says who the client is and what it uses.

<!-- kdr-doc: bundle=base -->
```json
{
  "kdr:clientDef": [
    {
      "clientId": "confdemo",
      "name": "Conference demo",
      "description": "A demo client: a conference's call for talks. Documented in docs/demo-client.",
      "usageType": "demo",
      "audience": "internal",
      "enabledEnvironments": ["unit", "local", "dev"],
      "preload": false,
      "staticConfig": false,
      "includedTraits": ["kdr:name"]
    }
  ]
}
```

| Field | What it means |
| --- | --- |
| `clientId` | The client's id. Every form and user that belongs to the client carries it. |
| `name` | The name people see. |
| `description` | A note for whoever maintains the client. Its users never see it. |
| `usageType` | What the client is for. The value `demo` marks a client that exists to show the product. |
| `audience` | Whose client it is. The value `internal` means it belongs to the platform, not to a customer. |
| `enabledEnvironments` | Where the client runs. Every client runs in `unit` and `local`, so that tests and local development can reach it. |
| `preload`, `staticConfig` | Two switches for production clients, both off here. |
| `includedTraits` | The shared traits the client uses. Here there is one, `kdr:name`, the trait that gives a form its name. |

A **trait** is a named part of a form's data, with its own schema; a form is made of traits. Some traits, such as
`kdr:name`, are defined by the platform and shared by every client, and a client lists the ones it uses in
`includedTraits`. A client can also define traits of its own, with whatever fields it needs. The pages on the demo's
later workflows show several, each where the workflow that collects it is introduced.

Next: [Creating a talk](create-talk.md).

## Reference

### Comments

A workflow, a task, an eligibility test, a report, and a layout entry can each carry a `$comment`. It is a note for
whoever maintains the definition. It changes nothing, and it is stored and returned with the definition.

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

A `$comment` anywhere else is refused, and the error says where comments are allowed.
