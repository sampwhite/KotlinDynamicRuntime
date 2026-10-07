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
- **config** — the definitions that give data its meaning. Traits, workflow definitions, and the definitions of
  the client spaces themselves.

**Config stored in a database** is a gedra carrying entries, exactly like data. That is deliberate: editing,
revisioning, auditing and permission-checking get written once and apply to both, and an administrator
authoring a definition is doing an ordinary act rather than a special one.

Entries are instances of traits, so stored config needs traits of its own — declared in `coreConfigTraits`
(#316). They are hardwired rather than contributed, since what may be stored into config is fixed
by us, and they are a different animal from the everyday sort: one of them has to describe a JSON Schema
document, which is checked by *parsing* the candidate rather than by describing our schema layer in schema.

**Config written in source code is not.** It is declared directly, with no entry envelope around it, because
the envelope exists to answer *when* and *by whom* — and once config is code, version control answers both
for free. So the wrapper is a **storage** concern rather than a definitional one, and extracting config from
a database unwraps the definition out of its protocol parent before it becomes ordinary source. See
[Where config comes from](#where-config-comes-from-and-where-it-goes).

### The bundle is an authoring unit, not an architectural one

A config bundle holds a set of traits and the workflows that use them, so that `$ref`s and
workflow-to-trait links stay mostly *inside* one object. Links between bundles remain possible and are meant
to be few — few enough that an audit of them would be worth reading.

But what actually groups definitions is the **client in their ids**: every config sharing a client is
assembled together, however many bundles they were written as. That makes granularity a free choice, sized to
the work rather than to the architecture — the same judgement as deciding whether a subsystem's schema, data
classes, service and endpoints belong in one Kotlin file or several.

- Setting up a QA scenario — one client, a simple workflow, a few traits to exercise one behaviour — is
  reasonably **one bundle holding all of it**.
- A serious client might define the client in one bundle, each workflow in its own, and pull separate groups
  of traits into bundles of their own.

Both assemble identically. Nothing downstream can tell how the definitions were divided up, which is what
makes the choice safe to get wrong and cheap to change.

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

*Built (issue #343; see [`client-definition.md`](client-definition.md)).* A client space is not a bare string that
appears in ids — it is **config**, declared the way everything else is, in a `GedraConfig` of its own.

That definition is **scoped to the client it defines**: the bundle declares the client id and carries that
same id in its own `GedraId`. The apparent circularity is the point rather than a problem — an id can be
constructed before the thing it names exists, so the definition and the space it defines come into being
together rather than one waiting on the other.

It also keeps `global` doing one job. `global` is the home of shared definitions; making it additionally the
registry of who exists would give the one space that never separates a second responsibility, and a
client-scoped definition means a deployment serving one client holds that client's definition in the same
place it holds everything else of theirs.

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

- A **namespace belongs to exactly one owner** — `global`, or one client. A client's is fixed by its id: its
  config declares into `client.<clientId>` or beneath it, and nowhere else (#949), so no client's types can land
  where a global name or another client's lives. A sandbox (#928) declares into its parent's.
- A config owned by client C may only `$ref` into namespaces owned by **C or `global`**. This holds by
  construction: C's own types sit in C's overlays, never in the global defs, and C's schema is the global defs
  plus C's overlays, so another client's type is simply not there to resolve.

Global names are owned by **root** (#950): every component declares the owner root its namespaces live under --
core's one root is `kdr` (`kdr.core`, `kdr.gedra`, ...) -- and a module or config under another owner's root says
so by name (`contributesTo`), config by config. `client` is a reserved root, holding only clients' own
namespaces. Stating it as ownership is what makes the rule survive the arrival of more owners; stating it as a
list of reserved names would not.

Visibility is also *why* two clients may define the same trait id harmlessly — neither can see the other's
(issue #807). Each gets its own trait, and it stops nothing working. Promoting one into source code as a global
trait gives it a rooted id (`kdr:…`, or its component's root), so it can never collide with either.

## Two uniqueness rules that only look contradictory

- **`traitId` is unique within a client's view** (issues #807, #951) — a client's own traits and the global ones
  it sees never share an id, **by construction**: a global trait's id is rooted with its owner's root
  (`kdr:name`, `sample:expenseReport`) and a client's is bare (`survey1`), so the two can never meet, and no
  release adding a global trait can take an id a client already uses. A global id is unique across every client
  and gedra kind; a client's own id within that client, and another client may declare the same one and get its
  own. This is what lets stored data carry a trait id and nothing else: the gedra holding an entry names its
  client, so the id resolves among that client's own traits and the global ones, with never two answers. (A
  string usage's search parameter is named for the trait, so a global trait's carries its root too:
  `kdr:name`, `kdr:nameContains`.) An entry met *outside* its gedra -- in a log line, an export, a queue message -- is
  unambiguous only beside the gedra's id (or its client), so anything carrying entries out carries that too.
- **Type names are namespaced** — so two config bundles may each generate a `NameEntry` without colliding.

The namespace is not scoping traits. It is scoping the *types* that traits create. Anybody who "fixes" the
inconsistency by namespacing trait ids breaks every stored entry, because the stored form has no namespace to
resolve against — the client is what scopes a trait id, and a stored entry already has one.

Code bound to a trait by bare id (a data deriver, a save-time function) must name a **global** trait, which the
boot checks: a client's trait id may be another client's too, and the binding would run on both.

Uniqueness within a client's view would force ugly names — a `name` trait and a `wfDataName` trait meaning the
same thing — except that **a trait declares the set of kinds it applies to**. `name` means the same thing on a
form document and on workflow data, so it is one trait bound to both. Where two traits genuinely are different
concepts, they get different ids, which was the right answer anyway.

## A trait's own data lives under `data`

An entry looks like this:

```json
{"traitId": "name", "data": {"name": "My Expense Form"}}
```

The trait's fields sit under `data` rather than beside `traitId`, and the reason is the envelope's future.
`entryId`, `source`, `createdAt`, `updatedAt`, `createdBy` and `updatedBy` are there today; `origin` and
`lockedBy` are coming. In a flat entry, **every one of those additions is a silent breaking change to any
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

**Stored configuration is judged differently** (issue #839). A problem in a client's configuration read back
from the database is **forgiven everywhere except unit tests**: logged, the offending definition dropped, and
the rest served, whether the node is local, staging or production. A node refused over stored data leaves no
tool to repair it but hand-written SQL or a temporary code change. `KDR_STORED_CONFIG_CHECK=strict|warn|off`
governs it (default `warn`, `strict` in `unit`), independently of `KDR_GEDRA_CONFIG_CHECK`.

Which rule applies follows **the definition holding the reference**, not whichever side changed last: a stored
workflow naming a function a code change unregistered is a stored problem, and forgiven; a dangling reference
inside source code still refuses the boot outside production. Each reported problem names its client, the
stored config holding it (when stored), and the definition at fault.

**A forgiven problem is kept on its client** (issue #840), whatever its origin: the client's definition
(`/admin/client/definition`), its summary row, each stored config's summary and bundle, and a reload's result all
carry an `issues` list, replaced each time the client is loaded. A client whose definition a check dropped still
answers its definition read -- `present: false`, with the issues that say why -- rather than a bare 404.

**A fault costs only itself** (issue #841). In a client's own schema, the smallest faulty piece is dropped rather
than the boot or the reload refused: an unregistered `g-optionsSource` or a bad `g-visibleWhen` loses the keyword,
a bad `g-errors` message or client-written `g-layout` is dropped, a type that will not compile reverts, and a client
cfact that redeclares a global one is left out. Beyond schema: an `includedTraits` entry naming nothing is dropped
rather than the client; a UiBlock or fragment overlay at fault is dropped (a reload now checks them too); a stored
config that will not reassemble, or breaks the extends rule, is skipped rather than refusing the reload; and an
unresolvable layout pull is reported, since delivery already renders it as written. A trait whose own type will not
compile leaves the client's supported set with it, so a workflow collecting it is dropped by the existing check.
Anything that breaks because of a drop is left to the reference checks that already exist.

**Strict at write** (issue #843). Every endpoint that changes a client's stored configuration runs a **trial
reload** of the client with the change in place -- the load's own checks over a scratch copy, nothing published --
and refuses the change with a 400 listing anything it finds that the client's configuration did not already have. A
problem the client already has does not block an unrelated write (or fixing one of two broken configs would be
refused over the other).

**A written slot is held to its shape first** (issue #1051). These endpoints are the outward-facing ones -- a
client's own administrators write through them -- so before a write's slots are reassembled they pass a gate
(`configSlotFailures`, run by `reassembleForWrite`, which the bundle write, the import and `patchConfig` all go
through). Today the gate is the **client definition**: a `kdr:clientDef` is validated against its schema
(`WrittenClientInfo`, compiled as `ClientDefSchema`), and an unknown key, a missing field, a value of the wrong type or a
choice that is not one is a 400 whose message names each path and what is wrong there, with the failures also
structured under `extraData.failures` as a request's own input failures are (`kdr:clientDef.enabledEnvironments[1]`).
A conversion fault the reassembly itself finds in a written body -- a client id holding a colon -- is a 400 for the
same reason, with the fault kept as its cause and its extra data (a parser's code and position) carried up; a fault
not marked a conversion is still a server error, until the other slots are gated too. **The rule and the response are separate**: what a valid definition is does not depend on where it
arrives, and what happens to an invalid one does. A write is refused; a row already stored is read by the lenient
reader (`ClientDef.fromInfo`, through `reassembleGedraConfig`), so the deploy that tightens a rule strands nobody.

The unit is the **client**, not the config. A client's definition is spread across its configs (its `clientDef` in
one, the traits its workflows collect in another), and a stored config depends only on source code and its own
client's other configs, never another client's. So every config write takes the **client's** lock (on the
`GedraConfigClientTran` root, keyed by client), and the trial judges the client's configs **as stored**, not as the
node last loaded them:

- **A write or patch** is judged with the client's other *latest* revisions. For a published-only client those are
  its drafts, so a change spanning two configs can be staged.
- **A bulk import** writes each client's bundles in one transaction and judges them as one set, so bundles that are
  only sound together are taken in any order. A client whose set is refused keeps none of it; other clients in the
  same import are unaffected.
- **Publishing** is judged, for a published-only client, against the published set it would then run. For any other
  client the revision is already what it runs.
- **Switching the tier** is judged against the set the client would run afterward.

`GedraConfigService.writeConfig` itself does not trial unless asked (`trial = true`), so a test can still store a
flawed config on purpose to exercise the forgiving load.

**Judged against the stored data, at publish** (issue #935). The trial says whether a candidate configuration is
*valid*; nothing in it says what the candidate does to the rows the client already stores. A sandbox tries a change
against its own data, so a change can look fine there and still hurt the client's when published, which for a
published-only client is the promotion step. So the publish endpoints also compute an **impact report**
(`ConfigImpact`): the candidate is built the way a trial builds it, and every live row is judged under the
configuration the client runs now and under the candidate. Only what the candidate breaks is reported:

- a trait in use the client would no longer support;
- an entry that would no longer validate, so every later edit of its form is refused;
- a workflow a form takes part in that would no longer exist;
- a task a form's recorded state names that its workflow would no longer define.

A non-empty report refuses the publish, carrying the report, unless the caller acknowledges it. The report is per
configuration, matching publish. Stored state is compared against the candidate's workflows rather than derived
again, since a trial's workflows carry no functions to run, so a state the candidate would only derive
*differently* is not reported. Past `KDR_IMPACT_SCAN_LIMIT` rows nothing is examined, and the report says so; a
publish then asks for acknowledgement all the same.

A **component's** schema fault follows the source rule: it refuses the boot outside production, and in production
the faulty keyword, message or layout is dropped and the node serves. A global document that will not compile still
refuses everywhere.

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
| A `traitId` claimed twice within one owner — a global id twice, or a client's own id twice in that client (#807, #951) | naming both bundles |
| A global config's trait id, cfact, workflow id, or a task id in one of its workflows, that is not `<root>:<name>` under its namespace's root (#951–#953) — a global name and a client's can then never be the same | naming the rule |
| A component's own cfact (declared in code) that is not `<root>:<name>` under the component's owner root, or under the root its `contributesTo` names; never under `client` (#952) | naming the component and the rule |
| A namespace claimed by two owners — a backstop since #949/#950, reached only when production forgave a component namespace under `client` | naming the owner it already has |
| A component namespace off its owner root without `contributesTo`, under `client`, or with no root; a declared type outside its contribution's root; a type name declared twice (#950) | naming the component and the rule |
| A client config declaring outside its own `client.<clientId>` namespace, or a new type declared into another owner's (#949) | naming the client's namespace |
| The same config bundle contributed twice | |
| A client's own trait id, cfact, workflow id or task id holding `:`, or breaking its kind's rule (#921) — a colon is the rooted form global names use, and a trait id may not hold a dot | naming the rule |

Each message names both sides, so a reader is never left hunting the other half.

## Where config comes from, and where it goes

Config is expected to *mature*. An administrator authors definitions through an admin surface, and they live
in the database — quick to change, quick to get wrong, and exactly right while a client is still working out
what they need. As a client's application settles and becomes something we maintain and test, those
definitions move into a source repository and are deployed as code.

Promotion is a **move plus an unwrapping**, and both halves matter.

The move is cheap because the two forms are built by the same objects and identified the same way: a config
bundle declared in code carries a real `GedraId` — `gc.cd.global.coreTraits` — exactly as a stored one does,
so nothing downstream has to know which sort it is holding.

The unwrapping is what makes the result idiomatic source rather than a database dump. Stored config is
wrapped in entries so that a database can answer *when* and *by whom*; extracting it takes the definition out
of that protocol parent and adds it as ordinary config, because the questions the envelope existed to answer
are now answered by the commit history. Carrying the wrapper into source would preserve a record of edits
that git already keeps, in a form nobody would read.

Outstanding configuration warnings must be cleared before a config is promoted. A duplicated trait id is
tolerable while config lives in a database and intolerable once it is code we release and test, and that gate
is what gives the warning teeth.

## What is real today

| | |
|---|---|
| `GedraId`, its format, parsing and minting | **built** |
| The config kind, config bundles, traits, and the entry types they generate | **built** |
| Component-contributed config, and the checks over it | **built** |
| The environment split for those checks | **built** |
| The default branch, and strictness as a reader's choice | **built** |
| Storing data gedras, and reading them back within a caller's scope | **built** — form documents, #310 |
| Updating a stored gedra's entries | **built** — the patch, #337 |
| Client separation across deployments | intended — nothing loads a subset of clients yet; a deployment will name the clients it loads in its own configuration, independent of its environment name |
| Client-authored config, and the visibility check | **built** — config stored per client (#611), namespace ownership, `traitsFor`, and `$ref` bounded by construction (a client's schema holds only global and its own types) |
| Client spaces defined in config, scoped to themselves | **built** — #343 |
| Traits for stored config, declared separately from data traits | **built** — #316 |
| Config revisions, and an absent suffix meaning "active" | **built** — #612 |
| Promotion from database to source code | intended |

Anything marked *intended* is a rule the built code is shaped to leave room for, not a promise about when it
arrives. Where the room matters, the code says so at the point somebody would otherwise take it away.
