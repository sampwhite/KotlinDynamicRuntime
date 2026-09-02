# The Gedra Workflow

**Status: superseded.** Phase 1 of this design shipped as issue #381 and was **retired under issue #533**
before phases 2–4 were built; the replacement is tracked by issue #532, with its design notes in the private
`sampwhite/Actions` repo (`kd2-design/thoughts-workflow-poc.md`, `kd2-design/thoughts-gedra-states.md`). Two
ideas carry forward: the **soft-validation seam** — a completeness gate stops an advance and never fails a
write; see the soft-validation section of `gedra-patch.md` — and completeness as **presence of an entry, not a
judgment of its content**. The rest of this document is left as written, as the record of a design that was
tried, so its arguments are not re-derived.

---

The design of the workflow: a stateful gedra that a **user** fills in, hands to an **advisor**, and moves
through a sequence of states until it is done — collecting form entries along the way. This is a **declaration
of intent**, written before the code, in the manner of [`gedra-entry.md`](gedra-entry.md).

It is a companion to [`gedra-patch.md`](gedra-patch.md), which already specifies the *edit* mechanism a
workflow uses (the cross-kind `PATCH`, entry locking, soft validation), and to
[`gedra-config-and-data.md`](gedra-config-and-data.md), which specifies where a *definition* lives. This
document is about everything those two left as "the workflow decides": the states, the transitions, who may
take one, and how the thing passes between people.

