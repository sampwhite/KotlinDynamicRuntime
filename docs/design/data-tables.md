# Data tables

A **table field** is a trait field whose value is a CSV file: rows and columns, typed by the trait, edited in the
webapp as a grid, and checked when its form is saved. It is built on data files (`data-files.md`): the field holds a
file's id, and the table is that file's text.

Built so far: the CSV codec and the `csv` kind (#1881), table fields (#1882), summaries of a column stored in the
trait's derived fields (#1883), the read-only table and the grid's model (#1884), and the editable grid (#1885).
Planned: a sample trait (#1886).

Theme: #DTABL.

## CSV text

The kernel's codec (`Csv`, in `common.table`) reads and writes RFC 4180 the same on the JVM and in JS, so the server
judging an uploaded table and the browser reading one agree. It takes CRLF, LF, and CR line ends, quotes around a
cell that holds a delimiter, a quote (doubled), or a line break, and drops a leading byte-order mark; it refuses
control characters but a tab and the line ends, a quote out of place, and a row of another width than the header's,
each problem naming its line. A header's names are read without the spaces around them, and must be there and
distinct. At most 5,000 rows, 100 columns, and 10,000 characters a cell. It writes CRLF line ends and a byte-order
mark, which is how Excel knows the file is UTF-8.

**Stored text is never rewritten.** A cell beginning `=`, `+`, `-`, or `@` is a formula to a spreadsheet; only a copy
made to open in one has such a cell guarded with a leading apostrophe (`Csv.spreadsheetCopy`), and a number is left a
number.

## The `csv` kind

A data file of kind `csv` (`FileKinds.csv`, family `table`, 4 MiB, downloaded rather than shown) is told by parsing it
whole when its upload finishes, since text has no mark in its bytes: one whose first bytes are another kind's, one that
is not UTF-8 -- with a hint to save Excel's "CSV UTF-8" -- or one the codec does not read is refused.

## A table field

```kotlin
type("ExpenseLine") {
    type = SCT.kObject
    property("day", "The day it was spent.", required = true) { dayOnlyDate() }
    property("category", "What it was for.") { type = SCT.string; option("meal", "Meal"); option("taxi", "Taxi") }
    property("amount", "How much.", required = true) { type = SCT.number; minimum = 0 }
}
trait("ClaimEntry", "claim", setOf(GedraDataType.formDoc), "An expense claim.") {
    property("lines", "What was spent.") { dataTable("ExpenseLine", maxRows = 500) }
}
```

`dataTable` writes a file field of kind `csv` with `g-fileRows`, the type of one row, and optionally `g-fileMaxRows`.
**The row type is the column list**: each property is a column, named in the CSV's header by its property name and
shown by its title. So a table reuses what a schema already says: `required`, bounds, choices, `pattern`, and
`g-primaryKey`, which here means no two rows share the key.

What the parser holds a table field to:

- `g-fileRows` only on a file field whose kinds are exactly `["csv"]`, and `csv` only with it; `g-fileMaxRows` from 1
  to 5,000, and only beside it. Unset, a table holds 1,000 rows.
- The row type, a `$ref` (bound like an array's `items`) or inline: an object of 1 to 100 declared properties, no union
  and no undeclared properties.
- Each column a value a cell carries as text: a string (with choices or without), an integer, a number, a boolean, or
  a day. Refused: a timestamp (a cell would need a zone), a file, binary content, a derived or counted value, a secret,
  a `g-visibleWhen` gate, and a name with spaces around it (a header is read without them, so it could never match).

A client's alteration may lower `g-fileMaxRows`, never raise it, and narrows an inline row type by the usual rules --
fewer columns, fewer choices, more required. A `$ref` row type is the shared type and stays as it is.

## Reading a table

`readTable(rowType, text, maxRows)` in the kernel reads a table as its field's:

- **The header** may name the columns in any order. A name that is no column is refused, listing the columns; a
  required column missing is refused; an optional one missing is empty in every row.
- **Each cell** is read as its column's type (`typedCell`) by the same code on both sides: a number by
  `plainNumberOrNull` -- not `toDoubleOrNull`, which reads `7d` on the JVM and not in JS -- kept whole when it is
  whole, so `12.0` is 12; an integer column refuses a fraction; a boolean by its spellings (yes, true, ...); a day as
  `yyyy-MM-dd`; a choice by its value, then its label, ignoring case. Every cell is read without the spaces around
  it, as a form's field is -- free text too, unless its column keeps them (`g-outerWhitespace: keep`) -- and an empty
  cell is no value. A whole number too large to hold exactly is refused as that, not as a fraction.
- **Each row** is then checked against the row type by the validator, so a bound or a required column fails as it
  would in a form. Then the key, when the row type declares one.

Problems name the row (counting from the first after the header) and the column's label: "Row 4, Amount: 'abc' is not
a number." At most 20 are reported. `canonicalText` is a cell's stored spelling -- a choice's value, `true`, a day --
leaving a number exactly as typed.

## Checked at save

A table field's newly named file is read when its form is saved (`DataFileLinks`, after the file's kind and size):
a table that does not fit the field's columns refuses the save with its first problem and a count of the rest. Only a
file the write newly names is judged, as for any file field. `DataTables` reads a file's bytes and keeps its parse in a
small node-local cache by content hash -- bytes under a hash never change, so an entry is never stale, only evicted --
with its reads against the last few row types asked for, so a reload of a client's schema reads it afresh. A file whose
bytes are gone from the store refuses the save as any unusable file does. `readFor` is what a
computation on a form's data uses to read the table a field names.

Where a table field is a file field -- outside a page that can save a form, which edits it as a grid -- a CSV file is
picked and uploaded, and the save reads it. The picker cannot tell a table by its first bytes, so for a field taking
`csv` it takes a file that carries no other kind's mark (`judgePick`), and leaves the rest to the server.

**Every save of an edited table is a new file**: data files are immutable, so the grid uploads its rows as a new file
and the form names it; the old one is unlinked and swept after the grace. When two people edit the same form, the
last save wins.

## Summaries

A **summary** is one value summing up a column of a table: how many rows, a total, an average, the least, or the
greatest. The kernel computes it (`TableSummary.summarize`) the way a report rolls up forms -- each cell read as its
column's kind, empty cells left out, a sum of whole numbers kept whole (falling back to a fraction rather than
overflowing), an average whole when it comes out whole -- so the grid's footer, which shows one live, and a save, which
stores one, run the same code and cannot disagree. Unlike a report's, a total of decimals is rounded to the most decimal
places among its cells, and an average to ten: a summary is stored, so `1.10 + 2.20` is kept as `3.3`, not floating
point's `3.3000000000000003`.

**Stored in the trait's own derived fields** by the write function `summarizeTable(target, table, combine, column)`,
beside `computeValue` among a type's `g-onWrite` functions, so a listing, a report, or a workflow reads a claim's total
as any field:

```kotlin
property("lineCount", "How many lines.") { type = SCT.integer; derived = true }
property("totalAmount", "The lines' total.") { type = SCT.number; derived = true }
property("lastSpentOn", "The latest day.") { dayOnlyDate(); derived = true }
onWrite(
    summarizeTable("lineCount", "lines", ReportCombine.count),
    summarizeTable("totalAmount", "lines", ReportCombine.sum, "amount"),
    summarizeTable("lastSpentOn", "lines", ReportCombine.max, "day"),
)
```

- **Checked when the type loads**: the table is a table field of the same type; the column, when named, is one of its
  row type's; the combine is one of the five and allowed for the column's kind (a sum or an average of numbers only, no
  least or greatest of a yes or no), and a count when no column is named; the target is derived, not gated by
  `g-visibleWhen`, and holds what the combine gives -- a number (an integer only where the result is always whole), a
  day for a day's least or greatest, a plain string for text's.
- **Computed on every write** from the table the field names, read through `DataTables.summaryRead` (cached by hash,
  so a save that checks a newly named table reads it once, and the file looked up once for all of a trait's summaries
  of it). No table, a table that does not fit its columns, or bytes that cannot be read, whatever the store threw,
  **clear the target** rather than failing the write. The write functions run before the link hook in the same
  transaction, so a save the link hook refuses for its table rolls the summary back with it.
- **Only a file the writer may read is summed up**: for the system, one some live form links -- it acts on nobody's
  behalf, and a value stored before its field held tables was never checked -- and for a person, one their read scope
  admits or a live form links. The table is read as the form's client's, so whatever runs the functions -- a write, a
  recompute, a publish's impact report -- runs them in that client.
- **Stale values** are what any derived value's are: the nightly recompute (`StateRecomputeJob`) asks
  `computedDataCurrent`, which runs the functions again and compares. A summary changes only with its table, which only
  a write changes, so a recompute finds it current -- at the cost of reading each form's table to know so (see
  `deferred-work.md`).

## In the webapp

**Read-only, a table field shows its table** (`DataTableValue`): the file, with **Download CSV** -- a copy made safe for
a spreadsheet, never the stored text -- then the rows under the field's columns, a choice by its label and a yes or no
in words, numbers to the right, and a footer summing up each column as the grid will (a total for a number, how many
are filled in otherwise) by the kernel's `TableSummary`, which a save's stored summaries run too. The file's text is
read once and kept by its id -- content never changes under an id -- with its parse, so the table drawn and the copy
downloaded come from one read; a page keeps the 16 it used most recently.

**The grid's model** (`DataGridModel.kt`) is pure, so every change is a function a test calls: a `GridState` of the
columns, the rows as cell text (a cell's stored spelling, `canonicalText`), the cursor, the selection, the cell being
edited, and whether anything changed. A row's typed values and problems are worked out when the row changes and kept
on it -- each cell read as its column's type, then the row checked whole by the validator -- so an edit re-checks one
row, and the footer and the problem list read what is kept. Its
operations: move and stretch the selection, edit and commit (Enter on the last row adding one), clear, paste a
spreadsheet's tab-separated cells (a header row skipped, rows added up to the field's limit and how many did not fit
said, columns past the grid dropped), add and remove rows, copy a block as tab-separated text, sum up each column, list the problems (a repeated
key among them), and write the CSV a save stores.

**The grid** (`DataGrid.kt`) edits a table field in the form: typed into, keyed through as a spreadsheet is, pasted
into from one, a choice picked from its list and a day from a calendar, its rows sorted by a column's header (the table
itself reordered, as a spreadsheet's Sort does, and saved so; the read-only table sorts only what it shows), each column summed up in its footer by a function picked for it, and each problem marked on its cell and
listed under it. **It is saved with the form**, never as it is typed: on Save the page flushes its grids (`TableDrafts`)
-- a grid with a problem stops the save, with the cursor on the cell; each changed table is uploaded once as a new CSV
file, and its id set in the field -- and then sends the form, whose save checks the table again and stores its
summaries. An empty field gets its file this way, and a grid emptied of rows saves a header-only one. Reset reloads every
grid from its field's file, and a grid's own Reset table reloads it alone. Outside a page that can save a form, a table field is a file field, a CSV uploaded whole.
