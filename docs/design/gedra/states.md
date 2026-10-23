# Gedra states

A gedra's **state** is what is known about it beyond what people entered: what the system derived from its data, such
as whether a form's survey is complete, and what a person or an outside system asserted about it, such as that the form
was put into a workflow or that a reviewer approved it. State is held as entries, like data, but in a table of its own,
and written by different code under different rules. This document says why state is kept apart from data, how a state
trait is declared, how each class of state is written and kept current, who reads it, and what is not built.

It complements [`entry.md`](entry.md), which describes the data side: entries, traits, and the unions they form.

**Status: built**, under the tracking issue #595:

| Phase | Issue |
|---|---|
| The `GedraDataStates` table, written under the data's lock | #596 |
| State as trait-modeled data: one global union, `derived` or `asserted` | #597 |
| The always-resident states cache | #598 |
| The state-to-cfact bridge, and initial state on create and import | #599 |
| Reading state: `withStates` on the forms listing, and the admin state endpoints | #600 |

Later work built on it: the survey's derived state (#657); a recompute after every data write (#675) on a
transaction-scoped context (#687); the per-workflow state of a normal workflow (#794), with eligibility (#783), merged
cfacts (#784), the call-to-action task (#785), approvals (#787), and time windows (#790); the nightly recompute job
(#793); a recompute that reads the data again under the lock (#862); trait locks, which read state and record their
overrides in it (#857); and the publish impact report, which judges stored state (#935). What is not built is listed at
the end.

## Why state is separate from data

A form's data is what its people entered. Its state is about the form, and is written by the process around it. The two
differ in who writes them, how often, and how a fault is treated, so they are stored apart:

| | Data | State |
|---|---|---|
| Table | `GedraData` | `GedraDataStates` (`GDT.gedraDataStates`), one row per gedra, keyed by the same `gedraId` |
| Written by | the people working on the form: create, patch, import, and the workflow saves | derivers, after each data write; the acts that assert something; an administrator |
| Traits | declared by components and by clients, with a per-client union per gedra kind | declared by components only, in one global union |
| Unknown trait on write | refused, unless the caller sends `allowAdditionalTraits` | always refused |
| Cache | `GedraDataCache` | `GedraStatesCache`, which always holds every row whole |

**Both are written under one lock.** A state write takes the `GedraDataTran` lock for the gedra, the same lock its data
is written under (#596). So a create, an import, or a patch commits the data and the state derived from it in one
transaction, and an act that reads state and then writes it cannot race a data write.

**A separate row keeps a state change off the data row.** The design note behind #595 expected state to change in
batches (a workflow revised, every engaged form's state recomputed) while data rows grow large. A small row of its own
is cheap to rewrite and to reload into a cache, and its `createdAt` and `updatedAt` record state changes apart from data
changes.

**A patch does not address state.** A patch's edits name data traits, checked against the gedra kind's edit union, and
nothing in a patch names the state row. The one state a patch writes is the change a write guard returns with it (see
[Asserted state](#asserted-state-written-by-an-act)). An import carries none: it keeps only a document's trait entries,
and the imported form's state is derived anew by the create path (`GedraDataService.importGedras`).

## Declaring a state trait

A state trait is declared in a component's `gedraConfig { ... }` block with `stateTrait`, the counterpart of `trait`. It
uses the same machinery: one generated entry type, keyed on `traitId`, with an optional `primaryKey` and the stored
envelope. It differs in two ways. It carries a `StateTraitClass`, which is required, and it is filed under
`GedraConfig.stateTraits`, never under `traits`, so the data unions and the patch keying never see it. State and data
traits share one id space, and `checkTraitIsNew` refuses an id used by either.

```kotlin
stateTrait(
    WFS.workflowEngagementEntry, WFS.workflowEngagement, setOf(GedraDataType.formDoc),
    StateTraitClass.asserted,
    "That a person put this form into a workflow, and the trail of what has happened to it since.",
    primaryKey = listOf(WFD.workflowId),
) {
    property(WFD.workflowId, "The workflow this form is engaged with.", required = true)
    // ...
}
```

The class, `GedraTrait.stateClass`, says how the state behaves under recomputation:

| `stateClass` | What it is | Examples | Under a recompute |
|---|---|---|---|
| `derived` | a projection of the gedra's data and the current definitions | survey completeness, eligibility, the form's cfacts | replaced whole |
| `asserted` | an act by a person or an outside system | an engagement, an approval, a third-party id | kept as stored |

**State is global.** The `StateEntry` union (`GU.stateUnionName`) is built once, at the global scope, by
`stateEntryUnionDefs` in `GedraEntryUnion.kt`. Unlike data it is one union across every gedra kind, since most state
could apply to any kind of gedra, and a state trait's `appliesTo` is checked when it is written rather than by union
membership. A client cannot vary it: a client's stored configuration that declares a state trait is refused when it
is written (#873, `GedraConfigSerialize`), and a client configuration from source that declares one has the trait
dropped and a config problem reported (`GedraConfigCollector.keptStateTraits`). A sandbox runs the same state traits as
every other client.

The runtime and the sample declare these:

| Trait id | Class | Key | Applies to | Declared in | Written by |
|---|---|---|---|---|---|
| `kdr:cfacts` | derived | none | `formDoc`, `wfData` | `CoreTraits.kt` | every deriver that contributes cfacts, merged into one entry |
| `kdr:surveyCompletion` | derived | none | `formDoc` | `SurveyState.kt` | `SurveyStateDeriver` |
| `kdr:workflowState` | derived | `workflowId` | `formDoc` | `WorkflowState.kt` | `WorkflowStateDeriver` |
| `kdr:workflowEngagement` | asserted | `workflowId` | `formDoc` | `WorkflowState.kt` | engaging and disengaging, and the trail events of later acts |
| `kdr:workflowApproval` | asserted | `workflowId`, `taskId` | `formDoc` | `WorkflowState.kt` | the approve endpoint |
| `sample:traitPresenceByYear` | derived | `year` | `formDoc` | `SampleTraits.kt` | `TraitPresenceByYearDeriver`, only on a test instance whose client lists its test feature |
| `sample:externalId` | asserted | `externalSource` | `formDoc`, `wfData` | `SampleTraits.kt` | tests, through `writeState` |

A form-wide fact is unkeyed, and there is one entry of it per form. Per-workflow state is keyed by the bare
`workflowId`, not by a revision-pinned reference, so an asserted engagement or approval survives a revision of the
workflow. The per-workflow entries are declared open (`additionalProperties`), so a later change can add a field
without reshaping a type its readers parse.

## The stored row

A state row's `data` holds `{"entries": [...]}`. Each entry is a stored entry of a state trait, with the same envelope a
data entry has (`entryId`, `source`, and the four audit fields), addressed by its trait id and its primary-key values.
The row carries the same ownership columns as a data row, stamped from the gedra's owner rather than from whoever
caused the write, so a state row scopes as its gedra does: an owner reads their own, an administrator their client's.

`GedraDataService.writeState` writes a **complete set**: it replaces the row's entries with the ones given. Before it
writes, it checks, in order:

1. Each entry names a state trait this node declares. An unknown one is refused, never stored on the union's open
   default branch, because state traits come only from components, so an unknown id is a caller's mistake. The open
   branch, `StateEntryUnknown`, exists so that a *read* of a state row tolerates a trait that is no longer declared.
2. The trait's `appliesTo` names the gedra's kind.
3. No two entries share an address.
4. Under the lock, each entry, with its envelope stamped, validates against the `StateEntry` union. One entry that
   does not conform refuses the whole write, and nothing is stored.

An entry whose address matches a stored one keeps that entry's `entryId`, `createdAt`, and `createdBy`. Only the
updated half of the envelope moves. A write advances the row's `updatedAt` strictly past the value read under the lock,
so the states cache sees the change.

Deleting a gedra disables its data row and leaves its state row in place. Readers that need the live set, such as the
listing summary and the recompute job, take their ids from the data side.

## Derived state: computed and kept current

**A deriver computes it.** A `GedraStateDeriver` is Kotlin a component registers with
`SchemaCollector.addStateDeriver` during `addSchema` (#599); see [`extending.md`](../../extending.md#a-component) for
the component hooks. A trait declaration is data that could be loaded from a database, while a computation is code,
so the two are kept apart. A deriver names the gedra kinds it `appliesTo`, may name a `featureName` that confines it to
test instances whose client lists that test feature, and returns `{traitId, data}` maps from `derive`. The caller
stamps, keys, validates, and writes them.

`derive` is handed a `GedraStateContext`:

| Field | What it holds |
|---|---|
| `row` | the gedra's data, as read or written under the lock (#862) |
| `currentState` | every state entry as it stood before this recompute, asserted and derived alike |
| `derivedThisPass` | what the derivers registered before this one produced in the same recompute (#783) |

**Registration order matters.** The runtime registers `SurveyStateDeriver` before `WorkflowStateDeriver`
(`CommonComponent`), because a workflow's eligibility is judged against the cfacts the survey's deriver emits in the
same pass. Reading `currentState` instead would see the cfacts as they stood before the write. Several derivers may
each emit a `kdr:cfacts` entry, and `mergeCfactContributions` folds them into the one entry the unkeyed trait allows
(#784).

A deriver does not carry its own last answer forward: derived state is recomputed from the data. `currentState` lets a
derivation follow an asserted fact beside it, as an engaged workflow keeps its `kdr:workflowState` entry. The one
exception is deliberate: a workflow past its relevancy window is not calculated, so an engaged form's entry for it is
carried forward as last calculated (#790).

**A recompute keeps asserted state and replaces derived state.**
`GedraDataService.recomputeDerivedStateUnderLock` reads the state row under the transaction's lock, keeps every entry
whose trait is `asserted`, runs the derivers, and writes the kept entries followed by the derived ones. A derived entry
a deriver no longer emits is removed by that replacement. The lock-held read matters: the resident cache can lag another
node, and a recompute that read asserted state from it could drop an assertion made a moment before.

It runs in four places:

| Where | When | What a derived entry that does not validate does |
|---|---|---|
| `DerivedStateWriteHook`, the built-in post-write hook (#675) | inside every create, import, and patch, after the data is written | logged, and the state left as it was; the data write stands |
| `POST /gedra/formDoc/recomputeState` (#794) | on demand, for one form the caller can see | the same |
| `GedraDataService.changeState` | before and after an act changes asserted state | the same |
| the `stateRecompute` job (#793) | daily at 03:00 UTC, and on demand, over every live gedra of a kind some deriver applies to | fails the gedra's task, with the scenario `derivedStateInvalid` |

**The job catches what no write did.** Stored derived state is only as current as the gedra's last data write. A form
whose workflow gained an eligibility test, or whose relevancy window closed, carries state computed against
configuration that no longer holds until something recomputes it. The job first compares, from the caches and outside
any lock, what a recompute would store with what is stored (`derivedStateCurrent`), and takes the lock only for a form
that differs. A dry run counts those forms and writes nothing. The nightly report history job runs half an hour later,
so its snapshots read state the recompute has just brought up to date (see [`reporting.md`](../reporting.md#history)).

The recompute binds its context to the gedra's owner and client first, so a deriver's feature gating and the state
row's ownership follow the gedra, not the person whose write triggered it.

## Asserted state: written by an act

Asserted state is written by the act that asserts it, through `GedraDataService.changeState`: a read-modify-write of
the whole entry set under the gedra's lock. It recomputes derived state, hands the act the entries as they now stand,
writes the set the act returns, and recomputes again. The first recompute means an act that decides from derived state,
such as the engagement gate reading eligibility, decides from what is true now rather than from what was stored at the
last data write. The second follows the change, since an engagement alters what is derived.

Reading through `readState` and writing through `writeState` would not be safe: the read comes from the cache, outside
any lock, so two acts on one form could each write back a set missing the other's change.

| Act | Endpoint or seam | What it writes |
|---|---|---|
| Engaging a form with a normal workflow, or disengaging it (#794) | `POST /gedra/workflow/engage` (`WorkflowEngagement.setEngaged`) | the `kdr:workflowEngagement` entry: `engaged`, `lastEngagedAt`, `lastEngagedBy`, and an `engaged` or `disengaged` event on its trail. Engaging is refused for a form that fails the workflow's eligibility tests (#783). Disengaging keeps the entry. |
| Approving an approval task (#787) | `POST /gedra/workflow/approve` (`WorkflowApprovals.approve`) | a `kdr:workflowApproval` entry, and an `approved` event on the engagement's trail. Refused unless the form is engaged and the task is the workflow's current task; a second approval in one engagement is a 409. |
| Overriding a trait lock (#857) | `TraitLockGuard`, a write guard, on a patch | a `lockOverridden` event on each locking workflow's engagement trail, written in the patch's transaction before the post-write recompute |
| Replacing a gedra's state outright (#600) | `POST /admin/gedraState`, gated `admin` and `allClients` | the complete set given, through `writeState` |

The engagement entry's `events` list is an append-only trail. Each act records its milestone there with
`WorkflowEngagement.withEvent`, which is what the entry was declared open for.

A write guard (`GedraWriteGuard`, registered with `addWriteGuard`) runs under the lock before a patch is applied, sees
the gedra's state, and may return a change to the state along with its verdict. The patch applies that change with
`writeState` after the data and before the post-write hooks, whose recompute keeps it because it is asserted.

## Reading state

**Outside a transaction, the states cache is trusted completely.** `GedraStatesCache` (#598) holds every state row, with
one index by client, over the same `gedraData` topic as the data cache. A miss is a definitive "no state", not a reason
to query. `readState` and `readStates` apply the caller's `ReadScope` to each row, so a caller can no more widen a state
read than a data read. Inside a write's transaction, state is read from SQL under the lock instead. The `_debug` tag
`stateFromSql` forces the SQL path for diagnosis, and a node with caches turned off takes it always.

| Read | What it returns |
|---|---|
| `GET /gedra/formDocs?withStates=true` (#600) | each listed form's `states`, absent rather than empty when not asked for. The webapp's forms page always asks, for its survey status column (#694) and workflow cell (#791). |
| `GET /gedra/formDocs` filters | the survey-status filter (#695) and the workflow drill-down (#792) test each row's state before paging |
| `withWorkflowSummary` (#791) | the workflow column's summary, computed over the state of every form the caller can see |
| `POST /gedra/workflow/engage`, `POST /gedra/workflow/approve`, `POST /gedra/formDoc/recomputeState` | the form's state as it stands after the act (`WorkflowStates`) |
| `GET /gedra/formDoc/states` (#1366) | one form's state as stored, with no recompute (`WorkflowStates`); with `includeRetired`, the entries of workflows that are not live too, named in `retiredWorkflows` |
| `GET /gedra/formDoc/locks` (#857) | the traits locked for the caller, worked out from the form's state |
| `GET /gedra/formDoc/singletonWorkflows` (#789) | the engaged workflows behind a Needs Review or Finished chip |
| `GET /admin/gedraState` (#600) | one gedra's state entries, for an administrator of every client |

The `states` arrays are typed by the `StateEntry` union, so a reader sees which trait each entry carries.

**A workflow that is not live is not shown** (#1354). Every read above but the admin one leaves out the per-workflow
entries (engagement, calculated state, approvals) of a workflow deleted from the configuration or past its lifetime
(`WorkflowPhases.shownStates`, applied by `GedraDataService.readShownStates`). Nothing stored changes, so restoring the
workflow, or extending its lifetime, shows them again. `GET /gedra/formDoc/states` with `includeRetired` reads them as
stored, for seeing what a form still holds for such a workflow.

## Who uses state

**Cfacts.** A form's `kdr:cfacts` entry is the bridge from state to cfact expressions (#599).
`GedraDataService.formCfacts` reads it and keeps only the names the client's registry still declares, since stored
state can outlive a cfact. Those names join the request's facts when an expression is evaluated about the form.

**Workflows.** A normal workflow is mostly built on state:

- **Eligibility** (#783) is judged against the form's cfacts and the singletons its other workflows contribute, and the
  verdict, with each failing test's id, is stored on the workflow's `kdr:workflowState` entry.
- **Engagement** gates the workflow's own work: saving and approving its tasks (#856, #787), its trait locks (#857),
  and, once the engagement window has closed, whether the workflow is shown for the form at all (#790). It does not
  gate reading the form or writing its data outside the locks.
- **Task status.** An engaged workflow's entry carries its call-to-action task, the earliest not both complete and
  valid, with its status, or `tasksDone` when there is none (#785).
- **Locks.** A `WfLock` is active while the form is engaged with its workflow, the workflow is within its lifetime, and
  the lock's `when` expression matches the form's cfacts together with the workflow's stored cfacts and singletons
  (`TraitLocks.heldFor`). Nothing about a lock is stored, so lifting one writes nothing.
- **The workflow view** reads the form's state once, for where the form stands with the workflow, its locked traits,
  and its approvals.

**Reports.** A report reads state through its `workflow.<workflowId>.<attr>` paths (`category`, `engaged`, `eligible`,
`finished`, `tasksDone`, `ctaTask`, `lastEngagedAt`, and an approval task's `approved`, `approvedAt`, `approvedBy`) and
through `meta.formStatus`, `meta.surveyStatus`, and `meta.cfacts`. Paths never expose raw state entries, and a run
reads states only when a column needs them. See [`reporting.md`](../reporting.md#paths).

**The publish impact report** (#935) judges each of a published-only client's forms by its stored state against the
candidate configuration's workflows (`ConfigImpact.judgeStates`). It reports `workflowGone` for a workflow the form
took part in (its creation workflow, an engagement, or a `kdr:workflowState` entry) that the candidate drops, and
`stateStranded` for a call-to-action task or an approved task the candidate no longer defines. State is compared, not
derived anew: a trial's workflows carry no functions to run, so a state the candidate would only derive differently is
not reported. See [`sandboxes.md`](../sandboxes.md#publishing-is-judged-against-the-client).

**Sandboxes.** A sandbox's forms are its own, so their state rows are too, kept apart by the client boundary (see
[`sandboxes.md`](../sandboxes.md#users-and-data)). A sandbox runs the global state traits and the runtime's derivers
like any client.

## What is not built

- **The class does not decide who may write.** The note on `StateTraitClass` says the flag decides both batch safety
  and who may write a trait. Only the first is built: `stateClass` is read solely by the recompute, to keep asserted
  entries. Who may write is decided per act, by the endpoint that performs it, and `POST /admin/gedraState` writes
  derived and asserted entries alike; the next recompute replaces the derived ones.
- **Nothing checks a deriver's output class.** `computeDerivedState` does not filter what a deriver returns, so a
  deriver that emitted an asserted trait would have it stored, and kept by later recomputes. The runtime's derivers
  emit derived traits only.
- **State as typed Kotlin classes.** State is held and cached as plain maps. The design note behind #595 deferred
  unwrapping state entries into Kotlin classes, which would cut the cache's memory and speed a batch scan, and kept it
  in view for state alone, since its traits are a fixed set.
- **An index by workflow.** The states cache is indexed by client only. "Every form with state for workflow X" is a
  scan of the client's rows.
- **Carrying state between deployments.** An import keeps no state, so an imported form's asserted state (an
  engagement, an approval) starts empty.
- **Removing state with its gedra.** A deleted gedra's state row stays, and nothing clears it.
