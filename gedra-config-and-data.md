# Gedra Config and Data

How definitions and stored data are separated, identified, and kept from seeing each other. Written for
whoever has to work inside these rules — developers now, client administrators later — and for the reader who
arrives six months from now wondering why an id looks like that.

It is a companion to [`gedra-entry.md`](gedra-entry.md), which describes the shape of a single stored entry.
This one is about everything around one: which family it belongs to, whose it is, who may see it, and what
happens when two definitions disagree.

Unlike `gedra-entry.md`, this document describes a mixture of what exists and what is intended. Each section
says which, and [What is real today](#what-is-real-today) collects the answer in one place. A rule that is not
yet enforced is still a rule worth knowing, because the code is being written to leave room for it.

## Two families, one shape

A **gedra** is the universal stored entity. There are two families of them:

- **data** — what people entered. Form documents, workflow data, user data, file references.
- **config** — the definitions that give data its meaning. Traits now; workflow definitions later, and the
  definitions of the client spaces themselves.

Underneath they are the *same shape*: a gedra carrying entries. That is deliberate. Editing, revisioning,
auditing and permission-checking get written once and apply to both, and a config object can be edited by the
same machinery that edits a form — which is what makes an administrator authoring a definition an ordinary
act rather than a special one.

Config is bundled rather than scattered. One config object holds a set of traits, and will hold the workflows
that use them, so that `$ref`s and workflow-to-trait links stay mostly *inside* one object. Links between
bundles remain possible and are meant to be few — few enough that an audit of them would be worth reading.

## How an id works

Every gedra is named by a `GedraId`:

```
gd.fd.acme.e20260812130405123AbCd~7
~~ ~~ ~~~~ ~~~~~~~~~~~~~~~~~~~~~~ ~
|  |  |    |                      the 7th row of the import that produced it
|  |  |    an opaque base id -- `e` says an Excel import made it
|  |  the owning client
|  a form document
stored as data
```

The first three segments are structure and are read back out. The last two are not.

**Storage and kind** say which family and what the gedra is: `gd.fd` is a form document, `gc.cd` is a config
bundle. Both abbreviations are two characters, always, so the first six characters of every id line up when
you read a column of them.

**The client** is the owning client space, or the reserved `global` for anything belonging to the deployment
rather than to a client. It is the most consequential segment; see [Client separation](#client-separation).

**The base id is opaque.** It is built to be invariant given its inputs, never to be taken apart. Two flavors:

- *Random* — a single letter naming where it came from (`e` an import, `u` somebody in the UI), followed by
  the project's standard time-sortable unique id. The letter is there so a column of ids shows at a glance
  how those objects arrived.
- *Deterministic* — spelled from what makes it unique. A user's own gedra is `u12`; the one workflow a user
  may have is `u12_acmePaymentWf`; a config bundle is its own code-explicit name, `coreTraits`.

Deterministic ids buy something worth knowing: **you can build the id rather than look it up.** "Does this
user already have one of these?" is answered by constructing the id and asking, with nothing stored to say so.

**The suffix** after `~` is optional and means different things by family. For data it is a child's position
within an imported parent — a spreadsheet's rows share their parent's base id and differ by suffix, so a set
that arrived together is visible as such and often traceable back to the row. For config it is the
**revision**, and an absent suffix will mean *the revision active in this context* — which is how a preview
environment and production can name the same workflow and get different answers.

Three properties fall out of the format and are worth not breaking:

- **`.` and `~` are unreserved in RFC 3986**, so an id needs no escaping in a URL.
- **The base id is held to `[A-Za-z0-9_]`**, ASCII only. That keeps the separators out, which is what makes
  the format parseable; it keeps a base id a single token to a log tokenizer, so pasting one into a search
  finds it; and it stops a Cyrillic lookalike minting an id that reads identically to a person and differently
  to a cache.
- **Ids sort usefully** — storage, then kind, then client, then origin, then time.

## Client separation

### A client is itself a defined thing

*Intended; nothing defines a client today.* A client space is not a bare string that appears in ids — it will
be **config**, defined the way everything else is. Config gedras carry entries like any other gedra, so a
client's definition is a config gedra carrying an entry of a globally defined trait, rather than a new gedra
kind. That is what bundling bought: what would have been a `clientDef` kind is an ordinary trait instead.

Two questions are open, and both are worth settling before anything is built:

- **Where a client's own definition lives** — in `global`, or in the client's own space. The self-referential
  form works, since an id can be constructed before the thing it names exists. What differs is discovery: a
  separated deployment learns *which* client it serves from its own configuration rather than from data, so
  either can be loaded, but keeping them in `global` puts every client definition in the one space every
  deployment already holds.
- **What that makes `global`.** Today it is the home of shared definitions. If client definitions live there
  too, it also becomes the registry of who exists — a second job for the one space that never separates, and
  one to take on deliberately rather than by default.

The client in every id is not bookkeeping. It is the seam the whole stack can be split along, and the depth
of the cut is a choice we make per deployment rather than once for the architecture.

The intent, stated plainly:

- A client can be served by a **different deployment**. That deployment loads *that client only*, plus the
  demo and internal clients we will mandate in source code.
- Other deployments do not load it and are not aware it exists.
- It may still live in the **same database**. That is a separate decision from which deployment serves it —
  sharing storage while separating service is a legitimate configuration, and so is separating both.

So the boundary is always the client; what varies is which layers we cut at. A deployment for one demanding
client, sharing a database with everyone else. A deployment with its own database. Eventually its own
everything. The code should never assume more sharing than the current configuration provides, and never
assume less.

### The consequence you will actually feel

**You cannot casually move a user, or their data, from one client to another.**

Moving is an **export followed by an import**, deliberately. It is not a database update, and it is not
something to do on a Tuesday afternoon because somebody was created in the wrong place. Everything that comes
back in receives **new `GedraId`s**, because the client is inside the id.

That last point looks like a cost and is the reason the scheme works. An id names its client, so it cannot be
silently reinterpreted in another client's space. A move that renumbered nothing would leave every id claiming
a client that no longer holds it — which is exactly the sort of quiet inconsistency that survives for months
and then surfaces as a permissions bug.

## Visibility

A client's definitions may reference definitions **they own, or that `global` owns**. Nothing else. Extending
a trait and pulling one in to make it available in a client space are both reaches across configs, and both
are bounded this way.

The mechanism is namespace ownership, not a separate store per client:

- A **namespace belongs to exactly one owner** — `global`, or one client.
- A config owned by client C may only `$ref` into namespaces owned by **C or `global`**.

Reserving `globalconfig` is the first instance of that rule rather than a special case: `global` claims it,
and nobody else may write into it. Stating it as ownership is what makes the rule survive the arrival of a
second owner; stating it as one reserved name would not.

Visibility is also *why* two clients may define the same trait id harmlessly — neither can see the other's.
That collision is reported to us as a configuration warning, never to the client, and it does not stop
anything working. It does have to be cleared before that config can be promoted into source code, which is
what keeps "we would prefer uniqueness" from being merely aspirational.

## Two uniqueness rules that only look contradictory

- **`traitId` is global** — one trait name across every namespace and every gedra kind. This is what lets
  stored data carry a bare trait id and nothing else, and what lets an entry be understood when it is met
  outside the gedra that held it: in a log line, an export, a queue message.
- **Type names are namespaced** — so two config bundles may each generate a `NameEntry` without colliding.

The namespace is not scoping traits. It is scoping the *types* that traits create. Anybody who "fixes" the
inconsistency by namespacing trait ids breaks every stored entry, because the stored form has no namespace to
resolve against.

Global uniqueness would force ugly names — a `name` trait and a `wfDataName` trait meaning the same thing —
except that **a trait declares the set of kinds it applies to**. `name` means the same thing on a form
document and on workflow data, so it is one trait bound to both. Where two traits genuinely are different
concepts, they get different ids, which was the right answer anyway.

## A trait's own data lives under `data`

An entry looks like this:

```json
{"traitId": "name", "data": {"name": "My Expense Form"}}
```

The trait's fields sit under `data` rather than beside `traitId`, and the reason is the envelope's future.
`entryId`, `source`, `createdAt` and `updatedAt` are there today; `origin`, `lockedBy`, `createdBy` and
`updatedBy` are coming. In a flat entry, **every one of those additions is a silent breaking change to any
trait that already used that field name** — and it would be found when a client's stored data stopped
validating, long after the fact.

One level down, the envelope grows forever and no trait author notices. The response envelope made the same
call for the same reason: `results` / `item` / `items` keep a handler's payload clear of `requestUri`,
`duration`, and everything added since.

The first trait shows why it is not hypothetical. Trait `name`, field `name`. Flat, that is
`{"traitId": "name", "name": "..."}` — and the day the envelope wants a name of its own there is nowhere to
put it.

## The default variant

Entries are validated through a discriminated union: the `traitId` selects a branch, and the branch says what
that trait's data must look like. The union also declares a **default branch**, where a `traitId` naming no
known branch goes.

That is not laxity. Trait definitions are authored at runtime by people who are not us, so **meeting a trait
this reader has never heard of is an ordinary event**, not a defect. Client separation guarantees it: an
administrative surface looking across clients will meet traits belonging to a client whose config this
deployment never loaded. Without a default branch, one unknown entry takes down the whole payload it arrived
in.

The default branch is deliberately **open** — it declares no fields beyond the discriminator. A closed
catch-all would reject every unrecognized entry that carried anything, which is all of them, and a closed one
that merely dropped the unknown fields would be worse: the entry would pass through emptied, with nothing
saying so.

### But not everywhere

Falling through is right when you are looking across clients and wrong when you are not. Inside one client
every trait is known, so an unrecognized `traitId` there is a caller error and should be reported as one.

So strictness is the **reader's** choice rather than a second union: one type, and an endpoint validating in
strict mode refuses to fall into the default instead of a differently-shaped type existing without one. Two
rules go with that:

- **Strict is the default and leniency is opted into.** The lenient direction silently accepts data nobody
  can interpret, and a mistake in the strict direction reports itself.
- Endpoints scoped to a single client are strict; endpoints that can see data across clients need the
  leniency.

## What is checked, and what a failure does

Configuration is checked as it is collected at startup. What a problem *does* depends on where the node is
running:

- **Everywhere but production** — the boot is refused. Silence while the author is still at the keyboard is
  how a defect reaches production.
- **Production** — the problem is logged at error, the offending definition is dropped, the first contributor
  wins, and the node serves. Refusing to boot a production node over one bad trait takes down every endpoint
  that had nothing to do with it.

`KDR_GEDRA_CONFIG_CHECK=strict|warn|off` overrides either way.

The production path is only survivable because of the default branch above: entries carrying a dropped trait
fall through as unrecognized rather than failing validation. The two decisions hold each other up, which is
worth knowing before changing either.

### The boundary, so this does not get over-applied

Production degrades for a **configuration defect on the side** — one trait wrong, the rest of the instance
fine. It does **not** degrade for:

- a **security fence**. Refusing to start a test instance outside `local`/`unit` is absolute, and so is
  anything else whose failure means the wrong people can reach something.
- a **misconfiguration that leaves the node unable to do its job**. Database drift that would make every
  insert fail refuses in production too: a table that cannot be written to is not a defect on the side.

The first fails toward "one feature is wrong". The second fails toward "this is not a working deployment".
Only the first is worth trading for uptime.

### What is checked today

| Check | Refused |
|---|---|
| A `traitId` claimed by two config bundles, in any namespace, for any kind | naming both bundles |
| A namespace claimed by two owners | naming the owner it already has |
| The same config bundle contributed twice | |

Each message names both sides, so a reader is never left hunting the other half.

## Where config comes from, and where it goes

Config is expected to *mature*. An administrator authors definitions through an admin surface, and they live
in the database — quick to change, quick to get wrong, and exactly right while a client is still working out
what they need. As a client's application settles and becomes something we maintain and test, those
definitions move into a source repository and are deployed as code.

Two properties make that a move rather than a rewrite:

- **Code-defined and database-defined config are the same shape**, built by the same objects.
- **They are identified the same way.** A config bundle declared in code carries a real `GedraId` —
  `gc.cd.global.coreTraits` — exactly as a stored one will. Nothing has to know which sort it is holding.

Outstanding configuration warnings must be cleared before a config is promoted. A duplicated trait id is
tolerable while config lives in a database and intolerable once it is code we release, and that gate is what
gives the warning teeth.

## What is real today

| | |
|---|---|
| `GedraId`, its format, parsing and minting | **built** |
| The config kind, config bundles, traits, and the entry types they generate | **built** |
| Component-contributed config, and the checks over it | **built** |
| The environment split for those checks | **built** |
| The default branch, and strictness as a reader's choice | designed; not yet built |
| Client separation across deployments | intended — nothing loads a subset of clients yet |
| Client-authored config, and the visibility check | intended — every config today is `global`, in code |
| Client spaces defined in config rather than named in ids | intended — the step after entries can be stored |
| Config revisions, and an absent suffix meaning "active" | intended |
| Promotion from database to source code | intended |

Anything marked *intended* is a rule the built code is shaped to leave room for, not a promise about when it
arrives. Where the room matters, the code says so at the point somebody would otherwise take it away.
