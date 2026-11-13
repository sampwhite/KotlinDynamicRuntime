<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Report paths

Each column of a report takes its value from somewhere on a form, named by its `path`: a field of one of the
form's traits, where the form stands with a workflow, something about the person who owns it, or a fact about the form
itself. This page describes how a path is written, lists every name a path can end in outside a trait's own data, and
says how a column makes several values one. The lists are made from the names the path parser reads.

A report is declared in the `kdr:reportDef` slot of a client's configuration. Its `groupBy` and
`excludeEmpty` name columns by their `columnId`, not by path.

## The shape of a path

```
form.<traitId>.<field>.<field>...
form.<traitId>[<selector>].<field>...
form.<traitId>.@<envelopeField>
workflow.<workflowId>.<attribute>
workflow.<workflowId>.approval[<taskId>].<attribute>
user.<attribute>
meta.<attribute>
```

The first part is the **source**, and what follows depends on it.

| Source | Reads | Example |
|---|---|---|
| `form` | A field of one of the form's traits, or a field of a trait entry's envelope. | `form.session.track` |
| `workflow` | Where the form stands with one workflow, or one of its approvals. | `workflow.review.category` |
| `user` | The account of the person who owns the form. | `user.name` |
| `meta` | The form itself: its id, its dates, its status. | `meta.formStatus` |

## A trait's data: `form`

- **The trait's id comes first,** as the configuration has it. A client's own trait has a bare id, such as `session`;
  one the platform ships keeps its full id, such as `kdr:name`.
- **Then the fields of its data,** separated by dots, as deep as the data goes: `form.abstract.coSpeakers.name`. A path
  must end at a value, not at an object or a list of objects.
