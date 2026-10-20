# UI block

**Status: written as intent, now largely built.** Written before the code, as `docs/design/gedra/entry.md` was, so that
the term and the decisions already settled are recorded somewhere more durable than an issue thread. The registry, its
overlays and merge (issue #457, `UiBlockService`), the backend's cfact resolution (`filterByCFacts`), the selector
(#788), the boot checks, and the menu editor exist, and a workflow task's `display` is resolved by the same walk. What
is still ahead says so where it appears: fragment references inside a block, and the two boot checks they would need.

## What it is

A **UiBlock** is a registered piece of **declared presentation structure**: JSON describing part of a user
interface, contributed by a component, varied by a client, and merged from layers the way a Markdown fragment
is. The home menu is a UiBlock (`homeMenuBlock`). A workflow task's `display` is not a registered block, but it is
resolved by the same walk.

It is the counterpart to the two things that already exist:

| | holds | varies by | 
| --- | --- | --- |
| **Gedra** | stored entity data | client, and the data itself |
| **fragment** | copy — the words a person reads | client, through overlays (issue #456) |
| **UiBlock** | structure — what is shown, in what order, under what condition | client, through the same layering |

## A UiBlock is the unit of registration, not of display

One UiBlock is one registered, mergeable, client-varying whole. **A menu is a UiBlock; a menu item is not.**
Items live inside one and are merged into it by their primary key.

Worth stating because the alternative reading is available and would dissolve the term: if every element were
a UiBlock, the word would mean "part of a UI" and carry no information. What it actually names is *the thing a
contributor registers and an overlay targets*.

## How layers merge

A **base** supplies the block and declares how its arrays merge; an **overlay** names only what it changes.

- **Objects merge key by key**, recursing into nested objects. Anywhere else, the later layer's value replaces the
  earlier one's.
- **An array merges item by item only where the base declares a primary key for it** (`arrayKeys`; the menu's
  items are keyed by `id`). An overlay's item whose key the base has is folded into that item, and one with a new
  key is appended. An array with no declared key is replaced wholesale, since position cannot identify an item once
  somebody inserts one.
- **Only a base declares the keys.** An overlay that tries is refused: it would be changing how it is itself
  folded in.
- **Nothing is ever removed.** An overlay withdraws an item by setting its `cfactExpression` to `#never`, and
  `#always` puts it back. The base stays one readable list of everything that exists, and "why is this gone?" is
  answered by reading the item. The menu editor hides an item the same way.
- **A base stamps `displayOrder`** on its items in the order they are written, in steps of 100, which leaves room
  for a later contributor to land between two. An overlay never stamps it, so an item an overlay adds states its
  own. Each keyed array is sorted by `displayOrder`, then by primary key, and an item with no `displayOrder` sorts
  last.

## Layer order, and seeing a client's layers

A UiBlock and a fragment file fold their layers in the same order, later winning (`overlayPrecedence`, issue
#916): the **bases**, then **component** overlays, then a deployment **flavor**'s (issue #1219), then what the
client **inherited** from the template it extends (issue #945), then the client's own from **source**
configuration, then the client's own from **stored** configuration. The flavor step applies to fragments only: no
UiBlock layer comes from a flavor yet. A stable sort, so declaration order settles ties within a step. Stored last
is a rule rather than a load-order accident because editing a client's copy or menu writes stored configuration,
and that edit has to win over the client's own source config. Inherited sits below the client's own layers for
the same kind of reason: a template is what the client starts from, so anything the client says itself wins.

`GET /clientAdmin/client/overrides` reports what a client's own layers change — fragment keys, and UiBlock items
(by their array key) or objects — each with what everybody else gets, what the client gets, and the config that
set it. A component's overlays count as what everybody gets, not as a client's change.

## How a client changes a block

A client changes a block with an overlay in its configuration: `uiBlockOverlay(blockId) { ... }` in the
`gedraConfig` DSL, naming only what it changes, matched to the base's items by their key. In stored
configuration, the overlay is an entry of the `kdr:uiBlockDef` slot, holding the block id and the content. The home
menu also has an editor (issue #919): `GET /clientAdmin/client/menu/items` lists the items as the client sees
them, `POST /clientAdmin/client/menu/set` renames, hides, or shows one, and `POST /clientAdmin/client/menu/reset`
removes the client's changes to one. Showing an item picks one of the audiences the menu declares
(`HomeMenuAudiences`, issue #1094) by name, rather than writing an expression. An edit is written into the
client's stored configuration, trial-checked, published, and reloaded in one call. For a client with a
[sandbox](sandboxes.md), it is saved as a draft that the sandbox shows instead, and goes live when the draft is
published (issue #930). Either way it changes what is offered, not what anyone may reach: the section gate still
decides.

## Not "blob"

The working name was "SDUI blob", and `blob` says *opaque bag of bytes* — which is precisely wrong. A UiBlock
is structured, its arrays merge by a declared primary key, its items carry a `displayOrder`, and its fragment
references and cfact expressions are checkable. A reader who sees "blob" assumes the inside is not their
business.

The `SDUI` qualifier is dropped too: it describes *why* the mechanism exists rather than what the thing is, and
"SDUI UiBlock" is redundant.

Casing is **`UiBlock`**, matching `UiConfig`, and its constants object is `UIB`, as `UiConfig`'s is `UIC`.

## What a UiBlock carries, and where each part resolves

A UiBlock holds **cfact expressions** (whether to show something) and is designed to hold **fragment references**
(the copy to show). Only the first is built: the menu's labels are literal text, and nothing in a block refers to
a fragment yet. The two resolve in different places, and the split is deliberate:

- **cfact expressions are evaluated by the backend**, before the block is put in a UI-config response. The
  frontend receives what it may see; an item whose condition did not match is simply absent.
- **Copy resolves in two passes.** A backend pull (`%{@t(...)}`) names its fragment file outright, so the server
  resolves it and the boot checks it; a workflow task's chosen `display` text goes through this pass. What is left
  for the frontend is a `${...}` substitution of data the frontend supplies, and a frontend pull
  (`${@t("ns.key")}`), resolved against copy it has already fetched and cached.

### Why that split

**Binding time.** A cfact is a fact about *this request* — who is calling, what they may see, which node this
is. It cannot be cached and differs for the next caller. A fragment reference resolves against content that is
stable for a whole (client, deployment), which is why that content is fetched separately and cached
immutably. Each is resolved where its answer is stable enough to be. Smaller UI-config responses follow from
that rather than driving it.

**And sending expressions would mean sending the cfacts with them.** The frontend cannot evaluate a condition
without the set to evaluate against, and that set names `isAdmin` and whatever private vocabulary a client
registered. Evaluating server-side is what keeps it off the wire.

Stated as one rule: **the backend decides whether and which; the frontend decides how it reads**, except for copy
a backend pull names outright, which the backend resolves because it can check it there.

### Choosing among renderings: the selector (issue #788)

A plain `cfactExpression` answers "this, if it applies". Its ordered sibling, **`select`**, answers "the first of
these that applies": an object carrying `select` stands for one of the branches it lists, each optionally
guarded by a `cfactExpression`. The resolver tries them **in order** and puts the first whose condition holds in
place of the selector; a branch with no condition always matches, so an unguarded last branch is the default.
When nothing matches, the selector is absent -- from its field, or from its array -- exactly like an object whose
condition failed.

```json
{"display": {"select": [
  {"cfactExpression": "acmeAuditApproved", "mode": "text", "text": "Approved by ${approvedByName}."},
  {"cfactExpression": "~kdr:isCta", "mode": "text", "text": "Not yet.", "disabled": true},
  {"cfactExpression": "kdr:reviewer", "mode": "default"},
  {"mode": "text", "text": "You must wait for a reviewer."}
]}}
```

It is resolved by the same recursive walk as the plain condition (`filterByCFacts`), so it works anywhere -- a
menu, a home block, a workflow task's `display` -- without the resolver knowing what it is choosing. The same
rule applies: only the chosen branch travels, never the others or their conditions. Order is the author's to
get right; in the example "approved" comes first because an approved step is no longer the CTA, so the
`~kdr:isCta` branch would otherwise claim it. Every branch condition is reached by the boot check's walk, so a
misspelled cfact in any branch is found like any other, at the cost *Checked at boot* gives for its layer.

## Actions: a string is a route, an array is a call

A menu item does something when chosen, through its `action`:

```json
"action": "/forms/123"
"action": ["setEnvDebug", "true"]
```

A string is a route to a frontend page. An array is a call: its first element names a frontend function, and the
rest are that function's parameters. The JSON **type** tells the two apart, so nothing needs a sigil, and there is
no delimiter for a parameter to collide with. A union told apart by type needs no declared discriminator: that
rule is for object variants, where nothing in the JSON says which branch an object is, while here a validator
checks the type directly.

The functions a block may name are the **`UiActions`** registry, each declared in shared kernel code with its
**arity** (`HACT.logout`, `HACT.setEnvDebug`, and their neighbors). Shared code because the reference is backend
data and the implementation is frontend code, so neither side can check the other without a common list, and a
misspelled name would otherwise be a click that silently does nothing.

The registry is hardwired on purpose. A configuration may only name a function a developer wrote, so a wish for a
conditional argument, or for two calls in a row, is answered with a new named function rather than a richer
array. Conditions hold the same line: a new condition is a new cfact computed in Kotlin, never a new operator.
Each step that moves logic from code into configuration looks sensible on its own, and configuration that keeps
gaining such steps ends up as a weaker programming language, so the stop is placed deliberately.

## Checked at boot

`UiBlockService` checks every block when the node starts, and a reload or a trial judges a client's new overlays
the same way. The block is merged for everybody and for each client that overlays it, and each is checked:

- **An overlay names a block somebody registered.** Otherwise it would merge onto nothing and never be served.
- **Every cfact name is registered** for the client the block is merged for (issue #455), in plain conditions and
  in selector branches alike.
- **Every call names a `UiActions` function, with that function's arity.** `UiCall` already refuses a wrong count
  where a call is written in Kotlin; this catches one that arrives as data.
- **Nesting by `parentId` holds** (issue #517): an item's parent is another item of the same array, not itself,
  and top-level, since nesting is one level; and an item with children carries no action, because it is drawn as
  a group header.

The frontend half of the action check is a webapp test: it asserts that `FrontendActions` implements every
declared name (`ActionRegistry.missing()`), the one gap the backend cannot see.

**A fault costs only its layer** (issue #841). A client's overlay at fault is dropped, and the block serves without
it, under the check mode of the configuration holding it. By default a source config's fault refuses the boot
outside production, and a stored config's refuses it only in unit tests. A fault in a component's own base block
refuses the boot outside production; in production it is reported and the block kept as it is, since there is
nothing narrower to drop.

### Not yet checked: fragment references

These wait for fragment references in blocks, which are not built:

- **Every fragment reference resolves.** A reference to a key no fragment declares does not fail — the render
  path prints the key path — which is the same silent failure an orphaned overlay key has, and it should get
  the same boot check (issue #456 built that check for overlays).
- **Every referenced fragment file is one the same response carries.** A UI-config hands the frontend a set of
  `fileId:buildId` refs; a UiBlock referring to a file outside that set leaves the frontend holding a
  reference it has nothing to resolve against.

A UiBlock and the fragments it references would be resolved for the same client, and the frontend fetches by the
ref the config gave it, so a client's block cannot resolve against another client's copy. That will hold by
construction rather than by a check.
