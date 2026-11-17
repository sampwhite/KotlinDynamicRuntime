<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Making changes

A client is configuration: bundles of JSON, each holding entries in slots (the
[client configuration reference](client-configuration.md) lists every slot). Anything in it can be changed by writing
JSON, with a bundle patch. Most kinds of change also have an **editor**: a panel of the Clients page, Design View on a
form, or the Reports page, each with an endpoint behind it, and a tool the workflow assistant uses. This page says, for
each kind of change, which to use.

## Why an editor, where there is one

- **It keeps what it does not change.** A bundle patch changes an entry field by field at most: whatever field it gives
  is written whole. Much of a client lives in one field of an entry -- a workflow's whole `definition`, a trait's
  `dataSchema` -- so a patch that changes one task's label restates every task. An editor writes only what it is
  told to change: a field's label, a task's traits, a menu item's name. A workflow made from a template, for example, is
  stored as only what it changes in the template, and its editor keeps it that way.
- **It checks what the change means.** Removing a choice is judged against the stored forms that hold it, and a
  narrowed trait against the data it would no longer accept. Such a change is refused with what it would do, until it
  is acknowledged.
- **It speaks in the client's terms.** A person picks a menu item's audience by its name, or a field by its label, and
  the editor writes the condition or the key.

## How a change takes effect

- **In a client with a sandbox**, every change is made in the sandbox, as a **draft**: the sandbox runs it at once, so it
  can be tried on a real form, and the client's people see it once it is published, from the sandbox's **Sandbox and
  publishing** on the Clients page. The client's own page shows its configuration and leads to the sandbox, and a change
  made from the client itself is refused. In a client without one, a change is published as it is saved.
- **A workflow's parameters** (its time windows, who sees it, and whether it is on) are the exception: they are in force
  at once and never drafted. Set in the sandbox, they are the sandbox's own, and the client's are set in the client.
- **A dry run** checks a change as its save would, and stores nothing. Each endpoint below marked "yes" takes one
  (`dryRun: true`), and the workflow assistant always takes it before it saves, since a save that can be refused
  or can reach stored forms is better seen first.
- **The assistant** works as the person's own user in the client's sandbox, so every change it makes to the configuration
  is a draft, and it cannot publish. A workflow's parameters it sets are the sandbox's own, in force there at once. It
  has no tool for images, which are files a person uploads.

## The client

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| Its own definition: its name, how it describes itself, and how it behaves | Clients page, **Definition** | `POST /clientAdmin/client/definition/set` | `set_client_definition` | Yes |

## Traits

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| Which of the platform's traits it includes | Clients page, **Forms and data**, **Traits** | `POST /clientAdmin/client/traits/set` | `set_included_traits` | No |
| A trait of its own, made or changed: its fields, their kinds, and their choices | Clients page, **Forms and data**, **Traits** | `POST /clientAdmin/client/traits/design` | `design_trait` | Yes |
| One of the platform's traits narrowed: fields hidden or required, choices kept, a name of its own | Clients page, **Forms and data**, **Traits** | `POST /clientAdmin/client/traits/narrow` | `narrow_trait` | Yes |

## A workflow's form

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| A field's label, description, or hint, on one workflow's form | Design View, on the form | `POST /clientAdmin/design/layoutEntry` | `set_field_copy` | No |
| A field's words or choices, on every workflow's form | Design View, on the form | `POST /clientAdmin/design/sharedField` | `set_shared_field` | Yes |
| A trait's heading and introduction, on one workflow's form | Design View, on the form | `POST /clientAdmin/design/heading` | `set_heading` | No |
| A trait's heading and introduction, on every workflow's form | Design View, on the form | `POST /clientAdmin/design/sharedHeading` | `set_shared_heading` | No |
| A workflow's, a task's, or a save's label | Design View, on the form | `POST /clientAdmin/design/label` | `set_workflow_label` | No |
| Which fields a workflow's form shows | Design View, on the form | `POST /clientAdmin/design/shownFields` | `set_shown_fields` | Yes |

## Workflows

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| A workflow made from a template, the starter New form among them: made, renamed, or its tasks' traits changed | Clients page, **Forms and data**, **Workflows** | `POST /clientAdmin/client/workflows/save` | `save_template_workflow` | Yes |
| A workflow made from a template, removed | Clients page, **Forms and data**, **Workflows** | `POST /clientAdmin/client/workflows/remove` | `remove_template_workflow` | Yes |
| A workflow's time windows, who sees it, and whether it is turned on | Clients page, **Forms and data**, **Workflows** | `POST /clientAdmin/client/workflows/params` | `set_workflow_params` | Yes |
| A workflow written whole: its tasks, saves, approvals, and conditions | Clients page, **Sandbox and publishing**, then **Open every bundle** | `POST /clientAdmin/config/bundle/patch` | `patch_config` | Yes |

