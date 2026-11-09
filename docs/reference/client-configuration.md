<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# A client's configuration

A client's configuration says what the client is and what it does: the traits that give its forms' data their shape, the workflows its forms move through, its reports, and more. This page says how that configuration is stored, lists every kind of thing it holds, and says what a write or an edit of one must send. It is made from the shapes a write is held to, so it says what the server accepts.

## Bundles, slots, and entries

A client's configuration is stored in pieces called **bundles**, each with a name, and assembled from them: what the client runs is every entry of every one of its bundles, taken together. No entry has to be in a particular bundle. Which entries go in which bundle is your choice, made when you write the bundles, and you can change it later. You could keep all of a client's traits in one bundle, or give each trait a bundle of its own.

**A bundle is what you save and what you publish.** Each save of a bundle makes a new revision of it. For a client with a sandbox, that revision is a draft: the sandbox runs it at once, and the client's own people get it only when the bundle is published. So the way you divide a configuration into bundles is the way you can release it. To publish a change to one workflow and nothing else, keep that workflow in a bundle of its own. Publishing is done by the client's own people in the app, after trying the changes in the sandbox, so a change written through the endpoints below stays a draft until then.

**The app often chooses the bundle for you.** Design View edits a workflow in whichever bundle declares it, and a workflow made on a client's page is given a new bundle of its own. The bundle editor and the endpoints below work on bundles directly.

