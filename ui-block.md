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

## Invoking frontend functionality: named registries

A UiBlock's third kind of reference, after copy and conditions. A menu item does something when clicked; a
workflow form names the component that renders it. Both are the backend's data pointing at the **frontend's**
code, and both are the same construct.

### A string is a route; an array is a call

```json
"action": "/forms/123"
"action": ["confirmDelete", "formDoc", "123"]
```

An array's first element is the name of a registered function; the rest are its parameters. The JSON **type**
is the discriminator, so nothing needs a sigil — and unlike a delimited path (`f:confirmDelete/formDoc/123`)
there is no separator for a parameter to collide with, so a parameter may be any string at all. That deleted
rule is the reason for the array rather than tidiness.

This is a legitimate union rather than a shortcut past `thoughts-schema-direction.md`'s "declare the
discriminator": that rule is about telling *object variants* apart, where nothing in the JSON says which branch
you are in. Here a validator checks the type directly — `{"anyOf": [{"type": "string"}, {"type": "array"}]}`.

### One construct, not a family of lookalikes

A component reference is the same shape as an action: a name, then parameters. **Keep it literally the same
construct** — one validator, one boot check, one frontend lookup — rather than two that resemble each other.
Registries introduced separately drift in small ways (one checks arity, one does not; one takes an array, one
an object), and then a comment saying two things must match is doing the work a mechanism should.

### Why the names live in the kernel

kd2 already runs *closed vocabulary, checked at the earliest binding time*: `optionsSource` ids, boot checks,
environment variables, cfacts. In all of those the reference and the implementation are both backend, so one
side can check the other.

This pattern is that one **across the boundary** — the reference is authored in backend data, the
implementation is a frontend artifact, and neither side can see the other. So the names must live in shared
kernel code, as `HACT.logout` already does. That is not tidiness; it is the only thing that makes the reference
checkable at all. Without it, a misspelled name is a click that silently does nothing — the same silent failure
as an orphaned overlay key or an unresolved fragment reference, which is the third time this family has come up
in this design.

The registry should declare each entry's **arity**, so a wrong parameter count is a boot failure rather than an
undefined argument at click time. The array makes arity readable without parsing, which is what makes that
check cheap.

### The registry stays hardwired, and that is load-bearing

A config may only ever name a function a developer wrote. This is the line that keeps *extensibility through
data* from becoming *code in data*: the name is the seam, everything on the data side is configurable,
per-client and overlayable, and everything on the code side is typed, tested and reviewed.

So when somebody wants a conditional argument, or two calls in sequence, the answer is a **new named
function**, not a richer array — the same line held for cfact expressions, where a new condition is a new cfact
computed in Kotlin rather than a new operator. This is the configuration complexity clock, and the registry is
the stop placed on it (see `vocabulary-code-vs-data.md`).

### Deferred, with the shape already decided

A parameter may eventually be a **Map**, which is what a component reference wants (`["FormEditor", {"mode":
"edit"}]`). Two notes for when that door opens:

- It is the same question as "one construct or two". With a Map, a component reference and a function call are
  indistinguishable in shape, which is right if they are one construct and confusing if they are not. Deciding
  they are one now is what makes the Map cheap to add later.
- The registry entry should **declare what it accepts**, because a map of arbitrary keys is one step from an
  expression tree. A schema is the kd2 answer everywhere else and applies here too.

A component reference is a stronger coupling than a function call, and worth knowing before it lands: the
component's props become a contract between backend-authored data and frontend code. Undeclared, that contract
fails the usual way — the backend passes `mode`, the component reads `viewMode`, and nothing says so.

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
