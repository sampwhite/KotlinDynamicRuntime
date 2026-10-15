[← Workshop logistics](workshop-logistics.md) · [Contents](README.md#the-pages)

# Reports

A **report** is a table an organizer runs over every talk. Each column takes its value from somewhere on the
talk: a field of one of its traits, where it stands in a workflow, who owns it, or a fact about the form itself.
A report is run two ways. Per form, it is a row for each talk. Grouped, it is a row for each distinct value of the
columns it groups by, with the talks in the group counted and some columns added up or listed.

A report is declared in the configuration, as a workflow is, so there is no code written for these either. The
demo declares two, and keeps both in one bundle, `reports`. They are not workflows, so the one-workflow-per-bundle
rule on the [opening page](README.md#how-a-client-is-stored) does not apply, and the two change together.

- **Submissions by track** is a roster of the talks. Grouped by track, it says how many talks each track has and
  how many minutes of program they add up to.
- **Questionnaire summary** is the questionnaire read across every talk. Grouped by who a talk is for and whether
  it may be recorded, it counts the talks in each group, lists what they promise the audience, and counts how many
  have needs for the day.

Here is the whole `reports` bundle:

<!-- kdr-doc: bundle=reports -->
```json
{
  "kdr:reportDef": [
    {
      "reportId": "submissionsByTrack",
      "definition": {
        "$comment": "Per form, a roster of the talks. Grouped, how many talks each track has and the minutes they add up to.",
        "description": "Every talk with its track, its speaker, and where it stands. Grouped by track, how many talks there are and how long they run in all.",
        "label": "Submissions by track",
        "reportId": "submissionsByTrack",
        "columns": [
          {"columnId": "track", "label": "Track", "path": "form.session.track"},
          {"columnId": "talk", "label": "Talk", "path": "form.kdr:name.name"},
          {"columnId": "speaker", "label": "Speaker", "path": "user.name"},
          {"columnId": "status", "label": "Status", "path": "meta.formStatus"},
          {"columnId": "review", "label": "Review", "path": "workflow.review.category"},
          {
            "columnId": "minutes",
            "label": "Minutes",
            "path": "form.abstract.durationMinutes",
            "rollup": "sum"
          }
        ],
        "groupBy": ["track"]
      }
    },
    {
      "reportId": "questionnaireSummary",
      "definition": {
        "$comment": "Grouped by who the talk is for and whether it may be recorded: how many talks, what they promise, and how many have needs for the day.",
        "description": "The questionnaire across every talk: who each talk is for, whether it may be recorded, what the audience takes away, and what the organizers should know.",
        "label": "Questionnaire summary",
        "reportId": "questionnaireSummary",
        "columns": [
          {"columnId": "audience", "label": "Audience", "path": "form.questionnaire.audienceLevel"},
          {"columnId": "recording", "label": "May be recorded", "path": "form.questionnaire.recordingOk"},
          {"columnId": "firstTalk", "label": "First talk", "path": "form.questionnaire.firstTalk"},
          {
            "columnId": "takeaway",
            "label": "Takeaway",
            "path": "form.questionnaire.takeaway",
            "rollup": "list"
          },
          {
            "columnId": "needs",
            "label": "Needs for the day",
            "path": "form.questionnaire.needs",
            "rollup": "count"
          }
        ],
        "excludeEmpty": ["audience"],
        "groupBy": ["audience", "recording"]
      }
    }
  ]
}
```

## Report

The `kdr:reportDef` slot has one entry per report. The entry has the report's id in `reportId`, and the report
itself in `definition`.

| Key | What it means |
| --- | --- |
| `reportId` | The report's id. It is bare, with no `kdr:` in front, because the client owns it. |
| `$comment` | A note for whoever maintains the report. It changes nothing. See [Comments](reference.md#comments). |
| `label` | What the report is called on the Reports page. |
| `description` | What the report shows, for the person choosing it. Shown under the label. |
| `columns` | The columns, in the order they are shown. |
| `groupBy` | The columns a grouped run groups by, unless the person running it chooses others. |
| `excludeEmpty` | The columns a talk must have a value in to appear, unless the person running it chooses others. |

## Column

| Key | What it means |
| --- | --- |
| `columnId` | The column's id within the report. It is what `groupBy` and `excludeEmpty` refer to. |
| `label` | The column's heading. |
| `path` | Where the column's value comes from. See [Report paths](reference.md#report-paths). |
| `rollup` | How the column is combined across the talks of a group, when the report is run grouped. |

**A path says where a column's value comes from.** The first part is the source. The value `form` means a field of
one of the talk's traits, written as the trait's id and then the field: `form.session.track` is the track. The
`kdr:name` trait the client includes is addressed the same way, with its full id: `form.kdr:name.name`. The other
three sources are fixed lists. The value `user.name` is the name of the speaker who owns the talk,
`meta.formStatus` is the talk's status, `valid` or `finished` for instance, and `workflow.review.category` is where the talk
stands with the committee review: eligible, engaged, finished, or ineligible. The reference lists the names each
source allows.

**A rollup says what a column shows for a group.** Grouped by track, the submissions report has one row per
track. The row counts the talks in the track, and the Minutes column, whose `rollup` is `sum`, adds their
durations up. A column with no rollup, such as the speaker or the status, is left out of a grouped run, because
there is no one value to show for a group of talks. The questionnaire summary uses two more rollups. The value
`list` shows every takeaway in the group, one after another, and `count` says how many talks in the group answered
the needs question at all.

**Grouping is a default.** The questionnaire summary groups by audience and then by recording consent, so a grouped
run has a row for beginners who may be recorded, beginners who may not, and so on. Its `excludeEmpty` leaves out a
talk that has not answered the audience question, which keeps a row for *no answer* off the table. The person
running the report can change both before each run.

A report's paths are checked when the configuration loads. A column whose path names a trait or a field the
client does not have costs the report, which the Reports page then lists as dropped, with the reason. The rest of
the bundle loads.

## What the organizer sees

An organizer is an administrator, so their menu has a **Reports** entry. The page lists the two reports by their
labels, each with its number of columns. Choosing one shows its description and the run controls: **Per form**,
**Grouped**, and **History**, then for a grouped run **Group by** and **Must have**, filled in from the report's own
`groupBy` and `excludeEmpty`. The report opens grouped. The **History** view charts nightly snapshots of a grouped
run, which a report asks for with a `history` key the demo's reports leave out, so for them it says *No snapshots
yet* and offers a **Snapshot now** button instead.

Grouped, **Submissions by track** is a table of **Track**, **Forms**, and **Minutes (sum)**: one row per track, how
many talks it has, and their durations added up. A rolled-up column's heading says its rollup. Under the table a
line says how many groups there are and how many forms were read. Run per form, the table has a row per talk,
starting with an **Open** link to it, then **Track**, **Talk**, **Speaker**, **Status**, **Review**, and **Minutes**.
A talk with no abstract yet shows a dash for its minutes. A track shows as its stored value, `kotlin` rather than
*Kotlin*, because a report shows what is stored, not the label a form gave it.

Grouped, **Questionnaire summary** is **Audience**, **May be recorded**, **Forms**, **Takeaway (list)**, and
**Needs for the day (count)**. A yes or no answer shows as *Yes* or *No*. The line under the table also says how many
talks were left out for a missing value, which is what `excludeEmpty` did. A **Download CSV** button saves whichever
table is showing as a file.

The speaker has no **Reports** entry. A report is for the administrators of the client.

Next: [Every bundle](bundles.md).