**Within a bundle, entries are sorted into slots,** one slot for each kind of thing: `kdr:traitDef` holds traits, `kdr:workflowDef` holds workflows, and so on. A slot is an array of entries of its one kind, each named by its **key**: a trait by its id, a workflow by its id. Most slots hold as many entries as you like. Two hold at most one: [`kdr:clientDef`](#kdrclientdef), the client's definition, since a client has one definition, and [`kdr:listingDef`](#kdrlistingdef), its listing-wide settings.

**A key names one thing in the whole client, not only in its bundle.** A second trait, workflow, report, or type under a key that is already used, in the same bundle or another, is refused. Overlays are the exception: two `kdr:fragmentDef` entries for one fragment file, or two `kdr:uiBlockDef` entries for one block, are two layers, and both apply.

**Entries depend on one another across bundles, so a change is judged against the whole client.** A workflow names the traits its forms collect, so it depends on them. Every write and patch is checked against the client as it would be with the change in place (see [what is checked, and when](#what-is-checked-and-when)), so removing a trait that a workflow still collects is refused, even when the two are in different bundles. There are two ways to do it:

- Save the workflow's bundle first, without the trait in the workflow, and then remove the trait from its own bundle.
- Make both changes in one [patch](#editing-entries), which edits several bundles and stores them together or not at all.

Publishing follows the same rule. Publishing a workflow's bundle without the bundle holding a new trait it collects is refused, so bundles that were changed together are published together.

## The endpoints

The bundle editor shows a client's bundles as they are stored, and edits them through these endpoints.

| Endpoint | What it does |
|---|---|
| `GET /clientAdmin/config/bundle` | One bundle's latest revision, every slot. |
| `GET /clientAdmin/config/entries` | One bundle's entries, each with its slot and its own accounting. |
| `POST /clientAdmin/config/bundle/write` | Writes a whole bundle. |
| `POST /clientAdmin/config/bundle/patch` | Edits entries of one or more bundles, together. |

Each acts on the caller's own client. An administrator of every client (`allClients`) reaches a named client's through the same endpoints under `/admin/client/config/`.

## Writing a whole bundle

`/clientAdmin/config/bundle/write` takes the bundle's `name`, its `namespace` (where the types it declares are named), and its `slots`: an object with one array of entries for each slot it fills, named as in [the slots](#the-slots) below. An entry is its data alone, as the slot's section lists it.

A write is the whole bundle. A slot it leaves out is emptied, unless it says `"impliedDelete": false`, when a slot it leaves out is kept as it was. It is judged only on what it changes: an entry it carries back exactly as stored is not judged again, so a bundle read, edited, and written back is not refused for a fault it already had.

`"reload": true` makes the client run the new revision at once. A client that runs only what is published, as a self-serve client does, runs a draft only in its sandbox.

## Editing entries

`/clientAdmin/config/bundle/patch` edits entries without sending the rest of the bundle. Its `configs` are the bundles it edits, each `{"name": ..., "edits": [...]}`. A bundle not yet written is created. Every edit of every bundle is applied together and judged together, so an entry can move from one bundle to another: delete it from one, add it to the other.

Each edit is an object:

| Field | Required | What it is |
|---|---|---|
| `slot` | yes | The slot the entry is in: one of [the slots](#the-slots). |
| `action` | yes | What to do: `deleteOrNoOp`, `addOrMerge`, `addOrReplace`, as the table below says. |
| `data` | yes | The entry's data, or as much of it as the action needs. Only a delete from a slot with one entry may leave it out. |
| `expectUpdatedAt` | no | The entry's `updatedAt` when the caller read it. |
| `expectNew` | no | `true` when the caller read no such entry. |

**Which entry an edit means is said by its data:** the entry whose key fields have the values the data gives. So every edit's data carries the slot's key fields -- for a slot with one entry, such as `kdr:clientDef`, there are none -- and an edit cannot change an entry's key. To rename one, delete it and add it under the new key.

**What each action's data must carry:**

| Action | What it does | Its data |
|---|---|---|
| `deleteOrNoOp` | Removes the entry, or does nothing if there is none. | The key fields, and nothing else is needed. |
| `addOrMerge` | Changes the fields it gives, keeping the rest; adds the entry if there is none. | The key fields and the fields to change. A field it leaves out keeps its stored value, so a required field need not be sent again; a new entry must be complete. |
| `addOrReplace` | Replaces the entry with what it gives; adds it if there is none. | The **whole** entry, required fields and all, even when the entry exists: a field it leaves out is gone. |

**Saying what was read.** An edit may say what its caller saw: `expectUpdatedAt`, the entry's `updatedAt` when read, or `"expectNew": true` when there was no entry. If the entry has changed since, or is gone, or now exists, the patch is refused, all of it, with status 409 and `errorCode` `staleEntries`. Read again and redo the edit.

**Trying it first.** `"dryRun": true` runs the patch -- its checks, its edits, and the trial reload -- and answers the bundles as they would be written, with what they would do to the forms the client already stores, without storing anything.

## What is checked, and when

A write or a patch is refused, all of it, with every problem named by where it is: its slot, the entry's key, and the path within it, as `kdr:cfactDef[ready].group`.

1. **Each entry it changes is held to its slot's shape:** the fields the slot's section lists and no others, each of its type, and the required ones present. A few rules go beyond the shape: a usage rule's `display` and `presentation` must be templates that parse, a file ref names its file the one way its `source` reads, and a type may be declared once.
2. **Then the client is loaded with the change in place, as a trial,** and refused if that finds a problem the client did not have. Some things are judged only here, in the configuration they belong to:
   - **A schema body** -- a `kdr:schemaDef`'s `schema`, a `kdr:traitDef`'s `dataSchema` -- is held to the shape of a schema at the first step, and is parsed only here, where the client's other types are there for it to name. What a schema may say is the [schema dialect](schema-dialect.md).
   - **Inside a report's `definition`**, and inside the definition of a workflow made from a template (`fromTemplate`), which says only what it changes in the template: the first step asks only that the definition is an object.

**A bundle read in portable form** writes the client's own namespace as `client.@self` (`?portable=true` on a read), so it can be written to another client. A write or a patch accepts it and resolves it to the client it is written to, everywhere but the `kdr:clientDef` and `kdr:customerDef` slots.

## Examples

Three edits to one bundle, `main`. The first declares a cfact, or replaces its declaration. The second changes only a cfact's description, keeping its other fields. The third removes one, by its key.

```json
{
  "configs": [
    {
      "name": "main",
      "edits": [
        {
          "slot": "kdr:cfactDef", "action": "addOrReplace",
          "data": {"name": "ready", "group": "review", "description": "The review is ready.", "toFrontend": true}
        },
        {
          "slot": "kdr:cfactDef", "action": "addOrMerge",
          "data": {"name": "accepted", "description": "The talk was accepted."},
          "expectUpdatedAt": "2026-10-29T16:00:00Z"
        },
        {
          "slot": "kdr:cfactDef", "action": "deleteOrNoOp",
          "data": {"name": "draft"}
        }
      ]
    }
  ],
  "reload": true
}
```

The merge says what its caller read, so it is refused if `accepted` changed after `2026-10-29T16:00:00Z`.

## The slots

| Slot | Keyed by | What an entry is |
|---|---|---|
| [`kdr:clientDef`](#kdrclientdef) | one entry | The client this configuration defines, as a stored entry. For a client defined in code it is instead a change to that definition (issue #1677), which may say only whether the client has a sandbox: `clientId`, `"overrides": "codeOverlay"`, and `sandbox`. |
| [`kdr:customerDef`](#kdrcustomerdef) | `customerId` | One customer the platform declares, keyed by its customer id. |
| [`kdr:traitDef`](#kdrtraitdef) | `traitId` | One data-trait declaration this configuration contributes, keyed by trait id. |
| [`kdr:usageDef`](#kdrusagedef) | `traitId`, `label` | One column of this client's forms listing, reading a trait, keyed by the trait and the column's label. |
| [`kdr:workflowDef`](#kdrworkflowdef) | `workflowId` | One workflow definition of this configuration, keyed by its workflow id. |
| [`kdr:reportDef`](#kdrreportdef) | `reportId` | One named report of this configuration, keyed by its report id. |
| [`kdr:schemaDef`](#kdrschemadef) | `typeName` | One schema type this configuration declares directly, keyed by the type's name. |
| [`kdr:fragmentDef`](#kdrfragmentdef) | `fileId` | One Markdown-fragment overlay this client contributes, keyed by the fragment file id. |
| [`kdr:uiBlockDef`](#kdruiblockdef) | `blockId` | One UiBlock overlay this client contributes, keyed by the block id. |
| [`kdr:cfactDef`](#kdrcfactdef) | `name` | One cfact name this configuration declares exists (declaration only), keyed by name. |
| [`kdr:fileRefDef`](#kdrfilerefdef) | `refId` | One file ref of this client -- a named file, by where its bytes are -- keyed by its ref id. The file store's are named by their `hash`; a local file's by a `root` and a `path` under it, and by no hash. |
| [`kdr:listingDef`](#kdrlistingdef) | one entry | The client's listing-wide settings: how its forms listing is drawn as a whole. A stored entry stands over the one its code or template declares. |

### kdr:clientDef

The client this configuration defines, as a stored entry. For a client defined in code it is instead a change to that definition (issue #1677), which may say only whether the client has a sandbox: `clientId`, `"overrides": "codeOverlay"`, and `sandbox`.

A bundle holds one entry in this slot.

An entry is [WrittenClientInfo](#writtenclientinfo).

### kdr:customerDef

One customer the platform declares, keyed by its customer id.

Keyed by `customerId`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `customerId` | string | yes | The customer's id, and the prefix of its clients' ids: letters and digits, starting with a letter. |
| `name` | string | yes | The name presented for the customer. |
| `description` | string |  | An internal note about who, what or why. |
| `rootClientId` | string |  | The client whose administrators act for the customer as a whole. |
| `plan` | one of `free`, `unlimited` |  | The plan the customer's clients are on; unlimited when absent. |

### kdr:traitDef

One data-trait declaration this configuration contributes, keyed by trait id.

Keyed by `traitId`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `traitId` | string | yes | The trait's id, unique within its client's view: its own and the global traits. |
| `typeName` | string | yes | The name of the entry type the trait generates. |
| `appliesTo` | array of one of `userData`, `formDoc`, `wfData`, `fileRef` | yes | The gedra kinds an entry of this trait may be carried on. |
| `description` | string |  | What the trait is for. |
| `dataSchema` | a schema | yes | The schema of the trait's own data, validated by parsing it. |
| `overrides` | one of `code` |  | Set to `code` when this entry stands over the client's code's entry of the same key -- its template's, or its own code's -- which it replaces whole. Without it, an entry matching a code entry is refused. |

### kdr:usageDef

One column of this client's forms listing, reading a trait, keyed by the trait and the column's label.

Keyed by `traitId` and `label`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `traitId` | string | yes | The trait this usage rule is for. |
| `label` | string | yes | The column label the listing shows. |
| `display` | string | yes | The string-script expression evaluated against the trait's data for the value. |
| `kind` | one of `string`, `number`, `date`, `boolean` |  | How the value reads -- what a search treats it as. Absent, a lone `${field}` display reads its field's schema (issue #1508), and a computed one is text. |
| `substring` | boolean |  | For a string value, whether the search also offers a substring match. |
| `presentation` | string |  | A string script over the trait's data, with the column's value as `value`, that the cell reads through. |
| `columnId` | string |  | The column's name in its search parameters and its sort, when it is not the trait id: needed on a second column of the same trait. Letters, digits, and underscores, starting with a letter. |
| `overrides` | one of `code` |  | Set to `code` when this entry stands over the client's code's entry of the same key -- its template's, or its own code's -- which it replaces whole. Without it, an entry matching a code entry is refused. |

### kdr:workflowDef

One workflow definition of this configuration, keyed by its workflow id.

Keyed by `workflowId`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `workflowId` | string | yes | The workflow's id within this configuration. |
| `definition` | [WfDef](#wfdef) | yes | The workflow definition, as the definition schema describes it. |
| `overrides` | one of `code`, `codeOverlay` |  | Set to `code` when this entry stands over the client's code's entry of the same key -- its template's, or its own code's -- which it replaces whole. Set to `codeOverlay` when it holds only what it changes of the code's entry, which every read merges it over, so a later change to the code reaches the parts it leaves alone. Without it, an entry matching a code entry is refused. |

### kdr:reportDef

One named report of this configuration, keyed by its report id.

Keyed by `reportId`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `reportId` | string | yes | The report's id within this configuration. |
| `definition` | [ReportDef](#reportdef) | yes | The report definition, as the definition schema describes it. |
| `overrides` | one of `code` |  | Set to `code` when this entry stands over the client's code's entry of the same key -- its template's, or its own code's -- which it replaces whole. Without it, an entry matching a code entry is refused. |

### kdr:schemaDef

One schema type this configuration declares directly, keyed by the type's name.

Keyed by `typeName`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `typeName` | string | yes | The qualified name of the type being defined. |
| `schema` | a schema | yes | The type's schema body, validated by parsing it. |
| `overrides` | one of `code`, `codeOverlay` |  | Set to `code` when this entry stands over the client's code's entry of the same key -- its template's, or its own code's -- which it replaces whole. Set to `codeOverlay` when it holds only what it changes of the code's entry, which every read merges it over, so a later change to the code reaches the parts it leaves alone. Without it, an entry matching a code entry is refused. |

### kdr:fragmentDef

One Markdown-fragment overlay this client contributes, keyed by the fragment file id.

Keyed by `fileId`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `fileId` | string | yes | The fragment file this overlays. |
| `content` | map of map of string | yes | The overlay content: a two-tier map of namespace to key to value. |

### kdr:uiBlockDef

One UiBlock overlay this client contributes, keyed by the block id.

Keyed by `blockId`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `blockId` | string | yes | The UiBlock this overlays. |
| `content` | object | yes | The overlay content. |

### kdr:cfactDef

One cfact name this configuration declares exists (declaration only), keyed by name.

Keyed by `name`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `name` | string | yes | The cfact name this configuration declares. |
| `group` | string | yes | The cfact's group. |
| `description` | string | yes | What the cfact means. |
| `toFrontend` | boolean |  | Whether the cfact is delivered to the frontend. |
| `overrides` | one of `code` |  | Set to `code` when this entry stands over the client's code's entry of the same key -- its template's, or its own code's -- which it replaces whole. Without it, an entry matching a code entry is refused. |

### kdr:fileRefDef

One file ref of this client -- a named file, by where its bytes are -- keyed by its ref id. The file store's are named by their `hash`; a local file's by a `root` and a `path` under it, and by no hash.

Keyed by `refId`.

| Field | Type | Required | What it is |
|---|---|---|---|
| `refId` | string | yes | The ref's id within its client: a variable name, at most 64 characters. |
| `source` | one of `store`, `local`, `clientLocal` | yes | Where the file's bytes are. |
| `hash` | string |  | For the file store: the SHA-256 of the file's bytes, in lowercase hex, which is what names them there. |
| `root` | string |  | For a local file: the root it is under, by the name the deployment declared it with. |
| `path` | string |  | For a local file: its path under the root -- or, for a client's own, under the client's directory there -- in segments of letters, digits, `.`, `_` and `-`, none beginning with a dot. |
| `description` | string |  | What the file is for, in the administrator's words. |
| `overrides` | one of `code` |  | Set to `code` when this entry stands over the client's code's entry of the same key -- its template's, or its own code's -- which it replaces whole. Without it, an entry matching a code entry is refused. |

### kdr:listingDef

The client's listing-wide settings: how its forms listing is drawn as a whole. A stored entry stands over the one its code or template declares.

A bundle holds one entry in this slot.

| Field | Type | Required | What it is |
|---|---|---|---|
| `workflowLayout` | one of `split`, `combined` |  | How each row's workflows are drawn: `split` (the default) or `combined`. |

## The types the slots refer to

Each is a type the server declares, named as the endpoint catalog names it. A field whose type is one of these links to it.

### WrittenClientInfo

`kdr.clientCatalog.WrittenClientInfo`: A client's definition as a configuration write must give it.

| Field | Type | Required | What it is |
|---|---|---|---|
| `clientId` | string | yes | The client's unique key, embedded in every gedra id it owns. |
| `name` | string | yes | The name presented to users as the name of the client. |
| `description` | string |  | An internal note about who, what or why. |
| `customerId` | string |  | The customer that owns the client; its id is the prefix of the client's. |
| `usageType` | one of `production`, `test`, `dev`, `template`, `demo` | yes | What the client is for. |
| `audience` | one of `internal`, `customer` | yes | Whose client this is: ours, or somebody else's. |
| `webResourcesId` | string |  | The web resources package a component ships that the client presents its images from; named in code only. |
| `timeZone` | string |  | The client's IANA time zone (America/Chicago), which its dates are read and shown in. |
| `enabledEnvironments` | array of one of `unit`, `local`, `dev`, `integration`, `prod` | yes | The environments the client is enabled in. |
| `preload` | boolean |  | Whether the client's caches are computed before the node reports ready. |
| `staticConfig` | boolean |  | Whether, in production, the client takes nothing from the database. |
| `extendsFromClientId` | string |  | The client whose definitions this one is built on top of. |
| `domainPrefix` | string |  | A prefix on a core domain that routes to this client. |
| `customDomain` | string |  | A whole hostname the client configured for itself. |
| `includedTraits` | array of string |  | Trait ids and group names the client takes as they stand. |
| `testFeatures` | array of string |  | Test/demo feature names, honored only on a test instance (stripped elsewhere). |
| `userLabels` | array of string |  | User labels the client suggests; a label editor offers them, without binding to them. |
| `userLabelNotes` | map of string |  | A sentence per suggested user label, by label, saying who should carry it. |
| `sandbox` | boolean |  | Whether the client has a sandbox beside it, running its latest configuration. |
| `retired` | boolean |  | Whether the platform retired the client: its data kept, and the client absent everywhere. |
| `publicTemplate` | boolean |  | Whether anyone creating a client -- a person's own, or a customer's -- may build it on this code-defined client. |
| `joinRoles` | array of object |  | The roles a visitor signed in to public may join the client as, on a deployment that offers it. |
| `sessionHours` | integer |  | How long a sign-in lasts, in hours; absent for the deployment's default, and never past its limit. |
| `refreshIntervalSeconds` | integer |  | How often, in seconds, an idle open tab re-reads what it shows; absent for the deployment's. |

### WfDef

`kdr.wfdef.WfDef`: A workflow definition: how it is entered, and its tasks.

| Field | Type | Required | What it is |
|---|---|---|---|
| `workflowId` | string | yes | The workflow's base name; its client comes from the bundle declaring it. |
| `entry` | one of `creation`, `coreDetails`, `normal` | yes | How the workflow is entered. |
| `$comment` | string |  | A note for whoever maintains this definition; it changes nothing about what the definition does. |
| `label` | string |  | What the workflow is called -- a page's title, a template evaluated in two passes like a task's label. Optional; absent, a page uses its own generic title. |
| `tasks` | array of [WfTask](#wftask) | yes | The tasks, in presentation order. |
| `functions` | array of object |  | Workflow-global function usages (e.g. cfactCalc); each a { fn, ... } block validated by its own kind. |
| `eligibility` | array of [WfEligibility](#wfeligibility) |  | A normal workflow's eligibility tests, in the order their reasons are listed. Every one is evaluated; an empty list of failures means eligible. |
| `singletons` | array of [WfSingleton](#wfsingleton) |  | A normal workflow's singleton-cfact rules: the framework cfacts it contributes to the form while engaged. |
| `locks` | array of [WfLock](#wflock) |  | A normal workflow's trait locks: what the form's data may not change while it is engaged, and who is exempt or may override. |
| `types` | object |  | The workflow's alterations of types, by qualified type name: each the shape a client's alteration takes, for this workflow's pages only. For now each holds a field layout (g-layout) and nothing else. |
| `typeBasis` | object |  | For each altered layout entry, the inherited entry it replaced when it was made, by type name and then field -- what a page compares to say the shared copy has changed since. |
| `fromTemplate` | string |  | The global workflow this one was made from, by its rooted id. Stored, such a workflow is an overlay of the template's current definition. |
| `highlightStart` | boolean |  | A normal workflow's: whether its Start is highlighted, as their turn, for whoever could act on its first task, while a form is eligible for it and not yet in it. It never decides who may engage. |

### WfTask

`kdr.wfdef.WfTask`: One task of a workflow: the traits it collects and the saves it offers -- or, with an approval, none, completed by a reviewer.

| Field | Type | Required | What it is |
|---|---|---|---|
| `id` | string | yes | Stable id of this task, unique within the workflow. |
| `$comment` | string |  | A note for whoever maintains this definition; it changes nothing about what the definition does. |
| `label` | string | yes | What the task is called -- a template, evaluated in two passes. |
| `traits` | array of [WfTraitRef](#wftraitref) | yes | The traits this task collects. |
| `saves` | array of [WfSave](#wfsave) | yes | The saves this task offers. |
| `layout` | [WfLayout](#wflayout) |  | How the task is drawn; declaration order and inline editing when absent. |
| `functions` | array of object |  | Task-scoped function usages (e.g. prefillData); each a { fn, ... } block validated by its own kind. |
| `approval` | [WfApproval](#wfapproval) |  | Makes this an approval task: it collects no traits and is complete when a reviewer approves it. |
| `display` | object |  | How the task is shown: a display branch (mode, text, disabled), or a selector choosing among several by the caller's task facts. |
| `saveWhen` | string |  | A normal workflow task's rule for who may save it: a cfact expression over the caller's task and viewer facts. Absent means anyone who can see the form. |
| `keyword` | one of `review` |  | A normal workflow task's search keyword: what kind of action its call to action asks for. An approval task carries 'review' without saying so. |

### WfTraitRef

`kdr.wfdef.WfTraitRef`: One trait a task collects, and whether an entry of it is required for completeness.

| Field | Type | Required | What it is |
|---|---|---|---|
| `traitId` | string | yes | The trait this task collects. |
| `required` | boolean |  | Whether an entry of this trait must be present for the task to be complete; true when absent. |

### WfSave

`kdr.wfdef.WfSave`: One way a task is saved: the button's label, and what pressing it does.

| Field | Type | Required | What it is |
|---|---|---|---|
| `id` | string | yes | Stable id of this save, unique within the task. |
| `label` | string | yes | What the save is called -- a template, evaluated in two passes. |
| `kind` | one of `create`, `edit` | yes | What the save does. |

### WfLayout

`kdr.wfdef.WfLayout`: The minimum a page needs to draw a task.

| Field | Type | Required | What it is |
|---|---|---|---|
| `order` | array of string |  | Trait ids in the order they are drawn; traits not named follow in declaration order. |
| `edit` | one of `inline` |  | How the task's traits are edited. |

### WfApproval

`kdr.wfdef.WfApproval`: What makes a task an approval task: the cfact an approval emits, and the copy around its button.

| Field | Type | Required | What it is |
|---|---|---|---|
| `cfact` | string | yes | The cfact the workflow gains once this task is approved. |
| `prompt` | string | yes | The text above the approve button -- a template, evaluated in two passes. |
| `button` | string | yes | The approve button's own text -- a template, evaluated in two passes. |
| `approveWhen` | string |  | Who may approve: a cfact expression over the caller's task and viewer facts and kdr:isOwner. Absent means a reviewer who does not own the form (kdr:reviewer,~kdr:isOwner). |
| `showTasks` | array of string |  | The tasks whose traits the approval shows, read-only, above its prompt. Absent means the nearest earlier task that collects traits; empty shows none. |

### WfEligibility

`kdr.wfdef.WfEligibility`: One eligibility test of a normal workflow: a cfact requirement and the reason shown when it is not met.

| Field | Type | Required | What it is |
|---|---|---|---|
| `id` | string | yes | Stable id of this test, unique within the workflow; what a stored failure records. |
| `$comment` | string |  | A note for whoever maintains this definition; it changes nothing about what the definition does. |
| `test` | string | yes | The cfact expression a form's cfacts must match for this test to pass. |
| `explanation` | string | yes | Why the form is not eligible when the test fails -- a template, evaluated in two passes. |

### WfSingleton

`kdr.wfdef.WfSingleton`: One singleton-cfact rule of a normal workflow: a framework singleton cfact, and when it is emitted.

| Field | Type | Required | What it is |
|---|---|---|---|
| `cfact` | string | yes | The framework singleton cfact emitted (needsReview, finished, ownerOnly). |
| `when` | string | yes | The cfact expression over the workflow's current cfacts that emits it. |

### WfLock

`kdr.wfdef.WfLock`: A trait lock: while the form is engaged and the condition holds, only the named task's savers may change the trait, unless a write overrides it.

| Field | Type | Required | What it is |
|---|---|---|---|
| `traitId` | string | yes | The trait locked. |
| `when` | string | yes | When it holds, beyond the form being engaged: a cfact expression over the form's and the workflow's facts. |
| `writableVia` | string | yes | The task, collecting the trait, whose savers the lock exempts. |
| `overrideWhen` | string |  | Who may override it: a cfact expression over the writer's request facts. Absent means nobody. |

### ReportDef

`kdr.reportdef.ReportDef`: A named report: the columns a client's forms are shown with, and how they are grouped and filtered by default.

| Field | Type | Required | What it is |
|---|---|---|---|
| `reportId` | string | yes | The report's id: rooted for a component's report, bare for a client's. |
| `$comment` | string |  | A note for whoever maintains this definition; it changes nothing about what the definition does. |
| `label` | string | yes | What the report is called. |
| `description` | string |  | What the report shows, for the person choosing it. |
| `columns` | array of [ReportColumnDef](#reportcolumndef) | yes | The columns, in the order shown. |
| `groupBy` | array of string |  | The columns a grouped run groups by, unless the run names others. |
| `excludeEmpty` | array of string |  | The columns that must hold a value for a form to appear, unless the run names others. |
| `history` | boolean |  | Whether the nightly job stores a snapshot of the report's grouped run, so its groups can be charted over time. |

### ReportColumnDef

`kdr.reportdef.ReportColumnDef`: One column of a report: the value it promotes, and how.

| Field | Type | Required | What it is |
|---|---|---|---|
| `columnId` | string | yes | The column's id within the report: what a run's groupBy and excludeEmpty name. |
| `label` | string | yes | The column's heading. |
| `path` | string | yes | The report path the column reads, such as form.sample:yearly[2024].note. |
| `kind` | one of `string`, `number`, `date`, `boolean` |  | The value's kind, when it should be read as other than its target says. |
| `combine` | one of `first`, `list`, `distinct`, `count`, `sum`, `min`, `max`, `avg` |  | How the path's several values are made one within a form; absent, the first, or a list for a path with several. |
| `rollup` | one of `first`, `list`, `distinct`, `count`, `sum`, `min`, `max`, `avg` |  | How the column is combined across the forms of a group when the report is grouped. |
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes it from the slot shapes a configuration write is
held to, so it cannot disagree with what the server accepts. Read it on the server's **Documents** page, under
**Reference**, then **Client configuration**, where anyone may open it without signing in. On the public reference site
it is at [dynamicruntime.org/wa/docs/client-configuration](https://dynamicruntime.org/wa/docs/client-configuration).
`./gradlew :launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit
the generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
|---|---|
| The slots, each with its key and the shape of its entries | [`CoreConfigTraits.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/CoreConfigTraits.kt) |
| The slot shapes compiled, as a write is held to them | [`ConfigSlotGate.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/ConfigSlotGate.kt) |
| The endpoints that write and edit a configuration | [`GedraConfigEndpoints.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/GedraConfigEndpoints.kt) |
| The reference page, made from all of these | [`ConfigReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/ConfigReference.kt) |
