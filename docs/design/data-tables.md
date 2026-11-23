# Data tables

A **table field** is a trait field whose value is a CSV file: rows and columns, typed by the trait, edited in the
webapp as a grid, and checked when its form is saved. It is built on data files (`data-files.md`): the field holds a
file's id, and the table is that file's text.

Built so far: the CSV codec and the `csv` kind (#1881), and table fields (#1882). Planned: summaries of a column stored
in the trait's derived fields (#1883), the read-only table and the grid's model (#1884), the editable grid (#1885),
and a sample trait (#1886).

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

Until the grid (#1885), a table field is drawn as any file field is: a CSV file is picked and uploaded, and the save
reads it. The picker cannot tell a table by its first bytes, so for a field taking `csv` it takes a file that carries no
other kind's mark (`judgePick`), and leaves the rest to the server.

**Every save of an edited table is a new file**: data files are immutable, so the grid uploads its rows as a new file
and the form names it; the old one is unlinked and swept after the grace. When two people edit the same form, the
last save wins.
