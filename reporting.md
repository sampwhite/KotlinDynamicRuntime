# Reporting

A client's forms carry their data in **traits**, each trait an entry with its own fields, and a keyed trait (a
record per year, say) holding several entries told apart by their key. A **report** promotes chosen values of those
traits — and facts about the form, its workflows and its owner — to the top level: one column per value, one row
per form, or one row per group of forms. It is what an administrator asks for when the question is "for every form,
what is X", or "how many forms have each X, and what do their Ys add up to".

Built in slices under #975: cursor paging (#976), report paths (#977), evaluating them (#978), report definitions
(#979), the registry that checks them when configuration loads (#980), and the endpoints that run them (#981).

## A report

A named report is declared in a client's configuration, in source or stored, beside its traits and workflows:

```kotlin
report("auditOverview", "Audit overview") {
    column("auditor", "Auditor", "form.acmeSiteAudit.auditor")
    column("total", "Total", "form.sample:expenseReport.totalAmount", rollup = ReportCombine.sum)
    column("review", "Review", "workflow.auditReview.category")
    column("owner", "Owner", "user.email")
    groupBy = listOf("auditor")
}
```

A column has an id, a label and a **path** (below), and optionally a `kind` (how its value is read), a `combine` (how
a path's several values become one within a form) and a `rollup` (how the column is combined across the forms of a
group). `groupBy` and `excludeEmpty` are defaults a run may override.

**Report ids are owned names** (#921): a component's is rooted (`kdr:formsByStatus`), a client's bare. So a client
sees the global reports beside its own, and a release adding a report can never take a name a client uses. Neither
replaces the other. Two of a client's own configs declaring one report id keep the first, and the second is a
configuration problem.

A client built on a **template** holds copies of the template's reports as its own, and is judged on them as on its
own (see *Checks*). A **sandbox** holds its parent's.

## Paths

```
form.<traitId>[selector]?.<field>(.<field>)*      form.<traitId>[selector]?.@<envelopeField>
workflow.<workflowId>.<attr>                      workflow.<workflowId>.approval[<taskId>].<attr>
user.<attr>                                       meta.<attr>
selector := [*] | [v1,v2,...] | [name=v,...]
```

- **`form`** reads a form's trait entry. After the trait, `data.` is implied: `form.acmeSiteAudit.auditor` is the
  `auditor` field of the entry's data. A field segment is letters, digits and `_ - $`, at most 32 of them. An array
  in the data is read whole — a path through one has several values. `@` reads a field of the entry's envelope
  instead: `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `source`.
- A **keyed** trait needs a selector, which says which entries: `[*]` every one; `[2024]` the one keyed 2024, a value
  per key field in the key's order; `[year=2024]` by name, which may leave key fields open and so read several. An
  unkeyed trait takes none. A key value is written bare, or in double quotes with JSON's escapes when it holds
  anything else; it is kept as **text** and matched against the stored key (`2024`, `2024.0` and `"2024"` are one
  year, but the zip `02134` is never `2134`).
- **`workflow`** reads what the forms listing shows about one workflow on the form: `category` (eligible, engaged,
  finished, ineligible), `engaged`, `eligible`, `finished`, `tasksDone`, `ctaTask`, `lastEngagedAt`, and an approval
  task's `approved`, `approvedAt`, `approvedBy`. Never raw state. A workflow the client no longer has, one outside
  its lifetime, and one closed to new forms that this form never engaged with show blank, whatever state is left
  behind.
- **`user`** reads the form owner's account: `userId`, `name`, `publicName`, `email`, `persona`, `org`, `labels`
  (several), `isEntity`, `enabled`.
- **`meta`** reads the form itself: `gedraId`, `client`, `org`, `ownerId`, `createdAt`, `updatedAt`, `formStatus`,
  `surveyStatus`, `cfacts` (several).

Trait, workflow and task ids are owned names, bare or `root:local` — which is why a path can hold a colon, and why
the separator between a trait and its key is a bracket.

## Kinds, combines and rollups

A value has a **kind**: `string`, `number`, `date` or `boolean`. A form field's kind is its schema's (a string with a
date format is a date); a vocabulary attribute's is fixed. A column may declare its own kind when it is the field's,
when it is `string` (anything reads as text), or when the field is text (a text field may hold numbers or dates; a
value that does not read is a blank cell). Two different kinds that are not text are refused.

Values are typed, not text: a number is whole or fractional by value, the same on the JVM and in JS; a day stays a
day; text is trimmed, so `Smith` and `Smith ` are one auditor; a boolean reads from the closed set of spellings the
schema validator accepts.

A **combine** makes a path's values one within a form:

| combine | yields | allowed for |
| --- | --- | --- |
| `first` | the first value — for a keyed trait, the lowest key's | any kind |
| `list` | every value, in order | any kind |
| `distinct` | each value once, sorted | any kind |
| `count` | how many values there are, a number | any kind |
| `sum`, `avg` | a number | numbers |
| `min`, `max` | the least or greatest | anything but booleans |

A column naming none gets `first` when its path has one value and `list` when it may have several, so the default
never drops a value silently. A **rollup** is the same set applied across the forms of a group, over each form's
column value; a column whose value is a list rolls up only by `count`. A grouped-by column must have one value per
form, so a list-valued one cannot be grouped by.

**Empty** is no value, a blank string, an empty list, or a list of nothing but empty values. `0` and `false` are
values.

## Checks

When configuration loads — at boot, on a client's reload (after its workflows), and in the trial every
configuration write runs — each report is **bound** to what it reads (`ReportService`, #980):

- its shape: a label, at least one column, unique column ids, labels, `groupBy` and `excludeEmpty` naming its
  columns once;
- each path parses;
- a form path's trait is one the scope supports and that applies to forms;
- the selector fits the trait's key: needed for a keyed trait, refused for an unkeyed one, a value per key field
  when positional, only key fields when named, and each value reading as its field's kind;
- the field exists in the trait's data schema — through `$ref`s, into arrays, in any branch of a union (a field the
  branches declare with different kinds reads as text) — and ends at a value or an array of values;
- a workflow path's workflow is a normal workflow of the client's — a creation or survey workflow has no place on a
  form to report — and an approval's task is one of its approval tasks;
- the declared kind, the combine and the rollup suit the value, and a grouped-by column has one value.

A global report is bound once, against the global traits, types and workflows; run for a client that lacks a trait it
names, it shows blank cells. A client's own — its template copies included — is bound against that client. So a
client redefining a template trait so that a template report's field is gone is refused at the write, naming the
report and the path, until it redefines the report too.

Each problem goes through `reportConfigProblem` and costs **that report alone**: in source config it refuses the boot
outside production, in stored config only in unit tests; otherwise it is logged, kept on the client's issues (which the
reports listing returns), and the rest of the configuration stands. A stored definition that cannot be read at all is
kept verbatim, so editing anything else in the config does not delete it.

## Running a report

Two endpoints, in the `clientAdmin` section: a client's administrator sees their own client, an `allClients`
administrator may name any with `client`, and a `public` self-administrator is refused. A run reads what the caller
may read: an administrator with a primary organization sees that organization's forms and the client's own (those with
no organization), as the forms listing shows them — a report never shows a form, or an owner, the caller could not
open elsewhere.

- **`GET /clientAdmin/reports`** — the client's reports, each column with the kind, combine and multiplicity it was
  bound to, which config declared the report and whether it came from a template, and in the summary the report
  problems that dropped one.
- **`GET /clientAdmin/report/run`** — `reportId`; `aggregate`; `groupBy` (an aggregate run's, the report's own when
  absent); `excludeEmpty` (the columns a form must have a value for: the report's own when absent, none when empty);
  `after` and `limit`.

A **detail** run returns `{gedraId, values}` per form. An **aggregate** run returns `{group, count, values}` per group,
where `group` holds the grouped-by columns' values and `values` the rollups of the columns that declare one; with
nothing to group by it is one total row. Beside the items, a `summary` names the report, its mode, columns, groupBy
and excludeEmpty, and how many forms were scanned and excluded — so an automated caller needs no second call.
`numAvailable` is the forms left after excluding, or the number of groups.

### What the cursor guarantees

Send each page's `next` back as `after` until a page has none.

- **Detail rows are ordered by form id, ascending.** An id never changes, and a page starts after the cursor's id
  rather than after an item, so **every form that exists for the whole walk is returned exactly once**, whatever is
  edited meanwhile. A form deleted during the walk is not returned if the walk had not reached it; a form created
  during it has a later id and appears at the end.
- **Groups are ordered by their key values**, no value last. A group is never skipped or repeated, but its count can
  change between pages, since the forms in it can.
- `excludeEmpty` applies before paging, so a form gaining a value mid-walk may join the walk if it is still ahead.
- A cursor belongs to one query — the client, the report as it was bound, the mode and the grouping. Another query's
  cursor — including one taken before a reload changed the report — or a malformed one is a 400, never a silent
  restart at the first page.

### Execution

A run is computed in memory over the caches, as the forms listing's search is: the client's live form ids from the
gedra cache, then the forms through a cache-first batch read (`GedraDataService.readGedras`). Each join is paid only
when a column needs it — entries, with fields computed on read (an expense total), for form paths; the form's states
for workflow paths and the form's status and cfacts; the owners' accounts for user paths. A detail run that excludes
nothing reads only its page's forms.

Above **`KDR_REPORT_SCAN_LIMIT`** forms (default 50,000, the ceiling #538 states for in-memory search) the run is
refused with a message naming the variable. A report covering part of a client's forms would be worse than none.

## Samples

The sample component declares one report per sample client:

- **acme `auditOverview`** — its site audit's auditor and findings, the expense report's year and computed total,
  where the audit review stands, the form's status, and the owner's email and name; grouped by auditor, the totals
  summed and the latest year kept.
- **globex `yearlyNotes`** — the keyed `yearly` trait read whole three ways (every year, how many, the latest) and
  one year's note picked by its key, `form.sample:yearly[2024].note`.
