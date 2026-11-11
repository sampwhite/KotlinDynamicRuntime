/- Copy for the Reports page (issue #1337): for now, the "What is this?" explanation of its views. The page's other
   words are still in the webapp, and belong here as they are moved. Named by the shell's UI-config after the home
   file, since the page has no UI-config of its own. -/

/-where none: the Reports page's explanations, an administrator's page. -/
# @views
# +whatIsThis
A report reads the client's forms in one of three views.

- **Per form** lists each form the report covers, one row each, with the report's columns.
- **Grouped** puts together the forms that share the values of the columns chosen under **Group by**. Each group is
  one row, with its count of forms and the other columns rolled up: a total, an average, the latest. **Chart** draws
  the groups above the table, as bars or as a pie of their shares, of the count or of a rolled-up number. A report
  may name the chart it opens on.
- **History** charts the report's **snapshots** over days. A snapshot keeps what the grouped view answered on a
  day, always with the report's own grouping, so the days can be compared. One is taken each night for a report
  that keeps a history, and **Snapshot now** takes one at any time.

**Must have** leaves out the forms with no value in the columns chosen there.

/-where none: the Reports page's explanations, an administrator's page. -/
# @newReport
# +whatIsThis
**New report** makes a report from a few choices, of one of two kinds.

- **A row per form** lists each form, with a column for each value chosen.
- **Totals by a column** puts together the forms that share the value of one column, and combines each other column
  over them: a number's total, a date's latest. The page's **Per form** view still lists the forms one by one. It
  may open on a **chart** of its groups, as bars or a pie, which anyone viewing it can change.

The columns are picked from what the client's forms hold: facts about the form itself, the fields of each of its
traits, and where a form stands in each workflow. A trait that keeps one entry per key -- a note a year, say -- holds
several values on one form, so its column says which to show: all of them, how many, the first, or one by its key.

The report's id is made from its name and never changes. Once made, a report is not changed here: open the client's
bundle editor, where it is in the **reports** bundle, to change it. **Remove this report**, under an open report the
client stores, removes it; its stored snapshots are kept. How a column names where its value comes from, and every
attribute a column can read, are in [the report paths reference](/docs/reference/report-paths.md).