Decisions are stated plainly. Genuinely unsettled points are under [Open questions](#open-questions) rather
than papered over.

## What already exists (and is not redesigned here)

The workflow is deliberately a thin layer. Most of what it needs is built:

- **`wfData`** is a defined gedra kind — "data captured while a workflow was followed"
  ([`GedraId.kt`](base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/GedraId.kt)). A workflow
  *instance* is one `gd.wf.` gedra.
- **`formDoc`** gedras are the forms. A workflow "manages a group of forms which belong to it"
  (`gedra-patch.md`).
- **The `PATCH`** already crosses kinds in one call, locks entries, deletes/merges/replaces, and applies
  scope up front — so *editing* a workflow and its forms together is done
  ([`GedraDataService.patchGedras`](base/common/src/main/kotlin/com/dynamicruntime/common/gedra/GedraDataService.kt)).
- **`GedraConfig`** source-code bundles (like
  [`coreTraits`](base/common/src/main/kotlin/com/dynamicruntime/common/gedra/CoreTraits.kt)) are where traits
  are authored — "traits now, workflows later." A workflow *definition* is a new member of that bundle.
- **`SectionRules`** already gates an endpoint section on an off-ladder **capability** (today `allClients`),
  which is exactly the shape the `advisor` role takes.
- **The gedra data cache** already indexes by client and by `client+kind`; an `assignee` index is one more of
  the same.

What does **not** exist, and is what this document adds: a **state model**, the **handoff** between user and
advisor, the **advisor role and its read path**, and the **definition** that ties states to tasks.

## The shape of a workflow

Three gedras, related by ownership and by id:

```
gd.wf.acme.u42_loanReview        the workflow instance (wfData) — the state lives here
 ├─ gd.fd.acme.u42_loanReview_income   a form that belongs to it (formDoc)
 └─ gd.fd.acme.u42_loanReview_assets   another
```

The workflow instance is the **root of a small graph**: it names its member forms, and it carries the state.
A deterministic base id (`u42_loanReview`) means "does user 42 already have a loanReview?" is answered by
*building the id and asking*, never by a lookup — the property `gedra-config-and-data.md` calls out for
deterministic ids, and the reason there is no assignee-to-workflow lookup table.

### Where the state lives — the `data` map, not columns

**No workflow state is a database column.** The status, the assignment and the reopened-task overlay are all
keys in the workflow's `data` map — which `GedraTables.kt` reserves for exactly this ("ACL grants,
relationships between gedras … each arriving as a key costs no migration"):

| `data` key | holds |
| --- | --- |
| `wfStatus` | the current state name; absent means the definition's initial state |
| `wfAssignment` | who may act now — `{kind, value}` naming a **role**, a **group** or a **user** |
| `wfReopened` | the steps the advisor sent back, as task id → note |

They ride out on `GedraDataRow.extra`, where every non-`entries` key of `data` lands. So this feature's whole
persisted footprint is *nothing* — no column, no index, no migration.

**Why not columns.** Two reasons, and the first is the load-bearing one:

- **A column locks in the design.** Workflow state is precisely the part of this that will change shape as it
  grows — history, per-step timestamps, richer assignment. Each of those, on a column, is a migration; in the
  `data` map, it is a new key for free. Ossifying today's shape into a schema is the mistake to avoid.
- **A single `assigneeId` column cannot even express the actor model we want** — a role, or a group — so the
  column would have committed us to one-user assignment before we knew we wanted more (see the handoff
  section).

**But the advisor queue needs to *find* a workflow** — "every workflow assigned to me, by status" — and that
looks like it wants an indexed column. It does not: **the gedra cache computes the index in memory.** The
cache already holds the whole table and already serves a scoped listing from an index that *is* the scope
(`listGedras` from the `clientKind` index). The queue is one more such index, its key computed from
`wfAssignment` — the caller looks up the keys matching their identity (their roles, groups, user id). This is
where derived, queryable state belongs: the cache captures it, the row does not carry it as a column. *(The
queue endpoint and its cache index are phase 3; the point settled here is that no column is added for them.)*

## The state machine

A workflow is a **named-state machine**, general rather than a fixed flow, because the definition drives it
(the choice made for [Q4] — see the config section). A definition declares:

- a set of **states**, each marked with **who holds the workflow there** — the user, the advisor, or nobody
  (a terminal state);
- a set of **tasks** — the *steps* (see below);
- a set of **transitions**, each with a name, a `from` state, a `to` state, the **role that may take it**, the
  **tasks it requires complete**, and whether it **reopens tasks**.

```
        submit                 return
draft ─────────────▶ inReview ─────────▶ changesRequested
(user)               (advisor)                (user)
                        │                        │
                        │ approve                │ resubmit
                        ▼                        └────────────┐
                     approved  ◀──────────────────────────────┘
                     (terminal)
```

The picture is one *instance* of the machine; the engine holds no opinion about these particular states. A
different definition names different ones.

### Steps: the reopenable task set

A **task** is a step: a unit of editing, declaring the traits its page may touch and the subset of those that
must be filled for the task to be *complete* (`gedra-patch.md`: "a task declares the traits and key ranges in
scope for the task edit"). The workflow's tasks are its steps, and completeness is expressed **per task** — a
`submit` requires *its tasks* complete, and reports which are unfinished when it refuses.

Tasks are also what a targeted **"request changes"** reopens. When the advisor returns a workflow, they name
**which tasks** to send back (at least one) with a note; the engine records them under `data.wfReopened` and
the workflow moves to a user-held state showing exactly those steps as needing attention. A task's effective
status is therefore derived, not stored: `changesRequested` if the advisor reopened it, else `complete` if its
required traits are present, else `pending`. **The owner moving the workflow forward — a transition leaving a
user-held state — clears the overlay**, so a resubmission is reviewed afresh: the advisor's ask ("this number
looks wrong") is a judgment the system cannot see as addressed, only the advisor can. Other transitions — an
advisor-side claim or triage move — **preserve** it, so no unrelated move can erase the notes the user is
still reading. That is the same division of labour the soft-validation seam draws: the system checks
*presence*, a person checks *correctness* — and for the same reason, a task's completeness is **presence** of
its required traits (an entry with a data value, however shaped, even empty), never a judgment of content: a
trait whose payload is legitimately empty must be able to satisfy a gate, or the submit is blocked forever.

So "send it back to the assets step" is: the advisor's `return` names the `assets` task; the user sees that one
step flagged with the note, edits it, and resubmits. The state machine stays coarse (four states); the steps
live in the task layer beside it. This is a **reopenable set**, not an ordered wizard — the advisor may flag
any subset, in any combination, and order among tasks is not enforced.

### Guards, and the soft-validation seam

`gedra-patch.md` already draws the crucial line and this design must not cross it:

> **Soft validation** is the workflow's. It does **not** fail the write. It stops you *advancing*.

So a guard is evaluated **at a transition, never at a write**. Editing a form entry always succeeds (subject
to scope and locking, which the `PATCH` already enforces); it is `submit` — the transition — that runs the
task's completeness rules and refuses when they are unmet, returning *what* is unmet so the UI can point at it.
This keeps the two mechanisms from fusing, which the doc names as "the trap next to it": a questionnaire trait
marks every answer optional in its own schema, and the requirement lives in the workflow, not the schema.

Two guard kinds cover the near term:

- **completeness** — a transition's required **tasks** are complete, a task being complete when its required
  traits are present and non-empty. This is soft validation, attached to `submit`/`resubmit`; the refusal
  names the *unfinished steps*.
- **role/assignment** — implied by the transition's declared role and the caller's capabilities; not authored,
  enforced by the engine.

A completeness check returns a list of *reasons* — the unfinished task ids — never a boolean, for the same
reason the `PATCH` returns per-edit outcomes: a refused advance has to tell the person *what to go finish*.

### A transition is edits, then a gate, then a state move — in one transaction

A transition is not a new write path. It runs **one topic transaction** on the workflow instance (the natural
lock root — `GedraDataTran` exists precisely so a lock can span the content table and, later, a file store):
re-read the row under the lock, **re-verify the state and the assignment against committed data**, apply the
caller's edits (the identical fold the `PATCH` performs, validation included), check the required tasks
against the resulting entries, and write once — the entries, and (only if the gate passed) the new status,
assignment and reopened overlay.

The re-verification is what makes the advance genuinely atomic: the pre-lock read may be cache-served or
simply raced, so a transition taken against a workflow another actor has already moved fails with a
**conflict** rather than overwriting their move — which is what keeps "no transition leaves a terminal state"
true at runtime and a claim meaningful across nodes. One transaction also means one `updatedAt` bump per
transition (the monotonic rule the cache depends on), and one lock take rather than two.

The soft-validation contract is unchanged: a `submit` refused for incompleteness still **saves the edits it
carried** — the refusal path writes the edited entries without touching the state keys — because the refusal
must not punish the user for saving. Only the *advance* is withheld. A refusal carrying no edits writes
nothing at all.

## The definition, authored in source

Per [Q1], we build toward the config-driven engine, and the buildable-now form of that is a definition
authored as **source-code config** — a `GedraConfig`, exactly as `coreTraits` is. This realizes the general
engine (states/transitions/guards are data the engine interprets) while sidestepping the deferred *database*
config storage (`gedra-config-and-data.md`: "Config written in source code is … declared directly"). The fall
back to a single concrete workflow, if the engine proves too large this pass, is then just *authoring one
definition and not generalizing* — no architectural undo.

The authoring DSL extends the existing `gedraConfig { trait(...) }` with `workflow(...)`:

```kotlin
fun loanConfig(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, "loanConfig", "acme") {
    trait(/* … income, assets traits … */)

    workflow("loanReview", "A loan application, filled by the applicant and reviewed by an advisor.") {
        state("draft", holder = USER, initial = true)
        state("inReview", holder = ADVISOR)
        state("changesRequested", holder = USER)
        state("approved", holder = NONE, terminal = true)

        task("income") { editable(GT.income); require(GT.income) }   // a step: what it edits, what it needs
        task("assets") { editable(GT.assets); require(GT.assets) }

        transition("submit", from = "draft", to = "inReview", by = ROLE.user, requires = ["income", "assets"])
        transition("approve", from = "inReview", to = "approved", by = ADVISOR_ROLE)
        transition("return", from = "inReview", to = "changesRequested", by = ADVISOR_ROLE, reopensTasks = true)
        transition("resubmit", from = "changesRequested", to = "inReview", by = ROLE.user, requires = ["income", "assets"])
    }
}
```

`return` carries `reopensTasks`; the advisor supplies the actual task ids **at call time** (they decide "the
assets step is wrong"), not in the definition — the definition only says this transition is the kind that sends
steps back. `submit`/`resubmit` name the tasks they require complete.

The definition is compiled into a **kernel-shared model** (`base/kernel`, beside `GedraConfig`) so the
**frontend runs the same state machine** the backend enforces — the pattern `CLAUDE.md` names as the point of
`base/kernel`: the UI computes "which transitions can I take from here?" with the identical rule the gate
enforces, and a renamed state breaks its compile, not its runtime.

## The handoff, and the advisor

Per [Q2] the user stays the **owner** (`userId`); the workflow's `wfAssignment` and `wfStatus` say *whose
turn*. This keeps "the user owns their in-progress thing" true and models real back-and-forth (`return` /
`resubmit`), which reassigning ownership would lose.

### Assignment is a role, a group or a user

Who may act is not a single person. It is a `WfAssignment` — a `{kind, value}` naming one of three:

- a **role** — anyone holding it (the "users belonging to a particular role" case);
- a **group** — anyone in it (a named group of users);
- a **user** — one specific person (a claim).

A state's `holder` gives the **default**, so the common cases need no explicit assignment: a user-held state
belongs to the **owner**, an advisor-held state to the **advisor role** (the whole pool). A caller narrows
that by passing an explicit assignment to a transition — claiming an advisor-held workflow to *themselves*
(`ofUser`), or routing it to a **group** (`ofGroup`). The state machine's `holder` enum stays coarse; the
richer "who exactly" is the assignment beside it.

Matching a caller against an assignment is `WfAssignment.matches(WfActor)`, where the actor is the caller's
`{roles, groups, userId}`. A role match runs through `RoleLadder.satisfies` — the same predicate the section
gate and a transition's `by` use — so "assigned to the advisor role" reads roles the one way the whole system
does. What a *group* means is decided at the edge (the `WfActor` is built from `KdrCxt`), not baked into the
kernel — and today the honest answer is **no groups at all**: there is no group-membership source, so the
engine refuses to *write* a group assignment (`EXC.notSupported`) rather than quietly mapping groups onto
something else. Mapping the caller's primary organization here was considered and rejected: it would turn "a
named group of users" into "everyone sharing an org name", un-qualified by client, and stored group rows would
be indistinguishable from real group ids when a membership source arrives.

Taking a transition needs **both** the transition's `by` role *and* a match against the workflow's current
assignment — the role says "may do this kind of thing", the assignment says "this one is yours". That is what
makes a *claim* meaningful: a second advisor holds the role but not the claim, and so cannot act on a workflow
claimed by the first. `availableTransitions` applies the same two gates, so what a UI shows and what the
enforcement allows cannot disagree — an unclaimed button is never rendered for a claimed workflow.

**The gate fails closed, in every direction.** No assignment — an ownerless workflow, a process-held
(`holder = none`) state — means *nobody* may act, not anybody. A stored assignment that cannot be read is a
fault, never a fall-back to the state's default: falling back would widen a one-person claim to the whole
advisor pool exactly when the data is least trustworthy. And a caller-supplied assignment is **validated
before anything is written** — only an advisor-held target accepts one, a named user must exist in the
workflow's client and hold the advisor role, a named role must be one the deployment knows — because a
workflow assigned to a principal nobody can match is permanently stuck: the only way to change an assignment
is to take a transition, which requires matching it.

### Advisor is a deployment capability role

Per [Q3] **advisor is a deployment capability role** — off the ladder, the same shape as `allClients`
([`ROLE`/`RoleLadder`](base/kernel), and the capability mechanism in the access-control note of `CLAUDE.md`).
"May act as an advisor" (the capability, gating the advisor *section*) is thus separated from "holds *this*
workflow" (the assignment). The `WfDefinition.advisorRole` is that capability's name; a deployment naming its
own sets it there.

### Two read paths, mirroring user administration

`CLAUDE.md` describes user admin as "two surfaces over the same handlers." The workflow takes the same shape:

- **the user's own** workflows — `ReadScope.ofUser`, already what `forCaller` returns for an ordinary caller;
- **the advisor's queue** — *workflows I may act on* — served from the **gedra cache's computed assignment
  index**, not a scope column. The caller's identity yields a set of assignment keys (their advisor role,
  their groups, their own user id); the queue is the union of those cache lookups, each row still passing the
  ordinary per-row scope check. This is the same shape as `listGedras` from the `clientKind` index — an index
  that *is* the dimension, never a scope predicate composed in memory.

The advisor never needs `allClients`; their reach is *their assignments within their client*.

### Access control

A new **section** (the first path segment after the context root — `CLAUDE.md`, "Access control"): endpoints
that act *as the advisor* live under an `advisor`-gated section requiring the `advisor` capability; the user's
own workflow endpoints stay in the ordinary user section. `RequestService` refuses to boot a section with no
rules, so the advisor surface cannot ship open by accident.

## Endpoints

Small, and mostly thin wrappers over the engine and the existing `PATCH`:

| endpoint | section | does |
| --- | --- | --- |
| `POST /wf/start` | user | create a workflow instance from a definition, in its initial state |
| `GET /wf/{id}` | user | the workflow, its forms, and *which transitions the caller may take now* |
| `POST /wf/{id}/patch` | user | edit entries (the existing `patchGedras`), no state change |
| `POST /wf/{id}/transition/{name}` | user | take a transition: patch + guard + state move, atomically |
| `GET /advisor/queue` | advisor | workflows assigned to me, by status |
| `POST /advisor/{id}/transition/{name}` | advisor | advisor-side transitions (`approve`/`return`) |

`GET /wf/{id}` returning the *available transitions* is what lets the UI render buttons without duplicating the
rule — it asks the engine, which is the same code the transition endpoint enforces with, so "see" and "do"
cannot disagree (the principle the dispatcher/catalog split already follows in `CLAUDE.md`).

## The testing framework

The request explicitly includes "a decent testing framework." The existing tools are strong —
`mkTestBootCxt`, `TestUser`/`becomeUser(level)`, `TestHttpClient`, the focused-vs-flow split (`kdr-testing`
skill) — and the workflow adds a **scenario harness** on top, because a workflow test is inherently a
*sequence of acts by different people*, and asserting one step at a time buries the story.

```kotlin
workflowScenario("loanReview") {
    asUser(applicant) {
        start()
        patch { set(GT.income, mapOf("amount" to 90_000)) }
        submit().shouldAdvanceTo("inReview")
    }
    asAdvisor(reviewer) {
        queue().shouldContain(workflowId)              // it reached the advisor's queue
        transition("return").because("assets missing") // a guard failure, asserted by reason
    }
    asUser(applicant) {
        patch { set(GT.assets, /* … */) }
        submit().shouldAdvanceTo("inReview")
    }
    asAdvisor(reviewer) { transition("approve").shouldReachTerminal() }
}
```

The harness is a thin driver over `TestHttpClient` and `becomeUser`, so it drives **real HTTP against a booted
instance** through the real gate — the scope and capability checks are exercised, not stubbed. Two properties
it must have, both learned in this codebase:

- **Assert on refusal, not just success.** The point of soft validation is the *reason* a submit was refused;
  a scenario asserts `because("…")`, mirroring the `PATCH`'s per-edit outcomes and the `_debug=explainAccess`
  philosophy.
- **A guard failure is a first-class expected outcome**, not an exception to catch — the harness distinguishes
  "the advance was correctly refused" from "the call errored."

The engine itself (state graph, guard evaluation) is also covered by focused, in-process tests with no HTTP,
the way `SqlTableCacheTest` covers the cache — the scenario harness is for the *flow*, not the unit.

## The UI

Net-new: the webapp has admin/users screens only, no gedra UI yet. Built in the existing stack
(kotlin-wrappers + React + antd), and driven by the **kernel-shared workflow model**, so the frontend derives
what to show from the same definition the backend runs.

Two views, because there are two roles:

- **User view** — a workflow's forms rendered as **task pages** (a task declares the traits its page edits —
  `gedra-patch.md`); a save that maps to `POST /wf/{id}/patch`; and a **Submit** that maps to the transition,
  showing soft-validation reasons inline against the fields they name rather than as a wall of errors.
- **Advisor view** — the **queue** (`GET /advisor/queue`), and a read-mostly form view with the advisor's
  transitions (**Approve** / **Request changes**) as the actions, the request-changes path carrying a note
  back to the user.

The buttons a view shows come from `GET /wf/{id}`'s *available transitions*, so the UI never hard-codes the
state graph — a definition change reshapes the UI without a frontend edit, which is the payoff of putting the
model in `base/kernel`.

A **form is rendered from its traits' schema**, not hand-built per form — the traits already carry their data
schema (`GedraTrait.dataSchema`), so a generic trait-driven form renderer is what makes adding a form a
config act rather than a UI one. That renderer is the largest single piece of UI work and the one most worth
getting right.

## Phasing

Each phase is an issue/PR, in the established rhythm, and each leaves the tree green and demonstrable:

1. **Data + engine core** *(done — issue #381).* Workflow state in the `data` map (**no columns**); the
   kernel-shared state-machine model including **tasks and the reopenable task set** and **role/group/user
   assignment**; per-task completeness; and the transition operation (edits → gate → move). Focused tests
   only, JVM+JS. No endpoints, no UI. *This was the phase that said whether the general engine (Q1 option 2)
   is the right size — it is.*
2. **The definition DSL + one real workflow** authored in `sample` (loaded in dev), exercising states, tasks,
   completeness, and a targeted `return`.
3. **Endpoints + the advisor read path** — the `assignee` scope dimension and cache index, the sections, the
   six endpoints, and the scenario test harness.
4. **UI** — the trait-driven form renderer, then the user and advisor views.

Phase 1 is the decision point the user flagged ("see how far option 2 takes us; may back down to option 1").
Nothing before phase 3 commits to the general engine being worth its weight; phases 1–2 are useful either way.

## Open questions

- **Member-form lifecycle.** When a workflow is created from a definition, are its forms created eagerly
  (all at once) or lazily (when a task first touches one)? Lazy is less state to carry in `draft`; eager makes
  the id graph complete up front. Leaning lazy.
- **Reassignment between advisors.** A transition may pass an explicit assignment, so an advisor claiming a
  pooled workflow, or handing it to a group, is already expressible. Whether a transition that *only*
  retargets the assignee (no state change — "pass this to Alice") is worth its own shape, versus doing it as a
  side effect of some state move, is unsettled until there is a workflow that needs it.
- **What a group is.** Arbitrary named groups (a review team that is not an org) need a group-membership
  source that does not exist yet. The `WfActor`/`WfAssignment` model is ready for it; until it exists the
  engine **refuses to write** a group assignment rather than approximating one, so nothing has to be migrated
  when the real thing arrives.
- **Locked entries and reopenability.** *A follow-up phase, gated on entry locking existing at all — which is
  first a `PATCH`-layer concern (`gedra-patch.md`), not the workflow's.* Locking touches the workflow in both
  directions. It **produces** locks: `approve` is the "approval has occurred" that locks the entries it signs
  off (`gedra-patch.md`), so a terminal transition is what would stamp `lockedBy`. And it **respects** them:
  reopening a task whose editable entries are all locked is moot — the `PATCH` would refuse every edit anyway —
  so a `return` naming such a task should be *refused*, not left as an empty gesture. The seam is already here:
  `WfTask.editableTraitIds` is what the reopenable-set check reads; when locks exist, a task is reopenable iff
  it has at least one unlocked editable entry, and the reopen validation (today only "the task exists") gains
  that predicate. This is the non-linear reopen model paying a debt it always implied: because the advisor may
  point at *any* task, the set of tasks that can genuinely be sent back has to be computed, not assumed.
- **Concurrent edits during review.** Once `inReview`, may the user still edit? The lock/`process-only`
  entry machinery (`gedra-patch.md`) is the tool, but which entries lock on `submit` is a per-definition
  policy this document does not fix.
- **History granularity.** Every transition is recorded in `data`; whether each *edit* is, or only each state
  change, is a volume question deferred until the workflow is real enough to measure.
- **Reopen without a resubmit clearing it.** The overlay clears on any forward move, so a user who resubmits
  without touching a flagged step still sends it back as "done" for the advisor to re-judge. That is the
  intended division of labour, but a definition that wants a step to *stay* flagged until genuinely re-edited
  would need a per-task "touched since reopen" signal this phase does not carry.
