/- Copy for the Reports page (issue #1337): for now, the "What is this?" explanation of its views. The page's other
   words are still in the webapp, and belong here as they are moved. Named by the shell's UI-config after the home
   file, since the page has no UI-config of its own. -/

# @views
# +whatIsThis
A report reads the client's forms in one of three views.

- **Per form** lists each form the report covers, one row each, with the report's columns.
- **Grouped** puts together the forms that share the values of the columns chosen under **Group by**. Each group is
  one row, with its count of forms and the other columns rolled up: a total, an average, the latest.
- **History** charts the report's **snapshots** over days. A snapshot keeps what the grouped view answered on a
  day, always with the report's own grouping, so the days can be compared. One is taken each night for a report
  that keeps a history, and **Snapshot now** takes one at any time.

**Must have** leaves out the forms with no value in the columns chosen there.
