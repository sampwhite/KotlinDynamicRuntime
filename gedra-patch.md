# The Gedra Patch

How a stored gedra is **changed** — why it has to be a patch rather than an update, what the input looks like,
and the family of endpoints around it. Written before any of it exists, as something to design against and
argue with.

It is a companion to [`gedra-entry.md`](gedra-entry.md), which describes the shape of a single stored entry,
and to [`gedra-config-and-data.md`](gedra-config-and-data.md), which describes everything around one. Those two
cover reading and creating; this one covers changing, which turns out to be where most of the difficulty is.

**Nothing here is built yet.** Creating, reading, listing and deleting a form document exist (issues #310, #325,
#326); changing one does not. Where this document claims the current code behaves some way, that was checked
rather than remembered — but everything it proposes is a proposal.

## How to read it

Two parts, and they are different in kind.

**The statement of the problem** comes first: the complications that make this a patch rather than an update —
locked entries, admin-only and process-only entries, merges, batches that cross data types, and a primary key
that lives inside the data it identifies. That half is the requirement, and it is not up for negotiation.

**The proposed shape** follows, worked out in discussion and recording *why* as much as *what*. It reverses
itself in a few places, on purpose: where a decision changed, the reason it changed is kept, because a later
reader who does not know it will re-derive the discarded answer.

The sections that carry actual decisions:

| | |
|---|---|
| [The shape](#the-shape) | the input payload, and what each layer of it is for |
| [Validation happens in two phases](#validation-happens-in-two-phases-and-that-is-the-point) | what the schema checks and what the service checks |
| [Soft validation, and the trap next to it](#soft-validation-and-the-trap-next-to-it) | the workflow's requirements are not the schema's, and must not be made so |
| [The endpoint](#the-endpoint) | the name, and why a POST rather than an HTTP PATCH |
| [Atomicity across gedras](#atomicity-across-gedras-and-why-it-matters-less-than-it-looks) | per target, and why the workflow makes that safe |
| [The endpoint family](#the-endpoint-family-answering-the-task-scoped-and-config-variants) | the task-scoped and config variants, as one shape with three policies |
| [Grouping targets by data type](#grouping-targets-by-data-type) | why the kind is stated twice, and what that buys |
| [What the form GUI actually offers](#what-the-form-gui-actually-offers) | the edit union, and the projection that produces it |
| [How the frontend learns what it may supply](#how-the-frontend-learns-what-it-may-supply) | not from this endpoint's schema |

Two sections named for open questions, in the order they happened: **Open choices** lists them, and **Open
choices, answered** resolves them. Anything still open says so where it stands.

---

This issue will also apply when it comes to updating database config rows. But for now we focus on Gedra Data.

In what follows a "form" is a Gedra Data object of "formDoc" type, a "user" of "userData" type, and "workflow" is
of "wfData" type. If we need to speak of workflow configuration, I will call it workflow definition.

A workflow will generally be managing a group of forms which are said to be forms belonging to the workflow.

A workflow has tasks. A task will construct a GUI to edit Gedra entries, usually in a single page of the GUI.
The workflow definition defines what Gedra traits and primary key ranges are in scope for the task edit.

The main focus of this document is all the complications that arise when doing a patch and then coming up with
a proposed shape that addresses these concerns.

Before getting into that, the reason why it is a PATCH is that we cannot do implicit deletes with an update. Some
gedra entries can be out of scope for the update and cannot be touched by the PATCH. The reasons for this
we address in the complications.

* Gedra entries can be locked for various reasons. Usually because some type of approval has occurred during
a workflow.

* Gedra entries can be admin only. These entries can only be touched by admins or updated as side effects
on functional endpoints.

* Gedra entries can be process only. For example, a Docusign integration may record state in a gedra entry, and
the entry is only touched by endpoint integrations with Docusign or by approval processes. These are implicitly
editable by admins, but generally admins have to opt in to editing them.

* Gedra entries can be deleted. And in particular, on occasion, this can happen when other entries are being
updated.

* Gedra entries can be merged. The input data merges keys with the data that is present in the Gedra entry.
A typical case is a large questionnaire where for simplicity the body of the questionnaire is kept in a single
gedra entry and a page in the UI may update just a few of them at a time.

* Many edits tend to apply to multiple form documents at once. The UI will tend to have a natural batch
semantic when a group of forms, in a container workflow. An example is when a user will say they have
no data on a particular topic for all the forms in a workflow.

* An edit can cross data type boundaries. For example, a patch can target a workflow and some forms with a single
edit. In some cases, a preprocessor (yet to be designed or implemented) will decide that some dry run logic
has to be applied to see if the change needs to be done atomically (all succeed or all fail). In other cases,
a user gedra entry will be targeted alongside a workflow gedra entry.

* Gedra entries can have a primary key which is put into the `data` block. This makes it difficult to use
the empty/absent `data` as being equivalent to a "delete" of that Gedra entry.

So a PATCH call needs to be able to the following.

Target multiple Gedra data rows with one call. Be able to target different data types in a single call. Be able
to designate that some Gedra entries are being deleted and others are being merged (but with an upsert). Otherwise,
they are replaced or inserted. Security checks should be done up front on all items addressed using
the usual scoping rules for user, client admin, "all clients" admin.

In prior work for Cedar, we have tried out multiple different shapes for input for the PATCH. None of them
we liked, so it is completely open for the correct shape. One proposal that is of interest is that
when you provide a "GedraEntry", you can supply an "action" (at the same level as "traitId") with
the verbs, "deleteOrNoOp", "addOrMerge", "addOrReplace". But this means altering the schema definition for
the input of the endpoint, which may cause complications.

And at some point, in the future, but not now, we will multi-thread parts of the update - security will always
be done upfront with no multi-threading. Dry run will sometimes but not always be multithreaded.
---

# A proposed shape

Everything below is a proposal. It is written against the code as it stands after #310 / #325 / #326, and where
it claims the current code behaves some way, that was checked rather than remembered.

## Two facts that rule out reusing the entry union as the patch input

The `action`-beside-`traitId` idea runs into the entry union's schema, as you suspected. But the schema
collision is the *smaller* of two problems, and the other one rules out the union no matter how `action` is
spelled.

**1. `entryId` is `g-derived`, so it is stripped on the way in.** Every field `storedEntryFields` declares is
derived, and the validator drops a derived property from input *silently* — deliberately, because
read-modify-write is how a form works and echoing back a server-computed value is the ordinary thing to do.
That is right for a create. For a patch it is fatal: the one identifier that says *which* entry to change never
reaches the handler. A patch input has to carry `entryId` as a real, caller-supplied field, which the entry
type by construction cannot do.

**2. A merge sends a fragment, and a fragment cannot satisfy `required`.** The questionnaire case is the whole
point of `addOrMerge`: a page updates a few keys of a large entry. But the trait's data type has `required`
fields, so validating that fragment against the trait fails on everything the page did not touch. The union
validates complete entries; a merge payload is not one.

So the input cannot be "an entry, plus a verb". Which is fine, because:

**An action is not a property of an entry.** An entry is a value; "delete this entry" is not a different kind
of value, it is a different thing being said *about* one. Putting the verb inside the noun is what forces the
schema change, and it also makes nonsense states representable — what does `deleteOrNoOp` mean on an entry
carrying full `data`? So wrap rather than decorate.

## The shape

```json
{
  "targets": {
    "wfData": [
      {
        "gedraId": "gd.wf.acme.u12_acmePaymentWf",
        "edits": [ { "action": "addOrMerge", "traitId": "wfProgress", "data": { "stage": "review" } } ]
      }
    ],
    "formDoc": [
      {
        "gedraId": "gd.fd.acme.e20260812130405123AbCd",
        "edits": [
          { "action": "addOrReplace", "traitId": "expenseReport", "entryId": "2026...xY", "data": { "year": 2024 } },
          { "action": "addOrMerge",   "traitId": "questionnaire", "entryId": "2026...pQ", "data": { "q7": "no" } },
          { "action": "deleteOrNoOp", "traitId": "managerApproval", "entryId": "2026...rS" }
        ]
      }
    ]
  }
}
```

> The kind grouping is a later addition to this proposal; see **Grouping targets by data type** at the end for
> the argument, which turns on per-kind typing rather than on readability. An earlier draft had `targets` as a
> flat list on the grounds that a `GedraId` already carries its kind — true, but a schema cannot read a prefix
> out of a string, so the kind has to be a token the schema can see for the edits to be typed per kind at all.

- **A target is one gedra data row**, named by its `gedraId`, grouped under the kind it belongs to. Both patch
  variants edit many rows.
- **Edits nest under a target rather than being one flat list.** The target is the lock unit — one gedra, one
  root row, one transaction — so nesting makes the unit structural. Flat, the same gedra could appear twice
  with conflicting edits, and a naive implementation would take its lock twice.
- **`entryId` is optional.** Absent means "the entry this `traitId` (and, once `g-primaryKey` exists, its key)
  names, or a new one". Present means that entry and no other. This is the seam where `g-primaryKey` lands
  later without a shape change.
- **`data` is absent for a delete**, which is what lets the primary-key-lives-in-`data` problem stay solved:
  absent `data` never means delete, because the *verb* says delete.

## Validation happens in two phases, and that is the point

**An edit is itself a manufactured union, discriminated on `traitId`** — so `data` is the trait's own data
schema and the form GUI draws a real sub-form, exactly as `create` does. Declaring `data` as a plain object was
the earlier draft here, and it would have meant a JSON textarea; see **What the form GUI actually offers**
below for why the union is the answer and why `data` cannot simply point at the entry union.

Even so, per-trait *completeness* is settled in the service, after `traitId` and `action` are known, because
only then is it decidable what to validate against:

- `addOrReplace` — validate the payload as a complete entry against the trait.
- `addOrMerge` — read the stored entry, merge, then **validate the result**, not the fragment. This is where
  the trait's own `required` is enforced, and it is the only place it can be.
- `deleteOrNoOp` — no data validation at all.

All three are **hard** validation: schema-level, and a failure is a 400 that writes nothing. That is not the
only kind of validation a patch is adjacent to — see the next section, which is why this one says *the trait's
own* `required` rather than just `required`.

The cost is that the endpoint catalog can no longer draw a per-trait form for this endpoint. That cost is
nominal: a patch form is built by a workflow **task**, from the traits and key ranges the task declares — not
from the endpoint's own schema. The patch endpoint is a machine surface, and the form engine reaches it with a
payload a task assembled.

## Soft validation, and the trap next to it

The questionnaire example above hides a second kind of validation, and it is worth naming now even though
building it is a later topic.

A questionnaire trait marks **every answer optional in its own schema**. The workflow then has a rule saying
some or all of them are required. Those are not the same claim and must not have the same consequence:

- **Hard validation** is the schema's. It fails the write, and the caller gets a 400.
- **Soft validation** is the workflow's. It does **not** fail the write. It stops you *advancing* — and the
  answer it produces is a report, not an error.

The optionality is load-bearing rather than sloppy. It is exactly what lets a page of a long questionnaire be
saved half-filled, which is what `addOrMerge` exists to serve. Tighten the trait and partial saves stop working.

### The trap

There is an obvious-looking way to express the workflow's requirement, and it is wrong: **overlay the trait and
narrow it**, adding the fields to `required`. The overlay mechanism exists, narrowing is the one thing an
overlay is allowed to do, and it looks like precisely the right tool.

It is wrong because `gedra-entry.md` already says what narrowing binds: *"Narrowing binds the write path, never
the read path."* So an overlay that made the answers required would fail the write — converting a soft
validation into a hard one, and breaking the partial save the optionality was there to allow. The rule that
makes overlays safe is what makes them the wrong instrument here.

### Where it probably belongs

`gedra-entry.md` has already sketched the mechanism under *"Tasks declare what is owed"*: a task declares which
`traitId`s and which primary keys it expects, and **missing data is a diff** between what the task expects and
what the gedra holds. That diff *is* soft validation. What has not been said before is that the same diff is
what gates workflow advancement, not merely what the GUI shades red — which raises the bar on it, since a
report that only drives styling can be approximate and one that decides whether somebody may proceed cannot.

Two consequences for this proposal, neither of which is work today:

- **The patch endpoint does hard validation only**, and must not grow the soft kind. A patch that refused a
  write because a workflow rule was unsatisfied would be the trap above, arrived at by a different road.
- **The response should signal that workflow state may have moved, not carry it.** My first instinct was to
  put the advisory result in the patch response — "saved, and here is what still stands between you and
  advancing". That is wrong at the sizes involved: in Cedar the *current workflow state*, soft validation
  results included, was a **large block of data held separately from everything else**, and computing it was
  some of the most complex logic in the application. Recomputing and shipping it on every keystroke-sized save
  would be the expensive thing done most often.

  So the state is its own fetch, and a patch says only that it may be stale. This codebase already has the
  shape for that: responses carry a `contentHash`, and the frontend re-fetches config on a refresh generation.
  A workflow-state block fits the same pattern — fetch separately, cache, invalidate on a signal — and the
  patch response's job is to send the signal.

## The endpoint

**`POST /gedra/patch`.**

The existing paths put the *kind* in the second segment (`/gedra/formDoc/create`). A patch has no kind, and
**the missing segment is the signal** — a reader who knows the convention sees that this one is not per-kind.
`/gedra/data/patch` was the alternative and is worse: it puts a *family* where a *kind* goes, and reads as
though `data` were a kind, which is confusing when `userData` and `wfData` are.

### A POST, and not because the enum is short

`HttpMethod` is `GET, POST, PUT`, so PATCH is not available — but that is a constraint we chose and could
change, and it is the weakest of the reasons. Three better ones:

- **This is not a REST PATCH.** HTTP PATCH means "apply these changes to *the resource at this URI*". Ours
  targets an arbitrary set of gedras across several kinds, named in the body. `PATCH /gedra` would have to mean
  patching the collection, and nothing about this codebase is resource-style anyway: paths match exactly, there
  is no path-parameter extraction, and ids travel in the body or the query string.
- **The method advertises a body format we do not use.** PATCH has two established payload conventions and we
  match neither. JSON Patch (RFC 6902) is an op array, which our `action` verbs superficially resemble, so a
  reader would reasonably expect it. Worse, JSON Merge Patch (RFC 7386) says **`null` means delete the
  member** — and this design specifically decided that absent `data` must not mean delete, because a primary
  key lives in `data`. Advertising PATCH would invite exactly the assumption the shape rejects.
- **Consistency.** `create` and `delete` already carry the verb in the path, and the catalog lists paths, so a
  path that says its verb is self-describing where a method column is a second thing to read.

**Delete is a different case and should not be lumped in with this.** `DELETE /gedra/formDoc?gedraId=…` targets
one resource and would mean exactly what DELETE means, so if HTTP methods are ever added, that is where to
start. The cost is the enum, the dispatcher, `TestHttpClient`, the frontend's request helper and the form
engine's Run button — modest, but real. Patch stays a POST either way, on the semantics rather than the
plumbing.

The config counterpart the document anticipates is then `/gedraConfig/patch` rather than a kind under `gedra`.
Config editing is an administrative surface with different authority, so a section of its own is probably right
regardless.

## Verbs

Keep `deleteOrNoOp` / `addOrMerge` / `addOrReplace` as written. The `xOrY` form makes the absent-entry case
explicit at every call site, which is exactly the property a patch wants.

**`action` should be required, with no default.** The reason PATCH exists here is that intent may not be
implied — the document's own "we cannot do implicit deletes". Defaulting the verb is the same class of mistake
one step down.

## Security up front

One pass before anything is written: resolve every `gedraId`, apply `ReadScopeRules.forCaller` to each, and
refuse the **whole call** if any target is out of reach. That reuses the rule the read and the delete already
share, so nothing decides separately who may patch.

The refusal should name the id but not say why — the caller supplied the id, so echoing it discloses nothing,
while distinguishing "not yours" from "not there" is exactly what an id-guessing caller wants. Same rule the
read already follows.

## Atomicity across gedras, and why it matters less than it looks

**There is no mechanism today.** A topic transaction locks *one* root row, so a patch spanning three gedras is
three transactions and can half-succeed.

I first wrote this up as the proposal's main unsolved problem, tied to the dry run. **That was wrong, and the
correction is worth recording**, because it comes from Cedar actually running this way: dry runs there were done
**outside** any transaction and guaranteed nothing, and it worked well — the activities needing a dry run were
rare, and hard for a concurrent change to upset. So the dry run is a **pre-flight check, not a transaction
mechanism**. It answers "would this apply cleanly?" advisorily, and something can in principle change
underneath it. Cheap to run, and in practice not raced.

That collapses the design. Instead of the dry run deciding which locking strategy a patch needs, the two are
independent:

- **Atomicity: per target.** Each target succeeds or fails alone, and the response says which. This is not a
  first-cut compromise to be replaced later — it is the arrangement, unless something concrete argues
  otherwise.
- **Dry run: later, and for the few edits that want it.** Most edits will not, so it is not first-cut work.

Two heavier options exist and should stay on the shelf rather than in the plan, in case a real case demands
all-or-nothing:

- **Multi-lock in a canonical order.** Take every target's lock before writing, ordered by `gedraId`. The ids
  sort lexically by construction, so two concurrent multi-target patches acquire in the same order and cannot
  deadlock — but it needs a transaction spanning several roots, which `SqlTopicTranProvider` does not express.
- **A patch-transaction root** — a row representing the patch itself, which is also where the outbox and the
  file-store case eventually live.

Neither is worth building on speculation. The thing to watch for is a *specific* edit that is genuinely
incorrect when half-applied; that is the trigger, and it has not appeared yet.

### The workflow is what makes per-target atomicity safe

Also from Cedar, and it is the missing half of the argument above rather than a detail. The real transaction
edited **the workflow first, then the forms** — and only the forms were multi-threaded. The workflow edit
checked the workflow's own state to see whether the work had already been done, which is what protected against
double submits.

That is the answer to "what happens when a multi-target patch half-applies", and it is a better answer than
rollback: **the workflow entry is an idempotency ledger.** A retry re-reads it, sees what was already recorded,
and skips or resumes. Recovery is by replay rather than by undo — which is also why the rare, hard-to-race
character of these edits was enough to make the whole thing work.

Two consequences for the shape:

- **Target order is meaningful.** The workflow target is serialized and goes first; the forms follow and are
  the parallelizable part. So `targets` is a sequence with a coordinator at its head, not an unordered set.
  Open question worth settling early: is the coordinator **declared** by the payload, or **derived** by the
  server from the kind (`wfData` before `formDoc`)? Deriving is free and needs no new field; declaring follows
  the rule this codebase keeps applying — record intent rather than re-deducing it — and survives the day two
  workflow-kind targets appear in one call. I lean to declaring.
- **An edit can stop the ones after it.** "Already done, do nothing further" is a decision made from stored
  state in the middle of a patch, which no purely declarative payload expresses. So the generic endpoint
  cannot be the whole story: a workflow submit is a **functional** endpoint that performs workflow-then-forms
  with the guard, and the generic patch is what it (and simpler callers) build on. Worth deciding whether the
  guard is expressible as a precondition on a target — something like "apply only if this entry still looks
  like this" — or stays application logic behind a named endpoint. The second is smaller and is what Cedar did.

## The response, and a gap it exposes

Returning the patched documents is what a UI wants, and it cannot be typed today: the targets have different
kinds, so the output would need a union **across** kinds, and only per-kind entry unions exist. Two options:

- A per-target summary (`gedraId`, and per-edit `applied` / `noOp`), leaving the caller to re-read. Types
  cleanly, and pairs well with per-target atomicity since the summary is where partial success is reported.
- Manufacture a `Gedra` union across kinds, the same way `FormDocEntry` is manufactured across traits. Real
  work, but it is the same machinery, and it is the thing to build if mixed-kind responses are wanted anywhere
  else.

I would start with the summary and treat the cross-kind union as a separate decision.

## Smaller notes

- **Locked / adminOnly / processOnly entries** are a *refusal* rather than a filter: an edit naming one should
  fail loudly rather than be quietly skipped, or a caller believes they wrote something they did not. That
  argues for the security pass checking entry-level directives too, not only row-level scope — which means it
  needs the stored entries, so the pass is a read of every target rather than an id check.
- **`updatedBy` / `updatedAt` per entry** (#325) now carry the audit for a patch, and are the reason an entry
  that a patch did *not* touch keeps its own history rather than inheriting the row's.
- **An entry is deleted, not disabled.** Unlike a row (#326), there is no `enabled` on an entry, and adding one
  would put a second delete mechanism next to the first. Removing it from the `entries` array is the delete,
  and history is the answer to "what was there" — which is deferred anyway.

## One layering decision to make before it is made for us

Not work for this issue, but the cheapest moment to think about it is now, while the gedra layer is three files
old.

The *current workflow state* described above — soft validation results and whatever else goes into "can this
person advance?" — was in Cedar both the most complex logic in the application **and** a pain point on the
frontend, which had to handle that state richly. Two implementations of one set of rules, in two languages, is
the shape that produces that pain.

kd2 has a lever Cedar did not: `base/kernel` is `commonMain`, so the same Kotlin runs on the server and in the
browser. The `Sch*` layer is already shared for exactly this reason — the webapp parses an endpoint's schema
and coerces input with the functions the backend runs, so the two cannot disagree about what a schema means.
Workflow state is a bigger instance of the same problem, and it is the one where sharing would pay most.

**The constraint that follows, and the reason to note it now:** anything the state computation will need has to
be in the kernel, and the gedra layer is currently split.

| already shared (`base/kernel`) | backend-only (`base/common`) |
|---|---|
| `GedraId` and its format | `GedraDataRow` |
| `GedraEntry` / `traitEntry` / the stored envelope | `GedraDataService`, the SQL |
| the whole `Sch*` layer | `entryUnionDefs` (union assembly) |

Most of that split is right. The SQL is rightly backend-only, and the frontend never *assembles* a union — it
receives one already compiled. The one worth a second look is **`GedraDataRow`**: a shared way to say "a gedra
and its entries" is the natural input to a shared state computation, and it is JVM-only today for no stronger
reason than that its first caller was.

The general rule already in the code guide applies — ask "would this be useful in the frontend?" and write
KMP-friendly if the answer is yes. The note here is narrower: **the ported-from-dn code is deliberately not
KMP-compliant**, so the boundary between "ported and backend-only" and "new and shared" runs straight through
the gedra layer, and the workflow-state work will be on the shared side of it.

None of this needs deciding today. It needs not being accidentally foreclosed, which is a much lower bar and is
mostly a matter of noticing when a new gedra type is put in `common` out of habit.

# The endpoint family (answering the task-scoped and config variants)

Three endpoints, and the claim worth testing first: **they are one payload shape with three different
policies**, not three APIs. What differs is who decides what may be written, not how an edit is expressed.

| | policy comes from | targets | verbs admitted | processors |
|---|---|---|---|---|
| `/gedra/patch` | the caller's read scope, plus entry-level directives | many, any data kind | all three | trait-bound only |
| `/gedra/task/patch` | the task definition — scope, narrowing, processors | many, each admitted against the task | all three | trait-bound **and** task-bound |
| `/gedraConfig/patch` | administrative authority | exactly one | `addOrReplace`, `deleteOrNoOp` | trait-bound only |

If that holds, the machinery is written once and each endpoint is a policy wrapped around it. If it turns out
not to hold, the place it will break is the task variant, because that is the one with rules of its own rather
than merely fewer permissions.

## Naming

**`/gedra/task/patch`** for the task-scoped one. Same section, adjacent in the catalog to the endpoint it is a
companion to, and the second segment says what supplies the policy — which is the one thing a reader needs in
order to choose between them.

The alternative is putting it on a workflow surface (`/workflow/task/patch`), and it is not unreasonable: its
inputs are workflow concepts and its rules come from the workflow definition. What decided me the other way is
that this endpoint *writes gedra data* and shares its payload with the generic patch, while the workflow
siblings it would sit beside — advance, submit, reopen — are workflow **state changes** that write something
else. Grouping by what an endpoint writes beats grouping by what parameterizes it.

**`/gedraConfig/patch`** for config, in a section of its own, and here the reason is concrete rather than
aesthetic: **the section is how this codebase declares access.** `gedra` is login-gated and nothing more,
because reach there is a scope question. Config authoring is not — it is administrative, and putting it under
`gedra` would inherit the login-only gate and hand config editing to every user. A `gedraConfig` section gated
the way `userAdmin` is (admin level, client-scoped) says the right thing in the one place that is enforced.

It also matches the storage split the ids already draw: `gc.` against `gd.` is a different family, not a
different kind, so it is a sibling of `/gedra/...` rather than a path under it.

## What actually varies

**Verbs.** One vocabulary, and each endpoint declares the subset it admits as `g-options` on its own input
type. Config gets `addOrReplace` and `deleteOrNoOp` — a definition is authored wholesale, so there is no
questionnaire-page case to merge. Doing it with options rather than with a second enum means the form engine
draws the right dropdown, and a refusal arrives as an ordinary `invalidOption` failure that *names what is
allowed*, which is what that mechanism already does for a union's branches.

**Target count.** Config targets exactly one row. Keep the `targets` array with `maxItems: 1` rather than
flattening to a bare `gedraId`: one shape across the family is most of the value, and the constraint is a
schema statement rather than a different type. It also survives the likely growth — promoting a bundle and the
client definition that references it is two rows in one call.

**Merge, for config.** Worth noting *why* it is absent rather than just that it is: a merge exists to serve
partial authoring of a large body of answers. Config is edited as a document. If config ever grows a
questionnaire-shaped case, the verb is already in the vocabulary and only the options list changes.

## The pipeline, and where the three diverge

```
admit  ->  pre-process  ->  hard validate  ->  write  ->  post-process  ->  signal state stale
```

- **admit** — row scope for the generic patch; the task's declared traits and key ranges for the task variant;
  administrative authority for config. Always up front, always for every target before anything is written.
- **pre-process** — this is where `g-derived` values are produced. **Trait-bound processors run on every
  write**, including create, because a code-backed trait computes its own values wherever it is stored. Task-
  bound processors are additional and run only in the task variant. Two tiers, and conflating them would mean a
  derived value that is correct through a task and missing through a direct edit.
- **hard validate** — as described earlier: complete entry for a replace, merged result for a merge.
- **post-process / signal** — the workflow-state block is recomputed lazily, not returned; the response says it
  may be stale.

The generic patch simply has fewer stages populated. That is the argument for one machinery.

## A task's narrowing is the *right* use of an overlay — unlike the soft case

This is worth putting directly beside the trap recorded earlier, because the two look identical and are
opposite.

- **"You may not supply this field in this task"** — a hard, write-path constraint. Narrowing an overlay is
  exactly the tool: the branch is a closed object, so an out-of-scope field is refused, and
  `gedra-entry.md`'s rule that narrowing binds the write path is the behavior being asked for.
- **"These answers are required before you may advance"** — soft. Narrowing would fail the write, which is the
  trap.

So one task definition will carry **both kinds of statement**, and they must not be compiled into one schema.
The narrowing goes into the effective type used to admit and validate the write; the requirements go into the
task-expectation diff that drives advancement. A task overlay that put its requirements into `required` would
work in testing and break the first partial save.

## Open choices

- **Does the task variant take targets, or derive them?** Supplying them and checking against the task is
  simpler and is what "you are not allowed to supply a detail out of scope" implies. Deriving — "every form in
  this workflow" — is the batch case from the complications list, and is better added later as a wildcard
  target than designed in now.
- **Does the generic patch stay open to ordinary users at all?** Its whole distinction is having no task
  policy. That may be right for integrations and admins and wrong for everyday callers, in which case the
  everyday path is always the task variant and the generic one is the primitive beneath it.
- **Whether `/gedraConfig` is one section or several.** Config reads (for an admin console) and config writes
  may want different levels.

# Per-client endpoint variants (future, but it constrains the shape now)

The direction: alongside the general endpoints, **dynamically generated per-client variants** of everything that
touches gedra data, with the client id in the path and visible only to that client. Those know *all* of that
client's traits, so their forms can offer complete typed input — and they exclude admin/process traits from the
input list. The frontend keeps using the general endpoints; the per-client ones serve integrations and the
people reading a catalog.

That makes the general endpoints deliberately **partial**: they know global traits only, so a client trait
arrives on the union's default branch and its `data` is raw JSON. Not a shortfall — a division of labor.

## Most of the affordance is already there

`entryUnionDefs` was written as **a function of (client, kind)** and is called once today with the global scope;
its own note says per-client types are a later step and that writing it this way is what keeps that from being a
rewrite. `GedraConfigCollector.traitsFor(client)` already answers "this client's traits, plus global's". So the
per-client variant is *calling the same function with a different set*, which is what it was shaped for.

**The edit union should be manufactured the same way** — `entryEditUnionDefs(traits)` beside
`entryUnionDefs(traits)`, both taking a trait collection rather than reaching for a global one. Then one
manufacturing pass serves both audiences:

- global traits → the general endpoints, client traits falling to the default branch as raw JSON;
- a client's traits, minus the ones they may not edit → that client's endpoints, complete and typed.

This also gives the default branch a second justification. #301 argued for it from cross-client
administration — meeting a trait belonging to a client whose config was never loaded. The everyday reason turns
out to be nearer to hand: the general endpoint *deliberately* does not know client traits.

## Three things that are genuinely new

**1. Excluding admin/process traits is a different filter from ownership.** `traitsFor(client)` filters by who
owns a definition. "May an ordinary caller edit this?" is a different question, and nothing answers it today —
it needs the directives that live on the trait wrapper, which does not exist. This is the third distinct reason
in this document to want `GedraTrait` to carry more than `traitId` / `typeName` / `appliesTo`.

**2. "All admin/process traits are global" should be enforced, not assumed.** It is a genuinely useful
invariant — it means a client's own traits are always ordinarily editable, so the exclusion only ever removes
global ones, and the filter stays cheap. But an invariant nobody checks stops being true quietly. `GedraConfigCollector`
already refuses incoherent configs at boot with a clear message, so a client config declaring an admin-only
trait has an obvious home as a config error.

**3. Per-client endpoint *visibility* is a new axis in the access model.** Sections gate on **roles** —
`RequestService.canAccess` compares a caller's roles to a section's requirement, and the endpoint catalog
filters on the same answer so that what is advertised and what is served cannot drift. Visible-only-to-that-client
is not a role question. It needs the caller's **client** compared against the endpoint's, which is a dimension
neither `SectionRules` nor the catalog filter has. Worth knowing before it is discovered by an endpoint that is
visible to everyone because nothing said otherwise.

## One naming hazard

Segment two currently means *the kind* (`/gedra/formDoc/create`), and the patch proposal already uses it for a
verb (`/gedra/patch`). Putting a client there — `/gedra/acme/patch` — makes it mean a third thing, and unlike
the first two it is not drawn from a closed set: a reader cannot tell a client id from a kind by looking. Either
the client segment goes somewhere unambiguous, or the per-client variants get a section of their own — which
they may want anyway, given the visibility question above.

# Grouping targets by data type

First, a clarification of the term, because "target" was doing too much work above. **A target is one gedra
data row**, named by its `gedraId` — not a data type. And the open question about the task variant was only
whether the *client* lists those rows or the server derives them from the task; multiple rows were always
assumed. Both variants edit many rows.

The real question is whether the shape gains a layer that groups those rows **by kind**:

```json
{
  "targets": {
    "wfData":  [ { "gedraId": "gd.wf.acme.u12_acmeWf", "edits": [ ... ] } ],
    "formDoc": [ { "gedraId": "gd.fd.acme.e2026...", "edits": [ ... ] },
                 { "gedraId": "gd.fd.acme.e2026...~2", "edits": [ ... ] } ]
  }
}
```

**I think yes**, and the argument that decides it is not the one I expected.

## The redundancy objection, and why it does not win

The obvious complaint is that the kind is already in every `gedraId`, so a grouping layer says it twice, and
two statements can disagree — a `gd.fd.` id sitting under `wfData`.

That is real, and it is the price of something worth more: **per-kind typing at the schema boundary.** A schema
cannot parse a prefix out of an id string to select a branch, so for the kind to be *typeable* it has to appear
as a token the schema can see. Either a grouping key or a discriminator field — the id cannot serve.

Pay that price and three things follow:

- **`appliesTo` becomes a schema check rather than service code.** The `formDoc` array's items are the edit
  union over traits that apply to form documents; a workflow-only trait is refused by the schema, at the path
  where it was written.
- **The form GUI offers only the traits valid for that kind**, instead of every trait in the deployment with a
  runtime failure for the wrong ones.
- **The disagreement failure mode is trivial** — one comparison per row between the id's kind and its group,
  with an obvious message. Cheap insurance against a cheap mistake.

## Ordering and threading become structural

This is the part that answers an earlier open question outright. Cedar edits the workflow first, then the forms,
and only the forms are multi-threaded. With a kind-keyed object, the server iterates kinds in **its own fixed
order** — `wfData` before `formDoc` — so:

- the coordinator no longer has to be declared or derived; it is the group that sorts first;
- the parallelizable set is literally one array;
- and a client **cannot** subvert the order by sending forms first, which a flat ordered list would allow.

That last point is why an object beats an array of groups. JSON object properties carry no semantic order, and
here that is a feature: order is the server's, not the payload's.

## The shape choice

Keep a single `targets` container with kinds inside it, rather than kinds at the top level. The task variant
needs `workflowId` and `taskId` as top-level siblings, and a dry-run flag will want to sit there too — mixing
control fields with kind groups at one level muddies both. The config variant then reads consistently:
`{"targets": {"configDoc": [ … ]}}`, with its single-row constraint expressed as `maxItems: 1`.

The costs, stated plainly: one more level of nesting for the common single-form case, and the input type
enumerates the kinds, so adding a gedra kind edits a schema. Kinds are a closed set we own, so the second is
small; the first is the real one, and it buys the three properties above.

# What the form GUI actually offers

The question that produced the edit union: with `data` declared as a plain object, what does the endpoint form
give you? A **JSON textarea** — the same `{ }` editor the entry union's default branch renders today. That is a
real downgrade from `create`, where picking a trait switches the branch and `data` becomes a structured
sub-form, and calling that cost "nominal" was wrong.

**Pointing `data` at the entry union does not fix it.** A discriminator selects a branch using a property
*inside* the object being validated, and here the selector (`traitId`) is a **sibling** of `data`, one level up.
A discriminated union cannot reach upward.

**Making the edit itself the union does.** Same machinery — `variantBranch` takes an arbitrary build block — so
each branch is `{ traitId: const, action, entryId, data: <that trait's data schema> }`, with `traitId` top-level
where a discriminator needs it. The form then offers `action` as a dropdown, `traitId` as a dropdown (free, it
is the discriminator), `entryId` as text, and `data` as a real sub-form.

Grouping targets by kind (see below) makes each group's union the one for *that* kind, so only traits that may
be carried are offered, and `appliesTo` is enforced by the schema rather than by service code.

## It is a projection — the fourth, and the first of its sort

In `gedra-entry.md`'s sense: a shape generated from one authored definition, never authored. That section lists
input, admin input and output; patch input is another.

Part of the family is already implemented — the `g-derived` half of the **input** projection has run since
#254, and #310 leans on it, which is why one `FormDoc` type serves both directions. What would be new:

- **the first projection producing a named type** rather than transforming one endpoint's schema in place,
  which it must be, since three endpoints `$ref` it;
- **the first projector in the Gedra tier**, which is where `gedra-entry.md` said it belongs, because it reads
  the *trait* and not merely the schema;
- **the first that adds rather than only removes** — `action` is new and `entryId` changes status. That
  stretches "projection", which elsewhere means narrowing. Worth naming as its own thing if that reads wrong,
  and the moment to decide is before code called `project…` does something the other projections do not.

**Build it from the trait, not from the entry union.** The subtractive route nearly works with existing
machinery — apply the input projection that drops every `g-derived` field, which is the whole envelope — but it
leaves a wart: `entryId` is dropped as derived and immediately added back, because on a patch the caller does
supply it. That wart is a finding rather than an annoyance: `g-derived` is a single boolean being asked a
question that is **per-projection, not per-field**. `gedra-entry.md` left room for an object form of the keyword
to say so; this does not justify inventing it.

The additive route sidesteps it, and is honest about how much the shapes differ. Its prerequisite:
**`GedraTrait` does not carry its data schema** — only `traitId`, `typeName` and `appliesTo` — so today the
schema has to be read back out of the built defs. Worth fixing on its own account; a trait is "the definition",
and its data shape is the most important part of it. With that field, `entryEditUnionDefs(traits)` sits beside
`entryUnionDefs(traits)`: one source, two manufactured outputs, neither derived from the other.

It composes as `gedra-entry.md`'s ordering rule says it should — **overlay, then project, then export**. A
task-scoped edit union is the trait, overlaid by the task's narrowing, then projected to patch-input shape.

## The relax-`required` question is retired

An earlier draft worried that a merge fragment cannot satisfy the trait's `required`, and proposed manufacturing
a relaxed variant of each data type. In practice the traits that get merged are the ones with no schema-required
elements — their requirements are soft and belong to the workflow. So the boundary type can carry the trait's
data schema unchanged, completeness is settled after the merge, and no relaxation projection is needed. Recorded
as a decision rather than an omission, in case a required-field trait ever does want merging.

# Open choices, answered

- **Does the task variant take targets, or derive them?** Take them. Both variants edit many rows, listed by
  the caller and admitted against the task.
- **Is the generic patch open to ordinary users?** Yes — available to all, in practice used by internal admins
  and by the frontend. But **not advertised**: hidden from the endpoint list shown to "normal" users (today,
  those without `allClients`; that definition will move). Still callable. See the next section, because this is
  a new idea rather than a setting.
- **Is `/gedraConfig` one section or several?** One, for now.

## Hidden-but-usable is a third state, and the code currently has only two

Worth pausing on, because it contradicts something the code states as an invariant. `RequestService.canAccess`
is the single answer used by **both** the dispatch gate and the endpoint catalog, and its own note says why:
*"so what is advertised and what is served cannot drift apart"* — and, at `adminSections`, *"'see' and 'use'
are one answer"*. Advertised-off deliberately separates them.

That is a legitimate thing to want — a power endpoint should not clutter or mislead an ordinary caller's view of
the API — but it needs building rather than configuring:

- **A per-endpoint flag, not a section property.** The generic patch lives in the same `gedra` section as
  endpoints that stay advertised, so the section cannot carry it. `forTestingOnly` is the existing precedent for
  a per-endpoint boolean, and this is its softer sibling: `forTestingOnly` removes an endpoint from the store
  entirely (not dispatchable anywhere it does not belong), while this leaves it dispatchable and merely
  unlisted.
- **The name has to say it is not a control.** Somebody will reach for this when they mean *gate*, and an
  endpoint that is hidden but callable by anyone is exactly the shape of an accident. Something like
  `notAdvertised` rather than anything with "private", "internal" or "restricted" in it.
- **`_debug=explainAccess` should account for it.** That tag exists to answer "why can this caller not see that
  endpoint", and it currently reasons purely in roles. An endpoint missing from a listing for a reason that is
  not a role is precisely the case it would otherwise explain wrongly.
- **It must not become a security story.** Hiding is presentation. The dispatch gate stays the only thing that
  decides who may call.

# How the frontend learns what it may supply

The answer is **not** the patch endpoint's own schema. The endpoint returning live workflow state for a task
also returns the available traits and their current schemas — augmented with the form layout wrapper and layout
elements, and influenced by the rest of the workflow state. That is what tells the frontend what it may send.

So there are two audiences for "what may I supply here", and they learn from different places:

| audience | learns from | shape |
|---|---|---|
| the application frontend | the workflow-state endpoint | task-narrowed, layout-augmented, computed per state |
| a developer or integrator reading the catalog | the endpoint's declared input schema | the manufactured edit union |

**This tempers the case I made for the edit union.** I argued for it largely so the endpoint form would offer
typed input; for the *frontend* that argument does not apply, because the frontend was never going to read the
endpoint's schema. What still earns the union is validation at the boundary, `appliesTo` enforced by the schema
rather than by service code, and the per-client endpoints, whose whole purpose is a complete typed catalog. The
catalog form being good is a benefit for developers rather than the justification.

## The drift risk this creates, and the rule that removes it

Two things now describe what may be sent to a task-scoped patch: the **state endpoint's trait schemas** and the
**task patch endpoint's edit union**. If those are produced independently they will disagree, and the failure is
the worst kind — the frontend is told it may send something the endpoint then refuses.

**They must be one projection, produced by one function.** A task's effective type is the trait, overlaid by the
task's narrowing; the state endpoint serves it with layout annotations attached, and the edit union is the same
thing projected to patch-input shape. One source, two renderings — the same discipline that keeps
`entryUnionDefs` and the entry types from drifting.

Two notes on the augmented schema itself:

- **Layout rides as annotations on the JSON Schema, not as fields on `SchType`.** That is the settled direction
  (presentation may be inline, but the parsed type does not learn about it), and it is what lets the frontend
  read layout from the raw document while validating against the parsed type.
- **It is computed, not stored.** A schema that varies with workflow state cannot live in the schema store, so
  this is the first schema the runtime emits per-request. The layer can do it — schemas are ordinary maps — but
  it is worth knowing that the store stops being the whole picture at that point.
