<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Menu and home cards

The menu along the top of every page and the cards on Home are each a **UI block**: a list of items, each with an id,
its words, what it does when chosen, and a condition deciding who is offered it. The platform ships both, a component
may add to them (the workflow assistant adds cards of its own), and a client changes them for itself. Every layer
names an item by its id, so a client's change says only what it changes, and the rest of the item stays as shipped.

This page says how to see a client's menu as it stands, how to change an item, and what the platform ships. The lists
at the end are made from the platform's own declarations.

## A client's menu as it stands

The lists at the end of this page are the **core layer**, not any one client's menu: a component may have added
items, and a client may have renamed, hidden, or shown some. To see what a client's people are offered, read the
client's own:

- **On the Clients page**, the client's **Menu** panel lists every item with its label and who it is shown to, as
  shipped and for this client, and marks what the client changed.
- **Through the API**, `GET /clientAdmin/client/menu/items` answers the same, one entry per item: its label and
  condition as shipped (`baseLabel`, `baseCondition`) and for this client (`label`, `condition`),
  the audience each condition is called, the item it sits under, and the audiences it may be shown to. An item marked
  `deploymentOnly` is the deployment's own, shown only to its operators, to a test instance's sessions, or to a
  debug session, which a client's people are never among.
- **The workflow assistant** reads it with its `read_menu` tool.

Who sees an item is decided by its condition, a test written in [facts](facts.md): `kdr:loggedIn,kdr:app` is offered to
anyone signed in to the application, `#never` to nobody. A condition changes what is offered, never what anyone may
reach: the pages and the API behind an item still decide who may use them.

## Changing an item with an overlay

A client changes a block with an overlay: an entry of the `kdr:uiBlockDef` slot of its configuration, naming the
block and, in its content, each item it changes. The menu's block is `homeMenu` and its items are under
`menu`. An item is named by its `id`, and given only what changes:

| Key | What it means |
|---|---|
| `id` | Which item. Required, and never changed: it is how every layer finds the item. |
| `label` | The words shown. |
| `cfactExpression` | Who is offered it, as a test written in [facts](facts.md). |
| `action` | What choosing it does: a string opens that page, and an array calls a function, then its arguments. |
| `parentId` | The item it sits under, for one level of nesting. A parent is drawn as a group header and has no action of its own. |
| `displayOrder` | Where it sorts. The platform's items are spaced 100 apart, so an item can land between two. |

**Turning one on.** The platform ships the **Workflows** item withdrawn, since only a client with normal workflows has
anything to show there. The conference demo turns it on for everyone signed in, with one entry in its `base` bundle
(see [the abstract page](../demo-client/abstract.md)):

```json
{
  "blockId": "homeMenu",
  "content": {
    "menu": [{ "id": "workflows", "cfactExpression": "kdr:loggedIn,kdr:app" }]
  }
}
```

**Hiding one.** Nothing is ever removed from a block: an item is withdrawn by giving it the condition `#never`.
So the shipped item stays in the list, and why it is gone can be read from the item:

```json
{ "id": "catalog", "cfactExpression": "#never" }
```

**Relabeling one.** Give it only the new words; who sees it and where it goes stay as shipped:

```json
{ "id": "docs", "label": "Help" }
```

**Putting one back.** Remove the entry from the overlay, and the item is as shipped again. An overlay can also give
`#always`, which shows an item to everyone, on every node, whatever a layer beneath it said.

**Adding one.** An item with an id no layer beneath has is added to the block. It needs a label and a
`displayOrder`, since an overlay's items are never numbered for it, and almost always a condition: without one it
is offered to everyone, on every node. An item that is chosen needs an action too. A group header for items of the
client's own has none, since a header is only drawn over the items naming it in their `parentId`.

An overlay is checked when its configuration is written, and refused for a block nobody registered, a fact that is not
declared, a call to a function the application does not have, or nesting that is not one level under a header with no
action. In a client with a sandbox, the write is a draft until it is published.

## Changing an item with the menu editor

The client's **Menu** panel changes one item at a time without any JSON, and the endpoints behind it do the same:
`POST /clientAdmin/client/menu/set` renames an item, hides it (`#never`), or shows it to an audience, and
`POST /clientAdmin/client/menu/reset` takes the client's changes to it back. Each writes the same overlay a
hand-written one is, into the client's stored configuration: live at once, or, for a client with a sandbox, a draft its
sandbox shows until it is published.

The editor shows an item to an audience chosen by name, never to a condition written by hand. These are the audiences
it offers, and an item may also be put back to the one it shipped with:

| Audience | Condition |
|---|---|
| Everyone | `kdr:app` |
| Everyone signed in | `kdr:loggedIn,kdr:app` |
| Signed-out visitors | `kdr:anonymous,kdr:signInOpenToSome,kdr:app` |
| Administrators | `kdr:administersClient,kdr:app` |
| Administrators and editors | `kdr:seesClientForms,kdr:app` |
| Signed-in people who are neither administrators nor editors | `kdr:loggedIn,~kdr:seesClientForms,kdr:app` |
| Operators and administrators | `kdr:isClientOperator,kdr:app` |

## Home cards

Home's cards are the block `homeCards`, their items under `cards`, and change the way the menu's items do:
named by id, hidden with `#never`, reworded with a new `title` or `text`. They have no editor, so a
change is an overlay in the client's configuration.

