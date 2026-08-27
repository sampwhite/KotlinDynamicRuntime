# The UiBlock

**Status: intent, not description. Nothing here is built** — see issue #457. Written before the code, as
`gedra-entry.md` was, so that the term and the decisions already settled are recorded somewhere more durable
than an issue thread.

## What it is

A **UiBlock** is a registered piece of **declared presentation structure**: JSON describing part of a user
interface, contributed by a component, varied by a client, and merged from layers the way a Markdown fragment
is. A menu is a UiBlock. So, eventually, is a workflow task's presentation.

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

## Not "blob"

The working name was "SDUI blob", and `blob` says *opaque bag of bytes* — which is precisely wrong. A UiBlock
is structured, its arrays merge by a declared primary key, its items carry a `displayOrder`, and its fragment
references and cfact expressions are checkable. A reader who sees "blob" assumes the inside is not their
business.

The `SDUI` qualifier is dropped too: it describes *why* the mechanism exists rather than what the thing is, and
"SDUI UiBlock" is redundant.

Casing is **`UiBlock`**, matching `UiConfig`, which leaves `UIB` free for a constants object as `UIC` pairs
with `UiConfig` today.

## What a UiBlock carries, and where each part resolves

A UiBlock holds **fragment references** (the copy to show) and **cfact expressions** (whether to show it).
Those resolve in different places, and the split is deliberate:

- **cfact expressions are evaluated by the backend**, before the block is put in a UI-config response. The
  frontend receives what it may see; an item whose condition did not match is simply absent, exactly as the
  menu behaves today.
- **fragment references are resolved by the frontend**, against copy it has already fetched and cached.

### Why that split

**Binding time.** A cfact is a fact about *this request* — who is calling, what they may see, which node this
is. It cannot be cached and differs for the next caller. A fragment reference resolves against content that is
stable for a whole (client, deployment), which is why that content is fetched separately and cached
immutably. Each is resolved where its answer is stable enough to be. Smaller UI-config responses follow from
that rather than driving it.

**And sending expressions would mean sending the cfacts with them.** The frontend cannot evaluate a condition
without the set to evaluate against, and that set names `isAdmin` and whatever private vocabulary a client
registered. Evaluating server-side is what keeps it off the wire.

Stated as one rule: **the backend decides whether and which; the frontend decides how it reads.**

## Invariants worth checking at boot

- **Every fragment reference resolves.** A reference to a key no fragment declares does not fail — the render
  path prints the key path — which is the same silent failure an orphaned overlay key has, and it should get
  the same boot check (issue #456 built that check for overlays).
- **Every referenced fragment file is one the same response carries.** A UI-config hands the frontend a set of
  `fileId:buildId` refs; a UiBlock referring to a file outside that set leaves the frontend holding a
  reference it has nothing to resolve against.
- **Every cfact name is registered** — already true by construction, since expressions are parsed against the
  client's registry (issue #455).

A UiBlock and the fragments it references are resolved for the same client, and the frontend fetches by the ref
the config gave it, so a client's block cannot resolve against another client's copy. That holds by
construction rather than by a check.