- **A list has several values.** A path through a list in the data reads every item, and the column's
  `combine` makes them one (see [Several values](#several-values)).
- **The value's kind comes from the field's schema:** text, a number, a date, or yes or no. A field with choices shows
  its label in the report's table, and its stored value in a downloaded file.

### Keyed traits

A keyed trait has an entry per key, such as one per year, so its path names which entries it reads with a selector in
square brackets after the trait's id. An unkeyed trait has one entry and takes no selector.

| Write | Reads |
|---|---|
| `form.yearly[*].revenue` | Every entry, so the path has several values. |
| `form.yearly[2024].revenue` | The entry whose key is 2024. A key of several fields gives each, in the key's order: `[2024,Q1]`. |
| `form.quarterly[year=2024].revenue` | The entries whose named key fields have these values. Naming only some reads several. |
| `form.offices["New York"].staff` | A quoted key value, for one holding anything but letters, digits, `_`, `.`, `:`, and `-`. |

A selector gives every key field by position, or names fields, but not both in one selector. Each key value must read
as its field's kind.

### An entry's envelope: `@`

After the trait and its selector, `@` reads a field of the entry itself rather than of its data:
`form.yearly[2024].@updatedAt`.

| Attribute | Kind | Holds |
|---|---|---|
| `createdAt` | Date | When the entry was first written. |
| `updatedAt` | Date | When the entry was last changed. |
| `createdBy` | Number | The user id of whoever first wrote the entry, who may not be the form's owner. |
| `updatedBy` | Number | The user id of whoever last changed the entry. |
| `source` | Text | How the entry's value came to be: `user` when a person entered, edited, or approved it. |

## A workflow: `workflow`

A workflow path names a normal workflow the client has, then one of these. A report says about a workflow only what
the forms list shows: every attribute is empty for a workflow the client no longer has, one past its lifetime, and one
closed to new forms that this form never entered.

| Attribute | Kind | Holds |
|---|---|---|
| `category` | Text | Where the form stands with the workflow: `engaged` while it is under way, `eligible` when it could start, `finished` once it is done, and `ineligible` when an eligibility test fails. |
| `engaged` | Yes or no | Whether the form has entered the workflow. |
| `eligible` | Yes or no | Whether the form passes every one of the workflow's eligibility tests. |
| `finished` | Yes or no | Whether the workflow has finished for the form. |
| `tasksDone` | Yes or no | Whether every task of the workflow is complete and valid for the form, so none is left to do. |
| `ctaTask` | Text | The id of the task the form's people are asked to do next, while the workflow is under way. |
| `ctaKeyword` | Text | The search keyword of the task the form's people are asked to do next, such as `review`, while the workflow is under way. A report's table shows the keyword's report label. |
| `ctaFilterLabel` | Text | Whom the task the form's people are asked to do next waits on, in the client's words, such as "Waiting on a Northwest reviewer", while the workflow is under way and its call to action says. |
| `lastEngagedAt` | Date | When the form last entered the workflow. |
| `lastActivityAt` | Date | When anyone last acted on the form in the workflow: entered or left it, gave or reversed an approval, or overrode a lock. Saving the form's data is not counted; the form's `updatedAt` says when that happened. |

### An approval

`approval[<taskId>]` names one of the workflow's approval tasks, then one of these:
`workflow.review.approval[accept].approved`.

| Attribute | Kind | Holds |
|---|---|---|
| `approved` | Yes or no | Whether a reviewer has approved the task. |
| `approvedAt` | Date | When the task was approved. |
| `approvedBy` | Number | The user id of the reviewer who approved it. |
| `reversed` | Yes or no | Whether an administrator reversed the task's approval, or a step of it, and it has not been given again since the form entered the workflow. |
| `reversedAt` | Date | When the approval was reversed, while it stands reversed. |
| `reversedBy` | Number | The user id of the administrator who reversed it. |
| `reversedReason` | Text | Why the administrator reversed it, in their words. |

## The owner: `user`

The account of the person who owns the form. Every attribute is empty when the owner is unknown, or one the person
running the report may not see.

| Attribute | Kind | Holds |
|---|---|---|
| `userId` | Number | The owner's user id. |
| `name` | Text | The owner's name: a person's full name, or a business's. Empty when they gave none. |
| `publicName` | Text | The name shown to others: the owner's username, or their email address until they choose one. |
| `email` | Text | The owner's email address. |
| `persona` | Text | The kind of user the owner is in the client: `member`, `editor`, or `admin`. |
| `org` | Text | The owner's organization within the client. Empty when they have none. |
| `labels` | Text, several | The labels the owner's user carries, such as `committee`. |
| `isEntity` | Yes or no | Whether the account belongs to a business rather than a person. |
| `enabled` | Yes or no | Whether the owner's account is enabled. |

## The form: `meta`

| Attribute | Kind | Holds |
|---|---|---|
| `gedraId` | Text | The form's id. |
| `client` | Text | The client the form belongs to. |
| `org` | Text | The organization the form belongs to within the client. Empty when it has none. |
| `ownerId` | Number | The user id of the form's owner. |
| `createdAt` | Date | When the form was created. |
| `updatedAt` | Date | When the form was last changed. |
| `formStatus` | Text | The status the forms list shows: the core details status, except that a valid form reads `needsReview` while a workflow waits on a review, and `finished` once one has finished. |
| `coreDetailsStatus` | Text | Whether the form's core details are done: `valid`, `needsInfo` when a required trait is missing, or `invalid` when entered data fails its checks. Empty for a client with no core details workflow. |
| `cfacts` | Text, several | The facts the form has, such as `kdr:finished`. |
| `ctaKeywords` | Text, several | The search keywords, such as `review`, that the calls to action of the workflows under way on the form carry, each once. A report's table shows each keyword's report label. |

## Several values

A path has several values when it reads every entry of a keyed trait, goes through a list, or names an attribute
marked "several" above. A column's `combine` makes them one value for the form. Left out, a path that can have
only one value takes the `first`, and one that can have several lists them, so no value is
dropped without saying so.

| Combine | Gives |
|---|---|
| `first` | The first value, in order. For a keyed trait read whole, the entry with the lowest key. |
| `list` | Every value, in order, as a list. |
| `distinct` | Each value once, sorted, as a list. |
| `count` | How many values there are. |
| `sum` | The values added up. For numbers only. |
| `min` | The least value. Not for yes or no. |
| `max` | The greatest value. Not for yes or no. |
| `avg` | The average of the values. For numbers only. |

A column's `rollup` takes the same choices, applied across the forms of a group when the report is run
grouped. A column with no rollup is left out of a grouped run, and one the run groups by is shown once, as the group's
value. A column whose value is a list rolls up only by `count`, and cannot be grouped by.

A column's `kind` reads its value as another kind: any value can be read as text, and text can be read as a
number or a date. Between two other kinds there is no reading, and declaring one is refused.

## When a path is checked

A path is checked when the configuration loads: that it is written correctly, and that what it names exists for the
client, with a selector that fits the trait's key. A path naming a trait, a field, or a workflow
the client does not have costs the report that holds it, not the rest of the configuration.

See the demo's [Reports](../demo-client/reports.md) page for two reports worked through, and the
[client configuration reference](client-configuration.md) for the rest of a report's definition.
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes its lists from the names the path parser reads,
so they cannot disagree with it. Read it on the server's **Documents** page, under **Reference**, then **Report paths**,
where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/report-paths](https://dynamicruntime.org/wa/docs/report-paths). `./gradlew
:launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit the
generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The attributes, each with its kind and what it holds | `RWF`, `RUSR`, `RMETA`, and `RENV` in [`ReportConstants.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportConstants.kt) |
| The combines, each with what it gives | `ReportCombine` in [`ReportEval.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportEval.kt) |
| The grammar the page states | [`ReportPath.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportPath.kt) and [`ReportBind.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportBind.kt) |
| The reference page, made from these | [`ReportPathReference.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportPathReference.kt) |
