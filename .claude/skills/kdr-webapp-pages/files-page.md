## The Files page (issue #1863)

`/wa/files` (`FilesPage.kt`, its pure half in `FilesListing.kt`, covered by `FilesListingTest`) lists the caller's data
files over `GET /files/list`, the files uploaded for forms' fields (`docs/design/data-files.md`). Not the operator's
file store browser (`file-store-page.md`), which shows a bucket's objects and knows nothing of forms.

- **Named for whose files it lists**, as the forms listing is: **My files** for a caller who reads their own,
  **Files** for one who reads every file of the client -- an administrator of it or an editor -- by the same fact
  (`seesClientForms`), so the two menu entries (`HMENU.files`, `HMENU.clientFiles`) never show together. Offered only to
  a client whose forms can hold files (`kdr:clientHoldsFiles`, remembered per entry union), since anywhere else the
  page could only say there are none. The listing is held to the client the caller acts in, however far their reach.
- **Only the latest read is drawn** (`asked`): an answer to a read a later filter replaced is dropped, so the rows always
  belong to the filter shown.
- **A growing listing** (`ShowMore.kt`): the first `listingBatch`, then more; a change of filter starts over. Filters are
  kind, status, and whether a form uses the file, each with an "any" choice (`filesAny`, since a Select's value cannot be
  null).
- **Each row**: the name as a link to the content (a new tab), kind and size, status -- an upload past its time reads
  "Stopped (no longer taken)" (`FileRow.abandoned`, from the row's `expiresAt`) -- when the upload began (relative),
  who uploaded it only when some row is not the caller's (`filesShowOwner`), and **Used by**, the count of live forms
  linking it -- read or not by the caller, since any keeps it. A count opens the forms under the row (`FileUsesList`,
  over `/files/usedBy`), each a link to the form's view named by its trait and field ("Site media · Photo",
  `fileUseText`) and when it was created, with the number the caller may not open. An image or a video offers
  **Preview**, which draws the thing itself under the row, from the row's own record. One row's forms and one row's
  preview open at a time.
- **A CSV table is never opened as it is stored** (#1881): its name is a link-styled button that saves a copy made
  safe for a spreadsheet (`fileSavedAsSpreadsheetCopy`, `FilesApi.spreadsheetCopy` over the kernel's
  `Csv.spreadsheetCopy`), every cell a spreadsheet would read as a formula given a leading apostrophe and numbers left
  alone, saved through `saveTextFile` under its name ending `.csv` (`spreadsheetCopyName`). Its cells are whatever
  somebody typed, and the stored file stays as written, so the guard is applied to the copy, here.
- **Remove only where it can succeed** (`fileRemovable`): a file no form uses, behind `confirmedRemove`, whose
  question names where it acts (`deleteTargetText`); for an upload under way it reads "Abort this upload?". A file a
  form uses says why it stays instead (`fileKeptText`): it is taken out of the form, which unlinks it.
- **It is a listing, not an editor**: a card with its filters and table, like the API keys page, not the panels of a
  page that edits one record.