A card whose body is copy is the exception: it names a key of the `home` copy file in `textCopy`, and the page
draws that key's words in place of its `text`, so an overlay's `text` changes nothing there. Reword such a card's
body as any copy is reworded, with the copy editor. The platform's are
**Register** (`registerCard.text`).

A card's condition decides only from facts that are cheap to know. A card whose words depend on the client's data, such
as what waits on the reader, names a `kind`, and the page reads that data when it draws the card. This overlay
hides the new client's checklist:

```json
{
  "blockId": "homeCards",
  "content": {
    "cards": [{ "id": "checklist","cfactExpression": "#never" }]
  }
}
```

## The core menu

Every item the platform ships to a client's people, in the order it is drawn. "Shown to" is the item's condition as
shipped, with the name the menu editor gives it where it has one. An item with no condition is offered on every node; a
header is drawn only while an item under it is shown.

The deployment's own items are left out, each with what is under it:
**Become deployment admin**, **Become a user…**, **Clients**, **Customers**, **Operator**, **Simulations**, and **Debug**.
Each is shown only to the deployment's operators and administrators, to a test instance's sessions, or to a debug
session, never to a client's people.

| Id | Label | Under | Does | Shown to |
|---|---|---|---|---|
| `home` | Home |  | Opens `home` | Everyone: `kdr:app` |
| `account` | Account |  | Group header | Everyone: `kdr:app` |
| `profile` | Profile | Account (`account`) | Opens `profile` | Everyone signed in: `kdr:loggedIn,kdr:app` |
| `logout` | Log out | Account (`account`) | Calls `["logout"]` | Everyone signed in: `kdr:loggedIn,kdr:app` |
| `login` | Log in | Account (`account`) | Opens `login` | Signed-out visitors: `kdr:anonymous,kdr:signInOpenToSome,kdr:app` |
| `register` | Register | Account (`account`) | Opens `register` | Signed-out visitors: `kdr:anonymous,kdr:signInOpenToSome,kdr:app` |
| `users` | Users |  | Opens `users` | Anyone at the administrator level, and editors: `(kdr:hasAdminLevel\|kdr:isEditor),kdr:app` |
| `myClient` | My client |  | Opens `clients` | Administrators of this client alone: `kdr:administersClient,~kdr:isDeploymentAdmin,kdr:app` |
| `myCustomer` | My customer |  | Opens `customers` | Administrators of a customer's root client: `kdr:administersCustomer,~kdr:isDeploymentAdmin,kdr:app` |
| `reports` | Reports |  | Opens `reports` | Administrators: `kdr:administersClient,kdr:app` |
| `apiKeys` | API keys |  | Opens `apiKeys` | Administrators: `kdr:administersClient,kdr:app` |
| `forms` | My forms |  | Opens `forms` | Signed-in people who are neither administrators nor editors: `kdr:loggedIn,~kdr:seesClientForms,kdr:app` |
| `clientForms` | Forms |  | Opens `forms` | Administrators and editors: `kdr:seesClientForms,kdr:app` |
| `workflows` | Workflows |  | Opens `workflows` | Nobody until a client turns it on (`#never`) |
| `reference` | Reference |  | Group header | Everyone, on every node |
| `docs` | Documents | Reference (`reference`) | Opens `docs` | Everyone: `kdr:app` |
| `catalog` | Endpoint catalog | Reference (`reference`) | Opens `catalog` | Everyone, on every node |
| `cfactReference` | Client facts | Reference (`reference`) | Opens `cfacts` | Operators and administrators: `kdr:isClientOperator,kdr:app` |

## The core home cards

Every card the platform ships to a client's people, in the order it is drawn, leaving out the deployment's own as the
menu above does. A component's cards, such as the workflow assistant's, are not among them.

| Id | Title | Shown to |
|---|---|---|
| `register` | Register | `kdr:anonymous,kdr:signInOpenToEveryone,kdr:app` |
| `waiting` | Waiting on you | `kdr:loggedIn,kdr:clientHasWaitingWorkflows,kdr:app` |
| `drafts` | Unpublished drafts | `kdr:administersClient,kdr:clientHasSandbox,kdr:app` |
| `checklist` | Getting started | `kdr:administersClient,kdr:selfServeClient,~kdr:inSandbox,kdr:app` |
| `administer` | Your client | Administrators of this client alone: `kdr:administersClient,~kdr:isDeploymentAdmin,kdr:app` |
| `edit` | Your client's forms | `kdr:isEditor,kdr:app` |
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes its lists of the core menu, the core home
cards, and the menu editor's audiences from the platform's own declarations, so they cannot disagree with what it
ships. Read it on the server's **Documents** page, under **Reference**, then **Menu and home cards**, where anyone may
open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/menu-and-home-cards](https://dynamicruntime.org/wa/docs/menu-and-home-cards).
`./gradlew :launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit
the generator (below), never the copy.

On a running server, a link to this file opens the guide itself.

## Where it comes from

| What | Source |
| --- | --- |
| The core menu | `homeMenuBlock` in [`HomeEndpoints.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/home/HomeEndpoints.kt) |
| The core home cards | `homeCardsBlock` in [`HomeCardsBlock.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/home/HomeCardsBlock.kt) |
| The audiences the menu editor offers | [`HomeMenuAudiences.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/home/HomeMenuAudiences.kt) |
| The guide, made from these | [`MenuReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/home/MenuReference.kt) |