## The forms list

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| A column, added or changed | Clients page, **Forms and data**, **Listing columns** | `POST /clientAdmin/client/usages/save` | `save_forms_list_column` | Yes |
| A column, removed | Clients page, **Forms and data**, **Listing columns** | `POST /clientAdmin/client/usages/remove` | `remove_forms_list_column` | Yes |
| How it shows the workflows a form takes part in | Clients page, **Forms and data**, **Workflows in the forms list** | `POST /clientAdmin/client/usages/layout` | `set_forms_list_layout` | No |

## Reports

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| A report made from simple choices: its name, its columns, and its chart | Reports page, **New report** | `POST /clientAdmin/report/create` | `create_report` | Yes |
| A report the client stores, removed | Reports page, under the open report | `POST /clientAdmin/report/remove` | `remove_report` | Yes |
| A report changed once it is made | Clients page, **Sandbox and publishing**, then **Open every bundle** | `POST /clientAdmin/config/bundle/patch` | `patch_config` | Yes |

## Words, the menu, and Home

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| One piece of wording, on a page or in a mail | Clients page, **Copy** | `POST /clientAdmin/client/copy/set` | `set_copy` | No |
| Several pieces of one copy file's wording, set or put back together | Clients page, **Copy** | `POST /clientAdmin/client/copy/apply` | `apply_copy` | No |
| A piece of wording put back to the platform's | Clients page, **Copy** | `POST /clientAdmin/client/copy/reset` | `reset_copy` | No |
| A menu item renamed, hidden, or shown | Clients page, **Menu** | `POST /clientAdmin/client/menu/set` | `set_menu_item` | No |
| A menu item put back as shipped | Clients page, **Menu** | `POST /clientAdmin/client/menu/reset` | `reset_menu_item` | No |
| A card on Home hidden or reworded | Clients page, **Sandbox and publishing**, then **Open every bundle** | `POST /clientAdmin/config/bundle/patch` | `patch_config` | Yes |
| Home's page: what it shows, and in what order | Clients page, **Copy**, the `home` file's own listing: its `page.home` key, written whole, fences and all | `POST /clientAdmin/client/copy/set` | `set_copy` | No |

## Images

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| The favicon or the brand mark | Clients page, **Images**, **Web assets** | Uploaded by a person | None | No |
| A named image the client's copy shows | Clients page, **Images**, **Files** | Uploaded by a person | None | No |

## Anything else

| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |
|---|---|---|---|---|
| Anything no editor above makes: a slot's entries, written as JSON | Clients page, **Sandbox and publishing**, then **Open every bundle** | `POST /clientAdmin/config/bundle/patch` | `patch_config` | Yes |

## Writing a bundle patch

For what no editor makes, a patch (`POST /clientAdmin/config/bundle/patch`) changes a bundle's entries in place:

1. **Read the bundle and its entries** first. Each entry has an `updatedAt`; send it back as `expectUpdatedAt`
   when changing that entry, so a change made meanwhile is not overwritten.
2. **Choose each edit's `action`.** `addOrMerge` changes the fields it gives and keeps the rest, so it is
   the one to use for a change to an entry that exists. `addOrReplace` replaces the entry with what it gives, and
   `deleteOrNoOp` removes it. Either way, a field it gives is written whole: to change one task of a workflow,
   give its whole `definition` as it should be.
3. **Dry run it** (`dryRun: true`), read what it says, and then send the same patch to save it.

The [client configuration reference](client-configuration.md) says what each slot's entries hold. The menu and Home's
cards are written as described in [Menu and home cards](menu-and-home-cards.md), Home's page as in
[Page widgets](page-widgets.md), and the `kdr:workflowDef` slot's entries are explained, piece by piece, in
[the conference demo](../demo-client/README.md). For how the parts of a client fit together, read
[What a client is](what-a-client-is.md).
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes its tables from the editors' endpoints and the
workflow assistant's tools as the code names them, and the assistant's tests hold each of its tools to a row, so the
page cannot name an endpoint or a tool that does not exist. Read it on the server's **Documents** page, under
**Reference**, then **Making changes**, where anyone may open it without signing in. On the public reference site it is
at [dynamicruntime.org/wa/docs/making-changes](https://dynamicruntime.org/wa/docs/making-changes).
`./gradlew :launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit
the generator (below), never the copy.

On a running server, a link to this file opens the guide itself.

## Where it comes from

| What | Source |
| --- | --- |
| Each kind of change, and its editor, endpoint, tool, and dry run | `changeGroups` in [`MakingChangesReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/home/MakingChangesReference.kt) |
| The guide, made from these | `makingChangesMarkdown`, in the same file |
| The test holding the assistant's tools to the rows | [`AssistantTurnsTest.kt`](../../assistant/src/test/kotlin/com/dynamicruntime/assistant/AssistantTurnsTest.kt) |
