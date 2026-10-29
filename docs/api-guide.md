# Using the API

This guide is for a program, or an AI assistant, that has a KotlinDynamicRuntime site's address and an API key, and
needs to configure one client, manage its users, and work with its forms. It is short on purpose: it gives the model,
the conventions every call shares, and a recipe for each common job. The site's endpoint catalog describes every
endpoint in full, and two generated references describe a client's configuration and the schema it is written in (see
[Further reading](#further-reading)).

On a site, this guide is served as Markdown at `/st/api/doc/api-guide`, and so is every other document it names, each
at `/st/api/doc/<its id>`.

## What you need

- **The site's address,** such as `https://dynamicruntime.org`. Every API path starts with `/kda`.
- **An API key** a person made for you, on their profile under **API keys**. A key acts as that person's user, with
  that user's access. A **read-only** key can only read.
- **Which client it is for.** A key acts in one client, the one its user belongs to. Most keys handed to a program are
  made in a client's **sandbox** (see the model below), so that what the program writes is a draft for a person to
  check and publish.

## The model

| Term | What it is |
| --- | --- |
| Client | One organization's own space on the site: its users, its forms, and its configuration. Nothing crosses from one client to another. |
| Sandbox | A second client beside a client, with users and forms of its own, that runs the client's draft configuration. Changes are made and tried there, and a person publishes them to the client. |
| User | Somebody who acts in one client, with roles (`user`, `admin`) and labels. One person may have users in several clients. |
| Form | A stored document, made of **entries**. Its id is its `gedraId`. |
| Trait | One kind of entry a form can hold: a named set of fields, described by a JSON Schema. A form's entry names its trait by `traitId` and carries the trait's fields as `data`. |
| Workflow | A process a form goes through, made of steps (**tasks**). Each task collects some traits and has buttons (**saves**) that store them. Every client has one creation workflow, which makes new forms. |
| Bundle | A named piece of a client's configuration. The client's configuration is every bundle's entries taken together. |
| Slot | A part of a bundle holding one kind of thing, such as `kdr:traitDef` (traits) or `kdr:workflowDef` (workflows), as a list of entries. |
| Draft, publish | A change saved for a client with a sandbox is a draft: the sandbox runs it at once, and the client's own people get it when a person publishes it. Publishing is theirs, not a program's. |

## Every call

- **Send the key** in the `Authorization` header, after `Bearer` and a space:
  `Authorization: Bearer kdr_...`. Never put it in the address. Every request below carries this header, which the
  examples leave out. [API keys and access tokens](api-keys-and-tokens.md) says more, including access tokens.
- **A GET takes its arguments in the query string;** anything else takes a JSON body, sent with
  `Content-Type: application/json`. A number or a yes/no in a query string is read as one.
- **A field the endpoint does not declare is refused,** not ignored, so a misspelled field is a 400 naming it.

### What comes back

Every answer is a JSON object carrying `requestUri` and `duration`, with the payload in one of three places, by the kind
of endpoint (the catalog gives each endpoint's `kind`):

| Kind | Where the payload is |
| --- | --- |
| `general` | `results`, an object |
| `item` | `item`, an object |
| `list` | `items`, a list, with `numItems`. A list cut short by its `limit` also says `hasMore: true`, and usually `numAvailable`. |

A file download answers with the file itself, and an upload is sent as `multipart/form-data`.

### When a call is refused

A refusal is a JSON object with the HTTP status in `status` and what was wrong in `errorMessage`. Some also carry an
`errorCode` to act on, and `extraData` with the details:

```json
{
  "status": 400,
  "errorMessage": "Validation failed for 1 field: configs[0].edits[0].slot.",
  "extraData": {
    "failures": [{"path": "configs[0].edits[0].slot", "code": "invalidOption", "message": "'kdr:nope' is not a valid option."}]
  }
}
```

| Status | What it means |
| --- | --- |
| 400 | The request is wrong: a field missing, misspelled, or of the wrong type, or a value the endpoint refuses. `extraData.failures` names each field. |
| 401 | The key is not valid: mistyped, expired, or revoked. |
| 403 | The key may not do this: a read-only key writing, or something only a person signed in may do, such as making keys. |
| 404 | No such thing, or none you may see. |
| 409 | With `errorCode` `staleEntries`: what you said you read has changed since. Read it again, and redo the change. |

## Finding an endpoint

The **catalog** lists the endpoints you may call, each with its description, its input as a JSON Schema
(`inputSchema`), and what it answers (`outputSchema`). The types they refer to are in the answer's `$defs`.

- `GET /kda/schema/endpoints?publicApi=true` lists the **published API**: the documented, supported endpoints. On a
  public site, a key is shown only these, and the endpoints a client's configuration makes for it, such as a report's.
- `GET /kda/schema/endpoint?method=POST&path=/clientAdmin/user/setRoles` looks up one endpoint.

The catalog lists the form endpoints under the client's own id, as `/gedra/<client>/formDocs`, typed with that client's
own traits. The same endpoints answer at `/gedra/formDocs`, acting in the key's client, which is what the recipes
below use. An endpoint's description links to more where there is more to say, as the configuration endpoints link the
client configuration reference.

## Recipes

Each recipe is a few calls, shown as the request, and then the answer, cut down to the fields that matter. The ids and
names in them are an example's: a real client's id looks like `free_bookclub93vue5`, and its sandbox's adds
`:sandbox`.

### Who the key acts as

<!-- kdr-api: selfInfo -->
```http
GET /kda/auth/self/info
```

```json
{"results": {"client": "bookclub:sandbox", "userId": 9, "persona": "admin", "roles": ["user", "admin"]}}
```

The `client` is the client the key acts in, here a sandbox: what it writes is a draft.

### Configure the client

A client's configuration is its bundles. List them, and read one, in **portable form**, which writes the client's own
namespace as `client.@self` so that the same JSON can be written to any client:

<!-- kdr-api: bundles -->
```http
GET /kda/clientAdmin/config/bundles
```

```json
{"items": [{"name": "main", "version": 1, "published": true, "slotCount": 1, "entryCount": 1}]}
```

<!-- kdr-api: bundle -->
```http
GET /kda/clientAdmin/config/bundle?name=main&portable=true
```

```json
{"item": {"name": "main", "namespace": "client.@self", "version": 1, "published": true, "slots": {"kdr:clientDef": [{"clientId": "bookclub", "name": "Book Club"}]}}}
```

To change it, **patch** entries: add, change, or remove them, in one bundle or several, in one call that stores all of
it or none. A bundle not yet written is created, and its name is yours to choose: letters, digits, and underscores,
starting with a letter. A workflow kept in a bundle of its own can be published on its own. Each edit names its slot, an
`action`, and the entry's `data`:

| Action | What it does | Its `data` |
| --- | --- | --- |
| `addOrReplace` | Adds the entry, or replaces it whole. | The whole entry. |
| `addOrMerge` | Adds the entry, or changes only the fields given. | The entry's key fields and the fields to change. |
| `deleteOrNoOp` | Removes the entry, if there is one. | The entry's key fields alone. |

This patch adds a trait for a member's answer to a meeting invitation, and a workflow with one step that collects it, in
a new bundle. `expectNew` says the caller read no such entries, so a repeat is refused rather than overwriting what the
first made. `reload` makes the sandbox run it at once. Optionally, send it with `"dryRun": true` first: the answer is
the bundles as they would be, and what the change would do to forms the client already has, with nothing stored.

<!-- kdr-api: patch -->
```http
POST /kda/clientAdmin/config/bundle/patch
Content-Type: application/json

{
  "configs": [
    {
      "name": "rsvp",
      "edits": [
        {
          "slot": "kdr:traitDef", "action": "addOrReplace", "expectNew": true,
          "data": {
            "traitId": "attendance",
            "typeName": "client.@self.AttendanceEntry",
            "appliesTo": ["formDoc"],
            "description": "Whether a member is coming to the meeting, and how many guests they bring.",
            "dataSchema": {
              "properties": {
                "attending": {"title": "Coming", "type": "boolean"},
                "guests": {"title": "Guests", "type": "integer", "minimum": 0, "maximum": 5}
              },
              "required": ["attending"]
            }
          }
        },
        {
          "slot": "kdr:workflowDef", "action": "addOrReplace", "expectNew": true,
          "data": {
            "workflowId": "rsvp",
            "definition": {
              "workflowId": "rsvp",
              "label": "RSVP to the meeting",
              "entry": "normal",
              "tasks": [
                {
                  "id": "answer",
                  "label": "Your answer",
                  "traits": [{"traitId": "attendance", "required": true}],
                  "saves": [{"id": "saveAnswer", "label": "Send the answer", "kind": "edit"}]
                }
              ]
            }
          }
        }
      ]
    }
  ],
  "reload": true
}
```

```json
{"results": {"configs": [{"name": "rsvp", "version": 1, "published": false, "issues": []}], "reload": {"issues": []}}}
```

`published: false` is the draft. A refused patch stores nothing, and says why for each entry at fault.

In the workflow's definition:

- **`entry`** is `normal` for a workflow a form is put into. The client's creation workflow, the one that makes forms,
  is its one workflow with `creation`.
- **A `label`** is what people see: the workflow's name, a task's heading, a save's button.
- **A save's `kind`** is `edit` for a button that stores the task's entries on a form the workflow is engaged on. The
  creation workflow's is `create`, which makes the form.

The reference below describes the rest of a definition: more tasks, who sees a workflow, and when a task is
available. To change one
of the entries later, read the bundle's entries, and send the one you change with the `updatedAt` you read as
`expectUpdatedAt`, so that a change somebody else made in between is not lost:

<!-- kdr-api: entries -->
```http
GET /kda/clientAdmin/config/entries?name=rsvp
```

```json
{"items": [{"slot": "kdr:traitDef", "data": {"traitId": "attendance"}, "updatedAt": "2026-10-29T21:31:29.268Z"}]}
```

What every slot's entries hold, and what each is checked against, is in
[the client configuration reference](reference/client-configuration.md). A trait's `dataSchema` is JSON Schema in this
site's [dialect](reference/schema-dialect.md).

### Make a form, and fill it in

A workflow's **view** says what its form takes: each task's traits, each with a `schemaRef` into the view's `$defs`,
and the saves that store them. With no `workflowId`, it is the client's creation workflow, the one that makes new forms:

<!-- kdr-api: creationView -->
```http
GET /kda/gedra/workflow/view
```

```json
{"results": {"workflowId": "newForm", "entry": "creation", "tasks": [{"id": "identify", "traits": [{"traitId": "kdr:name", "required": true, "schemaRef": "#/$defs/kdr.core.NameData"}], "saves": [{"id": "create", "kind": "create"}]}]}}
```

Here it collects one trait, `kdr:name`, which is required: the form's name. Make a form by saving that task, with an
entry for each trait it collects:

<!-- kdr-api: createForm -->
```http
POST /kda/gedra/workflow/save
Content-Type: application/json

{
  "workflowId": "newForm", "taskId": "identify", "saveId": "create",
  "entries": [{"traitId": "kdr:name", "data": {"name": "June meeting"}}]
}
```

```json
{"results": {"saved": true, "item": {"gedraId": "gd.fd.bookclub:sandbox.u20261029213146723boIvkQ", "entries": [{"traitId": "kdr:name", "data": {"name": "June meeting"}}]}}}
```

Any other workflow works on a form only once it is **engaged** on it. Engage it first: a save before that is refused
with "This form is not in workflow …". Then save its task by the form's `gedraId`:

<!-- kdr-api: engage -->
```http
POST /kda/gedra/workflow/engage
Content-Type: application/json

{"gedraId": "<form id>", "workflowId": "rsvp"}
```

<!-- kdr-api: saveAnswer -->
```http
POST /kda/gedra/workflow/save
Content-Type: application/json

{
  "gedraId": "<form id>", "workflowId": "rsvp", "taskId": "answer", "saveId": "saveAnswer",
  "entries": [{"traitId": "attendance", "data": {"attending": true, "guests": 1}}]
}
```

```json
{"results": {"saved": true}}
```

Read a form with `GET /kda/gedra/formDoc?gedraId=<form id>`, and list the forms you may see, newest first:

<!-- kdr-api: formDocs -->
```http
GET /kda/gedra/formDocs?limit=20
```

```json
{"items": [{"gedraId": "gd.fd.bookclub:sandbox.u20261029213146723boIvkQ", "entries": [{"traitId": "kdr:name", "data": {"name": "June meeting"}}]}], "hasMore": false}
```

A workflow's view of a form, `GET /kda/gedra/workflow/view?workflowId=rsvp&gedraId=<form id>`, also says
whether it is engaged, and each task's status: which required traits are still missing.

### Manage users

List the client's users, optionally narrowed by a search over email, username, and name:

<!-- kdr-api: users -->
```http
GET /kda/clientAdmin/users?search=pat
```

Create one. **This sends mail:** unless the address is your own, the site mails it at once, inviting its owner to
claim the user. To make a user without mailing anybody, send `"enabled": false`; a person then sends the invitation
from the Users page when it is wanted.

<!-- kdr-api: createUser -->
```http
POST /kda/clientAdmin/user/create
Content-Type: application/json

{"primaryId": "pat@example.com", "roles": ["user"], "labels": ["pilot"]}
```

```json
{"results": {"userId": 10, "primaryId": "pat@example.com", "client": "bookclub:sandbox", "persona": "member", "roles": ["user"], "labels": ["pilot"]}}
```

A user made with a sandbox key is the sandbox's, for trying things there; it does not reach the client's own people,
and it cannot be given `admin`. Change a user with `setRoles`, `setLabels`, `setEnabled`, `setName`, or `setOrg` under
`/kda/clientAdmin/user/`, each naming the user by `userId`. `setRoles` and `setLabels` replace the whole list:

<!-- kdr-api: setLabels -->
```http
POST /kda/clientAdmin/user/setLabels
Content-Type: application/json

{"userId": "<user id>", "labels": ["pilot", "reviewer"]}
```

### Add an image the client's copy can show

A client's **file refs** are named files its configuration holds. Ask what a ref may be, then upload one. An upload is
`multipart/form-data`, with the ref's id in `refId` and the file in `file`:

<!-- kdr-api: fileTakes -->
```http
GET /kda/clientAdmin/client/files/takes
```

```json
{"item": {"maxSize": 2097152, "kinds": ["PNG", "SVG", "ICO", "JPEG", "WebP"], "refLimit": 200}}
```

```bash
curl -H "Authorization: Bearer $KDR_API_KEY" -F refId=logo -F "file=@logo.svg;type=image/svg+xml" \
  -F "description=The club logo" https://dynamicruntime.org/kda/clientAdmin/client/files/upload
```

For a client with a sandbox, the upload is a draft, like any other change. The client's copy then shows it as
`![Our logo](ref:logo)`.

## What not to do

- **Do not publish.** Publishing a client's drafts is for the client's own people, who try a change in the sandbox
  first. The API a key is shown does not offer it.
- **Do not send a key anywhere but this site,** and never in an address.
- **Do not repeat a refused change blindly.** A 400 names what to fix, and a 409 means what you read has changed:
  read it again first.

## Further reading

| Document | Repository | On a site |
| --- | --- | --- |
| The client configuration reference: every slot, what its entries hold, and what an edit must send | [reference/client-configuration.md](reference/client-configuration.md) | `/st/api/doc/client-configuration` |
| The schema dialect: the JSON Schema a trait's `dataSchema` is written in | [reference/schema-dialect.md](reference/schema-dialect.md) | `/st/api/doc/schema-dialect` |
| API keys and access tokens | [api-keys-and-tokens.md](api-keys-and-tokens.md) | `/st/api/doc/api-keys-and-tokens` |
| The conference demo: a whole client, bundle by bundle | [demo-client/README.md](demo-client/README.md) | `/st/api/doc/demo-client-README` |
